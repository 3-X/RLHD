package rs117.hd.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonBehavior;
import rs117.hd.config.MoonPhase;
import rs117.hd.config.SeasonalHemisphere;
import rs117.hd.scene.daylight_cycle.SkyConfiguration;
import rs117.hd.scene.daylight_cycle.SkyState;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.utils.AstronomyUtils;
import rs117.hd.utils.Camera;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.HdPlugin.SEED;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

/**
 * Resolves per-frame sky state, lighting, and light schedules.
 * Angles use EnvironmentManager's {@code {altitude, azimuth}} convention in radians.
 */
@Slf4j
@Singleton
public class SkyManager {
	private static final ResourcePath SKY_PRESETS_PATH = path(SkyConfiguration.class, "sky_presets.json");
	private static volatile Map<String, JsonObject> presetJson = Map.of();

	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	private static final float NIGHT_RADIUS_BOOST_FRACTION = .25f;

	// One UTC-synchronized simulated day per real hour.
	private static final long SYNCED_DAYS_PERIOD_MS = 60L * 60 * 1000;

	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	private static final long HOUR_MS = 60L * 60 * 1000;
	// 5am–7pm occupies the first 70% of the unwarped cycle.
	private static final float NATURAL_DAY_BOUNDARY = .7f;
	private static final float ASTRONOMICAL_NIGHT_START = 19 / 24f;

	// One event per 24 simulated nights on average, lasting 20 ± 10 minutes at 2σ.
	private static final float AURORA_EVENT_CHANCE = 1f / 24;
	private static final float AURORA_EVENT_MEAN_DURATION_SECONDS = 20 * 60;
	private static final float AURORA_EVENT_DURATION_STD_DEV_SECONDS = 5 * 60;
	private static final float AURORA_EVENT_FADE_FRACTION = .2f;

	// Used by the Static moon behavior when an environment provides no moon position.
	private static final float[] DEFAULT_STATIC_MOON_ANGLES = HDUtils.sunAngles(15, 30);

	// Representative seasonal latitudes; longitude is irrelevant to the simulated clock.
	private static final double[] NORTHERN_LAT_LONG = { 52.2347902, 0.1407562 }; // Jagex office, Cambridge
	private static final double[] SOUTHERN_LAT_LONG = { -33.8472331, 150.6016524 }; // Sidney, Australia

	private static final double ANOMALISTIC_MONTH_DAYS = 27.55455;
	private static final double DRACONIC_MONTH_DAYS = 27.21222;
	private static final float LONGITUDE_LIBRATION_DEG = 7.9f;
	private static final float LATITUDE_LIBRATION_DEG = 6.7f;
	// Suppress sub-pixel shadow-camera movement; faster cycles use a smaller threshold.
	private static final float DIRECTIONAL_ANGLE_UPDATE_THRESHOLD = .25f * DEG_TO_RAD;

	private long lastUpdateTime = 0;
	// Start Custom at midday.
	private double accumulatedCycleTime = .35;
	private double fixedAuroraCycleTime = .35;
	private long completedCycles = 0; // Each completed cycle = one simulated day

	private DaylightCycle configCycle;
	private float configNightFraction;
	private MoonPhase configMoonPhase;
	private MoonBehavior configMoonBehavior;
	private float configCycleDuration;

	private final double[] currentLatLong = { 0, 0 };
	@Nullable
	private SkyConfiguration gielinorSky;
	private Map<String, SkyConfiguration> configurations = Map.of();
	private MoonPhase currentMoonPhase = MoonPhase.REALISTIC;
	@Nullable
	private float[] sunAnglesOverride;
	@Nullable
	private float[] skySunAnglesOverride;
	@Nullable
	private float[] moonAnglesOverride;

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
	private final SkyState state = new SkyState();

	// ===== Configuration and celestial state =====================================

	public void updateConfig(HdPluginConfig config) {
		configCycle = config.daylightCycle();
		configNightFraction = clamp(config.customNightPercentage(), 0, 100) / 100f;
		configMoonBehavior = config.moonBehavior();
		configMoonPhase = config.moonPhase();
		configCycleDuration = max(1e-6f, (float) config.customCycleDurationMinutes());
	}

	public void startUp() {
		try {
			loadPresets();
		} catch (IOException ex) {
			log.error("Failed to load sky presets:", ex);
		}
	}

	public void shutDown() {
		configurations = Map.of();
		presetJson = Map.of();
		gielinorSky = null;
	}

	/**
	 * Whether the player has selected a daylight-cycle mode other than Off.
	 */
	public boolean isCycleConfigured() {
		return configCycle != DaylightCycle.OFF;
	}

	private void loadPresets() throws IOException {
		var gson = plugin.getGson();
		JsonArray rawPresets = SKY_PRESETS_PATH.loadJson(gson, JsonArray.class);
		if (rawPresets == null)
			throw new IOException("Empty or invalid: " + SKY_PRESETS_PATH);

		var rawPresetMap = new HashMap<String, JsonObject>();
		for (int i = 0; i < rawPresets.size(); i++) {
			JsonElement element = rawPresets.get(i);
			if (!element.isJsonObject()) {
				log.error("Sky preset at index {} is not an object", i);
				continue;
			}
			JsonObject preset = element.getAsJsonObject();
			JsonElement name = preset.get("name");
			if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
				log.error("Sky preset at index {} has no string name", i);
				continue;
			}
			if (rawPresetMap.putIfAbsent(name.getAsString(), preset) != null)
				log.error("Duplicate sky preset '{}'", name.getAsString());
		}

		var resolved = new HashMap<String, JsonObject>();
		var names = rawPresetMap.keySet().iterator();
		while (names.hasNext())
			resolveSkyPreset(names.next(), rawPresetMap, resolved, new HashSet<>());
		presetJson = Map.copyOf(resolved);

		var parsed = new HashMap<String, SkyConfiguration>();
		var entries = resolved.entrySet().iterator();
		while (entries.hasNext()) {
			var entry = entries.next();
			SkyConfiguration configuration = gson.fromJson(entry.getValue(), SkyConfiguration.class).normalize();
			configuration.preset = entry.getKey();
			parsed.put(entry.getKey(), configuration);
		}
		configurations = Map.copyOf(parsed);
		gielinorSky = configurations.get(SkyConfiguration.DEFAULT_PRESET);
	}

	@Nullable
	public static JsonObject getPresetJson(String name) {
		return presetJson.get(name);
	}

	@Nullable
	private JsonObject resolveSkyPreset(
		String name,
		Map<String, JsonObject> raw,
		Map<String, JsonObject> resolved,
		HashSet<String> resolving
	) {
		JsonObject result = resolved.get(name);
		if (result != null)
			return result;
		JsonObject preset = raw.get(name);
		if (preset == null) {
			log.error("Unknown sky preset '{}'", name);
			return null;
		}
		if (!resolving.add(name)) {
			log.error("Sky preset '{}' contains a preset loop", name);
			return null;
		}
		result = new JsonObject();
		JsonElement parent = preset.get("preset");
		if (parent != null && parent.isJsonPrimitive() && parent.getAsJsonPrimitive().isString()) {
			JsonObject base = resolveSkyPreset(parent.getAsString(), raw, resolved, resolving);
			if (base != null)
				SkyConfiguration.merge(result, base);
		} else if (parent != null) {
			log.error("Sky preset '{}' has a non-string preset", name);
		}
		SkyConfiguration.merge(result, preset);
		result.remove("name");
		result.remove("preset");
		resolving.remove(name);
		resolved.put(name, result);
		return result;
	}

	@Nonnull
	SkyConfiguration getGielinorSky() {
		if (gielinorSky == null)
			throw new IllegalStateException("Missing " + SkyConfiguration.DEFAULT_PRESET + " sky preset");
		return gielinorSky;
	}

	@Nonnull
	public SkyConfiguration getSkyConfiguration(Environment environment) {
		return environment.hasSkyOverride ? environment.sky : getGielinorSky();
	}

	private void resolveSeasonalHemisphere() {
		double[] latLong = configCycle.forcesNorthernHemisphere || plugin.configSeasonalHemisphere != SeasonalHemisphere.SOUTHERN
			? NORTHERN_LAT_LONG
			: SOUTHERN_LAT_LONG;
		currentLatLong[0] = latLong[0];
		currentLatLong[1] = latLong[1];
	}

	private static float[] anglesToSkyDirection(float altitude, float azimuth) {
		return normalize(
			sin(azimuth) * cos(altitude),
			sin(altitude),
			cos(azimuth) * cos(altitude)
		);
	}

	/**
	 * Remap a linear cycle position so night occupies the configured share.
	 */
	private double applyNightDurationWarp(double cyclePosition) {
		float dayFraction = 1 - configNightFraction;
		if (abs(dayFraction - NATURAL_DAY_BOUNDARY) < 1e-6f)
			return cyclePosition;

		if (cyclePosition < dayFraction) {
			return (cyclePosition / dayFraction) * NATURAL_DAY_BOUNDARY;
		}
		double nightProgress = (cyclePosition - dayFraction) / (1 - dayFraction);
		return NATURAL_DAY_BOUNDARY + nightProgress * (1 - NATURAL_DAY_BOUNDARY);
	}

	// ===== Sun and shadow directions =============================================

	/**
	 * Update directional shadows only after a perceptible angle change.
	 */
	public void updateDirectionalCamera(Camera directionalCamera) {
		float[] angles = state.shadowAngles;
		float[] orientation = { PI - angles[1], angles[0] };
		float diff = max(abs(angleDiff(orientation, directionalCamera.getOrientation())));
		if (diff >= DIRECTIONAL_ANGLE_UPDATE_THRESHOLD * saturate(configCycleDuration / 300f))
			directionalCamera.setOrientation(orientation);
	}

	// ===== Aurora ================================================================

	private static float getAuroraEventRoll(long eventIndex, long salt) {
		long h = SEED + eventIndex * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 30);
		h *= 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 27);
		h *= 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		return (h >>> 40) * (1f / (1 << 24));
	}

	private float getAuroraEventStrength(double cycleTime, float eventStart) {
		long eventIndex = (long) Math.floor(cycleTime);
		if (getAuroraEventRoll(eventIndex, 0) >= AURORA_EVENT_CHANCE)
			return 0;

		double gaussian =
			Math.sqrt(-2 * Math.log(Math.max(1e-6f, getAuroraEventRoll(eventIndex, 2)))) *
			Math.cos(TWO_PI * getAuroraEventRoll(eventIndex, 3));
		float eventDuration = clamp(
			(AURORA_EVENT_MEAN_DURATION_SECONDS + (float) gaussian * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f),
			(AURORA_EVENT_MEAN_DURATION_SECONDS - 2 * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f),
			(AURORA_EVENT_MEAN_DURATION_SECONDS + 2 * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f)
		);
		float eventElapsed = (float) (cycleTime - eventIndex) - eventStart;
		if (eventElapsed < 0 || eventElapsed >= eventDuration)
			return 0;

		float fadeDuration = eventDuration * AURORA_EVENT_FADE_FRACTION;
		return
			smoothstep(0, fadeDuration, eventElapsed) *
			(1 - smoothstep(eventDuration - fadeDuration, eventDuration, eventElapsed));
	}

	private void resolveAuroraStrength() {
		double cycleTime;
		float eventStart;
		float sunAltitude = state.sunAngles[0];
		if (state.permanentNight) {
			cycleTime = fixedAuroraCycleTime;
			eventStart = 0;
		} else if (!configCycle.usesCustomNightDuration) {
			cycleTime = currentInstant.toEpochMilli() / (double) DAY_MS;
			eventStart = ASTRONOMICAL_NIGHT_START;
			if (configCycle.usesPresetSunAngles)
				sunAltitude = (float) AstronomyUtils.getSunAngles(currentInstant.toEpochMilli(), currentLatLong)[0];
		} else {
			cycleTime = completedCycles + accumulatedCycleTime;
			eventStart = 1 - configNightFraction;
		}
		// The sky shader supplies the near-horizon fade; skip when the sun is above the horizon.
		state.auroraStrength = sunAltitude < 0 ? getAuroraEventStrength(cycleTime, eventStart) : 0;
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
	 * Map cycle position to the project's dawn- and sunset-weighted hour of day.
	 */
	private double cyclePositionToHour(double cyclePosition) {
		// 0.0-0.15  dawn               -> 5am-7am
		// 0.15-0.35 morning            -> 7am-12pm
		// 0.35-0.55 afternoon          -> 12pm-5pm
		// 0.55-0.70 sunset             -> 5pm-7pm
		// 0.70-0.85 early night        -> 7pm-12am
		// 0.85-1.0  late night         -> 12am-5am
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
		resolveSkyConfiguration();
		resolveSeasonalHemisphere();

		frameWallClockMillis = System.currentTimeMillis();
		frameWallClockInstant = Instant.ofEpochMilli(frameWallClockMillis);
		initializeRealTimeClock();
		currentInstant = frameWallClockInstant;
		advanceCycle(frameWallClockMillis);
		currentInstant = resolveCurrentInstant();
		resolveSkyState();
		resolveLightScheduleState();
	}

	private void resolveSkyState() {
		state.sunAngles = sunAnglesOverride != null
			? sunAnglesOverride
			: vec(AstronomyUtils.getSunAngles(currentInstant.toEpochMilli(), currentLatLong));
		state.skySunAngles = skySunAnglesOverride != null ? skySunAnglesOverride : state.sunAngles;
		Instant moonInstant = resolveMoonInstant();
		if (moonAnglesOverride != null)
			state.moonAngles = moonAnglesOverride;
		else if (configMoonBehavior.mirrorsSun)
			state.moonAngles = mirrorAngles(state.sunAngles);
		else
			state.moonAngles = vec(AstronomyUtils.getMoonPosition(moonInstant.toEpochMilli(), currentLatLong));
		state.shadowAngles = state.sunAngles[0] < 0 && state.moonAngles[0] > 0 ? state.moonAngles : state.sunAngles;
		state.sunAltitudeDegrees = state.sunAngles[0] * RAD_TO_DEG;
		state.skySunAltitudeDegrees = state.skySunAngles[0] * RAD_TO_DEG;
		state.moonIllumination = currentMoonPhase.isLocked
			? currentMoonPhase.illumination
			: (float) AstronomyUtils.getMoonIllumination(moonInstant.toEpochMilli())[0];
		state.moonAltitudeDegrees = state.moonAngles[0] * RAD_TO_DEG;
		state.sunDirection = anglesToSkyDirection(state.sunAngles[0], state.sunAngles[1]);
		state.skySunDirection = anglesToSkyDirection(state.skySunAngles[0], state.skySunAngles[1]);
		state.moonDirection = anglesToSkyDirection(state.moonAngles[0], state.moonAngles[1]);
		if (state.permanentNight) {
			float[] sunAngles = vec(AstronomyUtils.getSunAngles(moonInstant.toEpochMilli(), currentLatLong));
			state.moonPhaseLightDirection = anglesToSkyDirection(sunAngles[0], sunAngles[1]);
		} else {
			state.moonPhaseLightDirection = state.sunDirection;
		}
		state.moonPhaseReversed = currentMoonPhase.reversesTerminator;
		// Approximate the Moon's visible east/west and north/south rocking over a month.
		if (moonAnglesOverride != null || configMoonBehavior.mirrorsSun) {
			state.moonLibration = vec(0, 0);
		} else {
			double days = moonInstant.toEpochMilli() / (double) DAY_MS;
			state.moonLibration = vec(
				sin((float) (days / ANOMALISTIC_MONTH_DAYS) * TWO_PI) * LONGITUDE_LIBRATION_DEG * DEG_TO_RAD,
				sin((float) (days / DRACONIC_MONTH_DAYS) * TWO_PI) * LATITUDE_LIBRATION_DEG * DEG_TO_RAD
			);
		}
		state.celestialPole = anglesToSkyDirection((float) currentLatLong[0] * DEG_TO_RAD, 0);
		state.celestialRotation = (currentInstant.toEpochMilli() % DAY_MS) / (float) DAY_MS * TWO_PI;
		resolveAuroraStrength();
	}

	private void resolveSkyConfiguration() {
		Environment from = environmentManager.getFromEnvironment();
		Environment to = environmentManager.getToEnvironment();
		state.fromConfiguration = getSkyConfiguration(from);
		state.toConfiguration = getSkyConfiguration(to);
		state.configurationTransition = environmentManager.getTransitionProgress();
		float fromMoonStrength = state.fromConfiguration.moonDirectionalStrength;
		if (fromMoonStrength < 0)
			fromMoonStrength = from.directionalStrength;
		float toMoonStrength = state.toConfiguration.moonDirectionalStrength;
		if (toMoonStrength < 0)
			toMoonStrength = to.directionalStrength;
		state.moonDirectionalStrength = mix(fromMoonStrength, toMoonStrength, state.configurationTransition);
		SkyConfiguration sky = state.toConfiguration;
		currentMoonPhase = sky.forceMoonPhase != null ? sky.forceMoonPhase : configMoonPhase;
		float[] skySunAngles = sky.sunAngles;
		if (skySunAngles == null && configCycle.skyPreset != null) {
			SkyConfiguration cycleSky = configurations.get(configCycle.skyPreset);
			if (cycleSky != null)
				skySunAngles = cycleSky.sunAngles;
		}
		skySunAnglesOverride = isCycleConfigured() ? skySunAngles : null;
		sunAnglesOverride = isCycleConfigured() && skySunAngles != null && (sky.sunAngles != null || configCycle.usesPresetSunAngles)
			? skySunAngles
			: null;
		float[] moonAngles = sky.moonAngles;
		if (moonAngles == null && configMoonBehavior.isStatic)
			moonAngles = DEFAULT_STATIC_MOON_ANGLES;
		moonAnglesOverride = moonAngles;
		state.hidesSun = sky.hideSun || configCycle.hidesSun;
		state.hidesMoon = sky.hideMoon || configMoonBehavior.isDisabled && !sky.forceMoonActive && sky.forceMoonPhase == null;
		state.permanentNight = sky.permanentNight;
		cycleActive = environmentManager.getTargetEnvironment().isOverworld && isCycleConfigured();
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
		if (sunAnglesOverride != null || configCycle.usesDefaultCycleTime)
			return getDefaultInstant();

		switch (configCycle) {
			case OFF:
				return frameWallClockInstant;
			case REAL_TIME:
				// The session-local timestamp advances in Unix time, so daylight-saving changes
				// cannot cause a discontinuity in the sun, moon, or seasonal date.
				return Instant.ofEpochMilli(realTimeStartEpochMillis + frameWallClockMillis - realTimeSessionStartMillis);
			case CUSTOM:
				// Custom night duration controls the cycle's night share before low-sun-weighted mapping.
				double cyclePosition = applyNightDurationWarp(accumulatedCycleTime);
				double mappedHour = cyclePositionToHour(cyclePosition);
				Instant startOfDay = frameWallClockInstant.truncatedTo(ChronoUnit.DAYS)
					.plus(completedCycles, ChronoUnit.DAYS);
				return startOfDay.plusMillis((long) (mappedHour * HOUR_MS));
		}
		throw new IllegalStateException("Unhandled daylight cycle mode: " + configCycle);
	}

	/**
	 * A full UTC-synchronized day per real hour, independent of Custom settings.
	 */
	private Instant getDefaultInstant() {
		double cyclePosition = (frameWallClockMillis % SYNCED_DAYS_PERIOD_MS) / (double) SYNCED_DAYS_PERIOD_MS;
		long day = frameWallClockMillis / SYNCED_DAYS_PERIOD_MS;
		return Instant.EPOCH.plus(day, ChronoUnit.DAYS)
			.plusMillis((long) (cyclePositionToHour(cyclePosition) * HOUR_MS));
	}

	private Instant resolveMoonInstant() {
		if (sunAnglesOverride != null)
			return currentInstant;
		if (!configCycle.usesCustomNightDuration)
			return currentInstant;

		double cyclePosition = configCycle.usesCustomNightDuration
			? applyNightDurationWarp(accumulatedCycleTime)
			: accumulatedCycleTime;
		long offsetMillis = (long) ((completedCycles + cyclePosition) * DAY_MS);
		return frameWallClockInstant.truncatedTo(ChronoUnit.DAYS).plusMillis(offsetMillis);
	}

	// ===== Light schedule ========================================================

	private void resolveLightScheduleState() {
		scheduleSunAltitude = state.sunAltitudeDegrees;
		sunDescending = Float.isNaN(previousScheduleSunAltitude) || scheduleSunAltitude <= previousScheduleSunAltitude;
		previousScheduleSunAltitude = scheduleSunAltitude;
		// Change offsets at noon, keeping each dusk-to-dawn schedule stable through midnight.
		scheduleNightIndex = Math.floorDiv(currentInstant.toEpochMilli() - DAY_MS / 2, DAY_MS);
		if (cycleActive)
			nightFactor = smoothstep(5, -18, scheduleSunAltitude);
	}

	public void applyLightSchedule(Light light) {
		light.daylightCycleActivation = 1;
		if (light.def.schedule == null)
			return;

		light.daylightCycleActivation = getScheduleActivation(light);
		if (light.daylightCycleActivation < .001f)
			light.visible = false;
	}

	public float getLightCullingRadius(Light light) {
		return light.def.radius * getNightRadiusScale(light.def, light.daylightCycleActivation);
	}

	public void applyDaylightCycleLighting(Light light) {
		if (!cycleActive && light.def.schedule == null)
			return;

		light.strength *= getNightStrengthScale(light.def, light.daylightCycleActivation);
		light.radius *= getNightRadiusScale(light.def, light.daylightCycleActivation);
	}

	private float getScheduleActivation(Light light) {
		if (light.def.schedule == null)
			return 1;
		if (!isCycleConfigured())
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
