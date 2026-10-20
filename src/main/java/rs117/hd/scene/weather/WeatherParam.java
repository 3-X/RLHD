package rs117.hd.scene.weather;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.MathUtils.*;

/**
 * The blendable weather parameter vector. Scalars take one slot, colours take three (linear RGB).
 * Adding a parameter requires a new constant here, a schema entry and JSON values; parsing, blending,
 * sampling, forbidding and the tests iterate over this enum. The debug overlay's rows are hand-written
 * (WeatherCommands.describe), so add new parameters there too.
 */
public enum WeatherParam {
	CLOUD_COVER("cloudCover", Group.CLOUD, 0, 0, 1),
	CLOUD_THICKNESS("cloudThickness", Group.CLOUD, 1, 0, 8),
	CLOUD_BILLOW("cloudBillow", Group.CLOUD, .5f, 0, 1),
	CLOUD_SCALE("cloudScale", Group.CLOUD, 1, .25f, 4),
	HIGH_CLOUD_COVER("highCloudCover", Group.CLOUD, 0, 0, 1),
	CLOUD_DARKNESS("cloudDarkness", Group.CLOUD, 0, 0, 1),
	CLOUD_TINT("cloudTint", Group.CLOUD, "#ffffff"),
	RAIN("rain", Group.PRECIP, 0, 0, 1, true),
	SNOW("snow", Group.PRECIP, 0, 0, 1, true),
	ASH("ash", Group.PRECIP, 0, 0, 1, true),
	DUST("dust", Group.PRECIP, 0, 0, 1, true),
	STORMINESS("storminess", Group.PRECIP, 0, 0, 1, true),
	HAZE("haze", Group.ATMOS, 0, 0, 1, true),
	FOG("fog", Group.ATMOS, 0, 0, 1, true),
	HAZE_COLOR("hazeColor", Group.ATMOS, "#c9d1d9"),
	/** Multiplier for the baseline wind strength (displacement amplitude). */
	WIND_STRENGTH("windStrength", Group.WIND, 1, 0, 3),
	/** Multiplier for the baseline wind speed (gust noise scroll rate). */
	WIND_SPEED("windSpeed", Group.WIND, 1, 0, 3),
	GUSTINESS("gustiness", Group.WIND, 0, 0, 1),
	/** How far the surface wind turns from its baseline direction toward the upper (cloud-level) wind. */
	WIND_ALIGN("windAlign", Group.WIND, 0, 0, 1),
	/** Multiplier for the steering speed at which cloud features drift. */
	CLOUD_SPEED("cloudSpeed", Group.WIND, 1, 0, 4),
	;

	public enum Kind {
		SCALAR,
		COLOR
	}

	/**
	 * Parameters in a group share an easing curve across transition windows. Clouds and wind lead
	 * on onset, while precipitation starts late and stops early.
	 */
	public enum Group {
		CLOUD(0, .7f, .3f, 1),
		PRECIP(.35f, 1, 0, .6f),
		ATMOS(.1f, .9f, .1f, .9f),
		WIND(0, .6f, .2f, 1);

		public final float onsetStart, onsetEnd, clearStart, clearEnd;

		Group(float onsetStart, float onsetEnd, float clearStart, float clearEnd) {
			this.onsetStart = onsetStart;
			this.onsetEnd = onsetEnd;
			this.clearStart = clearStart;
			this.clearEnd = clearEnd;
		}

		/**
		 * @param shape +1 for onset (severity increasing), -1 for clearing, 0 for neutral
		 * @param p     progress across the transition window, 0 to 1
		 * @return the eased blend factor, 0 at p = 0 and 1 at p = 1
		 */
		public float ease(int shape, float p) {
			if (shape > 0)
				return smoothstep(onsetStart, onsetEnd, p);
			if (shape < 0)
				return smoothstep(clearStart, clearEnd, p);
			return smoothstep(0, 1, p);
		}
	}

	public static final WeatherParam[] VALUES = values();
	public static final int VECTOR_SIZE;

	private static final Map<String, WeatherParam> BY_KEY = new HashMap<>();
	private static final WeatherParam[] SLOT_PARAMS;
	private static final float[] DEFAULT_VECTOR;

	static {
		int offset = 0;
		for (var param : VALUES) {
			param.offset = offset;
			offset += param.size;
			BY_KEY.put(param.key, param);
		}
		VECTOR_SIZE = offset;

		SLOT_PARAMS = new WeatherParam[VECTOR_SIZE];
		DEFAULT_VECTOR = new float[VECTOR_SIZE];
		for (var param : VALUES) {
			for (int i = 0; i < param.size; i++) {
				SLOT_PARAMS[param.offset + i] = param;
				DEFAULT_VECTOR[param.offset + i] = param.defaults[i];
			}
		}
	}

	public final String key;
	public final Group group;
	public final Kind kind;
	public final int size;
	public final float min, max;
	/** Whether a climate may forbid this parameter. Only parameters where 0 means "absent" qualify. */
	public final boolean forbiddable;
	private final float[] defaults;
	private int offset;

	WeatherParam(String key, Group group, float defaultValue, float min, float max) {
		this(key, group, defaultValue, min, max, false);
	}

	WeatherParam(String key, Group group, float defaultValue, float min, float max, boolean forbiddable) {
		this.key = key;
		this.group = group;
		this.kind = Kind.SCALAR;
		this.size = 1;
		this.min = min;
		this.max = max;
		this.forbiddable = forbiddable;
		this.defaults = new float[] { defaultValue };
	}

	WeatherParam(String key, Group group, String defaultColor) {
		this.key = key;
		this.group = group;
		this.kind = Kind.COLOR;
		this.size = 3;
		this.min = 0;
		this.max = 1;
		this.forbiddable = false;
		this.defaults = ColorUtils.rgb(defaultColor);
	}

	/** Index of this parameter's first slot in the parameter vector. */
	public int offset() {
		return offset;
	}

	public float defaultValue(int component) {
		return defaults[component];
	}

	@Nullable
	public static WeatherParam byKey(String key) {
		return BY_KEY.get(key);
	}

	public static WeatherParam ofSlot(int slot) {
		return SLOT_PARAMS[slot];
	}

	public static Group groupOfSlot(int slot) {
		return SLOT_PARAMS[slot].group;
	}

	/** A new parameter vector filled with every parameter's default value. */
	public static float[] defaultVector() {
		return DEFAULT_VECTOR.clone();
	}

	public static void fillDefaults(float[] out) {
		System.arraycopy(DEFAULT_VECTOR, 0, out, 0, VECTOR_SIZE);
	}
}
