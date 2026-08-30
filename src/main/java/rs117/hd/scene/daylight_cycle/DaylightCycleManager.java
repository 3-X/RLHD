package rs117.hd.scene.daylight_cycle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DayLength;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonBehavior;
import rs117.hd.config.MoonPhase;
import rs117.hd.config.SeasonalHemisphere;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.scene.lights.LightTimeOfDay;
import rs117.hd.utils.AstronomyUtils;
import rs117.hd.utils.Camera;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.MathUtils.*;

/**
 * Drives the day & night cycle: it owns the simulated clock and turns it into a sun/moon
 * position, moon phase, aurora state, and day/night light schedule. {@link SkyLighting}
 * turns those positions into sky colors and scene-lighting responses.
 *
 * <h2>Per-frame contract</h2>
 * The renderer calls, in this order, once per frame:
 * <ol>
 *   <li>{@link #update()} - applies the current environment's overrides,
 *       advances the simulated clock, resolves the latitude, pins
 *       {@link #currentInstant}, and resolves the celestial state.</li>
 *   <li>any number of state consumers</li>
 * </ol>
 * State consumers never perform astronomy calculations: they read the completed snapshot
 * resolved by {@link #update()}.
 *
 * <h2>Angle conventions</h2>
 * Two orderings are in play, and mixing them up is the classic bug here:
 * <ul>
 *   <li><b>Internal / astronomical:</b> {@code {azimuth, altitude}} in radians. Returned by
 *       {@link AstronomyUtils#getSunAngles} and {@link AstronomyUtils#getMoonPosition},
 *       and used by every method on this class.</li>
 *   <li><b>Fixed-angle data:</b> {@code {altitude, azimuth}} in radians, matching
 *       {@code Environment.fixedSunAngles} and {@code Environment.fixedMoonAngles}.
 *       This is retained until a fixed angle enters the astronomical calculations.</li>
 * </ul>
 *
 * <h2>Cycle modes</h2>
 * {@link DaylightCycle} splits into three families, and most branching in this file is one
 * of these three:
 * <ul>
 *   <li><b>DYNAMIC</b> - the simulated clock accumulates in {@link #accumulatedCycleTime}
 *       and maps to an hour of day via {@link #cyclePositionToHour}.</li>
 *   <li><b>REAL_TIME / SYNCED_DAYS</b> - stateless: the instant is derived directly from the
 *       player's local clock, or from the UTC clock so all players see the same sky.</li>
 *   <li><b>The fixed modes</b> ({@link DaylightCycle#isFixed}) - the sun sits at a constant angle,
 *       bypassing the clock entirely. See {@link #getFixedModeSunAngles}.</li>
 * </ul>
 */
@Singleton
public class DaylightCycleManager {
	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	private static final float NIGHT_RADIUS_BOOST_FRACTION = .25f;
	private static final float NIGHT_STAGGER_RAMP_WIDTH = .08f;

	// Length of one Synced Days cycle: a full day & night every real hour, phase-locked
	// to the UTC clock so every player sees the same sun position at the same moment.
	private static final long SYNCED_DAYS_PERIOD_MS = 60L * 60 * 1000;

	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	private static final long HOUR_MS = 60L * 60 * 1000;

	/**
	 * March 20, 2025 00:00 UTC - spring equinox, i.e. balanced day & night lengths.
	 */
	private static final long EQUINOX_EPOCH_MS = 1742428800000L;
	/** June 10, 2025 - near the summer solstice, for a higher midday sun arc. */
	private static final long SOLSTICE_EPOCH_MS = 1749513600000L;

	/** An hour-of-day in [0, 24) as a millisecond offset from the start of that day. */
	private static long hoursToMillis(double hourOfDay) {
		return (long) (hourOfDay * HOUR_MS);
	}

	// The natural (unwarped) cycle position where daytime ends and night begins.
	// 0.0-0.70 maps to 5am-7pm (day, incl. twilight), 0.70-1.0 maps to 7pm-5am (night).
	private static final float NATURAL_DAY_BOUNDARY = .7f;

	// Probability that any given simulated night is an "aurora night", in
	// environments flagged aurora-eligible. Rolled deterministically per night.
	private static final float AURORA_NIGHT_CHANCE = .02f;

	// Fixed Night's moon: locked to a prominent spot in the south-east sky and always rendered
	// full. Converted once from the environment convention into { azimuth, altitude }.
	private static final float[] FIXED_NIGHT_MOON_ANGLES = fixedToAstronomicalAngles(HDUtils.sunAngles(25, 135));

	// Latitudes used for the seasonal-hemisphere-based sun/moon arc: New York City
	// (northern) and Rio de Janeiro (southern). Only latitude affects the sun's
	// altitude/seasonal arc; longitude is left at 0 since the cycle drives its own
	// time-of-day rather than a real clock/timezone.
	private static final double[] NORTHERN_LAT_LONG = { 40.7128, 0.0 };  // New York City
	private static final double[] SOUTHERN_LAT_LONG = { -22.9068, 0.0 }; // Rio de Janeiro

	// Environment override and position policy for the current frame. The corresponding angle
	// references are held in the per-frame state.
	private boolean hasFixedSunOverride;
	private boolean hasFixedMoonOverride;
	private boolean moonPositionFixed;

	// Night Synced mode: day offset advances only once the moon is low enough that its light
	// no longer reaches the scene, so phase changes are never visible. We track pending
	// increments and apply them when the mirrored moon altitude drops past
	// MOON_PHASE_ADVANCE_ALTITUDE_RAD.
	private long nightSyncedDayOffset = 0;
	private long lastNightSyncedCycles = 0;
	private long pendingDayIncrements = 0;

	// Altitude the moon must fall below before a queued phase change is applied. Matches
	// DaylightCycleLighting's moon horizon cutoff and elevation-fade start (both -10),
	// the altitude at which moonlight has fully faded out. The geometric horizon (0) is too
	// early - the moon is still lighting and shadowing the scene between -10 and 0, so a phase
	// change there is visible as a brightness jump. Kept here rather than imported so this
	// class stays independent of the renderer; if that cutoff moves, this must follow.
	private static final float MOON_PHASE_ADVANCE_ALTITUDE_RAD = -10 * DEG_TO_RAD;
	private static final double NIGHT_SYNCED_MOON_START_HOUR = 3.4;

	// Below this sun altitude, the moon takes over the shadow camera when it is still high
	// enough to light the scene. This avoids a camera-orientation pop when moon shadows fade in.
	private static final float SUN_SHADOW_CUTOFF_DEG = 2;
	private static final float MOON_SHADOW_CUTOFF_DEG = -10;
	// Ignore sub-pixel directional-light movement to stabilize shadow-map edges. Fast cycles use
	// a smaller threshold so their visibly faster sun does not step between shadow updates.
	private static final float DIRECTIONAL_ANGLE_UPDATE_THRESHOLD = .25f * DEG_TO_RAD;

	// Simulated-clock state, preserved across config changes.
	private long lastUpdateTime = 0;
	// Start the dynamic cycle at midday. cyclePosition 0.35 maps to 12:00pm
	// in cyclePositionToHour()'s afternoon range (0.35-0.55 -> 12pm-5pm).
	private double accumulatedCycleTime = .35;
	private long completedCycles = 0; // Each completed cycle = one simulated day

	// Player-configured defaults are updated by HdPlugin.updateCachedConfigs().
	private DaylightCycle configCycle = DaylightCycle.DYNAMIC;
	// Current day length skew - set once per frame alongside the cycle mode.
	// Warps the linear cycle clock so day & night occupy different shares of the
	// fixed total cycle time (see applyDayLengthWarp).
	private DayLength configDayLength = DayLength.STANDARD;
	// Current moon phase lock - set once per frame. DYNAMIC = phase advances
	// naturally; any other value locks the moon's illumination fraction.
	private MoonPhase configMoonPhase = MoonPhase.DYNAMIC;
	private MoonBehavior configMoonBehavior = MoonBehavior.NIGHT_SYNCED;
	private float configCycleDuration = 700;
	@Getter
	private float directionalAngleUpdateThreshold = DIRECTIONAL_ANGLE_UPDATE_THRESHOLD;

	private DaylightCycle currentCycle = DaylightCycle.DYNAMIC;
	private MoonPhase currentMoonPhase = MoonPhase.DYNAMIC;

	private final double[] currentLatLong = { 0, 0 };

	private Instant currentInstant;

	// The single wall-clock sample for this frame. currentInstant is often remapped to a
	// simulated time, so retain the original separately for real-time moon phases and dates.
	private long frameWallClockMillis;
	private Instant frameWallClockInstant;

	// Real Time starts from the player's local calendar time, interpreted at longitude zero.
	// Advancing this Unix timestamp directly keeps the cycle continuous through daylight-saving
	// changes rather than reinterpreting the local clock every frame.
	private long realTimeStartEpochMillis = Long.MIN_VALUE;
	private long realTimeSessionStartMillis;

	// Per-light cycle state. Updated once by LightManager before it evaluates visibility.
	private boolean lightScheduleActive;
	private float nightFactor = 1;
	private boolean nightFactorIncreasing = true;
	private float previousNightFactor = -1;

	@Getter
	private boolean cycleActive;

	@Getter
	private final DaylightCycleState state = new DaylightCycleState();

	// ===== Per-frame state =======================================================

	/**
	 * Refresh player-configured values when HdPlugin processes pending config changes.
	 */
	public void updateConfig(HdPluginConfig config) {
		configCycle = config.daylightCycle();
		configDayLength = config.dayLength();
		configMoonBehavior = config.moonBehavior();
		configMoonPhase = config.moonPhase();
		configCycleDuration = config.cycleDurationMinutes();
		directionalAngleUpdateThreshold =
			DIRECTIONAL_ANGLE_UPDATE_THRESHOLD * saturate(configCycleDuration / 300f);
	}

	/**
	 * Set the per-environment fixed sun/moon angle overrides for this frame.
	 * <p>
	 * Inputs use the environment convention {altitude, azimuth} in radians, or null for no
	 * override. They are converted immediately into the internal {azimuth, altitude} convention.
	 * <p>
	 * Applied from {@link #update()}. Only takes effect under a fixed cycle
	 * mode; the dynamic cycle ignores these.
	 */
	private void setFixedAngleOverrides(@Nullable float[] sunAngles, @Nullable float[] moonAngles) {
		// Add PI while converting to the internal astronomical representation: anglesToSkyDirection
		// was changed (PI - az -> PI + az,
		// with the north/south component negated) to correct the real astronomical sun,
		// which rotates any fixed angle 180° in azimuth. These overrides were hand-
		// authored to look right under the old transform, so the conversion rotates them back
		// 180° at the single boundary that feeds both the disk and its shadow, so every
		// existing fixedSunAngles/fixedMoonAngles renders exactly as before.
		state.fixedSunAnglesOverride = sunAngles == null ? null : fixedToAstronomicalAngles(sunAngles);
		hasFixedMoonOverride = moonAngles != null;
		state.fixedMoonAngles = moonAngles != null
			? fixedToAstronomicalAngles(moonAngles)
			: FIXED_NIGHT_MOON_ANGLES;
	}

	/**
	 * Resolve the observer latitude from the synchronized seasonal hemisphere: northern ->
	 * New York City, southern -> Rio de Janeiro.
	 *
	 * <p>Synced Days is a special case: it is UTC-locked so every player sees the same sky
	 * at the same moment, so it always uses the northern latitude regardless of this
	 * setting, which would otherwise make the two hemispheres diverge.
	 */
	private void updateSeasonalHemisphere() {
		double[] latLong = currentCycle.isForcesNorthernHemisphere() || plugin.configSeasonalHemisphere != SeasonalHemisphere.SOUTHERN
			? NORTHERN_LAT_LONG
			: SOUTHERN_LAT_LONG;
		currentLatLong[0] = latLong[0];
		currentLatLong[1] = latLong[1];
	}

	// Convert an environment-order fixed angle to the internal {azimuth, altitude} convention.
	// The half turn preserves the fixed-angle orientation under the corrected sky transform.
	private static float[] fixedToAstronomicalAngles(float[] fixedAngles) {
		return new float[] { fixedAngles[1] + PI, fixedAngles[0] };
	}

	/** Whether the moon, rather than the low sun, should drive directional shadows. */
	private boolean shouldUseMoonShadows() {
		return state.sunAngles[1] * RAD_TO_DEG < SUN_SHADOW_CUTOFF_DEG &&
			state.moonAltitudeDegrees > MOON_SHADOW_CUTOFF_DEG;
	}

	/**
	 * Build a normalized direction vector FROM the camera TO the given
	 * {azimuth, altitude} sky position, using the renderer/light convention
	 * (pitch = altitude, yaw = PI - azimuth). Shared by the sun/moon sky
	 * direction getters.
	 */
	private float[] anglesToSkyDirection(float azimuth, float altitude) {
		// yaw = PI + azimuth maps the (now real, non-reversed) astronomical azimuth to
		// the renderer's sky direction so the sun/moon rise in the east. The north/south
		// (z) component is negated on top of that: without it the season rendered
		// inverted (equatorial June sun appeared south instead of north). x (east/west)
		// is left untouched so the correct sunrise-east direction is preserved.
		float yaw = PI + azimuth;

		float x = sin(yaw) * cos(altitude);
		float y = sin(altitude);
		float z = cos(yaw) * cos(altitude);

		float length = sqrt(x * x + y * y + z * z);
		if (length > 0.0001f) {
			x /= length;
			y /= length;
			z /= length;
		}
		return new float[] { x, y, z };
	}

	/**
	 * Warp a linear cycle position (0..1) so day and night occupy a different
	 * share of the cycle, without changing the total cycle length.
	 * <p>
	 * The cycle clock advances at a constant real-time rate; this remaps where
	 * that clock "is" in the day. The day segment [0, dayFraction) is stretched
	 * or compressed onto the natural day segment [0, NATURAL_DAY_BOUNDARY), and
	 * likewise for night. Net effect: the favored period elapses in slow motion
	 * while the other period is fast-forwarded, and a full cycle still takes
	 * exactly cycleDurationMinutes.
	 */
	private double applyDayLengthWarp(double cyclePosition) {
		float dayFraction = configDayLength.dayFraction;
		// STANDARD (and any config matching the natural split) is the identity map.
		if (abs(dayFraction - NATURAL_DAY_BOUNDARY) < 1e-6f)
			return cyclePosition;

		if (cyclePosition < dayFraction) {
			// Within the (re-sized) day: scale into the natural day segment.
			return (cyclePosition / dayFraction) * NATURAL_DAY_BOUNDARY;
		} else {
			// Within the (re-sized) night: scale into the natural night segment.
			double nightProgress = (cyclePosition - dayFraction) / (1 - dayFraction);
			return NATURAL_DAY_BOUNDARY + nightProgress * (1 - NATURAL_DAY_BOUNDARY);
		}
	}

	/**
	 * The cycle position used by moving-moon calculations. Fixed-sun modes deliberately use
	 * {@link DaylightCycle#usesDayLengthForMoon}: their sun ignores Day Length, while an unlocked
	 * moon still follows the resized day/night periods.
	 */
	private double getMoonCyclePosition() {
		return currentCycle.usesDayLengthForMoon
			? applyDayLengthWarp(accumulatedCycleTime)
			: accumulatedCycleTime;
	}

	// ===== Sun and shadow directions =============================================

	private float[] computeSunAngles() {
		// Fixed modes return their fixed angle directly, bypassing the time machinery.
		// Every sun-position-dependent value (sky gradient colors, brightness, blend
		// factors) reads this, so they all use the fixed position automatically.
		if (currentCycle.isFixed)
			return hasFixedSunOverride
				? state.fixedSunAnglesOverride
				: fixedToAstronomicalAngles(currentCycle.getFixedSunAngles());
		return getAstronomicalSunAngles(currentInstant);
	}

	private float[] getAstronomicalSunAngles(Instant instant) {
		double[] angles = AstronomyUtils.getSunAngles(instant.toEpochMilli(), currentLatLong);
		return new float[] { (float) angles[0], (float) angles[1] };
	}

	/**
	 * Update the directional camera angles in a way which minimizes shimmering.
	 */
	public void updateDirectionalCamera(Camera directionalCamera) {
		// Fixed sun and moon overrides take precedence. Otherwise, the moon takes over after
		// sunset while it remains above the lighting horizon.
		float[] angles;
		if (hasFixedSunOverride) {
			angles = state.sunAngles;
		} else if (moonPositionFixed) {
			angles = state.moonAngles;
		} else if (shouldUseMoonShadows()) {
			angles = state.moonAngles;
		} else {
			angles = state.sunAngles;
		}

		angles = copy(angles);
		// Environment fixedSunAngles were authored for the legacy directional-camera transform.
		// Their normalized state angle includes a compensating half turn, which the camera must undo
		// to preserve the existing shadow direction.
		angles[0] = hasFixedSunOverride ? PI - angles[0] : -angles[0];
		float diff = max(abs(angleDiff(angles, directionalCamera.getOrientation())));
		if (diff >= directionalAngleUpdateThreshold)
			directionalCamera.setOrientation(angles);
	}

	private float[] computeSunDirectionForSky() {
		float[] sunAngles = state.sunAngles;

		// sunAngles[0] = azimuth, sunAngles[1] = altitude
		// The renderers use: pitch = altitude, yaw = PI - azimuth
		// This matches how lightDir is calculated in ZoneRenderer and LegacyRenderer
		return anglesToSkyDirection(sunAngles[0], sunAngles[1]);
	}

	// ===== Moon ==================================================================

	private float[] computeMoonDirectionForSky() {
		return anglesToSkyDirection(state.moonAngles[0], state.moonAngles[1]);
	}

	private float[] computeMoonAngles() {
		// A fixed-mode moon override (or the default Fixed Night position) locks the moon
		// regardless of moon behavior. ALWAYS_NIGHT is excluded so its moon keeps moving.
		if (moonPositionFixed)
			return state.fixedMoonAngles;
		if (configMoonBehavior == MoonBehavior.NIGHT_SYNCED)
			return computeNightSyncedMoonAngles();

		double[] angles = AstronomyUtils.getMoonPosition(getMoonDate().toEpochMilli(), currentLatLong);
		return new float[] { (float) angles[0], (float) angles[1] };
	}

	private float computeMoonIlluminationFraction() {
		if (currentMoonPhase.isLocked()) {
			return currentMoonPhase.illumination; // Phase locked via config
		}
		if (currentCycle.isLocksMoonIllumination()) {
			return 1; // Always a full moon
		}
		// A realistic moon always uses its resolved astronomical date. Real Time also takes
		// this path for Night Synced, keeping its phase continuous through daylight-saving changes.
		if (configMoonBehavior != MoonBehavior.NIGHT_SYNCED || currentCycle.usesLocalTime)
			return getMoonIllumination(getMoonDate());

		// Synced Days uses its UTC day count so every player shares a phase; other simulated
		// modes use the stateful offset that advances only while the moon is unlit.
		long phaseDay = currentCycle.usesUtcSyncedTime
			? frameWallClockMillis / SYNCED_DAYS_PERIOD_MS
			: nightSyncedDayOffset;
		return getMoonIllumination(Instant.ofEpochMilli(EQUINOX_EPOCH_MS + phaseDay * DAY_MS));
	}

	private static float getMoonIllumination(Instant instant) {
		return (float) AstronomyUtils.getMoonIllumination(instant.toEpochMilli())[0];
	}

	private float computeMoonAltitudeDegrees() {
		return state.moonAngles[1] * RAD_TO_DEG;
	}

	// ===== Aurora ================================================================

	/**
	 * Whether the current simulated night is an "aurora night".
	 * <p>
	 * Each cycle contains one night; we hash that night's index to a stable
	 * pseudo-random value in [0,1) and compare against AURORA_NIGHT_CHANCE.
	 * The result is constant for the whole night (no flicker) and re-rolled once
	 * per cycle. Deterministic - no Math.random() - so it survives config changes
	 * and resumes consistently.
	 * <p>
	 * The index increments at cycle position 0.35 (~midday), the point furthest
	 * from any night, so the roll never flips while auroras are on screen - the
	 * switch happens in broad daylight where nightSkyBlend (and thus the aurora)
	 * is already zero. This avoids a pop at the natural 5am cycle boundary.
	 */
	private boolean isAuroraNight() {
		// Continuous simulated-day time, with the integer boundary shifted to
		// midday (cycle pos 0.35) so a night and its index never straddle a flip.
		int nightIndex = max(1, (int) Math.floor(completedCycles + accumulatedCycleTime - .35) + 1);

		// Cheap integer hash (splitmix64-style finalizer) -> uniform 53-bit mantissa.
		long h = nightIndex * 0x9E3779B97F4A7C15L;
		h ^= (h >>> 30);
		h *= 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 27);
		h *= 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		double roll = (h >>> 11) * (1.0 / (1L << 53)); // [0, 1)

		return roll < AURORA_NIGHT_CHANCE;
	}

	/**
	 * Aurora intensity envelope in [0, 1] for the current frame, combining the
	 * per-cycle aurora roll with a time-of-cycle shape.
	 * <p>
	 * In modes with a natural day & night arc, the sun goes down and comes back up, so
	 * the sky's own nightFactor fades auroras in and out - here we just return 1 on an
	 * aurora night and let the shader's nightFactor do the shaping.
	 * <p>
	 * In the always-night modes (Fixed Night / Always Night) the sun is pinned below
	 * the horizon, so nightFactor is ~1 the whole cycle and a binary on/off would leave
	 * auroras blazing for the entire cycle. Instead we apply an explicit envelope: on an
	 * aurora cycle the auroras ramp up and back down within the cycle (peaking mid-cycle,
	 * zero at the edges) so they come and go; off-cycle it's zero.
	 */
	private float computeAuroraStrength() {
		if (!isAuroraNight())
			return 0;

		if (!currentCycle.isPermanentNight())
			return 1;

		// Position within the current cycle. The night index flips at 0.35 (midday),
		// so re-center the envelope on that boundary: auroras are absent right after a
		// flip, swell to full a bit past mid-cycle, then fade back out before the next
		// flip. phase in [0,1) measured from the 0.35 flip point.
		float phase = fract((float) accumulatedCycleTime - .35f); // wrap into [0, 1)

		// Smooth bump: only visible over a fraction of the cycle. Ramp in over
		// [0.15, 0.40], hold near full through mid-cycle, ramp out over [0.60, 0.85].
		float env;
		if (phase < .15f || phase > .85f) {
			env = 0;
		} else if (phase < .4f) {
			env = smoothstep(.15f, .4f, phase);
		} else if (phase <= .6f) {
			env = 1;
		} else {
			env = smoothstep(.85f, .6f, phase);
		}
		return env;
	}

	// ===== Night-synced moon =====================================================

	private float[] computeNightSyncedMoonAngles() {
		// Real Time and Synced Days already resolve the sun from their respective clocks, so
		// mirroring that cached position keeps moonrise aligned with sunset without maintaining
		// a second clock. Synced Days remains identical for every player for the same reason.
		if (currentCycle.usesLocalTime || currentCycle.usesUtcSyncedTime)
			return mirrorAngles(state.sunAngles);

		float[] sunAngles = getAstronomicalSunAngles(getNightSyncedMoonInstant());
		applyPendingNightSyncedDays(-sunAngles[1]);
		return mirrorAngles(sunAngles);
	}

	/**
	 * Uniform moon motion keeps a night-synced moon moving at a constant rate while the sun slows
	 * through dawn and dusk. The 3.4-hour offset puts moonrise near the sun's visual sunset.
	 */
	private Instant getNightSyncedMoonInstant() {
		double hour = NIGHT_SYNCED_MOON_START_HOUR + getMoonCyclePosition() * 24;
		if (hour >= 24)
			hour -= 24;
		return Instant.ofEpochMilli(EQUINOX_EPOCH_MS + nightSyncedDayOffset * DAY_MS + hoursToMillis(hour));
	}

	/** Queue a lunar phase advance at each cycle boundary, then apply it only while unlit. */
	private void applyPendingNightSyncedDays(float moonAltitude) {
		long newCycles = completedCycles - lastNightSyncedCycles;
		if (newCycles > 0) {
			pendingDayIncrements += newCycles;
			lastNightSyncedCycles = completedCycles;
		}

		// The moon continues lighting and shadowing the scene between -10° and the geometric
		// horizon, so changing phase there would create a visible brightness step.
		if (pendingDayIncrements > 0 && moonAltitude < MOON_PHASE_ADVANCE_ALTITUDE_RAD) {
			nightSyncedDayOffset += pendingDayIncrements;
			pendingDayIncrements = 0;
		}
	}

	/** Mirror an astronomical {azimuth, altitude} position to its opposite point in the sky. */
	private static float[] mirrorAngles(float[] angles) {
		return new float[] { angles[0] + PI, -angles[1] };
	}

	/**
	 * Anchor the real-time cycle to the local calendar once. Subsequent frames add elapsed Unix
	 * time, rather than repeatedly converting the local clock and inheriting daylight-saving jumps.
	 */
	private void initializeRealTimeClock() {
		if (realTimeStartEpochMillis != Long.MIN_VALUE)
			return;

		realTimeStartEpochMillis = frameWallClockInstant
			.atZone(ZoneId.systemDefault())
			.toLocalDateTime()
			.toInstant(ZoneOffset.UTC)
			.toEpochMilli();
		realTimeSessionStartMillis = frameWallClockMillis;
	}

	/**
	 * Map a normalized cycle position [0, 1) to an hour-of-day [0, 24) using the
	 * project's twilight-weighted mapping (extended dawn/dusk, compressed deep
	 * night). Shared by the Dynamic cycle and Synced Days so both share the same
	 * sun arc shape.
	 */
	private double cyclePositionToHour(double cyclePosition) {
		// 0.0-0.15  dawn/sunrise twilight -> 5am-7am
		// 0.15-0.35 morning               -> 7am-12pm
		// 0.35-0.55 afternoon             -> 12pm-5pm
		// 0.55-0.70 sunset twilight       -> 5pm-7pm
		// 0.70-0.85 early night           -> 7pm-12am
		// 0.85-1.0  late night/pre-dawn   -> 12am-5am
		if (cyclePosition < .15) {
			return 5 + cyclePosition / .15 * 2;
		} else if (cyclePosition < .35) {
			return 7 + (cyclePosition - .15) / .2 * 5;
		} else if (cyclePosition < .55) {
			return 12 + (cyclePosition - .35) / .2 * 5;
		} else if (cyclePosition < .7) {
			return 17 + (cyclePosition - .55) / .15 * 2;
		} else if (cyclePosition < .85) {
			return 19 + (cyclePosition - .7) / .15 * 5;
		} else {
			return (cyclePosition - .85) / .15 * 5;
		}
	}

	// ===== Simulated clock =======================================================

	// Stateful: advances accumulatedCycleTime/completedCycles, pins the current instant,
	// and resolves the state every consumer reads for this frame.
	public void update() {
		resolveEnvironmentState();
		updateSeasonalHemisphere();

		frameWallClockMillis = System.currentTimeMillis();
		frameWallClockInstant = Instant.ofEpochMilli(frameWallClockMillis);
		initializeRealTimeClock();
		currentInstant = frameWallClockInstant;
		advanceCycle(frameWallClockMillis);
		currentInstant = resolveCurrentInstant();
		resolveState();
		resolveLightSchedule();
	}

	/**
	 * Resolve every value derived from this frame's environment and instant. This is deliberately
	 * ordered: Night Synced moon angles can apply a pending phase change before illumination is
	 * calculated.
	 */
	private void resolveState() {
		state.sunAngles = computeSunAngles();
		state.moonAngles = computeMoonAngles();
		state.moonIllumination = computeMoonIlluminationFraction();
		state.moonAltitudeDegrees = computeMoonAltitudeDegrees();
		state.moonAltitudeDegreesForLighting = currentCycle.isUsesFixedMoonAltitudeForLighting()
			? state.fixedMoonAngles[1] * RAD_TO_DEG
			: state.moonAltitudeDegrees;
		state.sunDirection = computeSunDirectionForSky();
		state.moonDirection = computeMoonDirectionForSky();
		state.hidesMoon = currentCycle.isHidesMoon();
		state.auroraStrength = computeAuroraStrength();
	}

	/** Apply the current environment's mode, moon phase, and fixed-angle overrides. */
	private void resolveEnvironmentState() {
		cycleActive = environmentManager.isOverworld() && plugin.configEnableDayNightCycle;

		DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
		MoonPhase forcedMoonPhase = environmentManager.getForcedMoonPhase();
		currentCycle = forcedMode != null ? forcedMode : configCycle;
		currentMoonPhase = forcedMoonPhase != null ? forcedMoonPhase : configMoonPhase;
		setFixedAngleOverrides(
			environmentManager.getForcedFixedSunAngles(),
			environmentManager.getForcedFixedMoonAngles()
		);
		hasFixedSunOverride = currentCycle.isFixed && state.fixedSunAnglesOverride != null;
		moonPositionFixed = currentCycle.isLocksMoonPosition() ||
			currentCycle.isFixed && hasFixedMoonOverride;
	}

	/** Advance the stateful dynamic-cycle clock. Fixed and real-time modes retain it for moons. */
	private void advanceCycle(long currentTimeMillis) {
		if (lastUpdateTime == 0)
			lastUpdateTime = currentTimeMillis;

		double cycleDurationMillis = configCycleDuration * 60.0 * 1000.0;
		accumulatedCycleTime += (currentTimeMillis - lastUpdateTime) / cycleDurationMillis;
		while (accumulatedCycleTime >= 1.0) {
			accumulatedCycleTime -= 1.0;
			completedCycles++;
		}
		lastUpdateTime = currentTimeMillis;
	}

	/** Resolve the astronomical instant for the already-updated cycle state. */
	private Instant resolveCurrentInstant() {
		if (currentCycle.isFixed) {
			long baseEpochMs = currentCycle.isUsesSolsticeEpoch() ? SOLSTICE_EPOCH_MS : EQUINOX_EPOCH_MS;
			return Instant.ofEpochMilli(baseEpochMs).plusMillis(hoursToMillis(currentCycle.getFixedHour()));
		}

		switch (currentCycle) {
			case REAL_TIME:
				// The session-local timestamp advances in Unix time, so daylight-saving changes
				// cannot cause a discontinuity in the sun, moon, or seasonal date.
				return Instant.ofEpochMilli(realTimeStartEpochMillis + frameWallClockMillis - realTimeSessionStartMillis);
			case SYNCED_DAYS:
				// A full day & night per real UTC hour. The resulting sky is identical for all
				// players and independent of Cycle Duration.
				double syncedCyclePosition =
					(frameWallClockMillis % SYNCED_DAYS_PERIOD_MS) / (double) SYNCED_DAYS_PERIOD_MS;
				long syncedDay = frameWallClockMillis / SYNCED_DAYS_PERIOD_MS;
				Instant syncedStartOfDay = Instant.EPOCH.plus(syncedDay, ChronoUnit.DAYS);
				return syncedStartOfDay.plusMillis(hoursToMillis(cyclePositionToHour(syncedCyclePosition)));
			case DYNAMIC:
				// Warp the linear cycle clock so day and night occupy the configured share,
				// then feed it through the twilight-weighted sun mapping.
				double cyclePosition = applyDayLengthWarp(accumulatedCycleTime);
				double mappedHour = cyclePositionToHour(cyclePosition);
				Instant startOfDay = frameWallClockInstant.truncatedTo(ChronoUnit.DAYS)
					.plus(completedCycles, ChronoUnit.DAYS);
				return startOfDay.plusMillis(hoursToMillis(mappedHour));
		}
		throw new IllegalStateException("Unhandled day & night cycle mode: " + currentCycle);
	}

	/**
	 * Get a continuously advancing date for moon calculations.
	 * Unlike getModifiedDate() which uses non-linear time mapping for the sun,
	 * this returns a timestamp that advances smoothly based on total elapsed cycles.
	 * Each cycle = 1 simulated day, so the moon's phase and position change gradually
	 * without discrete jumps at cycle boundaries.
	 */
	private Instant getMoonDate() {
		Instant startOfDay = frameWallClockInstant.truncatedTo(ChronoUnit.DAYS);

		// Real Time mode: the moon's phase and position are astronomically real for
		// today at the player's local hour, matching the real-clock sun.
		if (currentCycle.usesLocalTime) {
			return currentInstant;
		}

		// Synced Days mode: use the same UTC-derived instant as the sun so the moon
		// stays coherent with it and is identical for every player. One simulated
		// day advances per completed UTC hour.
		if (currentCycle.usesUtcSyncedTime) {
			return currentInstant;
		}

		// Total simulated days elapsed = completed whole cycles + current cycle progress.
		// Warp only the within-cycle fraction so the realistic moon's position tracks
		// the re-sized day & night, while whole completed cycles still advance the lunar
		// phase linearly (preventing phase jitter from the warp).
		long totalOffsetMillis = (long) ((completedCycles + getMoonCyclePosition()) * DAY_MS);

		return startOfDay.plusMillis(totalOffsetMillis);
	}

	// ===== Light schedule ========================================================

	/** Resolve the day/night light schedule used later by {@link rs117.hd.scene.LightManager}. */
	private void resolveLightSchedule() {
		lightScheduleActive = cycleActive;
		if (!lightScheduleActive) {
			previousNightFactor = -1;
			return;
		}

		// Fixed Twilight/Sunset use their authored sun angle, producing the same partial night
		// factor as a moving sky at that altitude.
		nightFactor = smoothstep(5, -18, state.sunAngles[1] * RAD_TO_DEG);
		nightFactorIncreasing = previousNightFactor < 0 || nightFactor >= previousNightFactor;
		previousNightFactor = nightFactor;
	}

	/** Whether a day/night-only light is allowed by the player's configuration. */
	public boolean isLightAllowedByConfiguration(Light light) {
		return !light.def.dayNightOnly || plugin.configEnableDayNightCycle;
	}

	/** Radius to use for culling before a light's final animation and cycle scale are applied. */
	public float getScheduledLightCullingRadius(Light light) {
		if (!lightScheduleActive)
			return light.def.radius;

		float scheduledNightFactor = getScheduledNightFactor(light);
		return light.def.radius * getNightRadiusScale(light.def, scheduledNightFactor);
	}

	/** Whether a time-restricted light is effectively off for the current day/night state. */
	public boolean isHiddenByLightSchedule(Light light) {
		return lightScheduleActive
			&& light.def.timeOfDay != null
			&& getNightStrengthScale(light.def, getScheduledNightFactor(light)) < .001f;
	}

	/** Apply the cycle's strength and radius response after LightManager's normal fades. */
	public void applyLightSchedule(Light light) {
		if (!lightScheduleActive)
			return;

		float scheduledNightFactor = getScheduledNightFactor(light);
		light.strength *= getNightStrengthScale(light.def, scheduledNightFactor);
		light.radius *= getNightRadiusScale(light.def, scheduledNightFactor);
	}

	/** Apply a definition's optional phase window to the global night factor. */
	private float getScheduledNightFactor(Light light) {
		LightDefinition def = light.def;
		if (def.timeOfDay == null)
			return nightFactor;

		LightTimeOfDay phase = nightFactorIncreasing || def.timeOfDayOff == null
			? def.timeOfDay
			: def.timeOfDayOff;
		float start = phase.start;
		float end = phase.end;
		if (def.staggered) {
			float rampWidth = min(NIGHT_STAGGER_RAMP_WIDTH, end - start);
			start += getNightStaggerOffset(light) * max(0, end - start - rampWidth);
			end = start + rampWidth;
		}
		return smoothstep(start, end, nightFactor);
	}

	/** Derive a stable pseudo-random stagger from a light's immutable placement. */
	private static float getNightStaggerOffset(Light light) {
		int hash = Float.floatToIntBits(light.pos[0]);
		hash ^= Float.floatToIntBits(light.pos[1]) * 374761393;
		hash ^= Float.floatToIntBits(light.pos[2]) * 668265263;
		hash ^= light.plane * 912271;
		hash ^= hash >>> 16;
		hash *= 0x85ebca6b;
		hash ^= hash >>> 13;
		hash *= 0xc2b2ae35;
		hash ^= hash >>> 16;
		return (hash & 0x7FFFFFFF) / 2147483647f;
	}

	private float getNightStrengthScale(LightDefinition def, float scheduledNightFactor) {
		float nightScale = mix(1, def.nightMultiplier, nightFactor);
		return def.timeOfDay != null ? scheduledNightFactor * nightScale : nightScale;
	}

	private float getNightRadiusScale(LightDefinition def, float scheduledNightFactor) {
		float multiplier = def.nightMultiplier;
		if (multiplier <= 0)
			return def.timeOfDay != null ? 0 : mix(1, 0, nightFactor);

		// A scheduled light's radius follows its phase window. Unscheduled lights retain their
		// authored radius unless explicitly boosted at night; this preserves their normal culling.
		float scheduleScale = def.timeOfDay != null ? scheduledNightFactor : 1;
		return scheduleScale * (multiplier > 1
			? mix(1, multiplier, nightFactor * NIGHT_RADIUS_BOOST_FRACTION)
			: 1);
	}
}
