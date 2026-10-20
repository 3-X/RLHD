package rs117.hd.tests.weather;

import java.util.Random;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.scene.weather.Ramp;

/**
 * The exposure ramp: snaps, lockstep with the environment's transition progress, timed smoothstep ramps, and
 * continuity whenever the target changes mid-ramp.
 */
public class RampTest {
	private static final float RAMP_SECONDS = 3;
	private static final double FRAME = 1 / 60.0;
	/** Steepest slope of smoothstep(0, 1, x), per unit x. */
	private static final double SMOOTHSTEP_MAX_SLOPE = 1.5;

	private static double smoothstep(double x) {
		double t = Math.max(0, Math.min(1, x));
		return t * t * (3 - 2 * t);
	}

	private static Ramp settledAt(float value, long transitionId, double now) {
		var ramp = new Ramp();
		ramp.update(value, transitionId, 1, now, RAMP_SECONDS, true);
		return ramp;
	}

	@Test
	public void snapJumpsStraightToTheTarget() {
		var ramp = new Ramp();
		ramp.update(.75f, 1, 0, 10, RAMP_SECONDS, true);
		Assert.assertEquals(.75f, ramp.value, 0);
		Assert.assertEquals(.75f, ramp.from, 0);
		Assert.assertEquals(.75f, ramp.to, 0);
		Assert.assertFalse(ramp.lockstep);

		// Holding the same target within the same environment transition keeps the value, whatever the progress says
		for (int i = 1; i <= 300; i++) {
			ramp.update(.75f, 1, i / 300f, 10 + i * FRAME, RAMP_SECONDS, false);
			Assert.assertEquals(.75f, ramp.value, 1e-7);
			Assert.assertFalse(ramp.lockstep);
		}

		// A snap in the middle of a timed ramp jumps too, and doesn't resume the old ramp
		ramp.update(0, 1, 1, 20, RAMP_SECONDS, false);
		ramp.update(0, 1, 1, 21.5, RAMP_SECONDS, false);
		Assert.assertEquals(.375f, ramp.value, 1e-6);
		ramp.update(1, 2, .2f, 21.5 + FRAME, RAMP_SECONDS, true);
		Assert.assertEquals(1, ramp.value, 0);
		Assert.assertFalse(ramp.lockstep);

		// The snap adopts the new transition id, so later frames of that environment transition don't restart a ramp
		for (int i = 2; i < 200; i++) {
			ramp.update(1, 2, Math.min(1, .2f + i / 100f), 21.5 + i * FRAME, RAMP_SECONDS, false);
			Assert.assertEquals(1, ramp.value, 1e-6);
			Assert.assertFalse(ramp.lockstep);
		}
	}

	@Test
	public void lockstepFollowsEnvironmentProgress() {
		var ramp = settledAt(0, 1, 0);

		// A new environment transition: follow its progress exactly, whatever the clock says
		for (int i = 0; i <= 100; i++) {
			float progress = i / 100f;
			double now = 1000 + 37 * Math.sin(i);
			ramp.update(1, 2, progress, now, RAMP_SECONDS, false);
			Assert.assertTrue("lockstep at progress " + progress, ramp.lockstep);
			Assert.assertEquals("value at progress " + progress, progress, ramp.value, 1e-6);
		}

		// Progress outside [0, 1] is clamped
		ramp.update(1, 2, 1.5f, 0, RAMP_SECONDS, false);
		Assert.assertEquals(1, ramp.value, 0);

		// The next environment transition starts from the current value, here going the other way
		ramp.update(.2f, 3, 0, 5, RAMP_SECONDS, false);
		Assert.assertTrue(ramp.lockstep);
		Assert.assertEquals(1, ramp.value, 1e-6);
		ramp.update(.2f, 3, .25f, 5, RAMP_SECONDS, false);
		Assert.assertEquals(.8f, ramp.value, 1e-6);
		ramp.update(.2f, 3, .5f, 500, RAMP_SECONDS, false);
		Assert.assertEquals(.6f, ramp.value, 1e-6);
		ramp.update(.2f, 3, -1, 500, RAMP_SECONDS, false);
		Assert.assertEquals(1, ramp.value, 1e-6);
		ramp.update(.2f, 3, 1, 500, RAMP_SECONDS, false);
		Assert.assertEquals(.2f, ramp.value, 1e-6);

		// Once the environment transition has finished, the value stays put
		for (int i = 1; i < 100; i++) {
			ramp.update(.2f, 3, 1, 500 + i * FRAME, RAMP_SECONDS, false);
			Assert.assertEquals(.2f, ramp.value, 1e-6);
		}
	}

	@Test
	public void lockstepStartsFromTheCurrentValueMidTimedRamp() {
		var ramp = settledAt(0, 1, 0);
		ramp.update(1, 1, 1, 0, RAMP_SECONDS, false);
		ramp.update(1, 1, 1, RAMP_SECONDS / 2.0, RAMP_SECONDS, false);
		Assert.assertEquals(.5f, ramp.value, 1e-6);

		// An environment transition begins halfway through the timed ramp: the value carries on from 0.5
		ramp.update(0, 2, 0, RAMP_SECONDS / 2.0 + FRAME, RAMP_SECONDS, false);
		Assert.assertTrue(ramp.lockstep);
		Assert.assertEquals(.5f, ramp.value, 1e-6);
		ramp.update(0, 2, .5f, RAMP_SECONDS / 2.0 + 2 * FRAME, RAMP_SECONDS, false);
		Assert.assertEquals(.25f, ramp.value, 1e-6);
		ramp.update(0, 2, 1, RAMP_SECONDS / 2.0 + 3 * FRAME, RAMP_SECONDS, false);
		Assert.assertEquals(0, ramp.value, 1e-6);
	}

	@Test
	public void timedRampUsesSmoothstepOverRampSeconds() {
		checkTimedRamp(0, 1, RAMP_SECONDS);
		checkTimedRamp(1, .2f, RAMP_SECONDS);
		checkTimedRamp(.3f, .9f, .5f);

		// Spot values of smoothstep over a 3 s ramp
		var ramp = settledAt(0, 7, 50);
		ramp.update(1, 7, 1, 50, RAMP_SECONDS, false);
		ramp.update(1, 7, 1, 50.75, RAMP_SECONDS, false);
		Assert.assertEquals(.15625f, ramp.value, 1e-6);
		ramp.update(1, 7, 1, 51.5, RAMP_SECONDS, false);
		Assert.assertEquals(.5f, ramp.value, 1e-6);
		ramp.update(1, 7, 1, 52.25, RAMP_SECONDS, false);
		Assert.assertEquals(.84375f, ramp.value, 1e-6);
	}

	private static void checkTimedRamp(float from, float to, float rampSeconds) {
		double start = 50;
		var ramp = settledAt(from, 7, start - 10);
		var rng = new Random(Float.floatToIntBits(from) * 31L + Float.floatToIntBits(to));
		int frames = (int) Math.ceil((rampSeconds + 1) / FRAME);
		for (int i = 0; i <= frames; i++) {
			double now = start + i * FRAME;
			// The target changes without an environment transition, so the progress must be ignored
			ramp.update(to, 7, rng.nextFloat(), now, rampSeconds, false);
			Assert.assertFalse(ramp.lockstep);
			double expected = from + (to - from) * smoothstep((now - start) / rampSeconds);
			Assert.assertEquals("value " + (now - start) + " s into a " + rampSeconds + " s ramp", expected, ramp.value, 1e-6);
		}
		Assert.assertEquals(to, ramp.value, 0);
	}

	@Test
	public void retargetingMidRampIsContinuous() {
		// Timed ramp from 0 toward 1, retargeted to 0 halfway
		var ramp = settledAt(0, 1, 0);
		ramp.update(1, 1, 1, 0, RAMP_SECONDS, false);
		ramp.update(1, 1, 1, 1.5, RAMP_SECONDS, false);
		Assert.assertEquals(.5f, ramp.value, 1e-6);
		ramp.update(0, 1, 1, 1.5, RAMP_SECONDS, false);
		Assert.assertEquals("no jump when retargeting", .5f, ramp.value, 1e-6);
		Assert.assertEquals(.5f, ramp.from, 1e-6);
		Assert.assertEquals(0, ramp.to, 0);
		for (int i = 0; i <= 240; i++) {
			double elapsed = i * FRAME;
			ramp.update(0, 1, 1, 1.5 + elapsed, RAMP_SECONDS, false);
			Assert.assertEquals(.5 * (1 - smoothstep(elapsed / RAMP_SECONDS)), ramp.value, 1e-6);
		}

		// Lockstep ramp retargeted halfway through the environment transition: continues from where it is, timed
		ramp = settledAt(0, 1, 0);
		ramp.update(1, 2, 0, 10, RAMP_SECONDS, false);
		ramp.update(1, 2, .5f, 10.5, RAMP_SECONDS, false);
		Assert.assertEquals(.5f, ramp.value, 1e-6);
		ramp.update(.1f, 2, .51f, 10.5 + FRAME, RAMP_SECONDS, false);
		Assert.assertFalse(ramp.lockstep);
		Assert.assertEquals("no jump when retargeting a lockstep ramp", .5f, ramp.value, 1e-6);
		ramp.update(.1f, 2, .52f, 10.5 + FRAME + RAMP_SECONDS, RAMP_SECONDS, false);
		Assert.assertEquals(.1f, ramp.value, 1e-6);
	}

	@Test
	public void valueNeverJumpsUnderRandomRetargets() {
		var rng = new Random(0x2A3B);
		var ramp = settledAt(0, 1, 0);
		long transitionId = 1;
		float target = 0, progress = 1;
		double now = 0;
		// A timed ramp moves at most smoothstep's steepest slope per ramp duration, and lockstep at most the progress step
		double maxStep = Math.max(SMOOTHSTEP_MAX_SLOPE / RAMP_SECONDS, 1 / RAMP_SECONDS) * FRAME + 1e-6;
		double largest = 0;
		int retargets = 0, transitions = 0;
		for (int frame = 0; frame < 60 * 600; frame++) {
			now += FRAME;
			if (rng.nextInt(90) == 0) {
				target = rng.nextFloat();
				retargets++;
			}
			if (rng.nextInt(400) == 0) {
				transitionId++;
				progress = 0;
				transitions++;
			}
			// The environment transition progresses linearly over the same duration
			progress = (float) Math.min(1, progress + FRAME / RAMP_SECONDS);
			float before = ramp.value;
			ramp.update(target, transitionId, progress, now, RAMP_SECONDS, false);
			double step = Math.abs(ramp.value - before);
			largest = Math.max(largest, step);
			Assert.assertTrue("jump of " + step + " at frame " + frame, step <= maxStep);
		}
		Assert.assertTrue(retargets > 100 && transitions > 30);
		Assert.assertTrue("the ramp should actually move", largest > maxStep / 4);

		// Left alone, the ramp settles exactly on the target
		for (int i = 0; i < 4 / FRAME; i++) {
			now += FRAME;
			progress = (float) Math.min(1, progress + FRAME / RAMP_SECONDS);
			ramp.update(target, transitionId, progress, now, RAMP_SECONDS, false);
		}
		Assert.assertEquals(target, ramp.value, 1e-6);
	}

	@Test
	public void zeroRampSecondsChangesImmediately() {
		var ramp = settledAt(0, 1, 0);
		ramp.update(1, 1, 1, 5, 0, false);
		Assert.assertEquals(1, ramp.value, 0);
		ramp.update(.25f, 1, 1, 5, 0, false);
		Assert.assertEquals(.25f, ramp.value, 0);
	}

	@Test
	public void resetForgetsTheEnvironmentTransition() {
		var ramp = settledAt(1, 4, 0);
		ramp.reset(.4f);
		Assert.assertEquals(.4f, ramp.value, 0);
		Assert.assertEquals(.4f, ramp.from, 0);
		Assert.assertEquals(.4f, ramp.to, 0);
		Assert.assertFalse(ramp.lockstep);

		// After a reset, even the same transition id counts as new, easing in lockstep from the reset value
		ramp.update(1, 4, 0, 1, RAMP_SECONDS, false);
		Assert.assertTrue(ramp.lockstep);
		Assert.assertEquals(.4f, ramp.value, 1e-6);
		ramp.update(1, 4, .5f, 1, RAMP_SECONDS, false);
		Assert.assertEquals(.7f, ramp.value, 1e-6);
	}
}
