package rs117.hd.scene;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.weather.Climate;
import rs117.hd.scene.weather.ClimateBinding;
import rs117.hd.scene.weather.Ramp;
import rs117.hd.scene.weather.WeatherClock;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.scene.weather.WeatherOverrides;
import rs117.hd.scene.weather.WeatherParam;
import rs117.hd.scene.weather.WeatherSampler;
import rs117.hd.scene.weather.WeatherState;
import rs117.hd.scene.weather.WeatherStrip;
import rs117.hd.scene.weather.WeatherTimeline;
import rs117.hd.scene.weather.WeatherType;
import rs117.hd.scene.weather.WeatherZone;
import rs117.hd.scene.weather.WindModel;
import rs117.hd.scene.weather.ZoneBlend;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static net.runelite.api.Perspective.LOCAL_HALF_TILE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.scene.weather.WeatherConstants.*;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

/**
 * Decides what the weather should be at the camera's focal point, and publishes it as a renderer-agnostic
 * {@link WeatherState} each frame. No visual effects live here; consumers (wind, and later clouds and
 * precipitation) read the state.
 * <p>
 * Weather comes from one deterministic, UTC-anchored timeline shared by every player. It is world-anchored,
 * so weather systems drift across the map along the timeline's drift direction. The climate bound to the
 * focal point maps the timeline into the weather it allows, and climate changes cross-fade over time.
 */
@Slf4j
@Singleton
public class WeatherManager {
	private static final ResourcePath WEATHER_PATH = Props
		.getFile("rlhd.weather-path", () -> path(WeatherManager.class, "weather.json"));

	private static final int MAX_CONSECUTIVE_FAILURES = 120;
	private static final long FAILURE_LOG_INTERVAL_MS = 60_000;
	private static final long VISIBLE_DISTANCE_MS = (long) (FRONT_VISIBLE_DISTANCE * 1000 / FRONT_SPEED);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	@Getter
	private final WeatherState state = new WeatherState();
	@Getter
	private final WeatherClock clock = new WeatherClock();
	@Getter
	private final WeatherOverrides overrides = new WeatherOverrides();

	@Nullable
	@Getter
	private WeatherData data;
	private FileWatcher.UnregisterCallback fileWatcher;
	private SeasonalTheme configSeason = SeasonalTheme.SUMMER;

	private final ZoneBlend blend = new ZoneBlend();
	private final Map<String, WeatherZone> zoneCache = new HashMap<>();
	private final Ramp exposure = new Ramp();
	private final WindModel windModel = new WindModel();
	private final WeatherSampler sampler = new WeatherSampler();
	private final float[] forbidMask = new float[WeatherParam.VECTOR_SIZE];
	private final int[] tmpWorld = new int[3];

	@Nullable
	private WeatherZone labelZone;
	private boolean hasWorldPoint;
	private long lastTransitionId = Long.MIN_VALUE;
	private long lastClockMs = Long.MIN_VALUE;
	private boolean environmentGate;
	private boolean exposureTarget;
	private boolean pendingSnap = true;
	@Getter
	private boolean failed;
	private int consecutiveFailures;
	private long lastErrorLogMs;

	// Last resolved zone, so the zone key only needs building when something changes
	private int overrideGeneration;
	@Nullable
	private Climate lastZoneClimate;
	@Nullable
	private SeasonalTheme lastZoneSeason;
	private int lastZoneGeneration = -1;
	@Nullable
	private WeatherZone lastZone;

	// Binding cache, keyed by tile and data version
	private int cacheX, cacheY, cachePlane, cacheVersion = -1;
	@Nullable
	private ClimateBinding cachedBinding;

	private WeakReference<SceneContext> lastScene = new WeakReference<>(null);

	public void startUp() {
		Arrays.fill(forbidMask, 1);
		windModel.resetOffsets(state.surfaceWind);
		windModel.resetBaseline();
		pendingSnap = true;
		failed = false;
		consecutiveFailures = 0;

		fileWatcher = WEATHER_PATH.watch((path, first) -> {
			try {
				JsonObject root = path.loadJson(plugin.getGson(), JsonObject.class);
				if (root == null)
					throw new IOException("Empty or invalid: " + path);
				var definitions = WeatherData.parse(root);
				clientThread.invoke(() -> {
					try {
						installData(definitions.bind(AreaManager.AREAS, Props.DEVELOPMENT));
					} catch (Exception ex) {
						log.error("Failed to load weather data, keeping the previous data:", ex);
					}
				});
			} catch (Exception ex) {
				log.error("Failed to load weather data, keeping the previous data:", ex);
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		data = null;
		zoneCache.clear();
		blend.clear();
		labelZone = null;
		lastZone = null;
		lastZoneClimate = null;
		cachedBinding = null;
		cacheVersion = -1;
		lastScene = new WeakReference<>(null);
		state.active = false;
		state.strip.valid = false;
		state.exposure = 0;
		plugin.windOffset = 0;
		// Developer overrides and the clock deliberately survive plugin restarts within a session
	}

	/** Called when returning to the login screen. */
	public void reset() {
		pendingSnap = true;
		hasWorldPoint = false;
		blend.clear();
		labelZone = null;
		lastTransitionId = Long.MIN_VALUE;
		lastClockMs = Long.MIN_VALUE;
		lastScene = new WeakReference<>(null);
	}

	public void updateConfig() {
		var season = plugin.configSeasonalTheme;
		configSeason = season == null || season == SeasonalTheme.AUTOMATIC ? SeasonalTheme.SUMMER : season;
	}

	private void installData(WeatherData newData) {
		data = newData;
		state.dataVersion++;
		zoneCache.clear();
		blend.clear();
		labelZone = null;
		lastZone = null;
		lastZoneClimate = null;
		overrideGeneration++;
		cacheVersion = -1;
		pendingSnap = true;
		failed = false;
		consecutiveFailures = 0;

		if (overrides.forcedTypeName != null && newData.type(overrides.forcedTypeName) == null ||
			overrides.heldTypeName != null && newData.type(overrides.heldTypeName) == null) {
			log.info("Clearing the forced weather type, which no longer exists");
			overrides.clearForcedType();
		}
		if (overrides.forcedClimate != null && newData.climate(overrides.forcedClimate) == null) {
			log.info("Clearing the forced climate '{}', which no longer exists", overrides.forcedClimate);
			overrides.forcedClimate = null;
		}
		log.debug("Loaded weather data: {} types, {} climates, {} bindings",
			newData.types.size(), newData.climates.size(), newData.bindings.size());
	}

	/**
	 * Update the weather for this frame. Must be called on the client thread, after the environment update.
	 * Never throws, since an exception here would stop the plugin.
	 */
	public void update(@Nullable SceneContext ctx) {
		if (ctx == null || data == null || failed || overrides.disabled) {
			publishBaseline(ctx);
			return;
		}

		try {
			updateInternal(ctx, data);
			consecutiveFailures = 0;
		} catch (RuntimeException ex) {
			onFailure(ex);
			publishBaseline(ctx);
		}
	}

	private void updateInternal(SceneContext ctx, WeatherData data) {
		float dt = max(0, plugin.deltaTime);
		long wallMs = System.currentTimeMillis();
		long clockMs = clock.now(wallMs);
		boolean snap = pendingSnap;
		pendingSnap = false;
		if (lastClockMs != Long.MIN_VALUE) {
			long expected = lastClockMs + (long) (dt * 1000 * clock.getScale());
			if (Math.abs(clockMs - expected) > CLOCK_JUMP_SNAP_MS)
				snap = true;
		}
		lastClockMs = clockMs;

		// Focal point in world coordinates, resolving instances to their template location
		var worldView = client.getTopLevelWorldView();
		int plane = worldView == null ? 0 : worldView.getPlane();
		ctx.localToWorld(plugin.cameraFocalPoint[0], plugin.cameraFocalPoint[1], plane, tmpWorld);
		if (tmpWorld[0] >= 0) {
			int[] previous = state.worldPoint;
			if (!hasWorldPoint || Math.max(Math.abs(tmpWorld[0] - previous[0]), Math.abs(tmpWorld[1] - previous[1])) >= SNAP_DISTANCE_TILES)
				snap = true;
			System.arraycopy(tmpWorld, 0, previous, 0, 3);
			hasWorldPoint = true;
			// Use the continuous focal point where possible, so fronts don't step from tile to tile
			int subX = ctx.sceneBase != null ? plugin.cameraFocalPoint[0] & (LOCAL_TILE_SIZE - 1) : LOCAL_HALF_TILE_SIZE;
			int subY = ctx.sceneBase != null ? plugin.cameraFocalPoint[1] & (LOCAL_TILE_SIZE - 1) : LOCAL_HALF_TILE_SIZE;
			state.originX = (double) previous[0] * LOCAL_TILE_SIZE + subX;
			state.originY = (double) previous[1] * LOCAL_TILE_SIZE + subY;
		} else if (!hasWorldPoint) {
			publishBaseline(ctx);
			return;
		}

		// Snapshot the environment gate once per environment transition. The flags are only valid on the
		// area-selected target environment; copies like current, from & to never carry them.
		long transitionId = environmentManager.getTransitionId();
		float progress = environmentManager.getTransitionProgress();
		Environment to = environmentManager.getToEnvironment();
		if (transitionId != lastTransitionId) {
			lastTransitionId = transitionId;
			Environment target = environmentManager.getTargetEnvironment();
			var sky = to.getSky();
			boolean fixedSun = sky != null && sky.sunAngles != null;
			environmentGate = target.isOverworld && !target.isUnderwater && !fixedSun;
			if (progress >= 1)
				snap = true; // Instant environment transition, e.g. after a teleport
		}

		ClimateBinding binding = resolveBinding(data, state.worldPoint);
		state.bindingIndex = binding == null ? -1 : binding.index;
		state.bindingClimate = binding == null ? null : binding.climateName;
		boolean outdoor = binding != null && !binding.isNone();

		// Target zone. Sheltered and unbound points keep the previous climate, so its weather keeps evolving.
		Climate climate = overrides.forcedClimate == null ? null : data.climate(overrides.forcedClimate);
		if (climate == null) {
			if (outdoor) {
				climate = binding.climate;
			} else if (blend.target != null) {
				climate = blend.target.climate;
			} else {
				climate = data.defaultClimate;
			}
		}
		SeasonalTheme season = overrides.forcedSeason != null ? overrides.forcedSeason : configSeason;
		WeatherZone zone = zoneFor(data, climate, season);
		if (zone != blend.target) {
			blend.setTarget(zone);
			// Changing the climate while sheltered is invisible, so skip the cross-fade
			if (exposure.value <= 0 && !snap) {
				blend.snap();
				state.discontinuityId++;
			}
		}

		if (snap) {
			blend.snap();
		} else {
			blend.step(dt, CLIMATE_BLEND_SECONDS);
		}
		exposureTarget = environmentGate && outdoor && !ctx.isInHouse;
		exposure.update(exposureTarget ? 1 : 0, transitionId, progress, plugin.elapsedTime, EXPOSURE_RAMP_SECONDS, snap);
		updateForbidMask(dt, snap);

		long phase = clockMs - phaseOffsetMs(data.timeline, state.originX, state.originY);
		labelZone = chooseLabelZone(data, phase);
		sampler.sample(data.timeline, blend.zones, blend.weights, blend.count, labelZone, phase, forbidMask, state.strip);
		state.strip.evaluateLocal(state.params);
		updateLabels(phase);

		data.steering.evaluate(clockMs, state.upperWind);
		double cloudDrift = state.upperWind.speed * state.get(WeatherParam.CLOUD_SPEED) * (double) dt * clock.getScale();
		state.upperWind.cloudOffsetX += state.upperWind.directionX * cloudDrift;
		state.upperWind.cloudOffsetZ += state.upperWind.directionZ * cloudDrift;

		updateSurfaceWind(ctx, data, to, dt, wallMs, transitionId, progress, snap);

		state.active = true;
		state.exposure = exposure.value;
		state.clockMillis = clockMs;
		state.phaseMillis = phase;
		state.season = season;
		var dominant = blend.dominant();
		state.climate = dominant == null ? climate : dominant.climate;
		if (snap)
			state.discontinuityId++;
	}

	private void updateForbidMask(float dt, boolean snap) {
		var target = blend.target;
		float step = dt / FORBID_MASK_SECONDS;
		for (int slot = 0; slot < forbidMask.length; slot++) {
			// Forced types are developer tools, so they ignore the climate's exclusions
			float goal = target == null || target.isForced() || !target.climate.forbidsSlot(slot) ? 1 : 0;
			if (snap) {
				forbidMask[slot] = goal;
			} else if (forbidMask[slot] < goal) {
				forbidMask[slot] = min(goal, forbidMask[slot] + step);
			} else if (forbidMask[slot] > goal) {
				forbidMask[slot] = max(goal, forbidMask[slot] - step);
			}
		}
	}

	/**
	 * Labels normally follow the dominant zone. While cross-fading into a climate that forbids what the dominant
	 * zone's current weather produces, they follow the incoming zone instead, so a label never names weather that
	 * has already been masked out, like rain in the desert.
	 */
	private WeatherZone chooseLabelZone(WeatherData data, long phase) {
		var dominant = blend.dominant();
		var target = blend.target;
		if (dominant == null)
			return target;
		if (target == null || dominant == target || target.isForced())
			return dominant;
		var type = dominant.typeFor(data.timeline.segmentAt(phase));
		return target.climate.conflictsWith(type) ? target : dominant;
	}

	private void updateLabels(long phase) {
		var strip = state.strip;
		final int c = WeatherStrip.CENTER;
		state.type = strip.types[c];
		state.previousType = strip.types[c - 1];
		state.intensity = strip.intensities[c];
		state.segmentStartPhase = strip.segmentStart[c];
		state.segmentEndPhase = strip.segmentEnd[c];

		state.nextType = strip.types[c + 1];
		state.nextChangePhase = Long.MAX_VALUE;
		for (int i = c; i < WeatherStrip.SIZE - 1; i++) {
			if (strip.types[i + 1] != strip.types[i]) {
				state.nextType = strip.types[i + 1];
				// When the window's leading edge reaches the focal point
				state.nextChangePhase = strip.segmentStart[i + 1] - (long) (strip.edgeWidth[i] * 500 / FRONT_SPEED);
				break;
			}
		}

		state.transitionProgress = 0;
		state.transitionShape = 0;
		for (int i = c - 1; i <= c; i++) {
			float p = strip.edgeProgress(i, 0);
			if (p > 0 && p < 1) {
				state.transitionProgress = p;
				state.transitionShape = strip.edgeShape[i];
				break;
			}
		}

		float cover = state.get(WeatherParam.CLOUD_COVER);
		float thickness = state.get(WeatherParam.CLOUD_THICKNESS);
		state.overcast = saturate(cover * (1 - (float) Math.exp(-thickness)));
		state.precipitation = saturate(
			state.get(WeatherParam.RAIN) +
			state.get(WeatherParam.SNOW) +
			state.get(WeatherParam.ASH) +
			state.get(WeatherParam.DUST));
	}

	private void updateSurfaceWind(
		SceneContext ctx,
		WeatherData data,
		Environment to,
		float dt,
		long wallMs,
		long transitionId,
		float progress,
		boolean snap
	) {
		// Blend the climates' default wind
		double cx = 0, cz = 0;
		float climateSpeed = 0, climateStrength = 0, climateBlend = 0;
		for (int i = 0; i < blend.count; i++) {
			float w = blend.weights[i];
			Climate climate = blend.zones[i].climate;
			cx += w * Math.cos(climate.windAngle);
			cz += w * Math.sin(climate.windAngle);
			climateSpeed += w * climate.windSpeed;
			climateStrength += w * climate.windStrength;
			climateBlend += w * climate.windBlend;
		}
		float climateAngle = cx * cx + cz * cz > 1e-8 ? (float) Math.atan2(cz, cx) : to.windAngle;

		// Environments authoring no wind take the climate's wind outdoors
		boolean unauthored = to.windStrength <= 0;
		float share = exposureTarget ? max(unauthored ? 1 : 0, climateBlend) : 0;
		windModel.updateBaseline(
			WindModel.mixAngle(to.windAngle, climateAngle, share),
			mix(to.windSpeed, climateSpeed, share),
			mix(to.windStrength, climateStrength, share),
			(exposureTarget ? 1 : 0) | (unauthored ? 2 : 0),
			transitionId,
			progress,
			plugin.elapsedTime,
			snap
		);

		var wind = state.surfaceWind;
		var env = environmentManager.getCurrentEnvironment();
		float gust = WindModel.gust(wallMs, data.timeline.seed());
		WindModel.modulate(
			wind,
			windModel.baseAngle,
			windModel.baseSpeed,
			windModel.baseStrength,
			env.windCeiling,
			exposure.value,
			state.params,
			state.upperWind,
			gust
		);
		wind.climateShare = share;
		applyWindOverride(wind, env);
		integrateWind(ctx, dt);
	}

	private void applyWindOverride(WeatherState.SurfaceWind wind, Environment env) {
		if (overrides.windOverride)
			wind.set(overrides.windAngle, overrides.windSpeed, overrides.windStrength, env.windCeiling);
	}

	private void integrateWind(@Nullable SceneContext ctx, float dt) {
		boolean sceneChanged = ctx != null && lastScene.get() != ctx;
		if (sceneChanged)
			lastScene = new WeakReference<>(ctx);
		windModel.integrate(state.surfaceWind, dt, sceneChanged);
		plugin.windOffset = state.surfaceWind.windOffset;
	}

	/** Publish the environment's wind unchanged, exactly matching the behaviour without a weather controller. */
	private void publishBaseline(@Nullable SceneContext ctx) {
		try {
			state.active = false;
			state.strip.valid = false;
			state.exposure = 0;
			var env = environmentManager.getCurrentEnvironment();
			var wind = state.surfaceWind;
			wind.set(WindModel.wrapAngle(env.windAngle), env.windSpeed, env.windStrength, env.windCeiling);
			wind.gust = 0;
			wind.gustiness = 0;
			wind.climateShare = 0;
			wind.baseAngle = wind.windAngle;
			wind.baseSpeed = wind.windSpeed;
			wind.baseStrength = wind.windStrength;
			if (!overrides.disabled)
				applyWindOverride(wind, env);
			integrateWind(ctx, max(0, plugin.deltaTime));
			windModel.resetBaseline();
		} catch (RuntimeException ex) {
			onFailure(ex);
		}
	}

	private void onFailure(RuntimeException ex) {
		consecutiveFailures++;
		pendingSnap = true;
		long now = System.currentTimeMillis();
		if (consecutiveFailures == 1 || now - lastErrorLogMs > FAILURE_LOG_INTERVAL_MS) {
			lastErrorLogMs = now;
			log.error("Error while updating the weather:", ex);
		}
		if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES && !failed) {
			failed = true;
			log.error("Disabled the weather controller after repeated errors. Use '::117hd weather on' to retry.");
		}
	}

	@Nullable
	private ClimateBinding resolveBinding(WeatherData data, int[] worldPoint) {
		if (cacheVersion != state.dataVersion || cacheX != worldPoint[0] || cacheY != worldPoint[1] || cachePlane != worldPoint[2]) {
			cacheVersion = state.dataVersion;
			cacheX = worldPoint[0];
			cacheY = worldPoint[1];
			cachePlane = worldPoint[2];
			cachedBinding = data.resolve(cacheX, cacheY, cachePlane);
		}
		return cachedBinding;
	}

	private WeatherZone zoneFor(WeatherData data, Climate climate, SeasonalTheme season) {
		if (lastZone != null && climate == lastZoneClimate && season == lastZoneSeason && overrideGeneration == lastZoneGeneration)
			return lastZone;

		WeatherType forced = overrides.forcedTypeName == null ? null : data.type(overrides.forcedTypeName);
		WeatherType held = forced == null || overrides.heldTypeName == null ? null : data.type(overrides.heldTypeName);
		String key = WeatherZone.key(climate, season, forced, held, overrides.forcedIntensity, overrides.forcedFromPhase);
		WeatherZone zone = zoneCache.get(key);
		if (zone == null) {
			zone = new WeatherZone(climate, season, forced, held, overrides.forcedIntensity, overrides.forcedFromPhase);
			zoneCache.put(key, zone);
		}
		lastZone = zone;
		lastZoneClimate = climate;
		lastZoneSeason = season;
		lastZoneGeneration = overrideGeneration;
		return zone;
	}

	/** Timeline phase offset of a world position (local units), along the drift direction. */
	private static long phaseOffsetMs(WeatherTimeline timeline, double worldX, double worldY) {
		double dx = worldX - PHASE_ORIGIN_X * (double) LOCAL_TILE_SIZE;
		double dy = worldY - PHASE_ORIGIN_Y * (double) LOCAL_TILE_SIZE;
		double along = dx * timeline.driftX() + dy * timeline.driftZ();
		return Math.round(along * 1000 / FRONT_SPEED);
	}

	/** Timeline phase at a world tile at the given weather clock time. */
	public long phaseAt(int worldX, int worldY, long clockMs) {
		if (data == null)
			return clockMs;
		return clockMs - phaseOffsetMs(data.timeline, worldX * (double) LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE,
			worldY * (double) LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE);
	}

	// ----------------------------------------------------------------------------------------------------
	// Queries
	// ----------------------------------------------------------------------------------------------------

	/** A run of consecutive segments with the same weather type, as seen by a stationary observer. */
	public static final class Spell {
		public final long startClock, endClock;
		public final WeatherType type;
		/** Intensity of the spell's first and last segment. */
		public final float intensityFrom, intensityTo;
		/** Lowest and highest intensity of any segment in the spell. */
		public final float intensityMin, intensityMax;

		Spell(long startClock, long endClock, WeatherType type, float intensityFrom, float intensityTo, float intensityMin, float intensityMax) {
			this.startClock = startClock;
			this.endClock = endClock;
			this.type = type;
			this.intensityFrom = intensityFrom;
			this.intensityTo = intensityTo;
			this.intensityMin = intensityMin;
			this.intensityMax = intensityMax;
		}
	}

	/**
	 * Upcoming weather at a world tile for a stationary observer, using the climate bound there (or the current
	 * zone, including developer overrides, if the point is sheltered or unbound or anything is forced). Spells are
	 * bounded by segment boundaries; transition windows straddle them. Client thread only, since it shares the zone
	 * cache with {@link #update}.
	 */
	public List<Spell> forecast(int worldX, int worldY, int plane, long durationMs) {
		var data = this.data;
		if (data == null)
			return new ArrayList<>();
		WeatherZone zone = forecastZone(data, worldX, worldY, plane);
		long clockNow = state.active ? state.clockMillis : clock.now(System.currentTimeMillis());
		long phaseNow = phaseAt(worldX, worldY, clockNow);
		return forecast(new WeatherTimeline(data.timeline.settings), zone, phaseNow, clockNow - phaseNow, durationMs);
	}

	/**
	 * Merge consecutive segments of the same type into spells, starting at phaseNow.
	 *
	 * @param clockOffset added to phases to turn them into weather clock times for the observer
	 */
	public static List<Spell> forecast(WeatherTimeline timeline, WeatherZone zone, long phaseNow, long clockOffset, long durationMs) {
		List<Spell> spells = new ArrayList<>();
		long offset = clockOffset;
		long end = phaseNow + Math.min(durationMs, FORECAST_MAX_HOURS * 3_600_000L);
		var segment = timeline.segmentAt(phaseNow);
		WeatherType type = zone.typeFor(segment);
		long spellStart = phaseNow;
		float intensityFrom = zone.intensityFor(segment), intensityTo = intensityFrom;
		float intensityMin = intensityFrom, intensityMax = intensityFrom;
		while (segment.start < end) {
			var next = timeline.next(segment);
			intensityTo = zone.intensityFor(segment);
			intensityMin = Math.min(intensityMin, intensityTo);
			intensityMax = Math.max(intensityMax, intensityTo);
			WeatherType nextType = zone.typeFor(next);
			if (nextType != type || next.start >= end) {
				spells.add(new Spell(spellStart + offset, Math.min(next.start, end) + offset, type,
					intensityFrom, intensityTo, intensityMin, intensityMax));
				type = nextType;
				spellStart = next.start;
				intensityFrom = zone.intensityFor(next);
				intensityMin = intensityMax = intensityFrom;
			}
			segment = next;
		}
		return spells;
	}

	/**
	 * Evaluate the weather at another world tile, using the climate bound there, at the current weather clock plus
	 * an optional look-ahead. Returns null when the point is sheltered or unbound. Ignores developer-forced types and
	 * climates (a forced season still applies), unlike {@link #forecast} and the published strip. Client thread only,
	 * and never from within {@link #update}, since it shares the zone cache, the timeline's epoch cache and the
	 * sampler's scratch space.
	 */
	@Nullable
	public WeatherType sampleAt(int worldX, int worldY, int plane, long msAhead, float[] outParams) {
		var data = this.data;
		if (data == null)
			return null;
		var binding = data.resolve(worldX, worldY, plane);
		if (binding == null || binding.isNone())
			return null;
		var zone = naturalZone(binding.climate, getSeason());
		long phase = phaseAt(worldX, worldY, state.clockMillis + msAhead);
		sampler.sample(data.timeline, zone, phase, null, scratchStrip);
		scratchStrip.evaluateLocal(outParams);
		return scratchStrip.types[WeatherStrip.CENTER];
	}

	private final WeatherStrip scratchStrip = new WeatherStrip();

	private WeatherZone forecastZone(WeatherData data, int worldX, int worldY, int plane) {
		var binding = data.resolve(worldX, worldY, plane);
		if (binding != null && !binding.isNone() && overrides.forcedClimate == null && overrides.forcedTypeName == null)
			return naturalZone(binding.climate, getSeason());
		if (blend.target != null)
			return blend.target;
		return naturalZone(data.defaultClimate, getSeason());
	}

	/** A cached zone for a climate and season without developer overrides. */
	private WeatherZone naturalZone(Climate climate, SeasonalTheme season) {
		String key = WeatherZone.key(climate, season, null, null, Float.NaN, Long.MIN_VALUE);
		return zoneCache.computeIfAbsent(key, k -> new WeatherZone(climate, season));
	}

	/** The zone currently providing labels, falling back to the blend target. */
	@Nullable
	public WeatherZone getLabelZone() {
		return labelZone != null ? labelZone : blend.target;
	}

	public ZoneBlend getBlend() {
		return blend;
	}

	public float getForbidMask(int slot) {
		return forbidMask[slot];
	}

	public boolean isExposureLockstep() {
		return exposure.lockstep;
	}

	public boolean isEnvironmentGateOpen() {
		return environmentGate;
	}

	public SeasonalTheme getSeason() {
		return overrides.forcedSeason != null ? overrides.forcedSeason : configSeason;
	}

	// ----------------------------------------------------------------------------------------------------
	// Developer hooks, used by ::117hd weather commands. All run on the client thread.
	// ----------------------------------------------------------------------------------------------------

	private void overridesChanged() {
		overrideGeneration++;
	}

	/** Where a front will appear: the segment boundary it is centred on, and the phase at which it reaches the horizon. */
	public static final class FrontPlan {
		public final long boundaryStart;
		/** Phase at which the window's leading edge sits at {@link WeatherConstants#FRONT_VISIBLE_DISTANCE} upwind. */
		public final long horizonPhase;
		public final WeatherType from, to;

		FrontPlan(long boundaryStart, long horizonPhase, WeatherType from, WeatherType to) {
			this.boundaryStart = boundaryStart;
			this.horizonPhase = horizonPhase;
			this.from = from;
			this.to = to;
		}
	}

	/**
	 * Plan a forced roll-in: the first segment boundary after phase whose front can be placed on the horizon by moving
	 * the clock forward, holding {@code held} until then and {@code type} from then on.
	 */
	@Nullable
	public static FrontPlan planRollIn(
		WeatherTimeline timeline,
		Climate climate,
		SeasonalTheme season,
		WeatherType type,
		WeatherType held,
		float intensity,
		long phase
	) {
		var previous = timeline.segmentAt(phase);
		var boundary = timeline.next(previous);
		for (int i = 0; i < 256; i++) {
			var probe = new WeatherZone(climate, season, type, held, intensity, boundary.start);
			long horizon = boundary.start - probe.halfWidthMs(previous, boundary) - VISIBLE_DISTANCE_MS;
			if (horizon >= phase)
				return new FrontPlan(boundary.start, horizon, held, type);
			previous = boundary;
			boundary = timeline.next(boundary);
		}
		return null;
	}

	/**
	 * Plan a jump to the next type change of the zone (optionally into a specific type) whose front hasn't yet reached
	 * the horizon, searching up to {@link WeatherConstants#FORECAST_MAX_HOURS} ahead.
	 */
	@Nullable
	public static FrontPlan planJump(WeatherTimeline timeline, WeatherZone zone, long phase, @Nullable WeatherType type) {
		long limit = phase + FORECAST_MAX_HOURS * 3_600_000L;
		var previous = timeline.segmentAt(phase);
		var boundary = timeline.next(previous);
		while (boundary.start < limit) {
			var a = zone.typeFor(previous);
			var b = zone.typeFor(boundary);
			if (a != b && (type == null || b == type)) {
				long horizon = boundary.start - zone.halfWidthMs(previous, boundary) - VISIBLE_DISTANCE_MS;
				if (horizon >= phase)
					return new FrontPlan(boundary.start, horizon, a, b);
			}
			previous = boundary;
			boundary = timeline.next(boundary);
		}
		return null;
	}

	/**
	 * Roll a forced weather type in from the visible horizon. Every segment until the front holds the given (or
	 * current) type, so exactly one front is visible. The weather clock jumps so the front's leading edge sits at
	 * {@link WeatherConstants#FRONT_VISIBLE_DISTANCE} upwind.
	 *
	 * @return a description of what happened
	 */
	public String forceRollIn(WeatherType type, @Nullable WeatherType from, float intensity) {
		var data = this.data;
		if (data == null || !state.active)
			return "The weather controller isn't running here";
		WeatherType held = from != null ? from : state.type != null ? state.type : type;
		var climate = blend.target != null ? blend.target.climate : data.defaultClimate;
		long phase = state.phaseMillis;
		var plan = planRollIn(data.timeline, climate, getSeason(), type, held, intensity, phase);
		if (plan == null)
			return "Couldn't find a segment boundary to roll in from";
		overrides.forcedTypeName = type.name;
		overrides.heldTypeName = held.name;
		overrides.forcedIntensity = intensity;
		overrides.forcedFromPhase = plan.boundaryStart;
		overridesChanged();
		clock.addOffset(plan.horizonPhase - phase);
		pendingSnap = true;
		return String.format("Rolling in %s from the horizon after %s; it reaches you in about %d weather-minutes",
			type.name, held.name, (plan.boundaryStart - plan.horizonPhase) / 60_000);
	}

	/** Force a weather type everywhere right away, cross-fading over the climate blend time. */
	public void forceNow(WeatherType type, float intensity) {
		overrides.forcedTypeName = type.name;
		overrides.heldTypeName = null;
		overrides.forcedIntensity = intensity;
		overrides.forcedFromPhase = Long.MIN_VALUE;
		overridesChanged();
	}

	public void clearForcedType() {
		overrides.clearForcedType();
		overridesChanged();
	}

	public void forceClimate(@Nullable String climateName) {
		overrides.forcedClimate = climateName;
		overridesChanged();
	}

	public void forceSeason(@Nullable SeasonalTheme season) {
		overrides.forcedSeason = season;
		overridesChanged();
	}

	/** Override the surface wind for testing. The angle is in degrees, using the environment windAngle convention. */
	public void overrideWind(float angleDegrees, float strength, float speed) {
		if (!Float.isFinite(angleDegrees) || !Float.isFinite(strength) || !Float.isFinite(speed) || strength < 0 || speed < 0)
			throw new IllegalArgumentException("Wind override values must be finite and non-negative");
		overrides.windOverride = true;
		overrides.windAngle = WindModel.wrapAngle((float) Math.toRadians(angleDegrees % 360.0));
		overrides.windStrength = strength;
		overrides.windSpeed = speed;
	}

	public void clearWindOverride() {
		overrides.windOverride = false;
	}

	public void shiftClock(long ms) {
		clock.addOffset(ms);
		pendingSnap = true;
	}

	public void setClockScale(double scale) {
		clock.setScale(scale, System.currentTimeMillis());
	}

	public void resetClock() {
		clock.reset();
		pendingSnap = true;
	}

	/**
	 * Jump the weather clock to the moment the next type change (optionally into a specific type) appears on the
	 * horizon, searching up to {@link WeatherConstants#FORECAST_MAX_HOURS} ahead.
	 */
	public String jumpToNext(@Nullable WeatherType type) {
		var data = this.data;
		// The jump snaps the blend, which leaves only its target. Mid cross-fade the label zone may still be the
		// outgoing climate, so plan with the target, as forceRollIn does.
		var zone = blend.target != null ? blend.target : getLabelZone();
		if (data == null || zone == null || !state.active)
			return "The weather controller isn't running here";
		long phase = state.phaseMillis;
		var plan = planJump(data.timeline, zone, phase, type);
		if (plan == null)
			return "No " + (type == null ? "weather change" : type.name) + " within " + FORECAST_MAX_HOURS + " hours here";
		clock.addOffset(plan.horizonPhase - phase);
		pendingSnap = true;
		return String.format("Jumped %s ahead: %s is appearing on the horizon, after %s",
			formatDuration(plan.horizonPhase - phase), plan.to.name, plan.from.name);
	}

	public void setEnabled(boolean enabled) {
		overrides.disabled = !enabled;
		if (enabled) {
			failed = false;
			consecutiveFailures = 0;
			pendingSnap = true;
		}
	}

	public void resetOverrides() {
		overrides.clearAll();
		overridesChanged();
		resetClock();
		failed = false;
		consecutiveFailures = 0;
	}

	public static String formatDuration(long ms) {
		long minutes = Math.abs(ms) / 60_000;
		String sign = ms < 0 ? "-" : "";
		if (minutes < 60)
			return sign + minutes + "m";
		long hours = minutes / 60;
		if (hours < 48)
			return sign + hours + "h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "m");
		return sign + hours / 24 + "d " + hours % 24 + "h";
	}
}
