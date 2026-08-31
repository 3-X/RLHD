package rs117.hd.scene.daylight_cycle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Resolves per-frame celestial state and the day/night light schedule.
 * {@link SkyLighting} turns that state into renderer lighting. Angles use
 * EnvironmentManager's {@code {altitude, azimuth}} convention in radians.
 */
@Singleton
public class DaylightCycleManager {
	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	private static final float NIGHT_RADIUS_BOOST_FRACTION = .25f;
	private static final float NIGHT_STAGGER_RAMP_WIDTH = .08f;

	// One UTC-synchronized simulated day per real hour.
	private static final long SYNCED_DAYS_PERIOD_MS = 60L * 60 * 1000;

	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	private static final long HOUR_MS = 60L * 60 * 1000;

	// 2025-03-20 UTC, near the spring equinox.
	private static final long EQUINOX_EPOCH_MS = 1742428800000L;
	// 2025-06-10 UTC, near the summer solstice.
	private static final long SOLSTICE_EPOCH_MS = 1749513600000L;

	// 5am–7pm occupies the first 70% of the unwarped cycle.
	private static final float NATURAL_DAY_BOUNDARY = .7f;

	// Deterministic per-night aurora probability for aurora-eligible environments.
	private static final float AURORA_NIGHT_CHANCE = .02f;

	// Fixed Night moon position, in the south-east sky.
	private static final float[] FIXED_NIGHT_MOON_ANGLES = HDUtils.sunAngles(25, 135);

	// Representative seasonal latitudes; longitude is irrelevant to the simulated clock.
	private static final double[] NORTHERN_LAT_LONG = { 40.7128, 0.0 };  // New York City
	private static final double[] SOUTHERN_LAT_LONG = { -22.9068, 0.0 }; // Rio de Janeiro

	private boolean hasFixedSunOverride;
	private boolean moonPositionFixed;

	// Night Synced advances lunar phase only after its light has faded out.
	private long nightSyncedDayOffset = 0;
	private long lastNightSyncedCycles = 0;
	private long pendingDayIncrements = 0;

	// Moon lighting fades out at −10°, so phase changes below this threshold are invisible.
	private static final float MOON_PHASE_ADVANCE_ALTITUDE_RAD = -10 * DEG_TO_RAD;
	private static final double NIGHT_SYNCED_MOON_START_HOUR = 3.4;

	// Hand shadows to a still-lit moon before sunset to avoid an orientation pop.
	private static final float SUN_SHADOW_CUTOFF_DEG = 2;
	private static final float MOON_SHADOW_CUTOFF_DEG = -10;
	// Suppress sub-pixel shadow-camera movement; faster cycles use a smaller threshold.
	private static final float DIRECTIONAL_ANGLE_UPDATE_THRESHOLD = .25f * DEG_TO_RAD;

	private long lastUpdateTime = 0;
	// Start Dynamic at midday.
	private double accumulatedCycleTime = .35;
	private long completedCycles = 0; // Each completed cycle = one simulated day

	private DaylightCycle configCycle;
	private DayLength configDayLength;
	private MoonPhase configMoonPhase;
	private MoonBehavior configMoonBehavior;
	private float configCycleDuration;

	private DaylightCycle currentCycle = DaylightCycle.DYNAMIC;
	private MoonPhase currentMoonPhase = MoonPhase.DYNAMIC;

	private final double[] currentLatLong = { 0, 0 };

	private Instant currentInstant;

	// Retain the frame's wall clock because currentInstant is often simulated.
	private long frameWallClockMillis;
	private Instant frameWallClockInstant;

	// Local time at startup, then a continuously advancing Unix timestamp.
	private long realTimeStartEpochMillis = Long.MIN_VALUE;
	private long realTimeSessionStartMillis;

	private boolean lightScheduleActive;
	private float nightFactor = 1;
	private boolean nightFactorIncreasing = true;
	private float previousNightFactor = -1;

	@Getter
	private boolean cycleActive;

	@Getter
	private final DaylightCycleState state = new DaylightCycleState();

	// ===== Per-frame state =======================================================

	public void updateConfig(HdPluginConfig config) {
		configCycle = config.daylightCycle();
		configDayLength = config.dayLength();
		configMoonBehavior = config.moonBehavior();
		configMoonPhase = config.moonPhase();
		configCycleDuration = config.cycleDurationMinutes();
	}

	/** Synced Days always uses the northern latitude so all players see the same sky. */
	private void updateSeasonalHemisphere() {
		double[] latLong = currentCycle.isForcesNorthernHemisphere() || plugin.configSeasonalHemisphere != SeasonalHemisphere.SOUTHERN
			? NORTHERN_LAT_LONG
			: SOUTHERN_LAT_LONG;
		currentLatLong[0] = latLong[0];
		currentLatLong[1] = latLong[1];
	}

	/** Convert {altitude, azimuth} to a normalized sky direction. */
	private float[] anglesToSkyDirection(float... angles) {
		return normalize(
			sin(angles[1]) * cos(angles[0]),
			sin(angles[0]),
			cos(angles[1]) * cos(angles[0])
		);
	}

	/** Remap a linear cycle position so day and night occupy the configured shares. */
	private double applyDayLengthWarp(double cyclePosition) {
		float dayFraction = configDayLength.dayFraction;
		if (abs(dayFraction - NATURAL_DAY_BOUNDARY) < 1e-6f)
			return cyclePosition;

		if (cyclePosition < dayFraction) {
			return (cyclePosition / dayFraction) * NATURAL_DAY_BOUNDARY;
		} else {
			double nightProgress = (cyclePosition - dayFraction) / (1 - dayFraction);
			return NATURAL_DAY_BOUNDARY + nightProgress * (1 - NATURAL_DAY_BOUNDARY);
		}
	}

	/** Fixed-sun modes can still apply Day Length to an unlocked moon. */
	private double getMoonCyclePosition() {
		return currentCycle.usesDayLengthForMoon
			? applyDayLengthWarp(accumulatedCycleTime)
			: accumulatedCycleTime;
	}

	// ===== Sun and shadow directions =============================================

	private float[] computeSunAngles() {
		if (currentCycle.isFixed)
			return hasFixedSunOverride ? state.fixedSunAnglesOverride : currentCycle.getFixedSunAngles();
		return getSunAngles(currentInstant);
	}

	private float[] getSunAngles(Instant instant) {
		return vec(AstronomyUtils.getSunAngles(instant.toEpochMilli(), currentLatLong));
	}

	/** Update directional shadows while suppressing sub-threshold movement. */
	public void updateDirectionalCamera(Camera directionalCamera) {
		// Fixed sun overrides win; fixed moons otherwise drive shadows.
		boolean useMoonForShadows =
			!hasFixedSunOverride && (
				moonPositionFixed ||
				state.sunAngles[0] * RAD_TO_DEG < SUN_SHADOW_CUTOFF_DEG &&
				state.moonAltitudeDegrees > MOON_SHADOW_CUTOFF_DEG
			);
		float[] angles = useMoonForShadows ? state.moonAngles : state.sunAngles;

		float[] orientation = { PI - angles[1], angles[0] };
		float diff = max(abs(angleDiff(orientation, directionalCamera.getOrientation())));
		if (diff >= DIRECTIONAL_ANGLE_UPDATE_THRESHOLD * saturate(configCycleDuration / 300f))
			directionalCamera.setOrientation(orientation);
	}

	// ===== Moon ==================================================================

	private float[] computeMoonAngles() {
		// Fixed Night and fixed-mode moon overrides lock position; Always Night does not.
		if (moonPositionFixed)
			return state.fixedMoonAngles;
		if (configMoonBehavior == MoonBehavior.NIGHT_SYNCED)
			return computeNightSyncedMoonAngles();

		return vec(AstronomyUtils.getMoonPosition(getMoonDate().toEpochMilli(), currentLatLong));
	}

	private float computeMoonIlluminationFraction() {
		if (currentMoonPhase.isLocked())
			return currentMoonPhase.illumination;
		if (currentCycle.isLocksMoonIllumination())
			return 1;
		// Real Time keeps a Night Synced moon continuous through daylight-saving changes.
		if (configMoonBehavior != MoonBehavior.NIGHT_SYNCED || currentCycle.usesLocalTime)
			return getMoonIllumination(getMoonDate());

		// Synced Days shares its phase; other modes advance it while the moon is unlit.
		long phaseDay = currentCycle.usesUtcSyncedTime
			? frameWallClockMillis / SYNCED_DAYS_PERIOD_MS
			: nightSyncedDayOffset;
		return getMoonIllumination(Instant.ofEpochMilli(EQUINOX_EPOCH_MS + phaseDay * DAY_MS));
	}

	private static float getMoonIllumination(Instant instant) {
		return (float) AstronomyUtils.getMoonIllumination(instant.toEpochMilli())[0];
	}

	private float computeMoonAltitudeDegrees() {
		return state.moonAngles[0] * RAD_TO_DEG;
	}

	// ===== Aurora ================================================================

	/**
	 * Deterministically select an aurora night. The index changes at midday so the result
	 * never flips while an aurora is visible.
	 */
	private boolean isAuroraNight() {
		int nightIndex = max(1, (int) Math.floor(completedCycles + accumulatedCycleTime - .35) + 1);

		// SplitMix64 finalizer to a uniform 53-bit mantissa.
		long h = nightIndex * 0x9E3779B97F4A7C15L;
		h ^= (h >>> 30);
		h *= 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 27);
		h *= 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		double roll = (h >>> 11) * (1.0 / (1L << 53)); // [0, 1)

		return roll < AURORA_NIGHT_CHANCE;
	}

	/** Always-night modes use an explicit aurora envelope; other modes use the sky fade. */
	private float computeAuroraStrength() {
		if (!isAuroraNight())
			return 0;

		if (!currentCycle.isPermanentNight())
			return 1;

		float phase = fract((float) accumulatedCycleTime - .35f); // wrap into [0, 1)

		// Smooth bump around the middle of the simulated night.
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
		// These modes already have a shared sun position to mirror.
		if (currentCycle.usesLocalTime || currentCycle.usesUtcSyncedTime)
			return mirrorAngles(state.sunAngles);

		float[] sunAngles = getSunAngles(getNightSyncedMoonInstant());
		applyPendingNightSyncedDays(-sunAngles[0]);
		return mirrorAngles(sunAngles);
	}

	/** A uniform moon clock keeps moonrise near the visual sunset. */
	private Instant getNightSyncedMoonInstant() {
		double hour = NIGHT_SYNCED_MOON_START_HOUR + getMoonCyclePosition() * 24;
		if (hour >= 24)
			hour -= 24;
		return Instant.ofEpochMilli(EQUINOX_EPOCH_MS + nightSyncedDayOffset * DAY_MS + (long) (hour * HOUR_MS));
	}

	/** Apply queued lunar phase advances only while the moon is unlit. */
	private void applyPendingNightSyncedDays(float moonAltitude) {
		long newCycles = completedCycles - lastNightSyncedCycles;
		if (newCycles > 0) {
			pendingDayIncrements += newCycles;
			lastNightSyncedCycles = completedCycles;
		}

		if (pendingDayIncrements > 0 && moonAltitude < MOON_PHASE_ADVANCE_ALTITUDE_RAD) {
			nightSyncedDayOffset += pendingDayIncrements;
			pendingDayIncrements = 0;
		}
	}

	private static float[] mirrorAngles(float[] angles) {
		return vec(-angles[0], angles[1] + PI);
	}

	/** Anchor local time once to avoid daylight-saving discontinuities. */
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

	/** Map cycle position to the project's twilight-weighted hour of day. */
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

	/** Resolve moon angles before illumination, which may consume a queued phase change. */
	private void resolveState() {
		state.sunAngles = computeSunAngles();
		state.moonAngles = computeMoonAngles();
		state.moonIllumination = computeMoonIlluminationFraction();
		state.moonAltitudeDegrees = computeMoonAltitudeDegrees();
		state.moonAltitudeDegreesForLighting = currentCycle.isUsesFixedMoonAltitudeForLighting()
			? state.fixedMoonAngles[0] * RAD_TO_DEG
			: state.moonAltitudeDegrees;
		state.sunDirection = anglesToSkyDirection(state.sunAngles);
		state.moonDirection = anglesToSkyDirection(state.moonAngles);
		state.hidesMoon = currentCycle.isHidesMoon();
		state.auroraStrength = computeAuroraStrength();
	}

	private void resolveEnvironmentState() {
		cycleActive = environmentManager.isOverworld() && plugin.configEnableDayNightCycle;

		DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
		MoonPhase forcedMoonPhase = environmentManager.getForcedMoonPhase();
		currentCycle = forcedMode != null ? forcedMode : configCycle;
		currentMoonPhase = forcedMoonPhase != null ? forcedMoonPhase : configMoonPhase;
		float[] sunAngles = environmentManager.getForcedFixedSunAngles();
		float[] moonAngles = environmentManager.getForcedFixedMoonAngles();
		state.fixedSunAnglesOverride = sunAngles;
		state.fixedMoonAngles = moonAngles != null
			? moonAngles
			: FIXED_NIGHT_MOON_ANGLES;
		hasFixedSunOverride = currentCycle.isFixed && state.fixedSunAnglesOverride != null;
		moonPositionFixed = currentCycle.isLocksMoonPosition() || currentCycle.isFixed && moonAngles != null;
	}

	private void advanceCycle(long currentTimeMillis) {
		if (lastUpdateTime == 0)
			lastUpdateTime = currentTimeMillis;

		double cycleDurationMillis = configCycleDuration * 60.0 * 1000.0;
		accumulatedCycleTime += (currentTimeMillis - lastUpdateTime) / cycleDurationMillis;
		long cyclesElapsed = (long) accumulatedCycleTime;
		if (cyclesElapsed > 0) {
			accumulatedCycleTime -= cyclesElapsed;
			completedCycles += cyclesElapsed;
		}
		lastUpdateTime = currentTimeMillis;
	}

	private Instant resolveCurrentInstant() {
		if (currentCycle.isFixed) {
			long baseEpochMs = currentCycle.isUsesSolsticeEpoch() ? SOLSTICE_EPOCH_MS : EQUINOX_EPOCH_MS;
			return Instant.ofEpochMilli(baseEpochMs).plusMillis((long) (currentCycle.getFixedHour() * HOUR_MS));
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
				return syncedStartOfDay.plusMillis((long) (cyclePositionToHour(syncedCyclePosition) * HOUR_MS));
			case DYNAMIC:
				// Warp the linear cycle clock so day and night occupy the configured share,
				// then feed it through the twilight-weighted sun mapping.
				double cyclePosition = applyDayLengthWarp(accumulatedCycleTime);
				double mappedHour = cyclePositionToHour(cyclePosition);
				Instant startOfDay = frameWallClockInstant.truncatedTo(ChronoUnit.DAYS)
					.plus(completedCycles, ChronoUnit.DAYS);
				return startOfDay.plusMillis((long) (mappedHour * HOUR_MS));
		}
		throw new IllegalStateException("Unhandled day & night cycle mode: " + currentCycle);
	}

	/** Advance moon phase continuously, while preserving one simulated day per cycle. */
	private Instant getMoonDate() {
		Instant startOfDay = frameWallClockInstant.truncatedTo(ChronoUnit.DAYS);

		if (currentCycle.usesLocalTime)
			return currentInstant;

		if (currentCycle.usesUtcSyncedTime)
			return currentInstant;

		// Warp only the in-cycle fraction; completed cycles advance phase linearly.
		long totalOffsetMillis = (long) ((completedCycles + getMoonCyclePosition()) * DAY_MS);

		return startOfDay.plusMillis(totalOffsetMillis);
	}

	// ===== Light schedule ========================================================

	private void resolveLightSchedule() {
		lightScheduleActive = cycleActive;
		if (!lightScheduleActive) {
			previousNightFactor = -1;
			return;
		}

		nightFactor = smoothstep(5, -18, state.sunAngles[0] * RAD_TO_DEG);
		nightFactorIncreasing = previousNightFactor < 0 || nightFactor >= previousNightFactor;
		previousNightFactor = nightFactor;
	}

	public boolean isLightAllowedByConfiguration(Light light) {
		return !light.def.dayNightOnly || plugin.configEnableDayNightCycle;
	}

	public float getScheduledLightCullingRadius(Light light) {
		if (!lightScheduleActive)
			return light.def.radius;

		float scheduledNightFactor = getScheduledNightFactor(light);
		return light.def.radius * getNightRadiusScale(light.def, scheduledNightFactor);
	}

	public boolean isHiddenByLightSchedule(Light light) {
		return
			lightScheduleActive &&
			light.def.timeOfDay != null &&
			getNightStrengthScale(light.def, getScheduledNightFactor(light)) < .001f;
	}

	public void applyLightSchedule(Light light) {
		if (!lightScheduleActive)
			return;

		float scheduledNightFactor = getScheduledNightFactor(light);
		light.strength *= getNightStrengthScale(light.def, scheduledNightFactor);
		light.radius *= getNightRadiusScale(light.def, scheduledNightFactor);
	}

	private float getScheduledNightFactor(Light light) {
		LightDefinition def = light.def;
		if (def.timeOfDay == null)
			return nightFactor;

		LightTimeOfDay phase = nightFactorIncreasing || def.timeOfDayOff == null ? def.timeOfDay : def.timeOfDayOff;
		float start = phase.start;
		float end = phase.end;
		if (def.staggered) {
			float rampWidth = min(NIGHT_STAGGER_RAMP_WIDTH, end - start);
			start += getNightStaggerOffset(light) * max(0, end - start - rampWidth);
			end = start + rampWidth;
		}
		return smoothstep(start, end, nightFactor);
	}

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
		return nightScale * (def.timeOfDay != null ? scheduledNightFactor : 1);
	}

	private float getNightRadiusScale(LightDefinition def, float scheduledNightFactor) {
		float multiplier = def.nightMultiplier;
		if (multiplier <= 0)
			return def.timeOfDay != null ? 0 : mix(1, 0, nightFactor);

		// Unscheduled lights retain their authored culling radius unless boosted at night.
		float scheduleScale = def.timeOfDay != null ? scheduledNightFactor : 1;
		return scheduleScale * (multiplier > 1
			? mix(1, multiplier, nightFactor * NIGHT_RADIUS_BOOST_FRACTION)
			: 1);
	}
}
