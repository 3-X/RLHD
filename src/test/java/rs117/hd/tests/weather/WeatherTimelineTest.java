package rs117.hd.tests.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.weather.ClimateLadder;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.scene.weather.WeatherTimeline;
import rs117.hd.scene.weather.WeatherTimeline.Segment;
import rs117.hd.scene.weather.WeatherTimeline.Settings;
import rs117.hd.scene.weather.WeatherType;

import static rs117.hd.scene.weather.WeatherConstants.EPOCH_MS;

/**
 * The shared weather timeline: determinism, gap-free tiling, the bounded latent walk (including for randomized
 * settings the loader accepts), random access through the epoch cache, statistics, and the loader's timeline rules.
 * Every check runs over a fixed, epoch-aligned stretch of time, so results never depend on when the tests run.
 */
public class WeatherTimelineTest {
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60 * MINUTE;
	private static final long DAY = EPOCH_MS;
	/** 2026-01-01T00:00Z, an epoch boundary. */
	private static final long START = 1_767_225_600_000L;
	private static final double STEP_TOLERANCE = 1e-12;
	/** One more epoch than the timeline's LRU holds. */
	private static final int EVICTING_EPOCH_JUMP = 7;

	private static WeatherData.Definitions shippedDefs;
	private static Settings shipped;

	@BeforeClass
	public static void loadShipped() throws IOException {
		shippedDefs = WeatherData.parse(WeatherTestData.loadShippedJson());
		shipped = shippedDefs.timeline;
		Assert.assertEquals("START must be an epoch boundary", 0, START % DAY);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Determinism
	// ------------------------------------------------------------------------------------------------------------

	@Test
	public void sameSettingsSameSegments() throws IOException {
		// Two independently loaded settings objects, so nothing can depend on object identity
		var a = new WeatherTimeline(WeatherData.parse(WeatherTestData.loadShippedJson()).timeline);
		var b = new WeatherTimeline(WeatherData.parse(WeatherTestData.loadShippedJson()).timeline);
		Assert.assertEquals(a.seed(), b.seed());
		Assert.assertEquals(a.driftX(), b.driftX(), 0);
		Assert.assertEquals(a.driftZ(), b.driftZ(), 0);

		long step = 7 * MINUTE;
		List<Segment> forward = new ArrayList<>();
		for (long t = START; t < START + 60 * DAY; t += step)
			forward.add(a.segmentAt(t));
		Assert.assertTrue(forward.size() > 12_000);

		// Query the second instance backwards, so its cache fills in a different order
		for (int i = forward.size() - 1; i >= 0; i--) {
			long t = START + i * step;
			Segment expected = forward.get(i);
			Assert.assertTrue("segmentAt must contain the queried phase", expected.start <= t && t < expected.end);
			assertSameSegment("t=" + t, expected, b.segmentAt(t));
		}
	}

	@Test
	public void differentNameDifferentSchedule() throws IOException {
		JsonObject root = WeatherTestData.loadShippedJson();
		root.getAsJsonObject("timeline").addProperty("name", shipped.name + "_ALTERNATE");
		Settings renamed = WeatherData.parse(root).timeline;
		Assert.assertEquals(shipped.volatility, renamed.volatility, 0);
		Assert.assertEquals(shipped.minSegmentMinutes, renamed.minSegmentMinutes, 0);
		Assert.assertEquals(shipped.maxSegmentMinutes, renamed.maxSegmentMinutes, 0);

		var a = new WeatherTimeline(shipped);
		var b = new WeatherTimeline(renamed);
		Assert.assertNotEquals(a.seed(), b.seed());

		int differ = 0, total = 0;
		for (long t = START; t < START + 60 * DAY; t += 7 * MINUTE) {
			Segment x = a.segmentAt(t), y = b.segmentAt(t);
			if (x.start != y.start || x.end != y.end || Math.abs(x.latent - y.latent) > 0.01)
				differ++;
			total++;
		}
		double fraction = differ / (double) total;
		Assert.assertTrue(String.format(Locale.ROOT, "only %.1f%% of samples differ", 100 * fraction), fraction >= 0.9);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Structure and the latent walk
	// ------------------------------------------------------------------------------------------------------------

	@Test
	public void segmentsTileTimeWithoutGaps() {
		var tl = new WeatherTimeline(shipped);
		WalkStats stats = checkWalk("shipped", tl, START, 365);
		Assert.assertEquals("every epoch edge inside the range must be crossed exactly once", 365, stats.epochEdges);
		// Lengths stay within [min, max + min], which is [30, 120] minutes for the shipped settings
		long minMs = (long) (shipped.minSegmentMinutes * 60_000), maxMs = (long) (shipped.maxSegmentMinutes * 60_000);
		Assert.assertTrue("shortest segment " + stats.minDuration, stats.minDuration >= minMs);
		Assert.assertTrue("longest segment " + stats.maxDuration, stats.maxDuration <= maxMs + minMs);
		Assert.assertTrue("lengths should vary between the bounds", stats.maxDuration - stats.minDuration > (maxMs - minMs) / 2);

		// Every multiple of EPOCH_MS is a segment start, also when looked up directly
		var fresh = new WeatherTimeline(shipped);
		for (long e = START / DAY; e < START / DAY + 365; e++) {
			Segment first = fresh.segmentAt(e * DAY);
			Assert.assertEquals(e * DAY, first.start);
			Assert.assertEquals(0, first.index);
			Assert.assertEquals(e, first.epoch);
			Assert.assertEquals(e * DAY, fresh.segmentAt(e * DAY - 1).end);
		}
	}

	@Test
	public void tilingWorksAroundPhaseZero() {
		var tl = new WeatherTimeline(shipped);
		WalkStats stats = checkWalk("around phase 0", tl, -3 * DAY, 6);
		Assert.assertEquals(6, stats.epochEdges);
		Segment zero = tl.segmentAt(0);
		Assert.assertEquals(0, zero.start);
		Assert.assertEquals(0, zero.epoch);
		Segment before = tl.previous(zero);
		Assert.assertEquals(-1, before.epoch);
		Assert.assertEquals(0, before.end);
		assertSameSegment("segmentAt(-1)", before, tl.segmentAt(-1));
		assertSameSegment("next(previous(segmentAt(0)))", zero, tl.next(before));
	}

	@Test
	public void latentStepBounded() {
		var tl = new WeatherTimeline(shipped);
		WalkStats stats = checkWalk("shipped", tl, START, 365);
		Assert.assertEquals(365, stats.epochEdges);
		Assert.assertTrue(stats.boundaries > 365 * 14);
		// The walk really moves: steps close to the volatility happen, both inside epochs and at epoch edges
		Assert.assertTrue("largest step " + stats.maxStep, stats.maxStep > 0.9 * shipped.volatility);
		Assert.assertTrue("largest epoch-edge step " + stats.maxEpochEdgeStep, stats.maxEpochEdgeStep > 0.5 * shipped.volatility);
	}

	@Test
	public void latentInUnitInterval() {
		// checkWalk asserts 0 <= latent < 1 for every segment; this also records the observed extremes
		var tl = new WeatherTimeline(shipped);
		WalkStats stats = checkWalk("shipped", tl, START, 365);
		Assert.assertTrue(stats.minLatent >= 0);
		Assert.assertTrue(stats.maxLatent < 1);
		// Both ends of the range are visited, so ladders' first and last rungs are reachable
		Assert.assertTrue("lowest latent " + stats.minLatent, stats.minLatent < 0.01);
		Assert.assertTrue("highest latent " + stats.maxLatent, stats.maxLatent > 0.99);
	}

	@Test
	public void latentStepBoundedForBoundarySettingsTheLoaderAccepts() throws IOException {
		// With min == max every epoch holds exactly the fewest segments the loader plans for. At the lowest volatility
		// the loader accepts, the reach clamp binds whenever consecutive epoch-start latents are far apart, which is
		// exactly where the walk guarantee could break.
		float[][] crafted = {
			{ 240, 240 }, { 120, 120 }, { 90, 90 }, { 60, 60 }, { 26.2f, 26.2f },
			{ 30, 240 }, { 200, 240 }, { 236.5f, 239.9f }, { 26.2f, 240 },
		};
		for (float[] minMax : crafted) {
			String label = String.format(Locale.ROOT, "[%s, %s]", minMax[0], minMax[1]);
			double volatility = minimumAcceptedVolatility(label, minMax[0], minMax[1]);
			Settings settings = parseTimeline(timelineJson("BOUNDARY " + label, minMax[0], minMax[1], volatility, 40));
			Assert.assertNotNull(label + " at the minimum accepted volatility " + volatility, settings);

			var tl = new WeatherTimeline(settings);
			int days = 2000;
			WalkStats stats = checkWalk(label + " volatility " + volatility, tl, START, days);
			Assert.assertEquals(days, stats.epochEdges);

			// Make sure the stressful case actually occurred: some pair of consecutive epoch-start latents far apart
			double maxStartGap = 0;
			var probe = new WeatherTimeline(settings);
			for (long e = START / DAY; e < START / DAY + days; e++)
				maxStartGap = Math.max(maxStartGap, Math.abs(probe.segmentAt((e + 1) * DAY).latent - probe.segmentAt(e * DAY).latent));
			Assert.assertTrue(label + ": largest epoch-start gap " + maxStartGap, maxStartGap > 0.9);
		}
	}

	@Test
	public void latentStepBoundedForRandomSettingsTheLoaderAccepts() throws IOException {
		var rng = new Random(0x117_4D);
		int accepted = 0;
		for (int i = 0; i < 40; i++) {
			// Lengths in tenths of a minute, the shortest just above the coverage limit
			float min = Math.round(262 + rng.nextDouble() * (2400 - 262)) / 10f;
			float max = rng.nextInt(4) == 0 ? min : Math.round(min * 10 + rng.nextDouble() * (2400 - min * 10)) / 10f;
			String label = String.format(Locale.ROOT, "random #%d [%s, %s]", i, min, max);
			double lowest = minimumAcceptedVolatility(label, min, max);

			// Half of the draws sit exactly at the lowest accepted volatility, the rest anywhere above it
			double volatility = rng.nextBoolean() ? lowest : lowest + rng.nextDouble() * (0.5 - lowest);
			double driftDegrees = rng.nextDouble() * 1440 - 720;
			Settings settings = parseTimeline(timelineJson("RANDOM_" + rng.nextLong(), min, max, volatility, driftDegrees));
			Assert.assertNotNull(label + " volatility " + volatility, settings);
			accepted++;

			// Slightly below the boundary, the loader must refuse it because of the walk guarantee
			String error = parseError(timelineJson("RANDOM", min, max, lowest * 0.999, driftDegrees));
			Assert.assertNotNull(label + ": accepted below the minimum volatility", error);
			Assert.assertTrue(label + ": " + error, error.contains("too low"));

			checkWalk(label + " volatility " + volatility, new WeatherTimeline(settings), START + i * 1000L * DAY, 500);
		}
		Assert.assertEquals(40, accepted);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Random access and navigation
	// ------------------------------------------------------------------------------------------------------------

	@Test
	public void randomAccessMatchesSequential() {
		int days = 40;
		long end = START + days * DAY;
		var sequential = new WeatherTimeline(shipped);
		List<Segment> reference = new ArrayList<>();
		for (Segment s = sequential.segmentAt(START); s.start < end; s = advance(sequential, s))
			reference.add(s);
		long[] starts = new long[reference.size()];
		for (int i = 0; i < starts.length; i++)
			starts[i] = reference.get(i).start;

		// 10,000 shuffled queries on a fresh instance, each at least 7 epochs away from the previous one, so the
		// 6-entry cache has to evict and regenerate epochs all the time
		var random = new WeatherTimeline(shipped);
		var rng = new Random(20_454);
		long previousEpoch = -1_000_000;
		for (int q = 0; q < 10_000; q++) {
			long t;
			do {
				t = START + (long) (rng.nextDouble() * (end - START));
			} while (Math.abs(Math.floorDiv(t, DAY) - previousEpoch) < EVICTING_EPOCH_JUMP);
			previousEpoch = Math.floorDiv(t, DAY);

			int i = indexOf(starts, t);
			Segment actual = random.segmentAt(t);
			assertSameSegment("query " + q + " at " + t, reference.get(i), actual);
			if (i + 1 < reference.size())
				assertSameSegment("next after query " + q, reference.get(i + 1), random.next(actual));
			if (i > 0)
				assertSameSegment("previous before query " + q, reference.get(i - 1), random.previous(actual));
		}

		// Cycle through one more epoch than the cache holds, which makes an LRU cache miss on every lookup
		var cycling = new WeatherTimeline(shipped);
		for (int round = 0; round < 60; round++) {
			for (int k = 0; k < EVICTING_EPOCH_JUMP; k++) {
				long t = START + (k * 5L + 1) * DAY + (round * 7919L * MINUTE + k * 101L * MINUTE) % DAY;
				assertSameSegment("cycle " + round + "." + k, reference.get(indexOf(starts, t)), cycling.segmentAt(t));
			}
		}
	}

	@Test
	public void previousInvertsNext() {
		int days = 30;
		var tl = new WeatherTimeline(shipped);
		List<Segment> reference = new ArrayList<>();
		int epochEdges = 0;
		for (Segment s = tl.segmentAt(START); s.start < START + days * DAY; s = advance(tl, s)) {
			reference.add(s);
			Segment next = tl.next(s);
			Segment previous = tl.previous(s);
			assertSameSegment("previous(next(" + s + "))", s, tl.previous(next));
			assertSameSegment("next(previous(" + s + "))", s, tl.next(previous));
			Assert.assertEquals(s.start, previous.end);
			if (s.index == 0) {
				epochEdges++;
				Assert.assertEquals(s.epoch - 1, previous.epoch);
				Assert.assertEquals(previous.epoch + 1, tl.next(previous).epoch);
			} else {
				Assert.assertEquals(s.epoch, previous.epoch);
				Assert.assertEquals(s.index - 1, previous.index);
			}
		}
		Assert.assertEquals(days, epochEdges);

		// Across every epoch edge again, on a fresh instance whose cache is flushed before each step
		var fresh = new WeatherTimeline(shipped);
		for (int i = 0; i < reference.size(); i++) {
			Segment s = reference.get(i);
			if (s.index != 0 || i == 0)
				continue;
			flush(fresh);
			Segment first = fresh.segmentAt(s.start);
			assertSameSegment("first segment of epoch " + s.epoch, s, first);
			flush(fresh);
			Segment last = fresh.previous(first);
			assertSameSegment("last segment of epoch " + (s.epoch - 1), reference.get(i - 1), last);
			flush(fresh);
			assertSameSegment("next(last segment of epoch " + (s.epoch - 1) + ")", s, fresh.next(last));
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// Statistics
	// ------------------------------------------------------------------------------------------------------------

	@Test
	public void latentTimeShareUniform() {
		var tl = new WeatherTimeline(shipped);
		long end = START + 3 * 365 * DAY;
		double[] share = new double[10];
		for (Segment s = tl.segmentAt(START); s.start < end; s = advance(tl, s)) {
			long from = Math.max(s.start, START), to = Math.min(s.end, end);
			share[(int) (s.latent * 10)] += to - from;
		}
		for (int d = 0; d < 10; d++) {
			double fraction = share[d] / (end - START);
			Assert.assertTrue(String.format(Locale.ROOT, "latent decile %d holds %.4f of the time (%s)", d, fraction, Arrays.toString(share)),
				fraction >= 0.085 && fraction <= 0.115);
		}
	}

	@Test
	public void noShortPeriodicity() {
		ClimateLadder ladder = shippedDefs.climates.get("TEMPERATE").ladderFor(SeasonalTheme.SUMMER);
		long sampleStep = 10 * MINUTE;
		long end = START + 365 * DAY;

		// Sanity check: the measure detects persistence, so a repeating pattern would show up
		double tenMinutes = typeAgreement(ladder, 10 * MINUTE, START, end, sampleStep);
		Assert.assertTrue("agreement at a 10 minute lag " + tenMinutes, tenMinutes > 0.75);

		for (long lagHours : new long[] { 3, 6, 12, 24 }) {
			double agreement = typeAgreement(ladder, lagHours * HOUR, START, end, sampleStep);
			Assert.assertTrue(String.format(Locale.ROOT, "TEMPERATE/SUMMER type agreement at a %dh lag is %.3f", lagHours, agreement),
				agreement < 0.5);
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// Loader rules for the timeline object
	// ------------------------------------------------------------------------------------------------------------

	@Test
	public void loaderAcceptsShippedTimeline() {
		// Pinned on purpose: these values seed every player's weather and size the cloud contract, so changing any of
		// them changes what everyone sees and should be a deliberate decision
		Assert.assertEquals("GIELINOR", shipped.name);
		Assert.assertEquals(30, shipped.minSegmentMinutes, 0);
		Assert.assertEquals(90, shipped.maxSegmentMinutes, 0);
		Assert.assertEquals(0.3, shipped.volatility, 0);
		Assert.assertEquals(Math.toRadians(40), shipped.driftAngle, 1e-6);
		var tl = new WeatherTimeline(shipped);
		Assert.assertEquals(Math.cos(Math.toRadians(40)), tl.driftX(), 1e-6);
		Assert.assertEquals(Math.sin(Math.toRadians(40)), tl.driftZ(), 1e-6);
	}

	@Test
	public void loaderRejectsSegmentsTooShortForCoverage() throws IOException {
		expectShippedRejected(timeline -> timeline.add("segmentMinutes", pair(20, 90)), "minimum segment length");
		expectShippedRejected(timeline -> timeline.add("segmentMinutes", pair(26, 90)), "minimum segment length");
		// Just above the limit (26.14 minutes) is fine
		Assert.assertNotNull(parseShipped(timeline -> timeline.add("segmentMinutes", pair(26.2, 90))));
		Assert.assertNotNull(parseShipped(timeline -> timeline.add("segmentMinutes", pair(30, 240))));
	}

	@Test
	public void loaderRejectsVolatilityTooLowForLongSegments() throws IOException {
		// [30, 240] allows epochs of only 6 segments, which a volatility of 0.1 can't always cross
		expectShippedRejected(timeline -> {
			timeline.add("segmentMinutes", pair(30, 240));
			timeline.addProperty("volatility", 0.1);
		}, "too low");
		// The critic's counterexample: [30, 90] with a volatility of 0.04
		expectShippedRejected(timeline -> timeline.addProperty("volatility", 0.04), "too low");
		// Enough volatility to cross the whole range in 6 segments
		Assert.assertNotNull(parseShipped(timeline -> {
			timeline.add("segmentMinutes", pair(30, 240));
			timeline.addProperty("volatility", 0.17);
		}));
	}

	@Test
	public void loaderRequiresDriftAngle() throws IOException {
		expectShippedRejected(timeline -> timeline.remove("driftAngle"), "'driftAngle' is required");
		expectShippedRejected(timeline -> timeline.addProperty("driftAngle", "north-east"), "'driftAngle' must be a number");
		// Degrees in JSON, radians in code, wrapped into [0, 2pi)
		Settings wrapped = parseShipped(timeline -> timeline.addProperty("driftAngle", -320));
		Assert.assertEquals(Math.toRadians(40), wrapped.driftAngle, 1e-5);
	}

	@Test
	public void acceptedDriftAngleAlwaysGivesAFiniteUnitDirection() throws IOException {
		// The drift direction feeds the phase mapping and the steering flow (upper wind), so it must never be NaN.
		// Rejecting absurd angles is just as good, as long as the error names the field.
		for (double degrees : new double[] { 0, 40, -320, 400, 1e6, 1e12, 1e40, -1e40 }) {
			JsonObject root = WeatherTestData.loadShippedJson();
			root.getAsJsonObject("timeline").addProperty("driftAngle", degrees);
			Settings settings;
			try {
				settings = WeatherData.parse(root).timeline;
			} catch (IOException ex) {
				Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("driftAngle"));
				continue;
			}
			var tl = new WeatherTimeline(settings);
			String where = "driftAngle " + degrees + " was accepted as " + settings.driftAngle + " rad, giving the direction ("
				+ tl.driftX() + ", " + tl.driftZ() + ")";
			Assert.assertTrue(where, Float.isFinite(tl.driftX()) && Float.isFinite(tl.driftZ()));
			Assert.assertEquals(where, 1, Math.hypot(tl.driftX(), tl.driftZ()), 1e-5);
		}
	}

	@Test
	public void loaderRejectsVolatilityOutOfRange() throws IOException {
		for (double volatility : new double[] { 0, -0.1, 0.5000001, 0.6, 1 })
			expectShippedRejected(timeline -> timeline.addProperty("volatility", volatility), "'volatility' must be within (0, 0.5]");
		expectShippedRejected(timeline -> timeline.remove("volatility"), "'volatility' is required");
		// 0.5 itself is allowed. The shipped ladders are too narrow for it, so use a single-rung climate.
		Settings max = parseTimeline(timelineJson("MAX", 30, 90, 0.5, 40));
		Assert.assertNotNull(max);
		Assert.assertEquals(0.5, max.volatility, 0);
		checkWalk("volatility 0.5", new WeatherTimeline(max), START, 60);
	}

	@Test(timeout = 10_000)
	public void loaderRejectsSubMillisecondSegmentsWithoutHanging() throws IOException {
		// Nonsense lengths must be reported like any other invalid value. The first load runs on the client thread
		// during plugin startup, so a loader that never returns freezes the whole client.
		String rule = "'segmentMinutes' must satisfy 10 <= min <= max <= 240";
		expectShippedRejected(timeline -> timeline.add("segmentMinutes", pair(1e-6, 1e-6)), rule);
		expectShippedRejected(timeline -> timeline.add("segmentMinutes", pair(1e-6, 1e-5)), rule);
	}

	@Test
	public void loaderRejectsMalformedTimeline() throws IOException {
		String rule = "'segmentMinutes' must satisfy 10 <= min <= max <= 240";
		expectShippedRejected(timeline -> timeline.add("segmentMinutes", pair(90, 30)), rule);
		expectShippedRejected(timeline -> timeline.add("segmentMinutes", pair(30, 300)), rule);
		expectShippedRejected(timeline -> timeline.add("segmentMinutes", pair(5, 90)), rule);
		expectShippedRejected(timeline -> {
			var single = new JsonArray();
			single.add(30);
			timeline.add("segmentMinutes", single);
		}, "'segmentMinutes' must be [min, max]");
		expectShippedRejected(timeline -> timeline.addProperty("segmentMinutes", "30-90"), "'segmentMinutes' must be");
		expectShippedRejected(timeline -> timeline.addProperty("name", ""), "'name' must be a non-empty string");
		expectShippedRejected(timeline -> timeline.addProperty("seed", 5), "unknown key 'seed'");
		expectShippedRejected(root -> root.remove("timeline"), "a 'timeline' object is required", true);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------------------------

	private static final class WalkStats {
		int segments, boundaries, epochEdges;
		double maxStep, maxEpochEdgeStep;
		double minLatent = Double.POSITIVE_INFINITY, maxLatent = Double.NEGATIVE_INFINITY;
		long minDuration = Long.MAX_VALUE, maxDuration;
	}

	/**
	 * Walk every segment overlapping [start, start + days) with next(), asserting the timeline's guarantees: contiguous
	 * tiling, every epoch edge a segment boundary, consistent epoch and index fields, segment lengths within
	 * [min, max + min], latent in [0, 1), and a latent step no larger than the volatility at every boundary, epoch
	 * edges included.
	 */
	private static WalkStats checkWalk(String label, WeatherTimeline tl, long start, int days) {
		Settings s = tl.settings;
		// Same float arithmetic as the timeline uses
		long minMs = (long) (s.minSegmentMinutes * 60_000);
		long maxMs = (long) (s.maxSegmentMinutes * 60_000);
		long end = start + days * DAY;
		var stats = new WalkStats();

		Segment seg = tl.segmentAt(start);
		if (!(seg.start <= start && start < seg.end))
			Assert.fail(label + ": segmentAt(" + start + ") returned " + seg);
		while (seg.start < end) {
			stats.segments++;
			if (!(seg.latent >= 0 && seg.latent < 1))
				Assert.fail(label + ": latent outside [0, 1) in " + seg);
			long duration = seg.duration();
			if (duration < minMs || duration > maxMs + minMs)
				Assert.fail(label + ": duration " + duration + " ms outside [" + minMs + ", " + (maxMs + minMs) + "] in " + seg);
			if (seg.epoch != Math.floorDiv(seg.start, DAY))
				Assert.fail(label + ": wrong epoch in " + seg);
			if (seg.epoch != Math.floorDiv(seg.end - 1, DAY))
				Assert.fail(label + ": straddles an epoch edge: " + seg);

			Segment next = tl.next(seg);
			stats.boundaries++;
			if (next.start != seg.end)
				Assert.fail(label + ": gap or overlap between " + seg + " and " + next);
			boolean epochEdge = next.epoch != seg.epoch;
			if (epochEdge) {
				if (next.epoch != seg.epoch + 1 || next.index != 0 || next.start != next.epoch * DAY)
					Assert.fail(label + ": bad epoch transition from " + seg + " to " + next);
				stats.epochEdges++;
			} else if (next.index != seg.index + 1) {
				Assert.fail(label + ": index doesn't advance from " + seg + " to " + next);
			}

			double step = Math.abs(next.latent - seg.latent);
			if (step > s.volatility + STEP_TOLERANCE) {
				Assert.fail(String.format(Locale.ROOT, "%s: latent step %.15f from %s to %s exceeds the volatility %.15f%s",
					label, step, seg, next, s.volatility, epochEdge ? " (epoch edge)" : ""));
			}
			stats.maxStep = Math.max(stats.maxStep, step);
			if (epochEdge)
				stats.maxEpochEdgeStep = Math.max(stats.maxEpochEdgeStep, step);
			stats.minLatent = Math.min(stats.minLatent, seg.latent);
			stats.maxLatent = Math.max(stats.maxLatent, seg.latent);
			stats.minDuration = Math.min(stats.minDuration, duration);
			stats.maxDuration = Math.max(stats.maxDuration, duration);
			seg = next;
		}
		return stats;
	}

	/** next(), failing instead of looping forever when the timeline doesn't advance contiguously. */
	private static Segment advance(WeatherTimeline tl, Segment s) {
		Segment next = tl.next(s);
		if (next.start != s.end)
			Assert.fail("next(" + s + ") returned " + next + ", which doesn't start where the segment ends");
		return next;
	}

	private static void assertSameSegment(String message, Segment expected, Segment actual) {
		if (expected.start != actual.start || expected.end != actual.end
			|| Double.doubleToLongBits(expected.latent) != Double.doubleToLongBits(actual.latent)
			|| expected.seed != actual.seed || expected.epoch != actual.epoch || expected.index != actual.index) {
			Assert.fail(message + ": expected " + expected + " (seed " + expected.seed + ") but got " + actual + " (seed " + actual.seed + ")");
		}
	}

	/** Index of the last start <= t. */
	private static int indexOf(long[] starts, long t) {
		int i = Arrays.binarySearch(starts, t);
		return i >= 0 ? i : -i - 2;
	}

	/** Push every cached epoch out of the timeline's LRU by touching distant epochs. */
	private static void flush(WeatherTimeline tl) {
		for (int k = 0; k < EVICTING_EPOCH_JUMP; k++)
			tl.segmentAt(START + (5000L + 11 * k) * DAY);
	}

	/** Fraction of samples whose TEMPERATE type equals the type a fixed lag later. */
	private static double typeAgreement(ClimateLadder ladder, long lag, long start, long end, long sampleStep) {
		var now = new WeatherTimeline(shipped);
		var later = new WeatherTimeline(shipped);
		int same = 0, total = 0;
		for (long t = start; t < end; t += sampleStep) {
			WeatherType a = ladder.typeAt(now.segmentAt(t).latent);
			WeatherType b = ladder.typeAt(later.segmentAt(t + lag).latent);
			if (a == b)
				same++;
			total++;
		}
		return same / (double) total;
	}

	private static JsonArray pair(double a, double b) {
		var array = new JsonArray();
		array.add(a);
		array.add(b);
		return array;
	}

	private static JsonObject timelineJson(String name, double min, double max, double volatility, double driftDegrees) {
		var timeline = new JsonObject();
		timeline.addProperty("name", name);
		timeline.add("segmentMinutes", pair(min, max));
		timeline.addProperty("volatility", volatility);
		timeline.addProperty("driftAngle", driftDegrees);
		return timeline;
	}

	/**
	 * A minimal weather.json around the given timeline: one weather type and one single-rung climate, so the ladder
	 * validator (which also depends on the volatility) can never be the reason for a rejection.
	 */
	private static JsonObject minimalRoot(JsonObject timeline) {
		var root = new JsonObject();
		root.addProperty("defaultClimate", "ONLY");
		root.add("timeline", timeline);

		var clear = new JsonObject();
		clear.addProperty("name", "CLEAR");
		clear.addProperty("severity", 0);
		clear.addProperty("onsetMinutes", 20);
		clear.addProperty("clearMinutes", 20);
		clear.add("params", new JsonObject());
		var types = new JsonArray();
		types.add(clear);
		root.add("types", types);

		var wind = new JsonObject();
		wind.addProperty("angle", 0);
		wind.addProperty("speed", 15);
		wind.addProperty("strength", 50);
		var rung = new JsonArray();
		rung.add("CLEAR");
		rung.add(1);
		var ladder = new JsonArray();
		ladder.add(rung);
		var climate = new JsonObject();
		climate.addProperty("name", "ONLY");
		climate.add("wind", wind);
		climate.add("ladder", ladder);
		var climates = new JsonArray();
		climates.add(climate);
		root.add("climates", climates);
		root.add("bindings", new JsonArray());
		return root;
	}

	/** The settings the loader produces for a minimal file around the timeline, or null when it refuses it. */
	@Nullable
	private static Settings parseTimeline(JsonObject timeline) {
		try {
			return WeatherData.parse(minimalRoot(timeline)).timeline;
		} catch (IOException ex) {
			return null;
		}
	}

	/** The loader's error for a minimal file around the timeline, or null when it accepts it. */
	@Nullable
	private static String parseError(JsonObject timeline) {
		try {
			WeatherData.parse(minimalRoot(timeline));
			return null;
		} catch (IOException ex) {
			return ex.getMessage();
		}
	}

	/** The lowest volatility the loader accepts for the segment lengths, found by bisection through WeatherData.parse. */
	private static double minimumAcceptedVolatility(String label, float min, float max) {
		double lo = 0, hi = 0.5;
		Assert.assertNotNull(label + " must be accepted at volatility 0.5", parseTimeline(timelineJson("BISECT", min, max, hi, 0)));
		for (int i = 0; i < 48; i++) {
			double mid = (lo + hi) / 2;
			if (parseTimeline(timelineJson("BISECT", min, max, mid, 0)) != null) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		String error = parseError(timelineJson("BISECT", min, max, lo, 0));
		Assert.assertNotNull(label + ": rejected below " + hi, error);
		Assert.assertTrue(label + ": the lower bound must come from the walk guarantee: " + error, error.contains("too low"));
		return hi;
	}

	private interface JsonEdit {
		void apply(JsonObject obj);
	}

	private static Settings parseShipped(JsonEdit timelineEdit) throws IOException {
		JsonObject root = WeatherTestData.loadShippedJson();
		timelineEdit.apply(root.getAsJsonObject("timeline"));
		return WeatherData.parse(root).timeline;
	}

	private static void expectShippedRejected(JsonEdit timelineEdit, String expectedError) throws IOException {
		expectShippedRejected(timelineEdit, expectedError, false);
	}

	private static void expectShippedRejected(JsonEdit edit, String expectedError, boolean editRoot) throws IOException {
		JsonObject root = WeatherTestData.loadShippedJson();
		edit.apply(editRoot ? root : root.getAsJsonObject("timeline"));
		try {
			WeatherData.parse(root);
		} catch (IOException ex) {
			Assert.assertTrue("expected an error containing \"" + expectedError + "\", got: " + ex.getMessage(),
				ex.getMessage().contains(expectedError));
			return;
		}
		Assert.fail("weather.json was accepted, but should fail with \"" + expectedError + "\"");
	}
}
