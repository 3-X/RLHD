package rs117.hd.tests.weather;

import java.util.Random;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.scene.weather.WeatherClock;

/**
 * The weather clock: wall-clock UTC by default, plus a developer offset and a continuous time-lapse scale.
 */
public class WeatherClockTest {
	private static final long SECOND = 1000;
	private static final long MINUTE = 60 * SECOND;
	private static final long HOUR = 60 * MINUTE;
	private static final long DAY = 24 * HOUR;
	/** An arbitrary wall-clock time (2026-01-01T00:00:12.345Z). */
	private static final long W0 = 1_767_225_612_345L;

	@Test
	public void defaultIsWallClock() {
		var clock = new WeatherClock();
		Assert.assertEquals(1, clock.getScale(), 0);
		for (long wall : new long[] { 0, 1, W0, System.currentTimeMillis(), 4_102_444_800_000L }) {
			Assert.assertEquals(wall, clock.now(wall));
			Assert.assertEquals(0, clock.getEffectiveOffset(wall));
			Assert.assertTrue(clock.isRealTime(wall));
		}
	}

	@Test
	public void scaleChangeIsContinuous() {
		var clock = new WeatherClock();
		long before = clock.now(W0);
		clock.setScale(60, W0);
		Assert.assertEquals(60, clock.getScale(), 0);
		Assert.assertEquals("changing the scale must not jump the clock", before, clock.now(W0));
		Assert.assertEquals(clock.now(W0) + 60_000, clock.now(W0 + SECOND));
		Assert.assertEquals(clock.now(W0) + HOUR, clock.now(W0 + MINUTE));
		Assert.assertFalse(clock.isRealTime(W0 + SECOND));

		// A second change, with an offset in place, is just as continuous
		clock.addOffset(-5 * MINUTE);
		long at = W0 + 10 * SECOND;
		long beforeSecond = clock.now(at);
		Assert.assertEquals(W0 + 600_000 - 5 * MINUTE, beforeSecond);
		clock.setScale(0.5, at);
		Assert.assertEquals(beforeSecond, clock.now(at));
		Assert.assertEquals(beforeSecond + 2 * SECOND, clock.now(at + 4 * SECOND));
	}

	@Test
	public void randomScaleChangesAndOffsetsAreContinuous() {
		var rng = new Random(117);
		var clock = new WeatherClock();
		long wall = W0;
		for (int i = 0; i < 10_000; i++) {
			long before = clock.now(wall);
			boolean anchoredHere = false;
			if (rng.nextInt(4) == 0) {
				long delta = rng.nextInt(2_000_001) - 1_000_000;
				clock.addOffset(delta);
				Assert.assertEquals(before + delta, clock.now(wall));
			} else {
				double scale = rng.nextInt(5) == 0 ? 0 : rng.nextDouble() * 100;
				clock.setScale(scale, wall);
				anchoredHere = true;
				Assert.assertEquals("step " + i + ": setScale(" + scale + ") jumped the clock", before, clock.now(wall));
			}
			long dt = rng.nextInt(100_000);
			long after = clock.now(wall + dt);
			double expectedAdvance = dt * clock.getScale();
			if (anchoredHere) {
				// Re-anchored at this wall time, so the advance is exactly the truncated product
				Assert.assertEquals("step " + i, clock.now(wall) + (long) expectedAdvance, after);
			} else {
				// Anchored earlier: truncation of the two products can differ by a millisecond
				Assert.assertEquals("step " + i, expectedAdvance, after - clock.now(wall), 1);
			}
			Assert.assertTrue("the clock must never run backwards", after >= clock.now(wall));
			wall += dt;
		}
	}

	@Test
	public void offsetApplies() {
		var clock = new WeatherClock();
		clock.addOffset(90 * MINUTE);
		Assert.assertEquals(W0 + 5_400_000, clock.now(W0));
		Assert.assertEquals(5_400_000, clock.getEffectiveOffset(W0));
		Assert.assertEquals(5_400_000, clock.getEffectiveOffset(W0 + DAY));
		Assert.assertFalse(clock.isRealTime(W0));
		Assert.assertEquals("an offset alone doesn't change the rate", 1, clock.getScale(), 0);

		clock.addOffset(-2 * HOUR);
		Assert.assertEquals(W0 - 30 * MINUTE, clock.now(W0));
		clock.addOffset(30 * MINUTE);
		Assert.assertEquals(W0, clock.now(W0));
		Assert.assertTrue("offsets that cancel out restore real time", clock.isRealTime(W0));

		// Under time-lapse, an offset is added on top without changing the rate
		clock.setScale(10, W0);
		clock.addOffset(SECOND);
		Assert.assertEquals(W0 + SECOND + 10 * 500, clock.now(W0 + 500));
	}

	@Test
	public void zeroScalePauses() {
		var clock = new WeatherClock();
		clock.addOffset(7 * SECOND);
		clock.setScale(0, W0);
		Assert.assertEquals(0, clock.getScale(), 0);
		long frozen = clock.now(W0);
		Assert.assertEquals(W0 + 7 * SECOND, frozen);
		Assert.assertEquals(frozen, clock.now(W0 + 1));
		Assert.assertEquals(frozen, clock.now(W0 + 10 * DAY));
		Assert.assertFalse(clock.isRealTime(W0 + SECOND));

		// An offset still applies while paused
		clock.addOffset(MINUTE);
		Assert.assertEquals(frozen + MINUTE, clock.now(W0 + HOUR));

		// Resuming continues from where the clock stopped
		clock.setScale(1, W0 + HOUR);
		Assert.assertEquals(frozen + MINUTE, clock.now(W0 + HOUR));
		Assert.assertEquals(frozen + MINUTE + 5 * SECOND, clock.now(W0 + HOUR + 5 * SECOND));
		Assert.assertEquals(7 * SECOND + MINUTE - HOUR, clock.getEffectiveOffset(W0 + HOUR + 5 * SECOND));
	}

	@Test
	public void effectiveOffsetAfterTimeLapse() {
		// Time-lapse for one second at x60, then back to real-time speed: the clock is now 59 s ahead for good
		var clock = new WeatherClock();
		clock.setScale(60, W0);
		clock.setScale(1, W0 + SECOND);
		Assert.assertEquals(1, clock.getScale(), 0);
		Assert.assertEquals(W0 + 60 * SECOND, clock.now(W0 + SECOND));
		Assert.assertEquals(59 * SECOND, clock.getEffectiveOffset(W0 + SECOND));
		Assert.assertEquals(59 * SECOND, clock.getEffectiveOffset(W0 + SECOND + DAY));
		Assert.assertFalse("scale 1 with an accumulated offset isn't real time", clock.isRealTime(W0 + SECOND));
		Assert.assertFalse(clock.isRealTime(W0 + DAY));

		// Slowing down instead leaves the clock behind
		var slow = new WeatherClock();
		slow.setScale(0.25, W0);
		slow.setScale(1, W0 + 4 * SECOND);
		Assert.assertEquals(-3 * SECOND, slow.getEffectiveOffset(W0 + HOUR));
		Assert.assertFalse(slow.isRealTime(W0 + HOUR));
	}

	@Test
	public void changingScaleWithoutElapsedTimeStaysRealTime() {
		var clock = new WeatherClock();
		clock.setScale(1, W0);
		Assert.assertTrue(clock.isRealTime(W0));
		Assert.assertTrue(clock.isRealTime(W0 + DAY));
		clock.setScale(60, W0 + DAY);
		clock.setScale(1, W0 + DAY);
		Assert.assertTrue(clock.isRealTime(W0 + 2 * DAY));
		Assert.assertEquals(W0 + 3 * DAY, clock.now(W0 + 3 * DAY));
	}

	@Test
	public void nonFiniteScaleThrows() {
		for (double bad : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
			var clock = new WeatherClock();
			clock.addOffset(1234);
			clock.setScale(2, W0);
			long before = clock.now(W0 + 10 * SECOND);
			try {
				clock.setScale(bad, W0 + 5 * SECOND);
				Assert.fail("setScale(" + bad + ") should throw");
			} catch (IllegalArgumentException expected) {
				// The clock keeps running as before
			}
			Assert.assertEquals(2, clock.getScale(), 0);
			Assert.assertEquals(before, clock.now(W0 + 10 * SECOND));
		}
	}

	@Test
	public void scaleIsClamped() {
		var clock = new WeatherClock();
		clock.setScale(1e9, W0);
		Assert.assertEquals(WeatherClock.MAX_SCALE, clock.getScale(), 0);
		Assert.assertEquals(clock.now(W0) + (long) (WeatherClock.MAX_SCALE * SECOND), clock.now(W0 + SECOND));

		clock.setScale(-5, W0 + SECOND);
		Assert.assertEquals("a negative scale pauses rather than running backwards", 0, clock.getScale(), 0);
		Assert.assertEquals(clock.now(W0 + SECOND), clock.now(W0 + HOUR));
	}

	@Test
	public void resetRestoresRealTime() {
		var clock = new WeatherClock();
		clock.setScale(60, W0);
		clock.addOffset(-DAY);
		clock.setScale(0, W0 + MINUTE);
		Assert.assertFalse(clock.isRealTime(W0 + HOUR));

		clock.reset();
		Assert.assertEquals(1, clock.getScale(), 0);
		for (long wall : new long[] { 0, W0, W0 + HOUR, System.currentTimeMillis() }) {
			Assert.assertEquals(wall, clock.now(wall));
			Assert.assertEquals(0, clock.getEffectiveOffset(wall));
			Assert.assertTrue(clock.isRealTime(wall));
		}
	}
}
