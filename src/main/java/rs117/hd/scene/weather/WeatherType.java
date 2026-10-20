package rs117.hd.scene.weather;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A named weather preset, such as RAIN or SANDSTORM. Each parameter is either a constant or a
 * [mild, severe] range, interpolated by how deep into its ladder bin the timeline's latent value is.
 */
public final class WeatherType {
	private static final Set<String> KEYS = Set.of(
		"name", "parent", "description", "severity", "onsetMinutes", "clearMinutes", "params");

	public final String name;
	@Nullable
	public final String description;
	/** 0 (fair) to 4 (severe). Ladders must be ordered by non-decreasing severity. */
	public final int severity;
	/** Length of a transition into this type when the severity increases. */
	public final float onsetMinutes;
	/** Length of a transition out of this type when the severity decreases. */
	public final float clearMinutes;
	/** Parameter vectors at the mild and severe end of the type's range, with defaults filled in. */
	final float[] mild, severe;

	private WeatherType(String name, @Nullable String description, int severity, float onsetMinutes, float clearMinutes, float[] mild, float[] severe) {
		this.name = name;
		this.description = description;
		this.severity = severity;
		this.onsetMinutes = onsetMinutes;
		this.clearMinutes = clearMinutes;
		this.mild = mild;
		this.severe = severe;
	}

	/** Parse a weather type whose parent has already been resolved into it. */
	@Nullable
	static WeatherType parse(String name, JsonObject obj, List<String> errors) {
		String ctx = "weather type '" + name + "'";
		int errorCount = errors.size();
		WeatherJson.checkKeys(obj, KEYS, ctx, errors);
		String description = WeatherJson.getString(obj, "description", ctx, errors);

		Integer severity = WeatherJson.getInteger(obj, "severity", ctx, errors);
		if (severity == null) {
			errors.add(ctx + ": 'severity' is required");
		} else if (severity < 0 || severity > 4) {
			errors.add(ctx + ": 'severity' must be within [0, 4]");
		}
		Double onset = WeatherJson.getNumber(obj, "onsetMinutes", ctx, errors);
		Double clear = WeatherJson.getNumber(obj, "clearMinutes", ctx, errors);
		if (onset == null || clear == null) {
			errors.add(ctx + ": 'onsetMinutes' and 'clearMinutes' are required");
		} else if (onset < 1 || onset > 60 || clear < 1 || clear > 60) {
			errors.add(ctx + ": 'onsetMinutes' and 'clearMinutes' must be within [1, 60]");
		}

		float[] mild = WeatherParam.defaultVector();
		float[] severe = WeatherParam.defaultVector();
		JsonObject params = WeatherJson.getObject(obj, "params", ctx, errors);
		if (params != null)
			WeatherJson.parseParams(params, mild, severe, ctx, errors);

		if (errors.size() > errorCount || severity == null || onset == null || clear == null)
			return null;
		return new WeatherType(name, description, severity, onset.floatValue(), clear.floatValue(), mild, severe);
	}

	/** Build a weather type directly, for tests and tools. Unspecified parameters keep their defaults. */
	public static WeatherType synthetic(String name, int severity, float onsetMinutes, float clearMinutes, Map<WeatherParam, float[]> values) {
		float[] mild = WeatherParam.defaultVector();
		float[] severe = WeatherParam.defaultVector();
		for (var entry : values.entrySet()) {
			WeatherParam param = entry.getKey();
			float[] v = entry.getValue();
			if (param.kind == WeatherParam.Kind.COLOR) {
				System.arraycopy(v, 0, mild, param.offset(), 3);
				System.arraycopy(v, 0, severe, param.offset(), 3);
			} else {
				mild[param.offset()] = v[0];
				severe[param.offset()] = v.length > 1 ? v[1] : v[0];
			}
		}
		return new WeatherType(name, null, severity, onsetMinutes, clearMinutes, mild, severe);
	}

	public float mild(WeatherParam param) {
		return mild[param.offset()];
	}

	public float severe(WeatherParam param) {
		return severe[param.offset()];
	}

	/** Whether the parameter is non-zero anywhere in this type's own range. */
	public boolean uses(WeatherParam param) {
		for (int i = 0; i < param.size; i++)
			if (mild[param.offset() + i] != 0 || severe[param.offset() + i] != 0)
				return true;
		return false;
	}

	@Override
	public String toString() {
		return name;
	}
}
