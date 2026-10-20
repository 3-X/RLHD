package rs117.hd.scene.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import rs117.hd.config.SeasonalTheme;

import static rs117.hd.utils.MathUtils.*;

/**
 * Which weather a region can have and how often. A climate maps the shared timeline's latent value
 * through a fair-to-foul ladder, optionally replaced per season, and can forbid parameters outright
 * (e.g. no snow in the desert), which the loader verifies against every reachable weather type.
 */
public final class Climate {
	/** Reserved binding climate meaning "sheltered: no weather here". */
	public static final String NONE = "NONE";

	private static final Set<String> KEYS = Set.of(
		"name", "parent", "description", "maxSeverityStep", "wind", "windBlend", "forbid", "ladder", "seasons", "overrides");
	private static final Set<String> WIND_KEYS = Set.of("angle", "speed", "strength");
	private static final Set<String> SEASON_KEYS = Set.of("SUMMER", "AUTUMN", "WINTER");

	public final String name;
	@Nullable
	public final String description;
	public final int maxSeverityStep;
	/** Default wind (radians, local units/s, local units), used outdoors where the environment authors no wind. */
	public final float windAngle, windSpeed, windStrength;
	/** How strongly this climate's wind replaces authored environment wind outdoors, 0 to 1. */
	public final float windBlend;
	public final List<WeatherParam> forbidden;
	public final ClimateLadder defaultLadder;
	private final EnumMap<SeasonalTheme, ClimateLadder> seasonal;
	private final boolean[] forbiddenSlots = new boolean[WeatherParam.VECTOR_SIZE];
	/** Per type: [0] mild and [1] severe vectors with this climate's overrides applied. */
	private final Map<WeatherType, float[][]> vectors;

	private Climate(
		String name,
		@Nullable String description,
		int maxSeverityStep,
		float windAngle,
		float windSpeed,
		float windStrength,
		float windBlend,
		List<WeatherParam> forbidden,
		ClimateLadder defaultLadder,
		EnumMap<SeasonalTheme, ClimateLadder> seasonal,
		Map<WeatherType, float[][]> vectors
	) {
		this.name = name;
		this.description = description;
		this.maxSeverityStep = maxSeverityStep;
		this.windAngle = windAngle;
		this.windSpeed = windSpeed;
		this.windStrength = windStrength;
		this.windBlend = windBlend;
		this.forbidden = Collections.unmodifiableList(forbidden);
		this.defaultLadder = defaultLadder;
		this.seasonal = seasonal;
		this.vectors = vectors;
		for (var param : forbidden)
			for (int i = 0; i < param.size; i++)
				forbiddenSlots[param.offset() + i] = true;
	}

	/** Parse a climate whose parent has already been resolved into it. */
	@Nullable
	static Climate parse(String name, JsonObject obj, Map<String, WeatherType> types, double volatility, List<String> errors) {
		String ctx = "climate '" + name + "'";
		int errorCount = errors.size();
		WeatherJson.checkKeys(obj, KEYS, ctx, errors);
		String description = WeatherJson.getString(obj, "description", ctx, errors);

		Integer maxStep = WeatherJson.getInteger(obj, "maxSeverityStep", ctx, errors);
		if (maxStep == null) {
			maxStep = 2;
		} else if (maxStep < 1 || maxStep > 4) {
			errors.add(ctx + ": 'maxSeverityStep' must be within [1, 4]");
		}

		float windAngle = 0, windSpeed = 0, windStrength = 0;
		JsonObject wind = WeatherJson.getObject(obj, "wind", ctx, errors);
		if (wind == null) {
			errors.add(ctx + ": 'wind' is required (it may be inherited from a parent)");
		} else {
			String windCtx = ctx + ".wind";
			WeatherJson.checkKeys(wind, WIND_KEYS, windCtx, errors);
			Double angle = WeatherJson.getNumber(wind, "angle", windCtx, errors);
			Double speed = WeatherJson.getNumber(wind, "speed", windCtx, errors);
			Double strength = WeatherJson.getNumber(wind, "strength", windCtx, errors);
			if (angle == null || speed == null || strength == null) {
				errors.add(windCtx + ": 'angle', 'speed' and 'strength' are required");
			} else {
				if (speed < 0 || speed > WeatherConstants.WIND_SPEED_MAX)
					errors.add(windCtx + ": 'speed' must be within [0, " + WeatherConstants.WIND_SPEED_MAX + "]");
				if (strength < 0 || strength > WeatherConstants.WIND_STRENGTH_MAX)
					errors.add(windCtx + ": 'strength' must be within [0, " + WeatherConstants.WIND_STRENGTH_MAX + "]");
				windAngle = WindModel.wrapAngle((float) (Math.toRadians(angle) % (2 * Math.PI)));
				windSpeed = speed.floatValue();
				windStrength = strength.floatValue();
			}
		}

		Double windBlend = WeatherJson.getNumber(obj, "windBlend", ctx, errors);
		if (windBlend == null) {
			windBlend = 0.;
		} else if (windBlend < 0 || windBlend > 1) {
			errors.add(ctx + ": 'windBlend' must be within [0, 1]");
		}

		List<WeatherParam> forbidden = new ArrayList<>();
		JsonArray forbid = WeatherJson.getArray(obj, "forbid", ctx, errors);
		if (forbid != null) {
			for (JsonElement e : forbid) {
				WeatherParam param = WeatherJson.isString(e) ? WeatherParam.byKey(e.getAsString()) : null;
				if (param == null) {
					errors.add(ctx + ": 'forbid' entries must be parameter names, got " + e);
				} else if (!param.forbiddable) {
					errors.add(ctx + ": '" + param.key + "' cannot be forbidden; only parameters where 0 means absent can be");
				} else if (!forbidden.contains(param)) {
					forbidden.add(param);
				}
			}
		}

		ClimateLadder defaultLadder = null;
		JsonElement ladderJson = obj.get("ladder");
		if (ladderJson == null) {
			errors.add(ctx + ": 'ladder' is required (it may be inherited from a parent)");
		} else {
			defaultLadder = ClimateLadder.parse(ladderJson, types, ctx + ".ladder", errors);
		}

		var seasonal = new EnumMap<SeasonalTheme, ClimateLadder>(SeasonalTheme.class);
		JsonObject seasons = WeatherJson.getObject(obj, "seasons", ctx, errors);
		if (seasons != null) {
			WeatherJson.checkKeys(seasons, SEASON_KEYS, ctx + ".seasons", errors);
			for (String season : SEASON_KEYS) {
				JsonElement seasonLadder = seasons.get(season);
				if (seasonLadder == null)
					continue;
				var ladder = ClimateLadder.parse(seasonLadder, types, ctx + ".seasons." + season, errors);
				if (ladder != null)
					seasonal.put(SeasonalTheme.valueOf(season), ladder);
			}
		}

		if (defaultLadder == null || errors.size() > errorCount)
			return null;

		// Every type this climate can produce, in ladder order
		var reachable = new LinkedHashSet<WeatherType>();
		Collections.addAll(reachable, defaultLadder.types);
		for (var ladder : seasonal.values())
			Collections.addAll(reachable, ladder.types);

		var vectors = new HashMap<WeatherType, float[][]>();
		for (var type : reachable)
			vectors.put(type, new float[][] { type.mild.clone(), type.severe.clone() });

		JsonObject overrides = WeatherJson.getObject(obj, "overrides", ctx, errors);
		if (overrides != null) {
			for (var entry : overrides.entrySet()) {
				String overrideCtx = ctx + ".overrides." + entry.getKey();
				WeatherType type = types.get(entry.getKey());
				if (type == null || !reachable.contains(type)) {
					errors.add(overrideCtx + ": overrides must name a weather type used by this climate's ladders");
					continue;
				}
				if (!entry.getValue().isJsonObject()) {
					errors.add(overrideCtx + ": expected a params object");
					continue;
				}
				float[][] v = vectors.get(type);
				WeatherJson.parseParams(entry.getValue().getAsJsonObject(), v[0], v[1], overrideCtx, errors);
			}
		}

		// Forbidden parameters must be exactly zero in every reachable state
		for (var param : forbidden) {
			for (var type : reachable) {
				float[][] v = vectors.get(type);
				if (v[0][param.offset()] != 0 || v[1][param.offset()] != 0) {
					errors.add(ctx + ": forbids '" + param.key + "', but weather type " + type.name + " in its ladders produces it");
				}
			}
		}

		defaultLadder.validate(volatility, maxStep, ctx + ".ladder", errors);
		for (var entry : seasonal.entrySet())
			entry.getValue().validate(volatility, maxStep, ctx + ".seasons." + entry.getKey(), errors);

		if (errors.size() > errorCount)
			return null;
		return new Climate(
			name, description, maxStep, windAngle, windSpeed, windStrength, windBlend.floatValue(),
			forbidden, defaultLadder, seasonal, vectors);
	}

	/** The ladder in effect for a resolved seasonal theme. AUTOMATIC is treated as SUMMER. */
	public ClimateLadder ladderFor(@Nullable SeasonalTheme season) {
		if (season == null)
			return defaultLadder;
		return seasonal.getOrDefault(season, defaultLadder);
	}

	public List<ClimateLadder> ladders() {
		var list = new ArrayList<ClimateLadder>(1 + seasonal.size());
		list.add(defaultLadder);
		list.addAll(seasonal.values());
		return list;
	}

	public boolean hasSeasonalLadder(SeasonalTheme season) {
		return seasonal.containsKey(season);
	}

	/** Whether any of this climate's ladders can produce the type. */
	public boolean reaches(WeatherType type) {
		return vectors.containsKey(type);
	}

	/** Mild-end vector for a type, with this climate's overrides applied when the type is one of its own. */
	public float[] mild(WeatherType type) {
		float[][] v = vectors.get(type);
		return v != null ? v[0] : type.mild;
	}

	public float[] severe(WeatherType type) {
		float[][] v = vectors.get(type);
		return v != null ? v[1] : type.severe;
	}

	public boolean forbids(WeatherParam param) {
		return forbiddenSlots[param.offset()];
	}

	public boolean forbidsSlot(int slot) {
		return forbiddenSlots[slot];
	}

	/** Whether the type produces any parameter this climate forbids, judged by the type's own values. */
	public boolean conflictsWith(WeatherType type) {
		for (var param : forbidden)
			if (type.uses(param))
				return true;
		return false;
	}

	@Override
	public String toString() {
		return name;
	}
}
