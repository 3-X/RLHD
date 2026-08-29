package rs117.hd.scene;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DayLength;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonBehavior;
import rs117.hd.config.MoonPhase;
import rs117.hd.config.SeasonalHemisphere;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.AtmosphereUtils;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.ColorUtils.linearToSrgb;
import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.ColorUtils.srgbToLinear;
import static rs117.hd.utils.MathUtils.*;

/**
 * Drives the day & night cycle: it owns the simulated clock and turns it into a sun/moon
 * position, from which every time-of-day-dependent value (sky gradient, light and ambient
 * color, brightness, moon phase, aurora) is derived.
 *
 * <h2>Per-frame contract</h2>
 * The renderer calls, in this order, once per frame:
 * <ol>
 *   <li>{@link #update()} - applies the current environment's overrides,
 *       advances the simulated clock, resolves the latitude, pins
 *       {@link #currentInstant}, and clears the astronomy snapshot.</li>
 *   <li>any number of getters</li>
 * </ol>
 * Getters are pure with respect to that state and share a per-frame astronomy snapshot, so
 * calling them repeatedly within a frame is cheap.
 *
 * <h2>Angle conventions</h2>
 * Two orderings are in play, and mixing them up is the classic bug here:
 * <ul>
 *   <li><b>Internal / astronomical:</b> {@code {azimuth, altitude}} in radians. Returned by
 *       {@link AtmosphereUtils#getSunAngles} and {@link AtmosphereUtils#getMoonPosition},
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
@Slf4j
public class TimeOfDay {
	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	// Pre-linearized deep-night sky color.
	// Read-only: every consumer only reads components into fresh blend arrays.
	private static final float[] NIGHT_SKY_LINEAR = rgb(5, 7, 15);
	private static final float[] SKY_LUMA_WEIGHTS = { .2126f, .7152f, .0722f };

	// Sky color keyframe tables, as { sunAltitudeDegrees, sRGB 0xRRGGBB }. Read-only
	// constant data; interpolateSrgb only reads them and returns a fresh
	// linear float[] per call. Rows must stay sorted by ascending altitude.
	private static final float[][] ZENITH_KEYFRAMES = { // top of the sky
		srgbRow(-30, 0x010104), // Deep night - near black
		srgbRow(-15, 0x03040A), // Late night
		srgbRow(-8,  0x2D2346), // Early twilight - purple tint
		srgbRow(-3,  0x503C64), // Twilight
		srgbRow(0,   0x645078), // Horizon sun
		srgbRow(5,   0x788CB4), // Early sunrise
		srgbRow(15,  0x6496C8), // Morning
		srgbRow(30,  0x5A91C8), // Mid-morning
		srgbRow(50,  0x558CC3), // Midday
		srgbRow(90,  0x5087BE), // High noon
	};

	private static final float[][] HORIZON_KEYFRAMES = { // sides/bottom of the sky
		srgbRow(-30, 0x010205), // Deep night - near black
		srgbRow(-15, 0x04050C), // Late night
		srgbRow(-8,  0x3C2D41), // Early twilight
		srgbRow(-3,  0x8C5046), // Twilight - orange/red
		srgbRow(0,   0xDC8250), // Sunrise/sunset - golden
		srgbRow(5,   0xE6AA78), // Early morning golden
		srgbRow(10,  0xC8B4A0), // Morning warm
		srgbRow(20,  0xAAAFB9), // Late morning
		srgbRow(30,  0x96A5BE), // Midday haze
		srgbRow(50,  0x8CA0BE), // Afternoon
		srgbRow(90,  0x879BB9), // High noon
	};

	private static final float[][] SUN_GLOW_KEYFRAMES = { // halo around the sun disk
		srgbRow(-30, 0x000000), // No glow at night
		srgbRow(-10, 0x140A1E), // Very faint purple
		srgbRow(-5,  0x50283C), // Purple/pink
		srgbRow(-2,  0xB45032), // Deep orange/red
		srgbRow(0,   0xFF9650), // Bright orange
		srgbRow(5,   0xFFC882), // Golden yellow
		srgbRow(15,  0xFFE6B4), // Warm white
		srgbRow(30,  0xFFFADC), // Nearly white
		srgbRow(50,  0xFFFFF0), // White with slight warmth
		srgbRow(90,  0xFFFFFA), // Pure white
	};

	// Sun altitude in degrees mapped to color temperature in kelvin.
	private static final float[][] DIRECTIONAL_TEMPERATURE_KEYFRAMES = {
		{ 3, 2500 },
		{ 5, 2600 },
		{ 10, 3000 },
		{ 15, 3300 },
		{ 20, 3600 },
		{ 30, 4000 },
		{ 40, 4300 },
		{ 50, 4750 },
		{ 60, 5250 },
		{ 70, 5500 },
		{ 80, 5750 },
		{ 90, 6000 }
	};

	// Procedural ambient colors in { sunAltitudeDegrees, linearR, linearG, linearB }.
	private static final float[][] AMBIENT_COLOR_KEYFRAMES = {
		linearRow(-5, 113, 140, 180),
		linearRow(25, 192, 185, 255),
		linearRow(40, 185, 214, 255),
	};

	/** Builds a keyframe row of { sunAltitudeDegrees, sRGB r, g, b } from a 0xRRGGBB literal. */
	private static float[] srgbRow(float altitudeDegrees, int srgb) {
		return new float[] {
			altitudeDegrees,
			((srgb >> 16) & 0xFF) / 255f,
			((srgb >> 8) & 0xFF) / 255f,
			(srgb & 0xFF) / 255f
		};
	}

	/**
	 * Builds a pre-linearized color keyframe row from sRGB components.
	 */
	private static float[] linearRow(float altitudeDegrees, int red, int green, int blue) {
		float[] linear = rgb(new Color(red, green, blue));
		return new float[] { altitudeDegrees, linear[0], linear[1], linear[2] };
	}

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

	/** In-place {@code dst = mix(dst, src, t)} over the first 3 components. */
	private static void blendTowards(float[] dst, float[] src, float t) {
		for (int i = 0; i < 3; i++)
			dst[i] = dst[i] * (1 - t) + src[i] * t;
	}

	/** In-place {@code dst *= 1 - t} over the first 3 components, for fading additive colors out. */
	private static void fadeOut(float[] dst, float t) {
		for (int i = 0; i < 3; i++)
			dst[i] *= 1 - t;
	}

	/**
	 * How much an area's own (regional) light color should win over the procedurally
	 * computed one, as a function of sun altitude: fully regional with the sun high,
	 * tapering to almost none at night so the cycle's own night colors take over.
	 * Shared by the directional and ambient blends so they stay in step.
	 */
	private static float regionalBlendFactor(float sunAltitudeDegrees) {
		if (sunAltitudeDegrees >= 30)
			return 1; // High sun - pure regional, matching the cycle-disabled look
		if (sunAltitudeDegrees >= 15)
			return .75f + (sunAltitudeDegrees - 15) / 15 * .25f; // Strong regional
		if (sunAltitudeDegrees >= 5)
			return .5f + (sunAltitudeDegrees - 5) / 10 * .25f; // Sunset/late sunrise
		if (sunAltitudeDegrees >= 0)
			return .3f + sunAltitudeDegrees / 5 * .2f; // Low sun
		return max(0, .3f + sunAltitudeDegrees / 10 * .3f); // Night/twilight
	}

	/** Linear blend of two colors: {@code mix(a, b, t)}, as a fresh array. */
	private static float[] mixColor(float[] a, float[] b, float t) {
		float[] result = new float[3];
		for (int i = 0; i < 3; i++)
			result[i] = a[i] * (1 - t) + b[i] * t;
		return result;
	}

	// The natural (unwarped) cycle position where daytime ends and night begins.
	// 0.0-0.70 maps to 5am-7pm (day, incl. twilight), 0.70-1.0 maps to 7pm-5am (night).
	private static final float NATURAL_DAY_BOUNDARY = .7f;

	// Probability that any given simulated night is an "aurora night", in
	// environments flagged aurora-eligible. Rolled deterministically per night.
	private static final float AURORA_NIGHT_CHANCE = .02f;

	// Fixed Night's moon: locked to a prominent spot in the south-east sky and always
	// rendered full. Uses the same { altitude, azimuth } order as environment fixed angles.
	private static final float[] FIXED_NIGHT_MOON_ANGLES = HDUtils.sunAngles(25, 135);

	// Latitudes used for the seasonal-hemisphere-based sun/moon arc: New York City
	// (northern) and Rio de Janeiro (southern). Only latitude affects the sun's
	// altitude/seasonal arc; longitude is left at 0 since the cycle drives its own
	// time-of-day rather than a real clock/timezone.
	private static final double[] NORTHERN_LAT_LONG = { 40.7128, 0.0 };  // New York City
	private static final double[] SOUTHERN_LAT_LONG = { -22.9068, 0.0 }; // Rio de Janeiro

	// Per-environment fixed-angle overrides {altitude, azimuth} in radians, or
	// null to use astronomical/default angles. Set once per frame by the renderer
	// from the current environment. Only consulted while a fixed cycle mode is
	// active - the dynamic cycle always computes angles.
	private float[] fixedSunAnglesOverride = null;
	private float[] fixedMoonAnglesOverride = null;

	// Night Synced mode: day offset advances only once the moon is low enough that its light
	// no longer reaches the scene, so phase changes are never visible. We track pending
	// increments and apply them when the mirrored moon altitude drops past
	// MOON_PHASE_ADVANCE_ALTITUDE_RAD.
	private long nightSyncedDayOffset = 0;
	private long lastNightSyncedCycles = 0;
	private long pendingDayIncrements = 0;

	// Altitude the moon must fall below before a queued phase change is applied. Matches
	// DayNightLighting's MOON_HORIZON_CUTOFF_DEG / MOON_ELEVATION_FADE_START_DEG (both -10),
	// the altitude at which moonlight has fully faded out. The geometric horizon (0) is too
	// early - the moon is still lighting and shadowing the scene between -10 and 0, so a phase
	// change there is visible as a brightness jump. Kept here rather than imported so this
	// class stays independent of the renderer; if that constant moves, this must follow.
	private static final float MOON_PHASE_ADVANCE_ALTITUDE_RAD = -10 * DEG_TO_RAD;

	// Below this sun altitude, the moon takes over the shadow camera when it is still high
	// enough to light the scene. This avoids a camera-orientation pop when moon shadows fade in.
	private static final float SUN_SHADOW_CUTOFF_DEG = 2;
	private static final float MOON_SHADOW_CUTOFF_DEG = -10;

	// Simulated-clock state, preserved across config changes.
	private long lastUpdateTime = 0;
	// Start the dynamic cycle at midday. cyclePosition 0.35 maps to 12:00pm
	// in cyclePositionToHour()'s afternoon range (0.35-0.55 -> 12pm-5pm).
	private double accumulatedCycleTime = .35;
	private long completedCycles = 0; // Each completed cycle = one simulated day

	// Player-configured defaults are updated by HdPlugin.updateCachedConfigs().
	@Setter
	private DaylightCycle configuredCycleMode = DaylightCycle.DYNAMIC;

	@Getter
	private DaylightCycle currentCycleMode = DaylightCycle.DYNAMIC;

	// Current day length skew - set once per frame alongside the cycle mode.
	// Warps the linear cycle clock so day & night occupy different shares of the
	// fixed total cycle time (see applyDayLengthWarp).
	@Setter
	private DayLength currentDayLength = DayLength.STANDARD;

	// Current moon phase lock - set once per frame. DYNAMIC = phase advances
	// naturally; any other value locks the moon's illumination fraction.
	@Setter
	private MoonPhase configuredMoonPhase = MoonPhase.DYNAMIC;

	private MoonPhase currentMoonPhase = MoonPhase.DYNAMIC;

	@Setter
	private MoonBehavior currentMoonBehavior = MoonBehavior.NIGHT_SYNCED;

	@Getter
	@Setter
	private float currentCycleDuration = 700;

	private final double[] currentLatLong = { 0, 0 };

	@Getter
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

	// Per-frame astronomy snapshot. update() already pins the wall-clock instant
	// once per frame; these cache the ephemeris solves derived from it, so the
	// ~12 getter calls per frame share one solve instead of re-deriving.
	// Callers must treat the returned arrays as read-only - they are shared.
	private float[] frameSunAngles;
	private float[] frameMoonAngles;
	private float[] frameNightSyncedMoonAngles;
	private float[] frameSunDirectionForSky;
	private float[] frameMoonDirectionForSky;
	private Float frameMoonIllumination;
	private Float frameMoonAltitudeDegrees;

	// ===== Per-frame state =======================================================

	/**
	 * Invalidate the per-frame astronomy snapshot. update() is the sole frame boundary:
	 * all state must be settled before getters run.
	 */
	private void beginFrame() {
		frameSunAngles = null;
		frameMoonAngles = null;
		frameNightSyncedMoonAngles = null;
		frameSunDirectionForSky = null;
		frameMoonDirectionForSky = null;
		frameMoonIllumination = null;
		frameMoonAltitudeDegrees = null;
	}

	/**
	 * Refresh player-configured values when HdPlugin processes pending config changes.
	 */
	public void updateConfig(HdPluginConfig config) {
		configuredCycleMode = config.daylightCycle();
		currentDayLength = config.dayLength();
		currentMoonBehavior = config.moonBehavior();
		configuredMoonPhase = config.moonPhase();
		currentCycleDuration = config.cycleDurationMinutes();
	}

	/**
	 * Set the per-environment fixed sun/moon angle overrides for this frame.
	 * <p>
	 * Inputs use the environment convention {altitude, azimuth} in radians, or null for no
	 * override. Fixed-angle APIs retain that order; conversion happens only when an angle enters
	 * the astronomical calculations.
	 * <p>
	 * Applied from {@link #update()}. Only takes effect under a fixed cycle
	 * mode; the dynamic cycle ignores these.
	 */
	private void setFixedAngleOverrides(@Nullable float[] sunAngles, @Nullable float[] moonAngles) {
		// Keep {altitude, azimuth}. Add PI only when converting to the internal astronomical
		// representation: anglesToSkyDirection was changed (PI - az -> PI + az,
		// with the north/south component negated) to correct the real astronomical sun,
		// which rotates any fixed angle 180° in azimuth. These overrides were hand-
		// authored to look right under the old transform, so the conversion rotates them back
		// 180° at the single boundary that feeds both the disk and its shadow, so every
		// existing fixedSunAngles/fixedMoonAngles renders exactly as before.
		float[] newSun = sunAngles == null ? null :
			new float[] { sunAngles[0], sunAngles[1] };
		float[] newMoon = moonAngles == null ? null :
			new float[] { moonAngles[0], moonAngles[1] };
		fixedSunAnglesOverride = newSun;
		fixedMoonAnglesOverride = newMoon;
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
		double[] latLong = currentCycleMode.isForcesNorthernHemisphere() || plugin.configSeasonalHemisphere != SeasonalHemisphere.SOUTHERN
			? NORTHERN_LAT_LONG
			: SOUTHERN_LAT_LONG;
		currentLatLong[0] = latLong[0];
		currentLatLong[1] = latLong[1];
	}

	/**
	 * Resolve the fixed sun angles {altitude, azimuth} in radians
	 * for the active fixed cycle mode: the environment's fixedSunAngles override when
	 * present, otherwise the built-in per-mode constant. Only valid for a fixed cycle mode.
	 * Drives everything sun-related in fixed modes (disk, shadow, sky colors, brightness)
	 * so those modes no longer depend on incremented time.
	 */
	private float[] getFixedModeSunAngles() {
		if (fixedSunAnglesOverride != null)
			return new float[] { fixedSunAnglesOverride[0], fixedSunAnglesOverride[1] };
		return currentCycleMode.getFixedSunAngles();
	}

	// Convert an environment-order fixed angle to the internal {azimuth, altitude} convention.
	// The half turn preserves the fixed-angle orientation under the corrected sky transform.
	private static float[] fixedToAstronomicalAngles(float[] fixedAngles) {
		return new float[] { fixedAngles[1] + PI, fixedAngles[0] };
	}

	/**
	 * Fixed moon angles {altitude, azimuth} in radians for the current fixed
	 * mode. Returns the environment's fixedMoonAngles override when set,
	 * otherwise the default Fixed Night position. Used both for the sky moon
	 * direction and the shadow-casting light direction so the moon disk and the
	 * shadows it casts stay locked together.
	 */
	private float[] getFixedNightMoonAngles() {
		if (fixedMoonAnglesOverride != null)
			return new float[] { fixedMoonAnglesOverride[0], fixedMoonAnglesOverride[1] };
		return new float[] { FIXED_NIGHT_MOON_ANGLES[0], FIXED_NIGHT_MOON_ANGLES[1] };
	}

	/**
	 * Whether the current environment supplies a fixed sun-angle override that
	 * should be honored (i.e. a fixed mode is active and an override is set).
	 */
	private boolean hasFixedSunOverride() {
		return currentCycleMode.isFixed && fixedSunAnglesOverride != null;
	}

	/**
	 * Whether the current environment supplies a fixed moon-angle override that
	 * should be honored (i.e. a fixed mode is active and an override is set).
	 */
	private boolean hasFixedMoonOverride() {
		return currentCycleMode.isFixed && fixedMoonAnglesOverride != null;
	}

	/**
	 * The fixed sun angles {altitude, azimuth} in radians. Only valid when {@link #hasFixedSunOverride()}.
	 */
	private float[] getFixedSunAngles() {
		return new float[] { fixedSunAnglesOverride[0], fixedSunAnglesOverride[1] };
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
		float dayFraction = currentDayLength.dayFraction;
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
		return currentCycleMode.usesDayLengthForMoon
			? applyDayLengthWarp(accumulatedCycleTime)
			: accumulatedCycleTime;
	}

	// ===== Sun position, light & sky colors ======================================

	/**
	 * The sun's {azimuth, altitude} in radians for this frame - the value nearly everything
	 * else in this class derives from. Cached per frame; treat the result as read-only.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 */
	public float[] getSunAngles() {
		if (frameSunAngles == null)
			frameSunAngles = computeSunAngles();
		return frameSunAngles;
	}

	private float[] computeSunAngles() {
		// Fixed modes return their fixed angle directly, bypassing the time machinery.
		// Every sun-position-dependent value (sky gradient colors, brightness, blend
		// factors) reads this, so they all use the fixed position automatically.
		if (currentCycleMode.isFixed)
			return fixedToAstronomicalAngles(getFixedModeSunAngles());
		double[] angles = AtmosphereUtils.getSunAngles(currentInstant.toEpochMilli(), currentLatLong);
		return new float[] { (float) angles[0], (float) angles[1] };
	}

	/**
	 * Procedural directional-light color for a sun angle. This is renderer policy rather than
	 * astronomical calculation, so it stays alongside the regional blend that consumes it.
	 */
	private static float[] getDirectionalLightForAngles(float[] sunAngles) {
		float[] directionalLight = multiply(ColorUtils.colorTemperatureToLinearRgb(4100), .1f);
		if (sunAngles[1] >= 0) {
			float temperature = interpolate(sunAngles[1] * RAD_TO_DEG, DIRECTIONAL_TEMPERATURE_KEYFRAMES);
			float strength = sin(sunAngles[1]);
			strength *= strength;
			strength *= 3;
			add(directionalLight, directionalLight, multiply(ColorUtils.colorTemperatureToLinearRgb(temperature), strength));
		}
		return directionalLight;
	}

	private static float[] getAmbientColorForAngles(float[] sunAngles) {
		return interpolateLinear(sunAngles[1] * RAD_TO_DEG, AMBIENT_COLOR_KEYFRAMES);
	}

	private static float interpolate(float x, float[][] keyframesDegreesValue) {
		int end = keyframesDegreesValue.length - 1;
		int i = 0;
		while (i < end && x > keyframesDegreesValue[i + 1][0])
			i++;

		if (i == end)
			return keyframesDegreesValue[end][1];

		float[] from = keyframesDegreesValue[i];
		float[] to = keyframesDegreesValue[i + 1];
		float t = clamp((x - from[0]) / (to[0] - from[0]), 0, 1);
		return mix(from[1], to[1], t);
	}

	private static float[] interpolateSrgb(float x, float[][] keyframesDegreesSrgb) {
		int end = keyframesDegreesSrgb.length - 1;
		int i = 0;
		while (i < end && x > keyframesDegreesSrgb[i + 1][0])
			i++;

		float[] from = keyframesDegreesSrgb[i];
		if (i == end)
			return srgbToLinear(new float[] { from[1], from[2], from[3] });

		float[] to = keyframesDegreesSrgb[i + 1];
		float t = clamp((x - from[0]) / (to[0] - from[0]), 0, 1);
		return mix(
			srgbToLinear(new float[] { from[1], from[2], from[3] }),
			srgbToLinear(new float[] { to[1], to[2], to[3] }),
			t
		);
	}

	private static float[] interpolateLinear(float x, float[][] keyframesDegreesLinear) {
		int end = keyframesDegreesLinear.length - 1;
		int i = 0;
		while (i < end && x > keyframesDegreesLinear[i + 1][0])
			i++;

		float[] from = keyframesDegreesLinear[i];
		if (i == end)
			return new float[] { from[1], from[2], from[3] };

		float[] to = keyframesDegreesLinear[i + 1];
		float t = clamp((x - from[0]) / (to[0] - from[0]), 0, 1);
		return new float[] {
			mix(from[1], to[1], t),
			mix(from[2], to[2], t),
			mix(from[3], to[3], t),
		};
	}

	/**
	 * The scene's directional (sun/moon) light color: the cycle's own color for the current
	 * sun altitude, blended toward the area's regional color as the sun climbs.
	 * Both inputs and the result are in linear space.
	 */
	public float[] getRegionalDirectionalLight(float[] regionalDirectionalColor) {
		float[] sunAngles = getSunAngles();
		float[] dynamicLight = getDirectionalLightForAngles(sunAngles);
		return mixColor(dynamicLight, regionalDirectionalColor, regionalBlendFactor(sunAngles[1] * RAD_TO_DEG));
	}

	/**
	 * The scene's ambient light color. Mirrors {@link #getRegionalDirectionalLight}, sharing
	 * its blend factor so ambient and directional light stay consistent with the skybox.
	 */
	public float[] getRegionalAmbientLight(float[] regionalAmbientColor) {
		float[] sunAngles = getSunAngles();
		float[] dynamicAmbient = getAmbientColorForAngles(sunAngles);
		return mixColor(dynamicAmbient, regionalAmbientColor, regionalBlendFactor(sunAngles[1] * RAD_TO_DEG));
	}

	/**
	 * Sky gradient colors for the current time of day, as
	 * { zenithColor, horizonColor, sunGlowColor } in sRGB.
	 *
	 * <p>The base colors come from the procedural keyframe tables, indexed by sun altitude.
	 * Four adjustments are then layered on, in order:
	 * <ol>
	 *   <li><b>sunStrength</b> - pulls a dark area's sky away from the procedural sunset
	 *       colors, toward the area's own color by day and the night sky after dusk.</li>
	 *   <li><b>sunriseSunsetStrength</b> - holds a strongly-colored area at its own color
	 *       right through the twilight window, so e.g. a blood-red sky doesn't turn blue at
	 *       sunrise.</li>
	 *   <li><b>the daytime regional blend</b> - as the sun climbs, the area's own color takes
	 *       over from the procedural gradient, completely by {@code skyColorTakeoverAngle}.</li>
	 *   <li><b>the night blend</b> - once the sun is well down, everything resolves to the
	 *       generic night sky so the moon tint and starfield (applied downstream) take over.</li>
	 * </ol>
	 *
	 * @param regionalFogColor      the area's own sky/fog color (sRGB), or null for none
	 * @param sunStrength           1 = full procedural sun, 0 = fully suppressed
	 * @param sunriseSunsetStrength 1 = full procedural twilight, 0 = hold the regional color
	 * @param skyColorTakeoverAngle sun altitude (degrees) at which the regional color fully wins
	 */
	public float[][] getSkyGradientColors(
		float[] regionalFogColor,
		float sunStrength,
		float sunriseSunsetStrength,
		float skyColorTakeoverAngle
	) {
		float sunAltitude = getSunAngles()[1] * RAD_TO_DEG;

		// Sun altitude at which the area's own color has fully taken over from the
		// procedural sunrise/sunset gradient. Shared by the sunrise/sunset suppression
		// window and the daytime regional blend so they stay in sync. Clamped to >= 0;
		// 0 means the regional color takes over immediately at the horizon.
		float takeover = max(0, skyColorTakeoverAngle);
		float[] regionalLin = regionalFogColor != null ? srgbToLinear(regionalFogColor) : null;

		float[] zenith = interpolateSrgb(sunAltitude, ZENITH_KEYFRAMES);
		float[] horizon = interpolateSrgb(sunAltitude, HORIZON_KEYFRAMES);
		float[] sunGlow = interpolateSrgb(sunAltitude, SUN_GLOW_KEYFRAMES);

		// 1. sunStrength: suppress the procedural sunset colors for dark environments.
		// Full suppression above the horizon (the regional blend below takes over from
		// there); below it, fade out by -25° where the night colors dominate anyway.
		if (regionalLin != null && sunStrength < 1) {
			float window = sunAltitude >= 0 ? 1 : smoothstep(-25, 0, sunAltitude);
			float suppression = (1 - sunStrength) * window;
			if (suppression > 0) {
				// Crossfade the blend target between regional and night sky around the
				// horizon, so there's no hard color jump as the sun crosses it.
				float[] target = mixColor(regionalLin, NIGHT_SKY_LINEAR, smoothstep(5, -5, sunAltitude));
				blendTowards(zenith, target, suppression);
				blendTowards(horizon, target, suppression);
				fadeOut(sunGlow, suppression); // the glow is additive, so suppress toward zero
			}
		}

		// 2. sunriseSunsetStrength: an independent per-area knob that stops the procedural
		// sunrise/sunset from overriding a strongly-colored area's own sky.
		//
		// Some areas set a vivid regional sky (e.g. Tolna's blood-red #290000) that is meant
		// to be the mood all day. The cycle's procedural twilight paints its own orange->blue
		// gradient over that, so at sunrise/sunset the intended red "turns blue". Lowering
		// this knob holds the sky at the area's OWN color through the twilight window
		// instead. The blend target is the regional color (NOT the night sky) so the area's
		// color is preserved rather than muted to black; the night blend in step 4 still
		// darkens things once the sun is well down, so nights stay dark regardless.
		//
		// The window's upper edge MUST be the same takeover angle used by step 3. If this
		// window closed earlier, there would be a gap where neither this suppression nor the
		// daytime blend holds the color, letting the raw keyframes show through - and those
		// are strongly blue at mid-high sun (the +15° zenith keyframe is 0x6496C8). That gap
		// was the "sky goes blue after sunrise before settling into the area's color" bug.
		if (regionalLin != null && sunriseSunsetStrength < 1) {
			float window;
			if (sunAltitude < 0) {
				window = smoothstep(-15, 0, sunAltitude); // Ramp in over deep night -> horizon
			} else if (takeover == 0) {
				// A zero-width takeover window must vanish rather than become a step.
				window = 0;
			} else {
				window = smoothstep(takeover, 0, sunAltitude); // Ramp out from horizon -> takeover
			}
			float suppression = (1 - sunriseSunsetStrength) * window;
			if (suppression > 0) {
				blendTowards(zenith, regionalLin, suppression);
				blendTowards(horizon, regionalLin, suppression);
				// Fade the additive orange/red halo so it doesn't fight the held color.
				fadeOut(sunGlow, suppression);
			}
		}

		// 3. Daytime regional blend, from peak sunset (0°) to fully regional at the takeover
		// angle. Lowering the takeover angle per-area pulls the regional color in earlier as
		// the sun climbs, so a strongly-colored sky wins sooner in the morning.
		if (regionalLin != null) {
			// takeover == 0 is the degenerate case: the regional color wins the moment the
			// sun clears the horizon, so there is no ramp to walk up.
			float blend = sunAltitude < 0 ? 0 : (takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitude));
			if (blend > 0) {
				blendTowards(zenith, regionalLin, blend);
				blendTowards(horizon, regionalLin, blend);
			}
		}

		// 4. Night blend, mirroring step 3: ramp from 0° (none) to -15° (full night sky).
		// The night sky always resolves to this generic base so that, once the sun is well
		// down, the moon-color tint (applied downstream in the renderer) and the procedural
		// starfield take over - including in reduced sunriseSunsetStrength areas, where the
		// regional hold only spans the visible sunrise/sunset and must not persist into
		// deep night.
		float nightBlend = smoothstep(0, -15, sunAltitude);
		if (nightBlend > 0) {
			blendTowards(zenith, NIGHT_SKY_LINEAR, nightBlend);
			blendTowards(horizon, NIGHT_SKY_LINEAR, nightBlend);
		}

		// interpolateSrgb returns linear; the shader wants sRGB.
		return new float[][] { linearToSrgb(zenith), linearToSrgb(horizon), linearToSrgb(sunGlow) };
	}

	/**
	 * Reference horizon color at peak daytime, matching the skybox at high sun.
	 * Returns sRGB, same space as {@link #getSkyGradientColors} horizon output.
	 */
	public float[] getReferenceHorizonColor(float[] regionalFogColor) {
		if (regionalFogColor != null)
			return regionalFogColor;

		float[] horizonLinear = interpolateSrgb(90, HORIZON_KEYFRAMES);
		return linearToSrgb(horizonLinear);
	}

	/**
	 * Get the sun direction vector for sky gradient rendering.
	 * Returns normalized direction FROM the camera TO the sun.
	 * Uses the same coordinate transformation as the shadow light direction.
	 */
	public float[] getSunDirectionForSky() {
		if (frameSunDirectionForSky == null)
			frameSunDirectionForSky = computeSunDirectionForSky();
		return frameSunDirectionForSky;
	}

	/**
	 * Write the sun's shadow-camera angles as {pitch, yaw}. Astronomical angles are
	 * {azimuth, altitude}, while fixed environment angles are authored as {altitude, azimuth}.
	 * Both require a half-turn azimuth compensation before driving the directional camera.
	 */
	private float[] getSunShadowAngles(float[] out) {
		if (hasFixedSunOverride()) {
			float[] fixedSunAngles = getFixedSunAngles();
			return setShadowAngles(out, fixedSunAngles[0], fixedSunAngles[1] + PI);
		}

		float[] sunAngles = getSunAngles();
		return setShadowAngles(out, sunAngles[1], sunAngles[0] + PI);
	}

	/**
	 * Write the shadow camera angles for the body currently lighting the scene. Fixed sun and
	 * moon overrides take precedence; otherwise the moon takes over after sunset when it is
	 * still above the lighting horizon.
	 */
	public float[] getDirectionalShadowAngles(float[] out) {
		if (hasFixedSunOverride())
			return getSunShadowAngles(out);

		if (currentCycleMode.isLocksMoonPosition() || hasFixedMoonOverride())
			return getMoonShadowAngles(out);

		if (getSunAngles()[1] * RAD_TO_DEG < SUN_SHADOW_CUTOFF_DEG
			&& getMoonAltitudeDegrees() > MOON_SHADOW_CUTOFF_DEG) {
			return getMoonShadowAngles(out);
		}

		return getSunShadowAngles(out);
	}

	/**
	 * Whether the active cycle mode suppresses the visible moon disk.
	 */
	public boolean hidesMoon() {
		return currentCycleMode.isHidesMoon();
	}

	private float[] computeSunDirectionForSky() {
		// getSunAngles() already handles the fixed modes (per-environment override or
		// the built-in per-mode constant) and shares the per-frame snapshot, so the
		// sun disk direction is derived from the same solve as everything else.
		float[] sunAngles = getSunAngles();

		// sunAngles[0] = azimuth, sunAngles[1] = altitude
		// The renderers use: pitch = altitude, yaw = PI - azimuth
		// This matches how lightDir is calculated in ZoneRenderer and LegacyRenderer
		return anglesToSkyDirection(sunAngles[0], sunAngles[1]);
	}

	// ===== Moon ==================================================================

	/**
	 * Get the moon direction vector for sky rendering, respecting moon behavior mode.
	 */
	public float[] getMoonDirectionForSky() {
		if (frameMoonDirectionForSky == null)
			frameMoonDirectionForSky = computeMoonDirectionForSky();
		return frameMoonDirectionForSky;
	}

	/**
	 * Write the moon's shadow-camera angles as {pitch, yaw}. Fixed moon positions use the
	 * environment order directly; moving moons use the astronomical {azimuth, altitude} order.
	 */
	private float[] getMoonShadowAngles(float[] out) {
		if (currentCycleMode.isLocksMoonPosition() || hasFixedMoonOverride()) {
			float[] fixedMoonAngles = getFixedNightMoonAngles();
			return setShadowAngles(out, fixedMoonAngles[0], fixedMoonAngles[1]);
		}

		float[] moonAngles = getMoonAngles();
		return setShadowAngles(out, moonAngles[1], moonAngles[0] + PI);
	}

	private static float[] setShadowAngles(float[] out, float pitch, float yaw) {
		out[0] = pitch;
		out[1] = yaw;
		return out;
	}

	private float[] computeMoonDirectionForSky() {
		float[] moonAngles = getMoonAngles();
		return anglesToSkyDirection(moonAngles[0], moonAngles[1]);
	}

	/**
	 * The moon's {azimuth, altitude} in radians for this frame. Fixed modes return the
	 * fixed moon position; otherwise the selected moon behavior determines its position.
	 * The result is cached with the rest of the frame's astronomy snapshot.
	 */
	private float[] getMoonAngles() {
		if (frameMoonAngles == null)
			frameMoonAngles = computeMoonAngles();
		return frameMoonAngles;
	}

	private float[] computeMoonAngles() {
		// A fixed-mode moon override (or the default Fixed Night position) locks the moon
		// regardless of moon behavior. ALWAYS_NIGHT is excluded so its moon keeps moving.
		if (currentCycleMode.isLocksMoonPosition() || hasFixedMoonOverride())
			return fixedToAstronomicalAngles(getFixedNightMoonAngles());
		if (currentMoonBehavior == MoonBehavior.NIGHT_SYNCED)
			return getNightSyncedMoonAngles();

		double[] angles = AtmosphereUtils.getMoonPosition(getMoonDate().toEpochMilli(), currentLatLong);
		return new float[] { (float) angles[0], (float) angles[1] };
	}

	/**
	 * Get the moon illumination fraction, respecting the moon phase lock and behavior mode.
	 * A config phase lock takes precedence; otherwise Night Synced mode derives illumination
	 * from the advancing equinox date so the phase cycles naturally (each game cycle = +1 day).
	 */
	public float getMoonIlluminationFraction() {
		if (frameMoonIllumination == null)
			frameMoonIllumination = computeMoonIlluminationFraction();
		return frameMoonIllumination;
	}

	private float computeMoonIlluminationFraction() {
		if (currentMoonPhase.isLocked()) {
			return currentMoonPhase.illumination; // Phase locked via config
		}
		if (currentCycleMode.isLocksMoonIllumination()) {
			return 1.0f; // Always a full moon
		}
		// Real Time: use the same locally anchored timestamp as the sun and moon position.
		// It advances continuously from session start, so daylight-saving changes cannot make
		// the lunar phase jump relative to the rest of the cycle.
		if (currentCycleMode.usesLocalTime) {
			return (float) AtmosphereUtils.getMoonIllumination(currentInstant.toEpochMilli())[0];
		}
		if (currentMoonBehavior == MoonBehavior.NIGHT_SYNCED) {

			// Synced Days: advance the phase by the UTC-synced day count so the phase
			// is identical for all players; otherwise use the stateful night offset.
			long phaseDay = currentCycleMode.usesUtcSyncedTime
				? frameWallClockMillis / SYNCED_DAYS_PERIOD_MS
				: nightSyncedDayOffset;
			long phaseMillis = EQUINOX_EPOCH_MS + phaseDay * DAY_MS;
			return (float) AtmosphereUtils.getMoonIllumination(phaseMillis)[0];
		}

		Instant moonDate = getMoonDate();
		return (float) AtmosphereUtils.getMoonIllumination(moonDate.toEpochMilli())[0];
	}

	/**
	 * Get the moon altitude in degrees, respecting moon behavior mode.
	 */
	public float getMoonAltitudeDegrees() {
		if (frameMoonAltitudeDegrees == null)
			frameMoonAltitudeDegrees = computeMoonAltitudeDegrees();
		return frameMoonAltitudeDegrees;
	}

	/**
	 * Moon altitude used by scene lighting. Fixed Night already gets its authored position from
	 * {@link #getMoonAngles()}; Always Night uses that same altitude so its moving moon cannot
	 * leave a permanently dark sky.
	 */
	public float getMoonAltitudeDegreesForLighting() {
		return currentCycleMode.isUsesFixedMoonAltitudeForLighting()
			? getFixedNightMoonAngles()[0] * RAD_TO_DEG
			: getMoonAltitudeDegrees();
	}

	private float computeMoonAltitudeDegrees() {
		return getMoonAngles()[1] * RAD_TO_DEG;
	}

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
	public float getAuroraStrength() {
		if (!isAuroraNight())
			return 0;

		if (!currentCycleMode.isPermanentNight())
			return 1;

		// Position within the current cycle. The night index flips at 0.35 (midday),
		// so re-center the envelope on that boundary: auroras are absent right after a
		// flip, swell to full a bit past mid-cycle, then fade back out before the next
		// flip. phase in [0,1) measured from the 0.35 flip point.
		float phase = (float) accumulatedCycleTime - .35f;
		phase -= floor(phase); // wrap into [0, 1)

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

	/**
	 * Get night synced moon angles {azimuth, altitude} by mirroring the sun.
	 * The moon is placed opposite the sun (azimuth + PI) with negated altitude,
	 * so it rises when the sun sets and vice versa.
	 * <p>
	 * Uses a fixed equinox base date plus a day offset that only advances
	 * while the moon is below the horizon. This means the moon's phase
	 * changes cycle-to-cycle, but the shift is never visible because it
	 * only happens when the moon can't be seen.
	 */
	private float[] getNightSyncedMoonAngles() {
		if (frameNightSyncedMoonAngles == null)
			frameNightSyncedMoonAngles = computeNightSyncedMoonAngles();
		return frameNightSyncedMoonAngles;
	}

	private float[] computeNightSyncedMoonAngles() {
		// Real Time: mirror the sun computed from the player's real local clock -
		// the same instant the REAL_TIME sun/realistic-moon use - so moonrise tracks
		// the real sunset and the moon spans the real night's length. Bypasses the
		// cycle-duration accumulator entirely; without this, the night-synced moon
		// would follow Cycle Duration while the sky follows the real clock.
		if (currentCycleMode.usesLocalTime) {
			double[] sa = AtmosphereUtils.getSunAngles(currentInstant.toEpochMilli(), currentLatLong);
			return new float[] { (float) sa[0] + PI, (float) -sa[1] };
		}

		// Synced Days: derive the moon's mirror position and phase purely from the
		// UTC clock so the night-synced moon is identical for every player, matching
		// the UTC-synced sun. Stateless - bypasses the pending-increment machinery.
		if (currentCycleMode.usesUtcSyncedTime) {
			double[] sa = AtmosphereUtils.getSunAngles(currentInstant.toEpochMilli(), currentLatLong);
			return new float[] { (float) sa[0] + PI, (float) -sa[1] };
		}

		// Warp identically to the sun so the night-synced moon stays aligned with
		// the (now re-sized) day & night periods - moonrise still tracks visual sunset.
		double cyclePosition = getMoonCyclePosition();

		// Use a uniform linear mapping: cycle 0→1 maps to a full 24-hour day.
		// This gives the moon constant angular speed across its whole arc,
		// unlike the piecewise mapping used for the sun which slows at twilight.
		//
		// The start hour is chosen so that the equinox sunset (~19:00) falls
		// at cycle position ~0.65, matching when the piecewise sun visually
		// reaches the horizon. This keeps moonrise aligned with visual sunset.
		// 19 = start + 0.65 * 24  =>  start ≈ 3.4
		double mappedHour = 3.4 + cyclePosition * 24;
		if (mappedHour >= 24) mappedHour -= 24;

		// Detect newly completed cycles and queue them as pending
		long newCycles = completedCycles - lastNightSyncedCycles;
		if (newCycles > 0) {
			pendingDayIncrements += newCycles;
			lastNightSyncedCycles = completedCycles;
		}

		long fixedMillis = EQUINOX_EPOCH_MS + nightSyncedDayOffset * DAY_MS
			+ hoursToMillis(mappedHour);

		double[] sunAngles = AtmosphereUtils.getSunAngles(fixedMillis, currentLatLong);
		float moonAltitude = (float) -sunAngles[1];

		// Apply pending day increments only once the moon is far enough down that its light
		// no longer reaches the scene. A whole simulated day of phase lands at once, which
		// near the quarters is a ~0.10 step in illumination, so doing this anywhere the moon
		// still contributes shows up as a sudden brightness jump.
		//
		// The geometric horizon is not far enough: DayNightLighting fades moonlight out over
		// MOON_ELEVATION_FADE_START_DEG..END and only stops lighting below
		// MOON_HORIZON_CUTOFF_DEG, both -10 degrees, so between -10 and 0 the moon is under
		// the horizon yet still lighting and shadowing the world. Waiting for that same
		// altitude puts the phase change where every consumer already reads zero.
		if (pendingDayIncrements > 0 && moonAltitude < MOON_PHASE_ADVANCE_ALTITUDE_RAD) {
			nightSyncedDayOffset += pendingDayIncrements;
			pendingDayIncrements = 0;
		}

		return new float[] { (float) sunAngles[0] + PI, moonAltitude };
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

	private Instant getRealTimeInstant() {
		return Instant.ofEpochMilli(realTimeStartEpochMillis + frameWallClockMillis - realTimeSessionStartMillis);
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

	/**
	 * Synced Days cycle position in [0, 1): where we are within the current UTC
	 * hour. Stateless and identical for every player at a given UTC instant.
	 */
	private double getSyncedDaysCyclePosition(long currentTimeMillis) {
		return (currentTimeMillis % SYNCED_DAYS_PERIOD_MS) / (double) SYNCED_DAYS_PERIOD_MS;
	}

	// ===== Simulated clock =======================================================

	// Stateful: advances accumulatedCycleTime/completedCycles, and re-pins the
	// instant every getter derives from. Called once per frame, so the astronomy
	// snapshot invalidated here is rebuilt at most once per frame.
	public void update() {
		DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
		MoonPhase forcedMoonPhase = environmentManager.getForcedMoonPhase();
		currentCycleMode = forcedMode != null ? forcedMode : configuredCycleMode;
		currentMoonPhase = forcedMoonPhase != null ? forcedMoonPhase : configuredMoonPhase;
		setFixedAngleOverrides(
			environmentManager.getForcedFixedSunAngles(),
			environmentManager.getForcedFixedMoonAngles()
		);
		beginFrame();
		updateSeasonalHemisphere();

		frameWallClockMillis = System.currentTimeMillis();
		frameWallClockInstant = Instant.ofEpochMilli(frameWallClockMillis);
		initializeRealTimeClock();
		long currentTimeMillis = frameWallClockMillis;
		currentInstant = frameWallClockInstant;

		// Initialize on first call
		if (lastUpdateTime == 0)
			lastUpdateTime = currentTimeMillis;

		// Calculate elapsed real time since last update
		long realTimeElapsed = currentTimeMillis - lastUpdateTime;

		// Convert cycle duration from minutes to milliseconds for the full cycle
		double cycleDurationMillis = currentCycleDuration * 60.0 * 1000.0; // minutes to milliseconds

		// Calculate how much cycle time has progressed based on current day length
		double cycleTimeElapsed = realTimeElapsed / cycleDurationMillis;

		// Add to accumulated cycle time to maintain continuity
		accumulatedCycleTime += cycleTimeElapsed;

		// Track completed cycles (each = one simulated day) for moon phase progression
		while (accumulatedCycleTime >= 1.0) {
			accumulatedCycleTime -= 1.0;
			completedCycles++;
		}

		lastUpdateTime = currentTimeMillis;

		switch (currentCycleMode) {
			case REAL_TIME:
				// The session-local timestamp advances in Unix time, so daylight-saving changes
				// cannot cause a discontinuity in the sun, moon, or seasonal date.
				currentInstant = getRealTimeInstant();
				break;
			case SYNCED_DAYS:
				// A full day & night per real UTC hour. The resulting sky is identical for all
				// players and independent of Cycle Duration.
				double syncedCyclePosition = getSyncedDaysCyclePosition(currentTimeMillis);
				long syncedDay = currentTimeMillis / SYNCED_DAYS_PERIOD_MS;
				Instant syncedStartOfDay = Instant.EPOCH.plus(syncedDay, ChronoUnit.DAYS);
				currentInstant = syncedStartOfDay.plusMillis(hoursToMillis(cyclePositionToHour(syncedCyclePosition)));
				break;
			case FIXED_DAWN:
			case FIXED_MIDDAY:
			case FIXED_SUNSET:
			case FIXED_TWILIGHT:
			case FIXED_NIGHT:
			case ALWAYS_NIGHT:
				// Cycle tracking above continues for moon calculations, but the sun's instant
				// remains at this mode's authored time of day.
				long baseEpochMs = currentCycleMode.isUsesSolsticeEpoch() ? SOLSTICE_EPOCH_MS : EQUINOX_EPOCH_MS;
				currentInstant = Instant.ofEpochMilli(baseEpochMs).plusMillis(hoursToMillis(currentCycleMode.getFixedHour()));
				break;
			case DYNAMIC:
				// Warp the linear cycle clock so day and night occupy the configured share,
				// then feed it through the twilight-weighted sun mapping.
				double cyclePosition = applyDayLengthWarp(accumulatedCycleTime);
				double mappedHour = cyclePositionToHour(cyclePosition);
				Instant startOfDay = currentInstant.truncatedTo(ChronoUnit.DAYS)
					.plus(completedCycles, ChronoUnit.DAYS);
				currentInstant = startOfDay.plusMillis(hoursToMillis(mappedHour));
				break;
		}
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
		if (currentCycleMode.usesLocalTime) {
			return currentInstant;
		}

		// Synced Days mode: use the same UTC-derived instant as the sun so the moon
		// stays coherent with it and is identical for every player. One simulated
		// day advances per completed UTC hour.
		if (currentCycleMode.usesUtcSyncedTime) {
			return currentInstant;
		}

		// Total simulated days elapsed = completed whole cycles + current cycle progress.
		// Warp only the within-cycle fraction so the realistic moon's position tracks
		// the re-sized day & night, while whole completed cycles still advance the lunar
		// phase linearly (preventing phase jitter from the warp).
		long totalOffsetMillis = (long) ((completedCycles + getMoonCyclePosition()) * DAY_MS);

		return startOfDay.plusMillis(totalOffsetMillis);
	}

	public float getNightLightFactor() {
		// Fixed Twilight/Sunset should contribute the same partial night factor as a
		// moving sky at that altitude; fixed modes already return their authored sun angle.
		float[] sunAngles = getSunAngles();
		float sunAltitudeDegrees = sunAngles[1] * RAD_TO_DEG;

		if (sunAltitudeDegrees >= 5)
			return 0;
		if (sunAltitudeDegrees <= -18)
			return 1;

		return smoothstep(5, -18, sunAltitudeDegrees);
	}

	public float getDynamicBrightnessMultiplier(int minimumBrightness) {
		// getSunAngles() returns the fixed angle in fixed modes, so brightness tracks the
		// fixed sun altitude there instead of an incremented-time position.
		float[] sunAngles = getSunAngles();

		// Calculate sun altitude in degrees (-90 to 90, where 90 is directly overhead)
		float sunAltitudeDegrees = sunAngles[1] * RAD_TO_DEG;

		// Convert minimum brightness from percentage to decimal
		float minBrightness = minimumBrightness / 100.0f;
		float horizonBrightness = minBrightness + 0.10f; // 10% brighter at horizon

		if (sunAltitudeDegrees <= -18) {
			// Deep night: minimum brightness
			return minBrightness;
		} else if (sunAltitudeDegrees <= -5) {
			// Night: smoothstep from minBrightness at -18° to twilightBrightness at -5°
			// twilightBrightness is partway between min and horizon
			float twilightBrightness = minBrightness + 0.07f;
			float s = smoothstep(-18, -5, sunAltitudeDegrees);
			return minBrightness + (twilightBrightness - minBrightness) * s;
		} else if (sunAltitudeDegrees <= 5) {
			// Horizon transition: smoothstep from twilightBrightness at -5° to earlyDayBrightness at +5°
			// This zone spans the critical 0° boundary with a single smooth curve
			float twilightBrightness = minBrightness + 0.07f;
			float earlyDayBrightness = horizonBrightness + 0.05f;
			float s = smoothstep(-5, 5, sunAltitudeDegrees);
			return twilightBrightness + (earlyDayBrightness - twilightBrightness) * s;
		} else {
			// Daytime: sine curve from earlyDayBrightness at +5° to peak at 90°.
			// Peak is 1.0 so the brightest part of the day matches the environment's
			// base strengths (i.e. how the world looks with the cycle disabled).
			float earlyDayBrightness = horizonBrightness + 0.05f;
			float peakBrightness = 1.2f;
			float sineFactor = sin(sunAltitudeDegrees * DEG_TO_RAD);
			// Scale so that at 5°, we match earlyDayBrightness
			float sineAt5 = sin(5 * DEG_TO_RAD);
			float normalizedSine = max(0, (sineFactor - sineAt5) / (1 - sineAt5));
			return earlyDayBrightness + (peakBrightness - earlyDayBrightness) * normalizedSine;
		}
	}

	/**
	 * Apply the current sky's color and strength response to an outdoor light. Light definitions
	 * remain the source of the authored daytime color; this only applies the day/night response
	 * for definitions that opt in.
	 */
	public void applyOutdoorLightLighting(Light light, int[] worldPos, int minimumBrightness) {
		EnvironmentManager.OutdoorSkySample sky = environmentManager.sampleOutdoorSky(worldPos, minimumBrightness);
		float[] authoredColor = light.def.color;
		float defLuma = dot(authoredColor, SKY_LUMA_WEIGHTS);
		float noonLuma = dot(sky.noonHorizonLinear, SKY_LUMA_WEIGHTS);
		float[] lightColor = copy(sky.horizonLinear);
		double sunAltDeg = getSunAngles()[1] * RAD_TO_DEG;

		// At night, blend the dark sky horizon toward moonColor: reduces the blue cast and adds
		// silver moonlight filtering through tunnel openings. moonStrengthFloor keeps deep-night
		// lights visibly moonlit even when the sampled sky brightness is near zero.
		float moonStrengthFloor = 0;
		if (sunAltDeg < 5) {
			float moonAltDeg = getMoonAltitudeDegreesForLighting();
			float moonIllumination = getMoonIlluminationFraction();
			if (moonAltDeg > -5 && moonIllumination > .01f) {
				float sunFade = (float) Math.max(0.0, Math.min(1.0, (5.0 - sunAltDeg) / 10.0));
				float moonElevation = (float) Math.min(1.0, Math.max(0.0, (moonAltDeg + 5.0) / 25.0));
				float moonElevationSmooth = moonElevation * moonElevation * (3 - 2 * moonElevation);
				float moonBlend = moonIllumination * .25f * moonElevationSmooth * sunFade;
				lightColor = mix(lightColor, environmentManager.currentMoonLightColor, moonBlend);
				moonStrengthFloor = moonIllumination * .12f * moonElevationSmooth;
			}
		}

		// Desaturate toward gray as the sun climbs - high sun produces whiter, more neutral light.
		if (sunAltDeg > 0) {
			float desaturation = smoothstep(0, 90, (float) sunAltDeg) * .75f;
			float luma = dot(lightColor, SKY_LUMA_WEIGHTS);
			for (int i = 0; i < 3; i++)
				lightColor[i] = mix(lightColor[i], luma, desaturation);
		}

		float horizonLuma = dot(lightColor, SKY_LUMA_WEIGHTS);
		// Only around midday does the light show its authored color; at sunrise/sunset and
		// through the night it is tinted by the sky instead.
		float middayFactor = smoothstep(15, 30, (float) sunAltDeg);
		if (middayFactor > 0)
			lightColor = mix(lightColor, authoredColor, middayFactor);

		System.arraycopy(lightColor, 0, light.color, 0, 3);
		float peakScale = defLuma / max(noonLuma, 1e-4f);
		float timeScale = max(min(horizonLuma / max(noonLuma, 1e-4f), 1) * sky.brightnessMultiplier, moonStrengthFloor);
		light.strength *= mix(peakScale * timeScale, 1, middayFactor);
	}
}
