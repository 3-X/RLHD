package rs117.hd.scene.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.GsonUtils;

/**
 * Immutable weather data loaded from weather.json: the shared timeline, weather types, climates and the
 * ordered climate bindings.
 */
@Slf4j
public final class WeatherData {
	private static final Set<String> ROOT_KEYS = Set.of("description", "defaultClimate", "timeline", "types", "climates", "bindings");

	public final WeatherTimeline timeline;
	public final SteeringFlow steering;
	/** In file order. */
	public final Map<String, WeatherType> types;
	/** In file order. */
	public final Map<String, Climate> climates;
	public final List<ClimateBinding> bindings;
	public final Climate defaultClimate;

	private WeatherData(
		WeatherTimeline timeline,
		Map<String, WeatherType> types,
		Map<String, Climate> climates,
		List<ClimateBinding> bindings,
		Climate defaultClimate
	) {
		this.timeline = timeline;
		this.steering = new SteeringFlow(timeline.seed(), timeline.settings.driftAngle);
		this.types = Collections.unmodifiableMap(types);
		this.climates = Collections.unmodifiableMap(climates);
		this.bindings = Collections.unmodifiableList(bindings);
		this.defaultClimate = defaultClimate;
	}

	/** Parsed and validated definitions whose area names are not yet resolved. */
	public static final class Definitions {
		public final WeatherTimeline.Settings timeline;
		public final Map<String, WeatherType> types;
		public final Map<String, Climate> climates;
		public final List<ClimateBinding> bindings;
		public final Climate defaultClimate;
		public final List<String> warnings;

		private Definitions(
			WeatherTimeline.Settings timeline,
			Map<String, WeatherType> types,
			Map<String, Climate> climates,
			List<ClimateBinding> bindings,
			Climate defaultClimate,
			List<String> warnings
		) {
			this.timeline = timeline;
			this.types = types;
			this.climates = climates;
			this.bindings = bindings;
			this.defaultClimate = defaultClimate;
			this.warnings = warnings;
		}

		/**
		 * Resolve binding area names. Must run on the client thread, since {@link Area#normalize()} reads
		 * {@code AreaManager.AREAS}.
		 *
		 * @param areas  the currently loaded areas
		 * @param strict whether unknown area names are errors rather than warnings
		 */
		public WeatherData bind(Area[] areas, boolean strict) throws IOException {
			List<String> errors = new ArrayList<>();
			List<String> bindWarnings = new ArrayList<>(warnings);
			List<ClimateBinding> bound = new ArrayList<>(bindings.size());
			for (var binding : bindings) {
				var b = binding.bind(areas, strict, errors, bindWarnings);
				if (b != null)
					bound.add(b);
			}
			if (!errors.isEmpty())
				throw new IOException(formatErrors(errors));
			for (String warning : bindWarnings)
				log.warn("weather.json: {}", warning);
			return new WeatherData(new WeatherTimeline(timeline), types, climates, bound, defaultClimate);
		}
	}

	/** Parse and validate weather.json, collecting every error before failing. */
	public static Definitions parse(JsonObject root) throws IOException {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		WeatherJson.checkKeys(root, ROOT_KEYS, "weather.json", errors);

		JsonObject timelineJson = WeatherJson.getObject(root, "timeline", "weather.json", errors);
		var settings = WeatherTimeline.Settings.parse(timelineJson, errors);

		Map<String, WeatherType> types = new LinkedHashMap<>();
		for (var entry : resolveDefinitions(root, "types", "weather type", errors).entrySet()) {
			var type = WeatherType.parse(entry.getKey(), entry.getValue(), errors);
			if (type != null)
				types.put(type.name, type);
		}

		// Climates are validated even when the timeline is invalid, so their own errors are reported too. Without a valid
		// volatility, only the buffer check between distant severities is skipped.
		Map<String, Climate> climates = new LinkedHashMap<>();
		double volatility = settings != null ? settings.volatility : WeatherTimeline.Settings.peekVolatility(timelineJson);
		for (var entry : resolveDefinitions(root, "climates", "climate", errors).entrySet()) {
			var climate = Climate.parse(entry.getKey(), entry.getValue(), types, volatility, errors);
			if (climate != null)
				climates.put(climate.name, climate);
		}

		Climate defaultClimate = null;
		String defaultClimateName = WeatherJson.getString(root, "defaultClimate", "weather.json", errors);
		if (defaultClimateName == null) {
			errors.add("weather.json: 'defaultClimate' is required");
		} else {
			defaultClimate = climates.get(defaultClimateName);
			if (defaultClimate == null && errors.stream().noneMatch(e -> e.startsWith("climate '" + defaultClimateName + "'")))
				errors.add("weather.json: 'defaultClimate' names an unknown climate '" + defaultClimateName + "'");
		}

		List<ClimateBinding> bindings = new ArrayList<>();
		JsonArray bindingsJson = WeatherJson.getArray(root, "bindings", "weather.json", errors);
		if (bindingsJson == null) {
			errors.add("weather.json: a 'bindings' array is required");
		} else {
			for (int i = 0; i < bindingsJson.size(); i++) {
				var binding = ClimateBinding.parse(i, bindingsJson.get(i), climates, errors);
				if (binding != null)
					bindings.add(binding);
			}
		}

		// Weather types and climates nothing refers to are probably mistakes
		var usedTypes = new java.util.HashSet<WeatherType>();
		for (var climate : climates.values())
			for (var ladder : climate.ladders())
				Collections.addAll(usedTypes, ladder.types);
		for (var type : types.values())
			if (!usedTypes.contains(type))
				warnings.add("weather type " + type.name + " is not used by any climate");
		for (var climate : climates.values()) {
			boolean used = climate == defaultClimate;
			for (var binding : bindings)
				used |= binding.climate == climate;
			if (!used)
				warnings.add("climate " + climate.name + " is not bound to any area");
		}

		if (!errors.isEmpty() || settings == null || defaultClimate == null)
			throw new IOException(formatErrors(errors));
		return new Definitions(settings, types, climates, bindings, defaultClimate, warnings);
	}

	/** Map entries by name and resolve parent inheritance, reporting duplicates, reserved names and broken parents. */
	private static Map<String, JsonObject> resolveDefinitions(JsonObject root, String key, String kind, List<String> errors) {
		Map<String, JsonObject> definitions = new LinkedHashMap<>();
		JsonArray array = WeatherJson.getArray(root, key, "weather.json", errors);
		if (array == null) {
			errors.add("weather.json: a '" + key + "' array is required");
			return definitions;
		}
		for (int i = 0; i < array.size(); i++) {
			JsonElement element = array.get(i);
			if (!element.isJsonObject()) {
				errors.add(kind + " at index " + i + ": expected an object");
				continue;
			}
			JsonObject obj = element.getAsJsonObject();
			JsonElement name = obj.get("name");
			if (!WeatherJson.isString(name) || name.getAsString().isEmpty()) {
				errors.add(kind + " at index " + i + ": 'name' must be a non-empty string");
				continue;
			}
			String n = name.getAsString();
			if (Climate.NONE.equals(n)) {
				errors.add(kind + " at index " + i + ": the name NONE is reserved");
				continue;
			}
			if (definitions.putIfAbsent(n, obj.deepCopy()) != null)
				errors.add(kind + " '" + n + "' is defined more than once");
		}

		var resolved = GsonUtils.resolveParentDefinitions(definitions, kind, null, GsonUtils::deepInheritFrom);
		Map<String, JsonObject> ordered = new LinkedHashMap<>();
		for (String n : definitions.keySet()) {
			JsonObject obj = resolved.get(n);
			if (obj == null) {
				errors.add(kind + " '" + n + "' has an unknown or circular parent");
				continue;
			}
			ordered.put(n, obj);
		}
		return ordered;
	}

	private static String formatErrors(List<String> errors) {
		var sb = new StringBuilder("Invalid weather data (" + errors.size() + " error" + (errors.size() == 1 ? "" : "s") + "):");
		for (String error : errors)
			sb.append("\n  - ").append(error);
		return sb.toString();
	}

	/** The first binding containing the point, or null when unbound. */
	@Nullable
	public ClimateBinding resolve(int x, int y, int plane) {
		for (var binding : bindings)
			if (binding.contains(x, y, plane))
				return binding;
		return null;
	}

	@Nullable
	public Climate climate(String name) {
		return climates.get(name);
	}

	@Nullable
	public WeatherType type(String name) {
		return types.get(name);
	}
}
