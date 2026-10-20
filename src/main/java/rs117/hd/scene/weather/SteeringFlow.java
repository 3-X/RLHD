package rs117.hd.scene.weather;

import static rs117.hd.scene.weather.WeatherConstants.*;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.murmurHash3;

/**
 * The upper-level (cloud deck) wind: a mean flow along the timeline's drift direction at
 * {@link WeatherConstants#FRONT_SPEED}, perturbed by a few slow, incommensurate harmonics. It depends
 * only on the weather clock, so it never repeats on a short period.
 */
public final class SteeringFlow {
	private final float baseAngle;
	private final double baseX, baseZ;
	private final double[] omega = new double[STEERING_PERIOD_HOURS.length];
	private final double[] phase0 = new double[STEERING_PERIOD_HOURS.length];
	private final double[] scratch = new double[2];

	public SteeringFlow(long timelineSeed, float driftAngle) {
		baseAngle = driftAngle;
		baseX = FRONT_SPEED * Math.cos(driftAngle);
		baseZ = FRONT_SPEED * Math.sin(driftAngle);
		for (int k = 0; k < omega.length; k++) {
			omega[k] = STEERING_SIGN[k] * 2 * Math.PI / (STEERING_PERIOD_HOURS[k] * 3600);
			long h = murmurHash3(timelineSeed ^ (0x57EE7L + k));
			phase0[k] = 2 * Math.PI * ((h >>> 11) * 0x1.0p-53);
		}
	}

	/** Steering velocity in local units per second at the given number of seconds since {@link WeatherConstants#TIME_ORIGIN_MS}. */
	public void velocity(double seconds, double[] out) {
		double x = baseX, z = baseZ;
		for (int k = 0; k < omega.length; k++) {
			double phi = omega[k] * seconds + phase0[k];
			x += STEERING_AMPLITUDE[k] * Math.cos(phi);
			z += STEERING_AMPLITUDE[k] * Math.sin(phi);
		}
		out[0] = x;
		out[1] = z;
	}

	/** Fill the direction and speed of the upper wind at the given weather clock time. Offsets are left alone. */
	public void evaluate(long clockMs, WeatherState.UpperWind out) {
		double[] v = scratch;
		velocity((clockMs - TIME_ORIGIN_MS) / 1000.0, v);
		double speed = Math.hypot(v[0], v[1]);
		out.speed = (float) speed;
		out.angle = WindModel.wrapAngle((float) Math.atan2(v[1], v[0]));
		out.directionX = (float) (v[0] / speed);
		out.directionZ = (float) (v[1] / speed);
		out.deviation = angleDiff(out.angle, baseAngle);
	}
}
