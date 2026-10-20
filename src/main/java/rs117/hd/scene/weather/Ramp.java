package rs117.hd.scene.weather;

import static rs117.hd.utils.MathUtils.*;

/**
 * A scalar that eases toward a target, either in lockstep with the environment's transition progress (so
 * weather fades together with the environment, e.g. at a cave entrance), or over a fixed time when the
 * target changes without an environment transition.
 */
public final class Ramp {
	public float value, from, to;
	public boolean lockstep;
	private long environmentId = Long.MIN_VALUE;
	private double start;

	/**
	 * @param target        the value to ease toward
	 * @param transitionId  the environment's transition counter
	 * @param progress      the environment's transition progress, 0 to 1
	 * @param nowSeconds    a monotonic clock, in seconds
	 * @param rampSeconds   duration of timed ramps
	 * @param snap          whether to jump straight to the target
	 */
	public void update(float target, long transitionId, float progress, double nowSeconds, float rampSeconds, boolean snap) {
		if (snap) {
			value = from = to = target;
			environmentId = transitionId;
			lockstep = false;
			start = nowSeconds;
			return;
		}

		if (transitionId != environmentId) {
			environmentId = transitionId;
			from = value;
			to = target;
			lockstep = true;
		} else if (target != to) {
			from = value;
			to = target;
			lockstep = false;
			start = nowSeconds;
		}

		float t;
		if (lockstep) {
			t = saturate(progress);
		} else {
			t = rampSeconds > 0 ? smoothstep(0, 1, (float) ((nowSeconds - start) / rampSeconds)) : 1;
		}
		value = mix(from, to, t);
	}

	public void reset(float v) {
		value = from = to = v;
		lockstep = false;
		environmentId = Long.MIN_VALUE;
	}
}
