package rs117.hd.scene;

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
import rs117.hd.scene.daylight_cycle.DaylightCycleState;
import rs117.hd.scene.daylight_cycle.SkyLighting;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.utils.AstronomyUtils;
import rs117.hd.utils.Camera;
import rs117.hd.utils.HDUtils;

import static rs117.hd.HdPlugin.SEED;
import static rs117.hd.utils.MathUtils.*;

/**
 * Resolves per-frame celestial state and light schedules. {@link SkyLighting} converts it to scene lighting.
 * Angles use EnvironmentManager's {@code {altitude, azimuth}} convention in radians.
 */
@Singleton
public class DaylightCycleManager {
	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	private static final float NIGHT_RADIUS_BOOST_FRACTION = .25f;

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
	private static final float ASTRONOMICAL_NIGHT_START = 19 / 24f;

	// One event per 24 simulated nights on average, lasting 20 ± 10 minutes at 2σ.
	private static final float AURORA_EVENT_CHANCE = 1f / 24;
	private static final float AURORA_EVENT_MEAN_DURATION_SECONDS = 20 * 60;
	private static final float AURORA_EVENT_DURATION_STD_DEV_SECONDS = 5 * 60;
	private static final float AURORA_EVENT_FADE_FRACTION = .2f;

	// Fixed Night moon position, in the south-east sky.
	private static final float[] FIXED_NIGHT_MOON_ANGLES = HDUtils.sunAngles(25, 135);

	// Representative seasonal latitudes; longitude is irrelevant to the simulated clock.
	private static final double[] NORTHERN_LAT_LONG = { 52.2347902, 0.1407562 }; // Jagex office, Cambridge
	private static final double[] SOUTHERN_LAT_LONG = { -33.8472331, 150.6016524 }; // Sidney, Australia

	private boolean hasFixedSunOverride;
	private boolean moonPositionFixed;

	// Night Synced advances lunar phase only after its light has faded out.
	private long nightSyncedDayOffset = 0;
	private long lastNightSyncedCycles = 0;
	private long pendingDayIncrements = 0;

	// Moon lighting fades out at −10°, so phase changes below this threshold are invisible.
	private static final float MOON_PHASE_ADVANCE_ALTITUDE_RAD = -10 * DEG_TO_RAD;
	private static final double NIGHT_SYNCED_MOON_START_HOUR = 3.4;
	private static final double ANOMALISTIC_MONTH_DAYS = 27.55455;
	private static final double DRACONIC_MONTH_DAYS = 27.21222;
	private static final float LONGITUDE_LIBRATION_DEG = 7.9f;
	private static final float LATITUDE_LIBRATION_DEG = 6.7f;

	// Hand shadows to a still-lit moon before sunset to avoid an orientation pop.
	private static final float SUN_SHADOW_CUTOFF_DEG = 2;
	private static final float MOON_SHADOW_CUTOFF_DEG = -10;
	// Suppress sub-pixel shadow-camera movement; faster cycles use a smaller threshold.
	private static final float DIRECTIONAL_ANGLE_UPDATE_THRESHOLD = .25f * DEG_TO_RAD;

	private long lastUpdateTime = 0;
	// Start Dynamic at midday.
	private double accumulatedCycleTime = .35;
	private double fixedAuroraCycleTime = .35;
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

	private float scheduleSunAltitude;
	private float previousScheduleSunAltitude = Float.NaN;
	private boolean sunDescending;
	private long scheduleNightIndex;
	private float nightFactor = 1;

	@Getter
	private boolean cycleActive;

	@Getter
	private final DaylightCycleState state = new DaylightCycleState();

	// ===== Configuration and celestial state =====================================

	public void updateConfig(HdPluginConfig config) {
		configCycle = config.daylightCycle();
		configDayLength = config.dayLength();
		configMoonBehavior = config.moonBehavior();
		configMoonPhase = config.moonPhase();
		configCycleDuration = max(1e-6f, (float) config.cycleDurationMinutes());
	}

	private void updateSeasonalHemisphere() {
		double[] latLong = currentCycle.isForcesNorthernHemisphere() || plugin.configSeasonalHemisphere != SeasonalHemisphere.SOUTHERN
			? NORTHERN_LAT_LONG
			: SOUTHERN_LAT_LONG;
		currentLatLong[0] = latLong[0];
		currentLatLong[1] = latLong[1];
	}

	/**
	 * Convert {altitude, azimuth} to a normalized sky direction.
	 */
	private float[] anglesToSkyDirection(float... angles) {
		return normalize(
			sin(angles[1]) * cos(angles[0]),
			sin(angles[0]),
			cos(angles[1]) * cos(angles[0])
		);
	}

	/**
	 * Remap a linear cycle position so daylight occupies DayLength's configured share.
	 */
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

	/**
	 * Apply DayLength only when the moon follows the dynamic clock.
	 */
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

	/**
	 * Update directional shadows only after a perceptible angle change.
	 */
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
		if (configMoonBehavior.usesNightSyncedMoon)
			return computeNightSyncedMoonAngles();

		return vec(AstronomyUtils.getMoonPosition(getMoonDate().toEpochMilli(), currentLatLong));
	}

	private float computeMoonIlluminationFraction() {
		if (currentMoonPhase.isLocked)
			return currentMoonPhase.illumination;
		if (currentCycle.isLocksMoonIllumination())
			return 1;
		// Real Time keeps a Night Synced moon continuous through daylight-saving changes.
		if (!configMoonBehavior.usesNightSyncedMoon || currentCycle.usesCurrentInstantForMoon)
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

	/**
	 * Always Night hides the sun, but uses dynamic sun positions for moon phases.
	 */
	private float[] computeMoonPhaseLightDirection() {
		return currentCycle.usesDynamicCelestialDate
			? anglesToSkyDirection(getSunAngles(getMoonDate()))
			: state.sunDirection;
	}

	/**
	 * Approximate the Moon's visible east/west and north/south rocking over a month.
	 */
	private float[] computeMoonLibration() {
		if (configMoonBehavior.usesNightSyncedMoon)
			return vec(0, 0);

		double days = getMoonDate().toEpochMilli() / (double) DAY_MS;
		return vec(
			sin((float) (days / ANOMALISTIC_MONTH_DAYS) * TWO_PI) * LONGITUDE_LIBRATION_DEG * DEG_TO_RAD,
			sin((float) (days / DRACONIC_MONTH_DAYS) * TWO_PI) * LATITUDE_LIBRATION_DEG * DEG_TO_RAD
		);
	}

	// ===== Aurora ================================================================

	private float getAuroraEventRoll(long eventIndex, long salt) {
		long h = SEED + eventIndex * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 30);
		h *= 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 27);
		h *= 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		return (h >>> 40) * (1f / (1 << 24));
	}

	private float getAuroraEventStart() {
		if (currentCycle.isPermanentNight())
			return 0;
		if (currentCycle.usesCurrentInstantForMoon)
			return ASTRONOMICAL_NIGHT_START;
		return currentCycle.usesDayLengthForMoon && !currentCycle.isFixed
			? configDayLength.dayFraction
			: NATURAL_DAY_BOUNDARY;
	}

	private double getAuroraCycleTime() {
		if (currentCycle.isPermanentNight())
			return fixedAuroraCycleTime;
		if (currentCycle.usesCurrentInstantForMoon)
			return currentInstant.toEpochMilli() / (double) DAY_MS;
		return completedCycles + accumulatedCycleTime;
	}

	private float getAuroraEventStrength(double cycleTime) {
		long eventIndex = (long) Math.floor(cycleTime);
		if (getAuroraEventRoll(eventIndex, 0) >= AURORA_EVENT_CHANCE)
			return 0;

		double gaussian = Math.sqrt(-2 * Math.log(Math.max(1e-6f, getAuroraEventRoll(eventIndex, 2)))) *
			Math.cos(TWO_PI * getAuroraEventRoll(eventIndex, 3));
		float eventDuration = clamp(
			(AURORA_EVENT_MEAN_DURATION_SECONDS + (float) gaussian * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f),
			(AURORA_EVENT_MEAN_DURATION_SECONDS - 2 * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f),
			(AURORA_EVENT_MEAN_DURATION_SECONDS + 2 * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f)
		);
		float eventElapsed = (float) (cycleTime - eventIndex) - getAuroraEventStart();
		if (eventElapsed < 0 || eventElapsed >= eventDuration)
			return 0;

		float fadeDuration = eventDuration * AURORA_EVENT_FADE_FRACTION;
		return smoothstep(0, fadeDuration, eventElapsed) *
			(1 - smoothstep(eventDuration - fadeDuration, eventDuration, eventElapsed));
	}

	private float computeAuroraStrength() {
		// The sky shader supplies the softer twilight fade; skip when the sun is above the horizon.
		if (state.sunAngles[0] >= 0)
			return 0;

		return getAuroraEventStrength(getAuroraCycleTime());
	}

	// ===== Night-synced moon =====================================================

	private float[] computeNightSyncedMoonAngles() {
		// These modes already have a shared sun position to mirror.
		if (currentCycle.usesCurrentInstantForMoon)
			return mirrorAngles(state.sunAngles);

		float[] sunAngles = getSunAngles(getNightSyncedMoonInstant());
		applyPendingNightSyncedDays(-sunAngles[0]);
		return mirrorAngles(sunAngles);
	}

	/**
	 * A uniform moon clock keeps moonrise near the visual sunset.
	 */
	private Instant getNightSyncedMoonInstant() {
		double hour = NIGHT_SYNCED_MOON_START_HOUR + getMoonCyclePosition() * 24;
		if (hour >= 24)
			hour -= 24;
		return Instant.ofEpochMilli(EQUINOX_EPOCH_MS + nightSyncedDayOffset * DAY_MS + (long) (hour * HOUR_MS));
	}

	/**
	 * Apply queued lunar phase advances only while the moon is unlit.
	 */
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

	// ===== Frame update and simulated clock ======================================

	/**
	 * Anchor local time once to avoid daylight-saving discontinuities.
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
	 * Map cycle position to the project's twilight-weighted hour of day.
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
	 * Resolve moon angles before illumination, which may consume a queued phase change.
	 */
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
		state.moonPhaseLightDirection = computeMoonPhaseLightDirection();
		state.moonLibration = computeMoonLibration();
		state.celestialPole = anglesToSkyDirection((float) currentLatLong[0] * DEG_TO_RAD, 0);
		// Always Night fixes the visible sun at midnight, but its moon and stars still
		// advance on the simulated Dynamic-cycle date.
		Instant celestialInstant = currentCycle.usesDynamicCelestialDate ? getMoonDate() : currentInstant;
		state.celestialRotation = (celestialInstant.toEpochMilli() % DAY_MS) / (float) DAY_MS * TWO_PI;
		state.hidesMoon = currentCycle.isHidesMoon();
		state.auroraStrength = computeAuroraStrength();
	}

	private void resolveEnvironmentState() {
		cycleActive = environmentManager.isOverworld() && plugin.configDaylightCycle;

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
		long elapsedMillis = currentTimeMillis - lastUpdateTime;
		accumulatedCycleTime += elapsedMillis / cycleDurationMillis;
		fixedAuroraCycleTime += elapsedMillis / (double) HOUR_MS;
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
				// Day Length controls the cycle's daylight share before twilight-weighted mapping.
				double cyclePosition = applyDayLengthWarp(accumulatedCycleTime);
				double mappedHour = cyclePositionToHour(cyclePosition);
				Instant startOfDay = frameWallClockInstant.truncatedTo(ChronoUnit.DAYS)
					.plus(completedCycles, ChronoUnit.DAYS);
				return startOfDay.plusMillis((long) (mappedHour * HOUR_MS));
		}
		throw new IllegalStateException("Unhandled day & night cycle mode: " + currentCycle);
	}

	/**
	 * Advance moon phase continuously, while preserving one simulated day per cycle.
	 */
	private Instant getMoonDate() {
		Instant startOfDay = frameWallClockInstant.truncatedTo(ChronoUnit.DAYS);

		if (currentCycle.usesCurrentInstantForMoon)
			return currentInstant;

		// Warp only the in-cycle fraction; completed cycles advance phase linearly.
		long totalOffsetMillis = (long) ((completedCycles + getMoonCyclePosition()) * DAY_MS);

		return startOfDay.plusMillis(totalOffsetMillis);
	}

	// ===== Light schedule ========================================================

	private void resolveLightSchedule() {
		scheduleSunAltitude = state.sunAngles[0] * RAD_TO_DEG;
		sunDescending = Float.isNaN(previousScheduleSunAltitude) || scheduleSunAltitude <= previousScheduleSunAltitude;
		previousScheduleSunAltitude = scheduleSunAltitude;
		// Change offsets at noon, keeping each dusk-to-dawn schedule stable through midnight.
		scheduleNightIndex = Math.floorDiv(currentInstant.toEpochMilli() - DAY_MS / 2, DAY_MS);
		if (cycleActive)
			nightFactor = smoothstep(5, -18, scheduleSunAltitude);
	}

	public void resolveLightDaylightCycle(Light light) {
		light.daylightCycleActivation = getScheduleActivation(light);
	}

	public float getDaylightCycleCullingRadius(Light light) {
		return light.def.radius * getNightRadiusScale(light.def, light.daylightCycleActivation);
	}

	public boolean isHiddenByDaylightCycle(Light light) {
		return light.def.schedule != null && light.daylightCycleActivation < .001f;
	}

	public void applyDaylightCycleLighting(Light light) {
		LightDefinition def = light.def;
		if (!cycleActive && def.schedule == null)
			return;

		light.strength *= getNightStrengthScale(def, light.daylightCycleActivation);
		light.radius *= getNightRadiusScale(def, light.daylightCycleActivation);
	}

	private float getScheduleActivation(Light light) {
		if (light.def.schedule == null)
			return 1;
		if (!plugin.configDaylightCycle)
			return 0;

		float randomOffset = (getScheduleRandomOffset(light) * 2 - 1) * light.def.schedule.randomOffset;
		return light.def.schedule.getActivation(scheduleSunAltitude, sunDescending, randomOffset);
	}

	private float getScheduleRandomOffset(Light light) {
		int hash = Float.floatToIntBits(light.pos[0]);
		hash ^= Float.floatToIntBits(light.pos[1]) * 374761393;
		hash ^= Float.floatToIntBits(light.pos[2]) * 668265263;
		hash ^= light.plane * 912271;
		hash ^= Long.hashCode(scheduleNightIndex) * 104395301;
		hash ^= hash >>> 16;
		hash *= 0x85ebca6b;
		hash ^= hash >>> 13;
		hash *= 0xc2b2ae35;
		hash ^= hash >>> 16;
		return (hash & 0x7FFFFFFF) / 2147483647f;
	}

	private float getNightStrengthScale(LightDefinition def, float scheduleActivation) {
		float nightScale = cycleActive ? mix(1, def.nightMultiplier, nightFactor) : 1;
		return nightScale * scheduleActivation;
	}

	private float getNightRadiusScale(LightDefinition def, float scheduleActivation) {
		float multiplier = def.nightMultiplier;
		if (!cycleActive)
			return scheduleActivation;
		if (multiplier <= 0)
			return def.schedule != null ? 0 : mix(1, 0, nightFactor);

		// Unscheduled lights retain their authored culling radius unless boosted at night.
		return scheduleActivation * mix(1, multiplier, nightFactor * NIGHT_RADIUS_BOOST_FRACTION);
	}
}
