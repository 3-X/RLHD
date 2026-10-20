package rs117.hd.scene.weather;

import static rs117.hd.scene.weather.WeatherConstants.*;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.murmurHash3;

/**
 * Surface wind: an environment-authored baseline, filled in by the climate where the environment authors no
 * wind, then modulated by the weather (strength, speed, gusts, veer and alignment with the upper wind).
 * <p>
 * The baseline eases from the previously displayed value, either in lockstep with environment transitions or
 * over a fixed time, so the environment's own interpolation is never applied twice. With zero exposure the
 * output reproduces the environment's wind exactly.
 */
public final class WindModel {
	private static final int MODE_NONE = 0;
	private static final int MODE_LOCKSTEP = 1;
	private static final int MODE_TIMED = 2;

	public float baseAngle, baseSpeed, baseStrength;
	private float fromAngle, fromSpeed, fromStrength;
	private int mode = MODE_NONE;
	private long environmentId = Long.MIN_VALUE;
	private int targetKey = Integer.MIN_VALUE;
	private double rampStart;
	private boolean initialized;

	private double rebase, rebaseX, rebaseZ;

	/** Offsets beyond this can no longer register a single frame's step in double precision, so they're reset. */
	private static final double OFFSET_LIMIT = 1e12;

	/** Wrap an angle into [0, 2pi). Unlike a plain modulo, tiny negative inputs never round up to exactly 2pi. */
	public static float wrapAngle(float angle) {
		float wrapped = mod(angle, TWO_PI);
		return wrapped >= TWO_PI || wrapped < 0 ? 0 : wrapped;
	}

	/** Interpolate between two angles along the shortest arc. The result is in [0, 2pi). */
	public static float mixAngle(float from, float to, float t) {
		return wrapAngle(from + angleDiff(to, from) * t);
	}

	/** Deterministic gust envelope in [0, 1], driven by real UTC time so it never speeds up under time-lapse. */
	public static float gust(long wallMs, long seed) {
		double t = (wallMs - TIME_ORIGIN_MS) / 1000.0;
		long seedA = murmurHash3(seed ^ 0x6057L);
		long seedB = seedA ^ 0x6A09E667F3BCC909L;
		return saturate((float) (.65 * valueNoise(t / GUST_PERIOD_A, seedA) + .35 * valueNoise(t / GUST_PERIOD_B, seedB)));
	}

	private static double valueNoise(double x, long seed) {
		double floor = Math.floor(x);
		long i = (long) floor;
		double f = x - floor;
		double a = knot(i, seed);
		double b = knot(i + 1, seed);
		double fade = f * f * f * (f * (f * 6 - 15) + 10);
		return a + (b - a) * fade;
	}

	private static double knot(long i, long seed) {
		return (murmurHash3(seed ^ (i * 0x9E3779B97F4A7C15L)) >>> 11) * 0x1.0p-53;
	}

	/**
	 * Ease the baseline toward a target.
	 *
	 * @param targetKey    encodes the discrete inputs of the target (e.g. exposure and authoring flags); a change
	 *                     without an environment transition starts a timed ramp
	 * @param transitionId the environment's transition counter; a change starts a lockstep ramp
	 * @param progress     the environment's transition progress
	 */
	public void updateBaseline(
		float targetAngle,
		float targetSpeed,
		float targetStrength,
		int targetKey,
		long transitionId,
		float progress,
		double nowSeconds,
		boolean snap
	) {
		if (snap || !initialized) {
			baseAngle = wrapAngle(targetAngle);
			baseSpeed = targetSpeed;
			baseStrength = targetStrength;
			mode = MODE_NONE;
			environmentId = transitionId;
			this.targetKey = targetKey;
			initialized = true;
			return;
		}

		if (transitionId != environmentId) {
			environmentId = transitionId;
			this.targetKey = targetKey;
			snapshotFrom();
			mode = MODE_LOCKSTEP;
		} else if (targetKey != this.targetKey) {
			this.targetKey = targetKey;
			snapshotFrom();
			mode = MODE_TIMED;
			rampStart = nowSeconds;
		}

		float t;
		switch (mode) {
			case MODE_LOCKSTEP:
				t = saturate(progress);
				break;
			case MODE_TIMED:
				t = smoothstep(0, 1, (float) ((nowSeconds - rampStart) / EXPOSURE_RAMP_SECONDS));
				break;
			default:
				t = 1;
				break;
		}
		if (t >= 1)
			mode = MODE_NONE;

		baseAngle = mixAngle(fromAngle, targetAngle, t);
		baseSpeed = mix(fromSpeed, targetSpeed, t);
		baseStrength = mix(fromStrength, targetStrength, t);
	}

	private void snapshotFrom() {
		fromAngle = baseAngle;
		fromSpeed = baseSpeed;
		fromStrength = baseStrength;
	}

	public void resetBaseline() {
		initialized = false;
		mode = MODE_NONE;
		environmentId = Long.MIN_VALUE;
		targetKey = Integer.MIN_VALUE;
	}

	/**
	 * Apply weather on top of a baseline. Every weather effect is scaled by exposure, so exposure 0 returns the
	 * baseline unchanged.
	 *
	 * @param params camera-local weather parameters
	 * @param upper  the upper wind, whose direction storms pull the surface wind toward
	 * @param gust   the current gust envelope, 0 to 1
	 */
	public static void modulate(
		WeatherState.SurfaceWind out,
		float baseAngle,
		float baseSpeed,
		float baseStrength,
		float ceiling,
		float exposure,
		float[] params,
		WeatherState.UpperWind upper,
		float gust
	) {
		float e = saturate(exposure);
		float gustiness = params[WeatherParam.GUSTINESS.offset()];
		float g = 2 * gust - 1;
		float strengthGust = 1 + gustiness * GUST_STRENGTH_DEPTH * g;
		float speedGust = 1 + gustiness * GUST_SPEED_DEPTH * g;

		// Veer with the upper wind, then turn toward it. The shortest arc changes side when the upper wind is directly
		// opposite, so over the last stretch before that point the alignment fades out instead of flipping the wind
		// around in a single frame.
		float angle = baseAngle + e * VEER_FRACTION * upper.deviation;
		float toUpper = angleDiff(upper.angle, angle);
		float align = e * params[WeatherParam.WIND_ALIGN.offset()] * smoothstep(PI, PI * WIND_ALIGN_FADE_START, abs(toUpper));
		angle = wrapAngle(angle + toUpper * align);

		// Never clamp below the baseline itself, so zero exposure always reproduces it exactly
		float strength = baseStrength * params[WeatherParam.WIND_STRENGTH.offset()] * strengthGust;
		strength = clamp(strength, 0, max(WIND_STRENGTH_MAX, baseStrength));
		float speed = baseSpeed * params[WeatherParam.WIND_SPEED.offset()] * speedGust;
		speed = clamp(speed, 0, max(WIND_SPEED_MAX, baseSpeed));

		out.set(angle, mix(baseSpeed, speed, e), mix(baseStrength, strength, e), ceiling);
		out.gust = gust;
		out.gustiness = gustiness * e;
		out.baseAngle = wrapAngle(baseAngle);
		out.baseSpeed = baseSpeed;
		out.baseStrength = baseStrength;
	}

	/**
	 * Advance the wind offsets by real time. The float offsets are rebased on scene changes once they grow large,
	 * so the resulting jump in the gust pattern is hidden by the scene load, and forcibly before float precision
	 * degrades the animation.
	 */
	public void integrate(WeatherState.SurfaceWind wind, float dt, boolean sceneChanged) {
		double distance = wind.windSpeed * (double) Math.max(0, dt);
		if (Double.isFinite(distance) && Float.isFinite(wind.windDirectionX) && Float.isFinite(wind.windDirectionZ)) {
			wind.windOffsetExact += distance;
			wind.windOffsetExactX += wind.windDirectionX * distance;
			wind.windOffsetExactZ += wind.windDirectionZ * distance;
		}
		// Recover if the offsets ever became non-finite or too large to advance (NaN-safe comparisons)
		if (!(Math.abs(wind.windOffsetExact) < OFFSET_LIMIT) ||
			!(Math.abs(wind.windOffsetExactX) < OFFSET_LIMIT) ||
			!(Math.abs(wind.windOffsetExactZ) < OFFSET_LIMIT))
			resetOffsets(wind);

		double sinceRebase = wind.windOffsetExact - rebase;
		if (sceneChanged && sinceRebase > LEGACY_OFFSET_SOFT_REBASE || sinceRebase > LEGACY_OFFSET_HARD_REBASE) {
			rebase = wind.windOffsetExact;
			rebaseX = wind.windOffsetExactX;
			rebaseZ = wind.windOffsetExactZ;
		}
		wind.windOffset = (float) (wind.windOffsetExact - rebase);
		wind.windOffsetX = (float) (wind.windOffsetExactX - rebaseX);
		wind.windOffsetZ = (float) (wind.windOffsetExactZ - rebaseZ);
	}

	public void resetOffsets(WeatherState.SurfaceWind wind) {
		rebase = rebaseX = rebaseZ = 0;
		wind.windOffsetExact = wind.windOffsetExactX = wind.windOffsetExactZ = 0;
		wind.windOffset = wind.windOffsetX = wind.windOffsetZ = 0;
	}
}
