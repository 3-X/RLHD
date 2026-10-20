package rs117.hd.scene.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import rs117.hd.utils.ColorUtils;

/**
 * Strict parsing helpers for weather.json. Every problem is appended to an error list instead of
 * throwing, so the loader can report all of them at once.
 */
final class WeatherJson {
	private WeatherJson() {}

	static void checkKeys(JsonObject obj, Set<String> allowed, String ctx, List<String> errors) {
		for (String key : obj.keySet())
			if (!allowed.contains(key))
				errors.add(ctx + ": unknown key '" + key + "'");
	}

	@Nullable
	static String getString(JsonObject obj, String key, String ctx, List<String> errors) {
		JsonElement e = obj.get(key);
		if (e == null || e.isJsonNull())
			return null;
		if (!isString(e)) {
			errors.add(ctx + ": '" + key + "' must be a string");
			return null;
		}
		return e.getAsString();
	}

	@Nullable
	static Double getNumber(JsonObject obj, String key, String ctx, List<String> errors) {
		JsonElement e = obj.get(key);
		if (e == null || e.isJsonNull())
			return null;
		if (!isNumber(e)) {
			errors.add(ctx + ": '" + key + "' must be a number");
			return null;
		}
		double value = e.getAsDouble();
		if (!Double.isFinite(value)) {
			errors.add(ctx + ": '" + key + "' must be finite");
			return null;
		}
		return value;
	}

	@Nullable
	static Integer getInteger(JsonObject obj, String key, String ctx, List<String> errors) {
		Double value = getNumber(obj, key, ctx, errors);
		if (value == null)
			return null;
		if (value != Math.rint(value)) {
			errors.add(ctx + ": '" + key + "' must be an integer");
			return null;
		}
		return value.intValue();
	}

	@Nullable
	static JsonObject getObject(JsonObject obj, String key, String ctx, List<String> errors) {
		JsonElement e = obj.get(key);
		if (e == null || e.isJsonNull())
			return null;
		if (!e.isJsonObject()) {
			errors.add(ctx + ": '" + key + "' must be an object");
			return null;
		}
		return e.getAsJsonObject();
	}

	@Nullable
	static JsonArray getArray(JsonObject obj, String key, String ctx, List<String> errors) {
		JsonElement e = obj.get(key);
		if (e == null || e.isJsonNull())
			return null;
		if (!e.isJsonArray()) {
			errors.add(ctx + ": '" + key + "' must be an array");
			return null;
		}
		return e.getAsJsonArray();
	}

	static boolean isString(JsonElement e) {
		return e != null && e.isJsonPrimitive() && ((JsonPrimitive) e).isString();
	}

	static boolean isNumber(JsonElement e) {
		return e != null && e.isJsonPrimitive() && ((JsonPrimitive) e).isNumber();
	}

	/**
	 * Parse a params object into the given mild and severe vectors. Only keys present in the object are
	 * written, so this can layer climate overrides on top of a type's own values.
	 */
	static void parseParams(JsonObject params, float[] mild, float[] severe, String ctx, List<String> errors) {
		for (var entry : params.entrySet()) {
			String key = entry.getKey();
			JsonElement value = entry.getValue();
			WeatherParam param = WeatherParam.byKey(key);
			if (param == null) {
				errors.add(ctx + ": unknown parameter '" + key + "'");
				continue;
			}
			String paramCtx = ctx + "." + key;
			int offset = param.offset();
			if (param.kind == WeatherParam.Kind.COLOR) {
				float[] rgb = parseColor(value, paramCtx, errors);
				if (rgb == null)
					continue;
				System.arraycopy(rgb, 0, mild, offset, 3);
				System.arraycopy(rgb, 0, severe, offset, 3);
				continue;
			}

			float lo, hi;
			if (isNumber(value)) {
				lo = hi = value.getAsFloat();
			} else if (value.isJsonArray() && value.getAsJsonArray().size() == 2
				&& isNumber(value.getAsJsonArray().get(0)) && isNumber(value.getAsJsonArray().get(1))) {
				lo = value.getAsJsonArray().get(0).getAsFloat();
				hi = value.getAsJsonArray().get(1).getAsFloat();
			} else {
				errors.add(paramCtx + ": expected a number or [mild, severe]");
				continue;
			}
			if (!Float.isFinite(lo) || !Float.isFinite(hi) || lo < param.min || lo > param.max || hi < param.min || hi > param.max) {
				errors.add(paramCtx + ": values must be within [" + param.min + ", " + param.max + "]");
				continue;
			}
			mild[offset] = lo;
			severe[offset] = hi;
		}
	}

	/** Parses "#rrggbb" or [r, g, b] in 0-255 sRGB into linear RGB. */
	@Nullable
	static float[] parseColor(JsonElement value, String ctx, List<String> errors) {
		if (isString(value)) {
			String hex = value.getAsString();
			if (!hex.matches("^#[0-9a-fA-F]{6}$")) {
				errors.add(ctx + ": expected a color like \"#rrggbb\"");
				return null;
			}
			return ColorUtils.rgb(hex);
		}
		if (value.isJsonArray() && value.getAsJsonArray().size() == 3) {
			JsonArray arr = value.getAsJsonArray();
			float[] c = new float[3];
			for (int i = 0; i < 3; i++) {
				if (!isNumber(arr.get(i))) {
					errors.add(ctx + ": color components must be numbers");
					return null;
				}
				c[i] = arr.get(i).getAsFloat();
				if (!(c[i] >= 0 && c[i] <= 255)) {
					errors.add(ctx + ": color components must be within [0, 255]");
					return null;
				}
			}
			return ColorUtils.rgb(c[0], c[1], c[2]);
		}
		errors.add(ctx + ": expected a color like \"#rrggbb\" or [r, g, b]");
		return null;
	}
}
