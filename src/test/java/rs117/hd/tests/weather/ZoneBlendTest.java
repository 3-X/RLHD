package rs117.hd.tests.weather;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.weather.WeatherConstants;
import rs117.hd.scene.weather.WeatherZone;
import rs117.hd.scene.weather.ZoneBlend;

import static rs117.hd.scene.weather.WeatherConstants.MAX_ZONES;

/**
 * The linear zone cross-fade: convergence within the blend time from any state, continuity when the target
 * reverses, normalisation, snapping, capacity, and robustness against zero or non-finite time steps.
 */
public class ZoneBlendTest {
	private static final float T = WeatherConstants.CLIMATE_BLEND_SECONDS;
	private static final float DT = 1 / 60f;
	private static final int FRAMES_PER_BLEND = Math.round(T / DT);

	private static WeatherZone[] zones;

	@BeforeClass
	public static void createZones() throws IOException {
		var data = WeatherTestData.shipped();
		var list = new ArrayList<WeatherZone>();
		for (var climate : data.climates.values())
			list.add(new WeatherZone(climate, SeasonalTheme.SUMMER));
		zones = list.toArray(new WeatherZone[0]);
		Assert.assertTrue("need more distinct zones than the blend can hold", zones.length > MAX_ZONES + 1);
	}

	private static ZoneBlend settledOn(WeatherZone zone) {
		var blend = new ZoneBlend();
		blend.setTarget(zone);
		blend.snap();
		return blend;
	}

	private static void steps(ZoneBlend blend, int frames) {
		for (int i = 0; i < frames; i++)
			blend.step(DT, T);
	}

	private static float sum(ZoneBlend blend) {
		float sum = 0;
		for (int i = 0; i < blend.count; i++)
			sum += blend.weights[i];
		return sum;
	}

	private static int indexOf(ZoneBlend blend, WeatherZone zone) {
		for (int i = 0; i < blend.count; i++)
			if (blend.zones[i] == zone)
				return i;
		return -1;
	}

	/** Structural invariants that hold after every operation. */
	private static void assertConsistent(ZoneBlend blend) {
		Assert.assertTrue("count " + blend.count, blend.count >= 0 && blend.count <= MAX_ZONES);
		for (int i = 0; i < blend.count; i++) {
			float w = blend.weights[i];
			Assert.assertNotNull("zone " + i, blend.zones[i]);
			Assert.assertTrue("weight " + i + " = " + w, w >= 0 && w <= 1);
			for (int j = 0; j < i; j++)
				Assert.assertNotSame("duplicate zone", blend.zones[i], blend.zones[j]);
		}
		for (int i = blend.count; i < MAX_ZONES; i++) {
			Assert.assertNull("stale zone beyond count at " + i, blend.zones[i]);
			Assert.assertEquals("stale weight beyond count at " + i, 0, blend.weights[i], 0);
		}
		if (blend.target != null)
			Assert.assertTrue("the target must always be present", indexOf(blend, blend.target) >= 0);
	}

	private static void assertSettledOn(ZoneBlend blend, WeatherZone zone) {
		assertConsistent(blend);
		Assert.assertSame(zone, blend.target);
		Assert.assertEquals("count", 1, blend.count);
		Assert.assertSame(zone, blend.zones[0]);
		Assert.assertEquals(1, blend.weights[0], 0);
		Assert.assertTrue(blend.isSettled());
		Assert.assertSame(zone, blend.dominant());
	}

	@Test
	public void rampConvergesWithinBlendTime() {
		WeatherZone a = zones[0], b = zones[1];
		var blend = settledOn(a);
		blend.setTarget(b);
		Assert.assertEquals(2, blend.count);
		Assert.assertEquals(0, blend.weightOf(b), 0);
		Assert.assertFalse(blend.isSettled());

		for (int frame = 1; frame <= FRAMES_PER_BLEND; frame++) {
			blend.step(DT, T);
			assertConsistent(blend);
			Assert.assertEquals(1, sum(blend), 1e-6);
			if (frame == FRAMES_PER_BLEND / 2) {
				float w = blend.weightOf(b);
				Assert.assertTrue("weight of the target halfway through: " + w, w >= .48f && w <= .52f);
				Assert.assertEquals(1 - w, blend.weightOf(a), 1e-6);
			}
		}
		Assert.assertEquals("A must be pruned once its weight reaches zero", -1, indexOf(blend, a));
		assertSettledOn(blend, b);
	}

	@Test
	public void reversingMidRampChangesWeightsContinuously() {
		WeatherZone a = zones[0], b = zones[1];
		var blend = settledOn(a);
		blend.setTarget(b);
		float maxStep = 2 * DT / T + 1e-6f;
		float previousA = 1, previousB = 0, peakB = 0;
		int reverseFrame = 10 * 60;
		for (int frame = 1; frame <= reverseFrame + FRAMES_PER_BLEND; frame++) {
			if (frame == reverseFrame) {
				blend.setTarget(a);
				Assert.assertEquals("reversing must not add or drop zones", 2, blend.count);
			}
			blend.step(DT, T);
			assertConsistent(blend);
			float wa = blend.weightOf(a), wb = blend.weightOf(b);
			Assert.assertEquals(1, wa + wb, 1e-6);
			Assert.assertTrue("A jumped by " + Math.abs(wa - previousA) + " at frame " + frame, Math.abs(wa - previousA) <= maxStep);
			Assert.assertTrue("B jumped by " + Math.abs(wb - previousB) + " at frame " + frame, Math.abs(wb - previousB) <= maxStep);
			if (frame < reverseFrame) {
				Assert.assertTrue("B rises before the reversal", wb > previousB);
			} else if (wb > 0) {
				Assert.assertTrue("B falls after the reversal", wb < previousB);
			}
			peakB = Math.max(peakB, wb);
			previousA = wa;
			previousB = wb;
		}
		Assert.assertEquals("B peaks at the reversal, 10 s into a 30 s ramp", 1 / 3f, peakB, .01f);
		assertSettledOn(blend, a);
	}

	@Test
	public void weightsAlwaysSumToOneWithThreeZones() {
		WeatherZone[] three = { zones[0], zones[1], zones[2] };
		var rng = new Random(0x3B1E);
		var blend = settledOn(three[0]);
		int threeWayFrames = 0;
		for (int frame = 0; frame < 20_000; frame++) {
			if (rng.nextInt(120) == 0) {
				blend.setTarget(three[rng.nextInt(3)]);
				assertConsistent(blend);
				Assert.assertEquals("setTarget must not change the weights", 1, sum(blend), 1e-6);
			}
			float dt = rng.nextInt(50) == 0 ? 0 : rng.nextFloat() * .1f;
			blend.step(dt, T);
			assertConsistent(blend);
			Assert.assertTrue(blend.count <= 3);
			Assert.assertEquals("sum of weights at frame " + frame, 1, sum(blend), 1e-6);
			if (blend.count == 3)
				threeWayFrames++;
		}
		Assert.assertTrue("the test should exercise three-way blends", threeWayFrames > 1000);
	}

	@Test
	public void snapReplacesAllWeights() {
		WeatherZone a = zones[0], b = zones[1], c = zones[2];
		var blend = settledOn(a);
		blend.setTarget(b);
		steps(blend, 300);
		blend.setTarget(c);
		steps(blend, 120);
		Assert.assertEquals(3, blend.count);

		blend.snap();
		assertSettledOn(blend, c);
		Assert.assertEquals(0, blend.weightOf(a), 0);
		Assert.assertEquals(0, blend.weightOf(b), 0);

		// A snap without a target empties the blend
		blend.clear();
		Assert.assertNull(blend.target);
		blend.snap();
		Assert.assertEquals(0, blend.count);
		Assert.assertNull(blend.dominant());
		assertConsistent(blend);
	}

	@Test
	public void capacityDropsTheLowestWeightZone() {
		var blend = new ZoneBlend();
		float[] weights = { .3f, .2f, .15f, .01f, .12f, .1f, .07f, .05f };
		for (int i = 0; i < MAX_ZONES; i++) {
			blend.zones[i] = zones[i];
			blend.weights[i] = weights[i];
		}
		blend.count = MAX_ZONES;
		blend.target = zones[0];

		// Re-targeting a zone that's already present never drops anything
		blend.setTarget(zones[5]);
		Assert.assertEquals(MAX_ZONES, blend.count);
		for (int i = 0; i < MAX_ZONES; i++)
			Assert.assertSame(zones[i], blend.zones[i]);

		// A new target replaces the zone contributing the least, keeping the others' order and weights
		WeatherZone incoming = zones[MAX_ZONES];
		blend.setTarget(incoming);
		assertConsistent(blend);
		Assert.assertEquals(MAX_ZONES, blend.count);
		Assert.assertEquals("the lowest-weight zone is dropped", -1, indexOf(blend, zones[3]));
		int[] expectedOrder = { 0, 1, 2, 4, 5, 6, 7 };
		for (int i = 0; i < expectedOrder.length; i++) {
			Assert.assertSame(zones[expectedOrder[i]], blend.zones[i]);
			Assert.assertEquals(weights[expectedOrder[i]], blend.weights[i], 0);
		}
		Assert.assertSame(incoming, blend.zones[MAX_ZONES - 1]);
		Assert.assertEquals(0, blend.weights[MAX_ZONES - 1], 0);

		blend.step(DT, T);
		assertConsistent(blend);
		Assert.assertEquals(1, sum(blend), 1e-6);
		Assert.assertTrue(blend.weightOf(incoming) > 0);
		steps(blend, FRAMES_PER_BLEND);
		assertSettledOn(blend, incoming);
	}

	@Test
	public void capacityAfterRealRampsDropsTheLowestNonTargetZone() {
		var blend = settledOn(zones[0]);
		// Each target ramps for less time than all the later ones together, so nothing reaches zero and gets pruned
		int[] frames = { 700, 340, 165, 80, 38, 18, 8 };
		for (int k = 1; k < MAX_ZONES; k++) {
			blend.setTarget(zones[k]);
			steps(blend, frames[k - 1]);
			assertConsistent(blend);
		}
		Assert.assertEquals(MAX_ZONES, blend.count);
		Assert.assertEquals(1, sum(blend), 1e-6);

		int lowest = 0;
		for (int i = 1; i < blend.count; i++)
			if (blend.weights[i] < blend.weights[lowest])
				lowest = i;
		WeatherZone dropped = blend.zones[lowest];
		var kept = new ArrayList<WeatherZone>();
		var keptWeights = new ArrayList<Float>();
		for (int i = 0; i < blend.count; i++) {
			if (i != lowest) {
				kept.add(blend.zones[i]);
				keptWeights.add(blend.weights[i]);
			}
		}

		WeatherZone incoming = zones[MAX_ZONES];
		blend.setTarget(incoming);
		assertConsistent(blend);
		Assert.assertEquals(MAX_ZONES, blend.count);
		Assert.assertEquals(-1, indexOf(blend, dropped));
		for (int i = 0; i < kept.size(); i++) {
			Assert.assertSame(kept.get(i), blend.zones[i]);
			Assert.assertEquals(keptWeights.get(i), blend.weights[i], 0);
		}
		Assert.assertEquals(0, blend.weightOf(incoming), 0);

		for (int frame = 1; frame <= FRAMES_PER_BLEND; frame++) {
			blend.step(DT, T);
			assertConsistent(blend);
			Assert.assertEquals(1, sum(blend), 1e-6);
		}
		assertSettledOn(blend, incoming);
	}

	/** Regression: a dt == 0 frame on a climate change used to prune the new target, leaving the blend stuck. */
	@Test
	public void zeroTimeStepNeverPrunesTheNewTarget() {
		WeatherZone a = zones[0], b = zones[1];
		var blend = settledOn(a);
		blend.setTarget(b);
		blend.step(0, T);
		assertConsistent(blend);
		Assert.assertEquals(2, blend.count);
		Assert.assertTrue("the target must survive a zero time step", indexOf(blend, b) >= 0);
		Assert.assertEquals(0, blend.weightOf(b), 0);
		Assert.assertEquals(1, blend.weightOf(a), 0);
		Assert.assertSame("the old zone still dominates", a, blend.dominant());

		// A negative time step (e.g. the system clock slewing back) counts as no time passing
		blend.step(-.5f, T);
		assertConsistent(blend);
		Assert.assertEquals(0, blend.weightOf(b), 0);
		Assert.assertEquals(1, blend.weightOf(a), 0);

		// Normal frames then converge on the target within the blend time
		for (int frame = 1; frame <= FRAMES_PER_BLEND; frame++) {
			blend.step(DT, T);
			assertConsistent(blend);
			Assert.assertEquals(1, sum(blend), 1e-6);
		}
		assertSettledOn(blend, b);
	}

	@Test
	public void nonFiniteOrInvalidTimeStepsNeverProduceNaNWeights() {
		float[] badDts = { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1, Float.MAX_VALUE, Float.MIN_VALUE };
		for (float dt : badDts)
			checkBadStep(dt, T, "dt = " + dt);
		float[] badBlendTimes = { Float.NaN, 0, -5, Float.POSITIVE_INFINITY, Float.MIN_VALUE };
		for (float seconds : badBlendTimes)
			checkBadStep(DT, seconds, "blend time = " + seconds);
	}

	private static void checkBadStep(float dt, float seconds, String what) {
		WeatherZone a = zones[0], b = zones[1], c = zones[2];
		var blend = settledOn(a);
		blend.setTarget(b);
		steps(blend, 300);
		blend.setTarget(c);
		steps(blend, 60);

		blend.step(dt, seconds);
		for (int i = 0; i < blend.count; i++)
			Assert.assertFalse(what + ": weight " + i + " is NaN", Float.isNaN(blend.weights[i]));
		assertConsistent(blend);
		Assert.assertEquals(what + ": sum", 1, sum(blend), 1e-6);
		Assert.assertSame(c, blend.target);

		// The blend keeps working normally afterwards
		steps(blend, FRAMES_PER_BLEND);
		assertSettledOn(blend, c);
	}

	@Test
	public void convergesWithinBlendTimeFromAnyState() {
		var rng = new Random(0xB1E4D);
		for (int trial = 0; trial < 300; trial++) {
			var blend = new ZoneBlend();
			int count = 1 + rng.nextInt(MAX_ZONES);
			float total = 0;
			for (int i = 0; i < count; i++) {
				blend.zones[i] = zones[i];
				blend.weights[i] = rng.nextInt(4) == 0 ? 0 : rng.nextFloat();
				total += blend.weights[i];
			}
			if (total <= 0) {
				blend.weights[0] = 1;
				total = 1;
			}
			for (int i = 0; i < count; i++)
				blend.weights[i] /= total;
			blend.count = count;
			blend.target = blend.zones[0];

			// Either an existing zone, possibly with zero weight, or a new one
			WeatherZone target = zones[rng.nextInt(count + 2)];
			blend.setTarget(target);
			for (int frame = 1; frame <= FRAMES_PER_BLEND; frame++) {
				blend.step(DT, T);
				assertConsistent(blend);
				Assert.assertEquals("trial " + trial + ", frame " + frame, 1, sum(blend), 1e-6);
			}
			assertSettledOn(blend, target);
		}
	}

	@Test
	public void dominantTiesGoToTheTarget() {
		WeatherZone a = zones[0], b = zones[1];
		var blend = new ZoneBlend();
		blend.zones[0] = a;
		blend.zones[1] = b;
		blend.weights[0] = .5f;
		blend.weights[1] = .5f;
		blend.count = 2;
		blend.target = b;
		Assert.assertSame(b, blend.dominant());
		blend.target = a;
		Assert.assertSame(a, blend.dominant());
		Assert.assertEquals(.5f, blend.weightOf(b), 0);
		Assert.assertEquals(0, blend.weightOf(zones[2]), 0);
	}

	@Test
	public void steppingWithoutATargetClears() {
		var blend = settledOn(zones[0]);
		blend.setTarget(zones[1]);
		steps(blend, 100);
		blend.setTarget(null);
		blend.step(DT, T);
		Assert.assertEquals(0, blend.count);
		Assert.assertNull(blend.target);
		Assert.assertNull(blend.dominant());
		assertConsistent(blend);
	}
}
