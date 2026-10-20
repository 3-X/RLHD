package rs117.hd.scene.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;

/**
 * Assigns a climate to a set of areas. Bindings are ordered, and the first one containing a point wins.
 * Points matching no binding get no weather.
 */
public final class ClimateBinding {
	private static final Set<String> KEYS = Set.of("climate", "description", "areas", "aabbs", "regions");

	public final int index;
	/** The climate's name, or {@link Climate#NONE} for sheltered areas. */
	public final String climateName;
	/** The bound climate, or null for {@link Climate#NONE}. */
	@Nullable
	public final Climate climate;
	@Nullable
	public final String description;
	public final String[] areaNames;
	public final AABB[] aabbs;
	public final int[] regions;
	/** Synthetic area combining every referenced area, AABB and region. Null until bound. */
	@Nullable
	public final Area area;

	private ClimateBinding(
		int index,
		String climateName,
		@Nullable Climate climate,
		@Nullable String description,
		String[] areaNames,
		AABB[] aabbs,
		int[] regions,
		@Nullable Area area
	) {
		this.index = index;
		this.climateName = climateName;
		this.climate = climate;
		this.description = description;
		this.areaNames = areaNames;
		this.aabbs = aabbs;
		this.regions = regions;
		this.area = area;
	}

	public boolean isNone() {
		return climate == null;
	}

	@Nullable
	static ClimateBinding parse(int index, JsonElement element, Map<String, Climate> climates, List<String> errors) {
		String ctx = "binding #" + index;
		if (!element.isJsonObject()) {
			errors.add(ctx + ": expected an object");
			return null;
		}
		JsonObject obj = element.getAsJsonObject();
		int errorCount = errors.size();
		WeatherJson.checkKeys(obj, KEYS, ctx, errors);

		String climateName = WeatherJson.getString(obj, "climate", ctx, errors);
		Climate climate = null;
		if (climateName == null) {
			errors.add(ctx + ": 'climate' is required");
		} else if (!Climate.NONE.equals(climateName)) {
			climate = climates.get(climateName);
			if (climate == null)
				errors.add(ctx + ": unknown climate '" + climateName + "'");
		}
		String description = WeatherJson.getString(obj, "description", ctx, errors);

		List<String> names = new ArrayList<>();
		JsonArray areas = WeatherJson.getArray(obj, "areas", ctx, errors);
		if (areas != null) {
			for (JsonElement e : areas) {
				if (!WeatherJson.isString(e)) {
					errors.add(ctx + ": 'areas' must only contain area names");
					continue;
				}
				String name = e.getAsString();
				if ("ALL".equals(name) || "NONE".equals(name)) {
					errors.add(ctx + ": the area '" + name + "' cannot be bound");
					continue;
				}
				names.add(name);
			}
		}

		List<AABB> aabbs = new ArrayList<>();
		JsonArray aabbsJson = WeatherJson.getArray(obj, "aabbs", ctx, errors);
		if (aabbsJson != null) {
			for (JsonElement e : aabbsJson) {
				AABB aabb = parseAabb(e);
				if (aabb == null) {
					errors.add(ctx + ": 'aabbs' entries must be arrays of 2 to 6 integers, got " + e);
					continue;
				}
				aabbs.add(aabb);
			}
		}

		List<Integer> regions = new ArrayList<>();
		JsonArray regionsJson = WeatherJson.getArray(obj, "regions", ctx, errors);
		if (regionsJson != null) {
			for (JsonElement e : regionsJson) {
				if (!WeatherJson.isNumber(e) || e.getAsDouble() != Math.rint(e.getAsDouble())) {
					errors.add(ctx + ": 'regions' must only contain region IDs");
					continue;
				}
				regions.add(e.getAsInt());
			}
		}

		if (names.isEmpty() && aabbs.isEmpty() && regions.isEmpty())
			errors.add(ctx + ": at least one of 'areas', 'aabbs' or 'regions' is required");

		if (errors.size() > errorCount)
			return null;
		return new ClimateBinding(
			index,
			climateName,
			climate,
			description,
			names.toArray(String[]::new),
			aabbs.toArray(AABB[]::new),
			regions.stream().mapToInt(Integer::intValue).toArray(),
			null
		);
	}

	@Nullable
	private static AABB parseAabb(JsonElement e) {
		if (!e.isJsonArray())
			return null;
		JsonArray arr = e.getAsJsonArray();
		int[] v = new int[arr.size()];
		for (int i = 0; i < v.length; i++) {
			if (!WeatherJson.isNumber(arr.get(i)))
				return null;
			double d = arr.get(i).getAsDouble();
			if (d != Math.rint(d))
				return null;
			v[i] = (int) d;
		}
		switch (v.length) {
			case 2:
				return new AABB(v[0], v[1]);
			case 3:
				return new AABB(v[0], v[1], v[2]);
			case 4:
				return new AABB(v[0], v[1], v[2], v[3]);
			case 5:
				return new AABB(v[0], v[1], v[2], v[3], v[4]);
			case 6:
				return new AABB(v[0], v[1], v[2], v[3], v[4], v[5]);
			default:
				return null;
		}
	}

	/**
	 * Resolve area names into a synthetic {@link Area}. Must run on the client thread, since
	 * {@link Area#normalize()} reads {@code AreaManager.AREAS}.
	 *
	 * @param areas  the currently loaded areas
	 * @param strict whether unknown area names are errors rather than warnings
	 * @return the bound copy, or null if nothing remains to bind
	 */
	@Nullable
	ClimateBinding bind(Area[] areas, boolean strict, List<String> errors, List<String> warnings) {
		String ctx = "binding #" + index + " (" + climateName + ")";
		List<String> resolved = new ArrayList<>();
		for (String name : areaNames) {
			boolean found = false;
			for (Area area : areas) {
				if (name.equals(area.name)) {
					found = true;
					break;
				}
			}
			if (found) {
				resolved.add(name);
			} else if (strict) {
				errors.add(ctx + ": no area exists with the name '" + name + "'");
			} else {
				warnings.add(ctx + ": ignoring unknown area '" + name + "'");
			}
		}

		Area area = new Area("weather binding #" + index);
		area.areas = resolved.toArray(String[]::new);
		area.rawAabbs = aabbs;
		area.regions = regions;
		try {
			area.normalize();
		} catch (RuntimeException ex) {
			errors.add(ctx + ": " + ex.getMessage());
			return null;
		}
		if (area.aabbs.length == 0) {
			warnings.add(ctx + ": covers nothing after resolving its areas, so it is ignored");
			return null;
		}
		return new ClimateBinding(index, climateName, climate, description, areaNames, aabbs, regions, area);
	}

	public boolean contains(int x, int y, int plane) {
		return area != null && area.containsPoint(false, x, y, plane);
	}

	@Override
	public String toString() {
		return "#" + index + " " + climateName;
	}
}
