package rs117.hd.tests.weather;

import java.util.Locale;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.scene.weather.SteeringFlow;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.scene.weather.WeatherState;

import static rs117.hd.scene.weather.WeatherConstants.*;
import static rs117.hd.utils.MathUtils.TWO_PI;

/**
 * The upper (cloud deck) wind: bounded speed and deviation around the drift direction, a mean flow along the drift
 * direction, determinism, and mutually consistent outputs.
 */
public class SteeringFlowTest {
	private static final double TAU = 2 * Math.PI;
	private static final double DEG = Math.PI / 180;
	/** 2026-01-01T00:00Z. */
	private static final long YEAR_START = 1_767_225_600_000L;
	private static final long MINUTE_MS = 60_000;
	private static final long DAY_MS = 86_400_000L;
	private static final int YEAR_MINUTES = 365 * 24 * 60;

	private static final double MIN_SPEED = 11;
	private static final double MAX_SPEED = 53;
	private static final double MAX_DEVIATION = 40.5 * DEG;
	private static final double MAX_MEAN_DIRECTION_ERROR = 2 * DEG;

	@Test
	public void shippedFlowIsBoundedOverOneYear() throws Exception {
		WeatherData data = WeatherTestData.shipped();
		assertBoundedOverOneYear("shipped", data.steering, data.timeline.settings.driftAngle);
	}

	@Test
	public void flowIsBoundedOverOneYearForOtherSeedsAndDriftAngles() {
		long[] seeds = { 0, -1, 0x313137204844L, 0x5EED_0001L, Long.MIN_VALUE };
		double[] drifts = { 0, 40, 135, 270, 359 };
		for (int i = 0; i < seeds.length; i++) {
			float drift = (float) (drifts[i] * DEG);
			assertBoundedOverOneYear("seed " + seeds[i] + ", drift " + drifts[i], new SteeringFlow(seeds[i], drift), drift);
		}

		// The bounds hold at any time, not just in 2026: random times within two centuries either side of the origin
		Random rng = new Random(0x117_1001L);
		var upper = new WeatherState.UpperWind();
		for (int i = 0; i < 200_000; i++) {
			long seed = rng.nextLong();
			float drift = rng.nextFloat() * TWO_PI;
			var flow = new SteeringFlow(seed, drift);
			long clockMs = TIME_ORIGIN_MS + (long) ((rng.nextDouble() * 2 - 1) * 200 * 365 * DAY_MS);
			flow.evaluate(clockMs, upper);
			String ctx = "seed " + seed + ", drift " + drift + ", clock " + clockMs;
			Assert.assertTrue(ctx + ": speed " + upper.speed, upper.speed >= MIN_SPEED && upper.speed <= MAX_SPEED);
			Assert.assertTrue(ctx + ": deviation " + Math.toDegrees(upper.deviation),
				Math.abs(upper.deviation) <= MAX_DEVIATION);
			assertConsistent(ctx, upper, drift);
		}
	}

	@Test
	public void sameSeedGivesIdenticalResults() throws Exception {
		WeatherData data = WeatherTestData.shipped();
		long seed = data.timeline.seed();
		float drift = data.timeline.settings.driftAngle;
		var a = data.steering;
		var b = new SteeringFlow(seed, drift);
		var other = new SteeringFlow(seed + 1, drift);

		Random rng = new Random(0x117_1002L);
		int n = 20_000;
		long[] times = new long[n];
		for (int i = 0; i < n; i++) {
			times[i] = i < n / 2
				? YEAR_START + i * 7 * MINUTE_MS
				: YEAR_START + (long) ((rng.nextDouble() * 2 - 1) * 3650 * DAY_MS);
		}

		var upper = new WeatherState.UpperWind();
		int[][] expected = new int[n][];
		double[][] expectedVelocity = new double[n][2];
		for (int i = 0; i < n; i++) {
			a.evaluate(times[i], upper);
			expected[i] = bits(upper);
			a.velocity(seconds(times[i]), expectedVelocity[i]);
		}

		// Evaluated in reverse order on another instance: no state carries over between calls
		double[] velocity = new double[2];
		for (int i = n - 1; i >= 0; i--) {
			b.evaluate(times[i], upper);
			Assert.assertArrayEquals("evaluate at " + times[i], expected[i], bits(upper));
			b.velocity(seconds(times[i]), velocity);
			Assert.assertEquals("velocity X at " + times[i], Double.doubleToLongBits(expectedVelocity[i][0]),
				Double.doubleToLongBits(velocity[0]));
			Assert.assertEquals("velocity Z at " + times[i], Double.doubleToLongBits(expectedVelocity[i][1]),
				Double.doubleToLongBits(velocity[1]));
		}

		// A different seed gives a different flow
		int differing = 0;
		for (int i = 0; i < n; i++) {
			other.evaluate(times[i], upper);
			int[] actual = bits(upper);
			if (actual[0] != expected[i][0] && actual[1] != expected[i][1])
				differing++;
		}
		Assert.assertTrue("a different seed should change the flow, but only " + differing + "/" + n + " samples differ",
			differing > .99 * n);
	}

	@Test
	public void upperWindDefaultsAreTheMeanFlow() {
		var upper = new WeatherState.UpperWind();
		Assert.assertEquals(1, Math.hypot(upper.directionX, upper.directionZ), 1e-6);
		Assert.assertEquals(Math.cos(upper.angle), upper.directionX, 1e-6);
		Assert.assertEquals(FRONT_SPEED, upper.speed, 0);
		Assert.assertEquals(0, upper.deviation, 0);
	}

	@Test
	public void evaluateLeavesTheCloudOffsetsAlone() {
		var flow = new SteeringFlow(0x117L, (float) (40 * DEG));
		var upper = new WeatherState.UpperWind();
		upper.cloudOffsetX = 1234.5;
		upper.cloudOffsetZ = -678.25;
		for (int i = 0; i < 100; i++)
			flow.evaluate(YEAR_START + i * 3_600_000L, upper);
		Assert.assertEquals(1234.5, upper.cloudOffsetX, 0);
		Assert.assertEquals(-678.25, upper.cloudOffsetZ, 0);
	}

	@Test
	public void flowDoesNotRepeatOnShortPeriods() {
		var flow = new SteeringFlow(0x313137204844L, (float) (40 * DEG));
		double[] a = new double[2];
		double[] b = new double[2];
		for (double lagHours : new double[] { 1, 6.1, 12, 14.3, 24, 37.7, 24 * 7 }) {
			double lag = lagHours * 3600;
			double sum = 0;
			int n = 0;
			for (long ms = 0; ms < 365 * DAY_MS; ms += 10 * MINUTE_MS, n++) {
				double t = seconds(YEAR_START + ms);
				flow.velocity(t, a);
				flow.velocity(t + lag, b);
				sum += Math.hypot(b[0] - a[0], b[1] - a[1]);
			}
			double mean = sum / n;
			Assert.assertTrue(String.format(Locale.ROOT, "the flow %.1f h later should differ, mean |dV| = %.3f u/s", lagHours, mean),
				mean > 2);
		}
	}

	private static void assertBoundedOverOneYear(String name, SteeringFlow flow, float drift) {
		var upper = new WeatherState.UpperWind();
		double[] velocity = new double[2];
		double minSpeed = Double.POSITIVE_INFINITY, maxSpeed = Double.NEGATIVE_INFINITY, maxDeviation = 0;
		long minSpeedAt = 0, maxSpeedAt = 0, maxDeviationAt = 0;
		double sumDirectionX = 0, sumDirectionZ = 0, sumVelocityX = 0, sumVelocityZ = 0;
		double worstVelocityError = 0;
		long worstVelocityAt = 0;
		for (int i = 0; i < YEAR_MINUTES; i++) {
			long clockMs = YEAR_START + i * MINUTE_MS;
			flow.evaluate(clockMs, upper);
			if (upper.speed < minSpeed) {
				minSpeed = upper.speed;
				minSpeedAt = clockMs;
			}
			if (upper.speed > maxSpeed) {
				maxSpeed = upper.speed;
				maxSpeedAt = clockMs;
			}
			if (Math.abs(upper.deviation) > maxDeviation) {
				maxDeviation = Math.abs(upper.deviation);
				maxDeviationAt = clockMs;
			}
			if (i % 16 == 0)
				assertConsistent(name + " at " + clockMs, upper, drift);
			sumDirectionX += upper.directionX;
			sumDirectionZ += upper.directionZ;

			flow.velocity(seconds(clockMs), velocity);
			double error = Math.max(
				Math.abs(velocity[0] - upper.speed * (double) upper.directionX),
				Math.abs(velocity[1] - upper.speed * (double) upper.directionZ));
			if (error > worstVelocityError) {
				worstVelocityError = error;
				worstVelocityAt = clockMs;
			}
			sumVelocityX += velocity[0];
			sumVelocityZ += velocity[1];
		}

		Assert.assertTrue(String.format(Locale.ROOT, "%s: minimum speed %.3f u/s at %d is below %.0f", name, minSpeed, minSpeedAt, MIN_SPEED),
			minSpeed >= MIN_SPEED);
		Assert.assertTrue(String.format(Locale.ROOT, "%s: maximum speed %.3f u/s at %d is above %.0f", name, maxSpeed, maxSpeedAt, MAX_SPEED),
			maxSpeed <= MAX_SPEED);
		Assert.assertTrue(String.format(Locale.ROOT, "%s: deviation %.3f deg at %d exceeds 40.5 deg", name, Math.toDegrees(maxDeviation),
			maxDeviationAt), maxDeviation <= MAX_DEVIATION);
		// The harmonics should actually be felt, or the bounds above say nothing
		Assert.assertTrue(name + ": the speed should vary, range " + minSpeed + " - " + maxSpeed, maxSpeed - minSpeed > 20);
		Assert.assertTrue(name + ": the direction should vary, max deviation " + Math.toDegrees(maxDeviation), maxDeviation > 20 * DEG);
		Assert.assertTrue(String.format(Locale.ROOT, "%s: velocity() and evaluate() disagree by %.3g u/s at %d", name, worstVelocityError,
			worstVelocityAt), worstVelocityError < 1e-4);

		double meanDirection = Math.atan2(sumDirectionZ, sumDirectionX);
		double meanVelocityDirection = Math.atan2(sumVelocityZ, sumVelocityX);
		Assert.assertTrue(String.format(Locale.ROOT, "%s: the mean direction %.3f deg should be within 2 deg of the drift %.3f deg", name,
			Math.toDegrees(wrap(meanDirection)), Math.toDegrees(drift)),
			Math.abs(signedDiff(meanDirection, drift)) <= MAX_MEAN_DIRECTION_ERROR);
		Assert.assertTrue(String.format(Locale.ROOT, "%s: the mean velocity direction %.3f deg should be within 2 deg of the drift %.3f deg",
			name, Math.toDegrees(wrap(meanVelocityDirection)), Math.toDegrees(drift)),
			Math.abs(signedDiff(meanVelocityDirection, drift)) <= MAX_MEAN_DIRECTION_ERROR);
		// The mean flow runs at the front speed
		double meanSpeed = Math.hypot(sumVelocityX, sumVelocityZ) / YEAR_MINUTES;
		Assert.assertEquals(name + ": mean velocity magnitude", FRONT_SPEED, meanSpeed, .5);
	}

	/** Angle, direction, speed and deviation describe the same vector. */
	private static void assertConsistent(String ctx, WeatherState.UpperWind upper, float drift) {
		Assert.assertTrue(ctx + ": angle " + upper.angle + " is not normalised to [0, 2pi]", upper.angle >= 0 && upper.angle <= TWO_PI);
		Assert.assertTrue(ctx + ": speed " + upper.speed, upper.speed > 0 && Float.isFinite(upper.speed));
		Assert.assertEquals(ctx + ": directionX", Math.cos(upper.angle), upper.directionX, 1e-5);
		Assert.assertEquals(ctx + ": directionZ", Math.sin(upper.angle), upper.directionZ, 1e-5);
		Assert.assertEquals(ctx + ": |direction|", 1, Math.hypot(upper.directionX, upper.directionZ), 1e-5);
		Assert.assertTrue(ctx + ": deviation " + upper.deviation + " outside [-pi, pi]", Math.abs(upper.deviation) <= Math.PI + 1e-6);
		Assert.assertEquals(ctx + ": deviation", signedDiff(upper.angle, drift), upper.deviation, 1e-5);
		Assert.assertEquals(ctx + ": the angle is the drift plus the deviation", 0,
			signedDiff(upper.angle, drift + (double) upper.deviation), 1e-5);
	}

	private static int[] bits(WeatherState.UpperWind upper) {
		return new int[] {
			Float.floatToRawIntBits(upper.speed),
			Float.floatToRawIntBits(upper.angle),
			Float.floatToRawIntBits(upper.directionX),
			Float.floatToRawIntBits(upper.directionZ),
			Float.floatToRawIntBits(upper.deviation)
		};
	}

	private static double seconds(long clockMs) {
		return (clockMs - TIME_ORIGIN_MS) / 1000.0;
	}

	private static double wrap(double angle) {
		angle %= TAU;
		return angle < 0 ? angle + TAU : angle;
	}

	/** a - b along the shortest arc, in [-pi, pi). */
	private static double signedDiff(double a, double b) {
		double d = wrap(a - b);
		return d >= Math.PI ? d - TAU : d;
	}
}
