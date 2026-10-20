package rs117.hd.tests.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.BiPredicate;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.weather.Climate;
import rs117.hd.scene.weather.ClimateLadder;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.scene.weather.WeatherParam;
import rs117.hd.scene.weather.WeatherSampler;
import rs117.hd.scene.weather.WeatherStrip;
import rs117.hd.scene.weather.WeatherTimeline;
import rs117.hd.scene.weather.WeatherTimeline.Segment;
import rs117.hd.scene.weather.WeatherType;
import rs117.hd.scene.weather.WeatherZone;
import rs117.hd.scene.weather.ZoneBlend;

import static rs117.hd.scene.weather.WeatherConstants.*;

/**
 * Checks the forecast strip against an independent ground truth built straight from the timeline, and the
 * properties future cloud rendering relies on: world anchoring, continuity, asymmetric easing, non-overlapping
 * transition windows, re-indexing without any visible change, and coverage of the visible sky.
 */
public class WeatherSamplerTest {
	private static final long MINUTE_MS = 60_000L;
	private static final long HOUR_MS = 60 * MINUTE_MS;
	private static final long DAY_MS = 24 * HOUR_MS;
	/** 2026-01-01T00:00Z. Arbitrary, but fixed so every run checks the same weather. */
	private static final long BASE_PHASE = 1_767_225_600_000L;
	/** Timeline milliseconds per local unit of upwind distance. */
	private static final double MS_PER_UNIT = 1000.0 / FRONT_SPEED;
	/** The strip must be exact over the visible sky downwind, and the visible sky plus the cirrus lead upwind. */
	private static final float U_MIN = -FRONT_VISIBLE_DISTANCE;
	private static final float U_MAX = FRONT_VISIBLE_DISTANCE + HIGH_DECK_LEAD_DISTANCE;
	private static final float GRID_STEP = 400;
	/** Positions within each transition window to sample, as multiples of its half-width from its centre. */
	private static final double[] WINDOW_LANDMARKS = { -1, -.999, -.75, -.5, -.25, 0, .25, .5, .75, .999, 1 };
	private static final double TOLERANCE = 1e-4;
	private static final int VECTOR_SIZE = WeatherParam.VECTOR_SIZE;
	private static final SeasonalTheme[] SEASONS = { SeasonalTheme.SUMMER, SeasonalTheme.AUTUMN, SeasonalTheme.WINTER };

	private static WeatherData data;
	private static WeatherTimeline timeline;
	private static WeatherZone temperate, desert;
	private static Mix single, twoZone, twoZoneMasked, threeZone;

	@BeforeClass
	public static void loadData() throws IOException {
		data = WeatherTestData.shipped();
		timeline = data.timeline;
		temperate = new WeatherZone(climate("TEMPERATE"), SeasonalTheme.SUMMER);
		desert = new WeatherZone(climate("DESERT"), SeasonalTheme.SUMMER);
		var arctic = new WeatherZone(climate("ARCTIC"), SeasonalTheme.WINTER);

		// A forbid mask part-way through its ramp: rain fully masked, the other precipitation half masked
		float[] mask = new float[VECTOR_SIZE];
		Arrays.fill(mask, 1);
		for (var param : WeatherParam.VALUES)
			if (param.forbiddable && param.group == WeatherParam.Group.PRECIP)
				mask[param.offset()] = .5f;
		mask[WeatherParam.RAIN.offset()] = 0;

		single = new Mix("TEMPERATE/SUMMER", new WeatherZone[] { temperate }, new float[] { 1 }, temperate, null);
		twoZone = new Mix("0.6 TEMPERATE + 0.4 DESERT", new WeatherZone[] { temperate, desert }, new float[] { .6f, .4f }, temperate, null);
		twoZoneMasked = new Mix(
			"0.6 TEMPERATE + 0.4 DESERT, labelled by DESERT, masked",
			new WeatherZone[] { temperate, desert }, new float[] { .6f, .4f }, desert, mask);
		threeZone = new Mix(
			"0.5 TEMPERATE + 0.3 DESERT + 0.2 ARCTIC/WINTER",
			new WeatherZone[] { temperate, desert, arctic }, new float[] { .5f, .3f, .2f }, temperate, null);
	}

	private static Climate climate(String name) {
		var climate = data.climate(name);
		Assert.assertNotNull("missing climate " + name, climate);
		return climate;
	}

	/** A fixed mix of zones, as the sampler receives it from the zone blend. */
	private static final class Mix {
		final String name;
		final WeatherZone[] zones;
		final float[] weights;
		final WeatherZone label;
		@Nullable
		final float[] mask;

		Mix(String name, WeatherZone[] zones, float[] weights, WeatherZone label, @Nullable float[] mask) {
			this.name = name;
			this.zones = zones;
			this.weights = weights;
			this.label = label;
			this.mask = mask;
		}

		void sample(WeatherSampler sampler, long phase, WeatherStrip strip) {
			sampler.sample(timeline, zones, weights, zones.length, label, phase, mask, strip);
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Independent ground truth
	// ---------------------------------------------------------------------------------------------------------

	/**
	 * The true blended weather at an absolute timeline phase, built directly from the timeline and the design rules,
	 * without WeatherSampler, WeatherStrip, WeatherZone's state and window helpers, or ClimateLadder's lookups. It finds
	 * the segment containing the phase and its neighbours, and blends each zone's pure states across whichever
	 * transition window contains the phase, using a window half-width averaged by zone weight and the edge shape of
	 * the label zone.
	 */
	private static final class Reference {
		/** A separate instance, so the reference never shares segment caches with the sampler. */
		final WeatherTimeline timeline = new WeatherTimeline(data.timeline.settings);
		final Mix mix;
		private final double[] stateA = new double[VECTOR_SIZE];
		private final double[] stateB = new double[VECTOR_SIZE];

		Reference(Mix mix) {
			this.mix = mix;
		}

		void evaluate(double phase, double[] out) {
			Segment current = timeline.segmentAt((long) Math.floor(phase));
			if (!(current.start <= phase && phase < current.end))
				throw new AssertionError("segmentAt(" + phase + ") returned " + current);
			Segment previous = timeline.previous(current);
			Segment next = timeline.next(current);
			double incoming = halfWidth(previous, current);
			double outgoing = halfWidth(current, next);

			Segment a = current, b = current;
			double progress = 0;
			int shape = 0;
			if (phase < current.start + incoming) {
				a = previous;
				b = current;
				progress = (phase - (current.start - incoming)) / (2 * incoming);
				shape = shape(previous, current);
			} else if (phase > current.end - outgoing) {
				a = current;
				b = next;
				progress = (phase - (current.end - outgoing)) / (2 * outgoing);
				shape = shape(current, next);
			}

			Arrays.fill(out, 0);
			for (int z = 0; z < mix.zones.length; z++) {
				double w = mix.weights[z];
				pureState(mix.zones[z], a, stateA);
				pureState(mix.zones[z], b, stateB);
				for (int slot = 0; slot < VECTOR_SIZE; slot++) {
					double e = ease(WeatherParam.groupOfSlot(slot), shape, progress);
					out[slot] += w * (stateA[slot] + (stateB[slot] - stateA[slot]) * e);
				}
			}
			if (mix.mask != null)
				for (int slot = 0; slot < VECTOR_SIZE; slot++)
					out[slot] *= mix.mask[slot];
		}

		/** Window half-width at the boundary between two consecutive segments, averaged by zone weight. */
		double halfWidth(Segment a, Segment b) {
			double sum = 0;
			for (int z = 0; z < mix.zones.length; z++)
				sum += mix.weights[z] * halfWidthMs(mix.zones[z], a, b);
			return sum;
		}

		int shape(Segment a, Segment b) {
			int change = typeOf(mix.label, b).severity - typeOf(mix.label, a).severity;
			if (change != 0)
				return Integer.signum(change);
			double latentChange = b.latent - a.latent;
			return latentChange > 1e-9 ? 1 : latentChange < -1e-9 ? -1 : 0;
		}
	}

	/** Largest ladder bin whose lower edge is at or below the latent value, by linear search. */
	private static int binOf(ClimateLadder ladder, double latent) {
		int bin = 0;
		for (int i = 1; i < ladder.types.length; i++)
			if (ladder.edges[i] <= latent)
				bin = i;
		return bin;
	}

	/** Whether a developer-forced type (or the type held until the forced front) replaces the ladder for this segment. */
	private static boolean isOverridden(WeatherZone zone, Segment segment) {
		return zone.forcedType != null && (segment.start >= zone.forcedFromPhase || zone.heldType != null);
	}

	private static WeatherType typeOf(WeatherZone zone, Segment segment) {
		if (isOverridden(zone, segment))
			return segment.start >= zone.forcedFromPhase ? zone.forcedType : zone.heldType;
		return zone.ladder.types[binOf(zone.ladder, segment.latent)];
	}

	/** Position within the type's [mild, severe] range: within the ladder bin, or the raw latent for forced types. */
	private static double intensityOf(WeatherZone zone, Segment segment) {
		if (isOverridden(zone, segment)) {
			if (segment.start >= zone.forcedFromPhase && !Float.isNaN(zone.forcedIntensity))
				return zone.forcedIntensity;
			return segment.latent;
		}
		var ladder = zone.ladder;
		int bin = binOf(ladder, segment.latent);
		double width = ladder.edges[bin + 1] - ladder.edges[bin];
		return width > 0 ? Math.max(0, Math.min(1, (segment.latent - ladder.edges[bin]) / width)) : 0;
	}

	/** The segment's untransitioned state: the type's [mild, severe] range at the segment's intensity. */
	private static void pureState(WeatherZone zone, Segment segment, double[] out) {
		var type = typeOf(zone, segment);
		double intensity = intensityOf(zone, segment);
		float[] mild = zone.climate.mild(type);
		float[] severe = zone.climate.severe(type);
		for (int i = 0; i < VECTOR_SIZE; i++)
			out[i] = mild[i] + ((double) severe[i] - mild[i]) * intensity;
	}

	/** The design's window rule: onset length when worsening, clearing length when improving, capped per segment. */
	private static double halfWidthMs(WeatherZone zone, Segment a, Segment b) {
		var from = typeOf(zone, a);
		var to = typeOf(zone, b);
		double minutes;
		if (from == to) {
			minutes = SAME_TYPE_BLEND_MINUTES;
		} else if (to.severity > from.severity) {
			minutes = to.onsetMinutes;
		} else if (to.severity < from.severity) {
			minutes = from.clearMinutes;
		} else {
			minutes = (from.clearMinutes + to.onsetMinutes) / 2.0;
		}
		return Math.min(minutes * MINUTE_MS / 2, MAX_HALF_WIDTH_FRACTION * (double) Math.min(a.duration(), b.duration()));
	}

	private static double ease(WeatherParam.Group group, int shape, double p) {
		double e0 = 0, e1 = 1;
		if (shape > 0) {
			e0 = group.onsetStart;
			e1 = group.onsetEnd;
		} else if (shape < 0) {
			e0 = group.clearStart;
			e1 = group.clearEnd;
		}
		double t = Math.max(0, Math.min(1, (p - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	/** Tracks the largest error seen, counting NaN as infinitely wrong. */
	private static final class Worst {
		double error;
		long phase;
		double u;
		int slot = -1;
		/** Describes the case being compared; captured along with the worst error. */
		String context = "";
		String worstContext = "";

		void track(double err, long phase, double u, int slot) {
			if (!(err <= error)) {
				error = Double.isNaN(err) ? Double.POSITIVE_INFINITY : err;
				this.phase = phase;
				this.u = u;
				this.slot = slot;
				worstContext = context;
			}
		}

		void assertWithin(String what, double tolerance) {
			String where = slot < 0 ? "-" : String.format(Locale.ROOT, "%s at phase %d, u = %.3f%s",
				WeatherParam.ofSlot(slot).key, phase, u, worstContext);
			System.out.printf(Locale.ROOT, "%s: max error %.3g (%s)%n", what, error, where);
			Assert.assertTrue(what + ": max error " + error + " (" + where + ")", error <= tolerance);
		}
	}

	private static long randomPhase(Random rng, int n) {
		// Some phases straddle epoch boundaries, where the timeline switches between independently generated days
		if (n % 10 == 0) {
			long epoch = Math.floorDiv(BASE_PHASE, EPOCH_MS) + rng.nextInt(1000);
			return epoch * EPOCH_MS + (long) ((rng.nextDouble() * 2 - 1) * 3 * HOUR_MS);
		}
		return BASE_PHASE + (long) (rng.nextDouble() * 3 * 365 * DAY_MS);
	}

	/** A fine regular grid with a random offset, plus landmarks within every transition window in range. */
	private static float[] sampleDistances(Reference reference, long phase, Random rng) {
		var list = new ArrayList<Float>();
		float offset = rng.nextFloat() * GRID_STEP;
		for (int i = 0; ; i++) {
			float u = U_MIN + offset + i * GRID_STEP;
			if (u > U_MAX)
				break;
			list.add(u);
		}
		list.add(U_MIN);
		list.add(0f);
		list.add(U_MAX);

		Segment segment = reference.timeline.segmentAt((long) (phase + U_MIN * MS_PER_UNIT) - 3 * HOUR_MS);
		long end = (long) (phase + U_MAX * MS_PER_UNIT) + 3 * HOUR_MS;
		while (segment.start < end) {
			Segment next = reference.timeline.next(segment);
			double halfWidth = reference.halfWidth(segment, next);
			for (double f : WINDOW_LANDMARKS) {
				float u = (float) ((next.start + f * halfWidth - phase) / MS_PER_UNIT);
				if (u >= U_MIN && u <= U_MAX)
					list.add(u);
			}
			segment = next;
		}

		float[] out = new float[list.size()];
		for (int i = 0; i < out.length; i++)
			out[i] = list.get(i);
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Tests
	// ---------------------------------------------------------------------------------------------------------

	@Test
	public void referenceUsesTheSameSchedule() {
		var reference = new Reference(single);
		var rng = new Random(1);
		for (int i = 0; i < 1000; i++) {
			long phase = randomPhase(rng, i);
			Segment a = timeline.segmentAt(phase), b = reference.timeline.segmentAt(phase);
			Assert.assertEquals(a.start, b.start);
			Assert.assertEquals(a.end, b.end);
			Assert.assertEquals(a.latent, b.latent, 0);
		}
	}

	/** The key property: within coverage, the strip at upwind distance u is the true weather at phase + u / V. */
	@Test
	public void stripMatchesIndependentGroundTruthWithinCoverage() {
		var rng = new Random(0x117D);
		for (var mix : new Mix[] { single, twoZone, twoZoneMasked, threeZone }) {
			var reference = new Reference(mix);
			var sampler = new WeatherSampler();
			var strip = new WeatherStrip();
			var worst = new Worst();
			int phases = mix == single || mix == twoZone ? 520 : 160;
			long checks = 0;
			for (int n = 0; n < phases; n++)
				checks += compareWithReference(reference, sampler, strip, randomPhase(rng, n), rng, worst);
			Assert.assertTrue(checks > phases * 500L);
			worst.assertWithin(mix.name, TOLERANCE);
		}
	}

	/**
	 * The same property with a developer-forced front. With the shipped ladders, severity never decreases up the
	 * ladder, so edge shapes always follow the latent direction; a forced type breaks that, so here the shapes must
	 * really come from the label zone's severities.
	 */
	@Test
	public void stripMatchesGroundTruthWithAForcedFront() {
		var rng = new Random(0xF0CED);
		var thunderstorm = data.type("THUNDERSTORM");
		var clear = data.type("CLEAR");
		var sampler = new WeatherSampler();
		var strip = new WeatherStrip();
		var worst = new Worst();
		int againstLatent = 0;
		for (int n = 0; n < 400; n++) {
			long phase = randomPhase(rng, n);
			// The forced front arrives anywhere from one segment behind the camera to three ahead, or everywhere
			Segment front = timeline.segmentAt(phase);
			int ahead = rng.nextInt(5) - 1;
			for (int i = 0; i < ahead; i++)
				front = timeline.next(front);
			if (ahead < 0)
				front = timeline.previous(front);
			long forcedFrom = n % 7 == 0 ? Long.MIN_VALUE : front.start;
			var held = n % 3 == 0 ? null : clear;
			float pinned = n % 2 == 0 ? Float.NaN : .7f;
			var forced = new WeatherZone(climate("TEMPERATE"), SeasonalTheme.SUMMER, thunderstorm, held, pinned, forcedFrom);
			var mix = new Mix("forced", new WeatherZone[] { forced, desert }, new float[] { .7f, .3f }, forced, null);
			var reference = new Reference(mix);
			worst.context = String.format(Locale.ROOT, ", forced %s from %d, held %s, intensity %s", forced.forcedType, forcedFrom, held, pinned);
			compareWithReference(reference, sampler, strip, phase, rng, worst);

			// Count the boundaries in range whose true shape disagrees with the latent direction, to show the case is exercised
			Segment a = reference.timeline.segmentAt((long) (phase + U_MIN * MS_PER_UNIT));
			while (a.start < phase + U_MAX * MS_PER_UNIT) {
				Segment b = reference.timeline.next(a);
				if (reference.shape(a, b) * (b.latent - a.latent) < 0)
					againstLatent++;
				a = b;
			}
		}
		worst.assertWithin("forced THUNDERSTORM front", TOLERANCE);
		Assert.assertTrue("forced fronts should produce edges against the latent direction: " + againstLatent, againstLatent > 50);
	}

	/** Compares a freshly sampled strip with the reference over the visible range, returning the number of positions checked. */
	private static int compareWithReference(Reference reference, WeatherSampler sampler, WeatherStrip strip, long phase, Random rng, Worst worst) {
		double[] expected = new double[VECTOR_SIZE];
		reference.mix.sample(sampler, phase, strip);
		Assert.assertTrue(strip.valid);
		int checks = 0;
		for (float u : sampleDistances(reference, phase, rng)) {
			reference.evaluate(phase + u * MS_PER_UNIT, expected);
			for (int slot = 0; slot < VECTOR_SIZE; slot++)
				worst.track(Math.abs(strip.evaluateSlot(slot, u) - expected[slot]), phase, u, slot);
			checks++;
		}
		return checks;
	}

	@Test
	public void forbidMaskZeroesMaskedParametersEverywhere() {
		var rng = new Random(0xF0B1D);
		var sampler = new WeatherSampler();
		var masked = new WeatherStrip();
		var unmasked = new WeatherStrip();
		int rain = WeatherParam.RAIN.offset();
		for (int n = 0; n < 200; n++) {
			long phase = randomPhase(rng, n);
			twoZoneMasked.sample(sampler, phase, masked);
			sampler.sample(timeline, twoZoneMasked.zones, twoZoneMasked.weights, 2, twoZoneMasked.label, phase, null, unmasked);
			for (float u = U_MIN; u <= U_MAX; u += 1000) {
				Assert.assertEquals(0, masked.evaluateSlot(rain, u), 0);
				for (int slot = 0; slot < VECTOR_SIZE; slot++) {
					double expected = twoZoneMasked.mask[slot] * unmasked.evaluateSlot(slot, u);
					Assert.assertEquals(expected, masked.evaluateSlot(slot, u), 1e-6);
				}
			}
		}
	}

	/** Weather systems travel downwind at FRONT_SPEED: what is u units upwind now arrives u / V later. */
	@Test
	public void stripIsAnchoredToTheWorld() {
		var rng = new Random(0xA4C408);
		// Shifts in multiples of 125 ms are multiples of 4 local units, so u is exact in float
		long minSteps = (long) Math.ceil(U_MIN * MS_PER_UNIT / 125);
		long maxSteps = (long) Math.floor(U_MAX * MS_PER_UNIT / 125);
		for (var mix : new Mix[] { single, twoZone }) {
			var sampler = new WeatherSampler();
			var here = new WeatherStrip();
			var there = new WeatherStrip();
			var worst = new Worst();
			for (int n = 0; n < 300; n++) {
				long phase = randomPhase(rng, n);
				mix.sample(sampler, phase, here);
				for (int k = 0; k < 40; k++) {
					long shiftMs = 125 * (minSteps + (long) (rng.nextDouble() * (maxSteps - minSteps + 1)));
					float u = (float) (shiftMs / MS_PER_UNIT);
					mix.sample(sampler, phase + shiftMs, there);
					for (int slot = 0; slot < VECTOR_SIZE; slot++)
						worst.track(Math.abs(here.evaluateSlot(slot, u) - there.evaluateSlot(slot, 0)), phase, u, slot);
				}
			}
			worst.assertWithin(mix.name, TOLERANCE);
		}
	}

	@Test
	public void localParamsChangeContinuously() {
		checkContinuity(temperate, "a THUNDERSTORM clearing",
			(from, to) -> from.name.equals("THUNDERSTORM") && !to.name.equals("THUNDERSTORM"));
		checkContinuity(desert, "a SANDSTORM onset",
			(from, to) -> !from.name.equals("SANDSTORM") && to.name.equals("SANDSTORM"));
	}

	/**
	 * Samples the camera-local parameters every second for 48 hours, centred on the zone's fastest kind of transition,
	 * and requires every parameter to change by at most 1% of its range per second.
	 */
	private static void checkContinuity(WeatherZone zone, String fastest, BiPredicate<WeatherType, WeatherType> isFastest) {
		long center = findBoundary(zone, BASE_PHASE, isFastest);
		long start = center - 24 * HOUR_MS;
		var sampler = new WeatherSampler();
		var strip = new WeatherStrip();
		float[] previous = new float[VECTOR_SIZE];
		float[] current = new float[VECTOR_SIZE];
		double[] worstRate = new double[VECTOR_SIZE];
		long[] worstAt = new long[VECTOR_SIZE];
		int typeChanges = 0;
		WeatherType lastType = null;
		for (long second = 0; second <= 48 * 3600; second++) {
			long phase = start + second * 1000;
			sampler.sample(timeline, zone, phase, null, strip);
			strip.evaluateLocal(current);
			if (second > 0) {
				for (int slot = 0; slot < VECTOR_SIZE; slot++) {
					var param = WeatherParam.ofSlot(slot);
					double rate = Math.abs(current[slot] - previous[slot]) / (param.max - param.min);
					if (!(rate <= worstRate[slot])) {
						worstRate[slot] = Double.isNaN(rate) ? Double.POSITIVE_INFINITY : rate;
						worstAt[slot] = phase;
					}
				}
			}
			var type = strip.types[WeatherStrip.CENTER];
			if (lastType != null && type != lastType)
				typeChanges++;
			lastType = type;
			float[] swap = previous;
			previous = current;
			current = swap;
		}

		Assert.assertTrue(zone + ": the window should contain plenty of weather changes, got " + typeChanges, typeChanges >= 10);
		double overall = 0;
		int fastestSlot = 0;
		for (int slot = 0; slot < VECTOR_SIZE; slot++) {
			if (worstRate[slot] > overall)
				fastestSlot = slot;
			overall = Math.max(overall, worstRate[slot]);
			Assert.assertTrue(String.format(Locale.ROOT,
				"%s around %s: %s changed by %.3f%% of its range in one second at phase %d",
				zone, fastest, WeatherParam.ofSlot(slot).key, worstRate[slot] * 100, worstAt[slot]), worstRate[slot] <= .01);
		}
		System.out.printf(Locale.ROOT, "%s around %s: fastest change %.3f%% of range per second (%s), %d type changes%n",
			zone, fastest, overall * 100, WeatherParam.ofSlot(fastestSlot).key, typeChanges);
		Assert.assertTrue(zone + ": the parameters should actually change", overall > 1e-4);
	}

	/**
	 * Crossing a climate border cross-fades the zones over the blend time. The camera-local parameters must start
	 * exactly at the old zone's weather, end exactly at the new zone's, and never jump in between, including when the
	 * labels switch to the new zone halfway through. Window widths are weight-averaged, so they shift during the fade,
	 * which can make parameters change somewhat faster than the weights alone would (measured at up to about 4.5% of the
	 * range per second); the per-frame bound only rules out discontinuities.
	 */
	@Test
	public void climateCrossFadeIsContinuousAndCompletes() {
		var arctic = new WeatherZone(climate("ARCTIC"), SeasonalTheme.WINTER);
		WeatherZone[][] pairs = { { temperate, desert }, { desert, temperate }, { temperate, arctic }, { arctic, desert } };
		var rng = new Random(0xC1A7E);
		var sampler = new WeatherSampler();
		var strip = new WeatherStrip();
		var alone = new WeatherStrip();
		float[] previous = new float[VECTOR_SIZE];
		float[] current = new float[VECTOR_SIZE];
		float[] expected = new float[VECTOR_SIZE];
		float dt = 1 / 60f;
		int frames = Math.round(CLIMATE_BLEND_SECONDS / dt);
		double worstStep = 0;
		String worstAt = "";
		for (int trial = 0; trial < 120; trial++) {
			var from = pairs[trial % pairs.length][0];
			var to = pairs[trial % pairs.length][1];
			long phase0 = randomPhase(rng, trial);
			var blend = new ZoneBlend();
			blend.setTarget(from);
			blend.snap();
			blend.setTarget(to);

			sampler.sample(timeline, blend.zones, blend.weights, blend.count, blend.dominant(), phase0, null, strip);
			strip.evaluateLocal(previous);
			sampler.sample(timeline, from, phase0, null, alone);
			alone.evaluateLocal(expected);
			Assert.assertArrayEquals("before the fade starts", expected, previous, 1e-6f);

			long phase = phase0;
			for (int frame = 1; frame <= frames + 60; frame++) {
				blend.step(dt, CLIMATE_BLEND_SECONDS);
				phase = phase0 + frame * 1000L / 60;
				sampler.sample(timeline, blend.zones, blend.weights, blend.count, blend.dominant(), phase, null, strip);
				strip.evaluateLocal(current);
				for (int slot = 0; slot < VECTOR_SIZE; slot++) {
					var param = WeatherParam.ofSlot(slot);
					double step = Math.abs(current[slot] - previous[slot]) / (param.max - param.min);
					if (!(step <= worstStep)) {
						worstStep = Double.isNaN(step) ? Double.POSITIVE_INFINITY : step;
						worstAt = String.format(Locale.ROOT, "%s -> %s, %s at frame %d from phase %d", from, to, param.key, frame, phase0);
					}
				}
				float[] swap = previous;
				previous = current;
				current = swap;
			}

			Assert.assertTrue(blend.isSettled());
			sampler.sample(timeline, to, phase, null, alone);
			alone.evaluateLocal(expected);
			Assert.assertArrayEquals("after the fade completes", expected, previous, 1e-6f);
		}
		System.out.printf(Locale.ROOT, "climate cross-fades: largest per-frame change %.4f%% of range (%s)%n", worstStep * 100, worstAt);
		Assert.assertTrue(String.format(Locale.ROOT, "a parameter jumped by %.3f%% of its range in one frame (%s)", worstStep * 100, worstAt),
			worstStep <= .0025);
	}

	/** Phase of the first segment boundary at or after the given phase where the zone's types match the predicate. */
	private static long findBoundary(WeatherZone zone, long after, BiPredicate<WeatherType, WeatherType> predicate) {
		Segment a = timeline.segmentAt(after);
		Segment b = timeline.next(a);
		for (int i = 0; i < 365 * 48; i++) {
			if (predicate.test(zone.typeFor(a), zone.typeFor(b)))
				return b.start;
			a = b;
			b = timeline.next(b);
		}
		throw new AssertionError(zone + ": no matching boundary within a year");
	}

	/**
	 * Precipitation starts late and stops early, so with onset windows at least as long as clearing windows, rain should
	 * take longer to build from 10% to 90% than to fall from 90% to 10%. Measured on the camera-local value.
	 */
	@Test
	public void rainOnsetIsSlowerThanClearing() {
		var zone = temperate;
		var sampler = new WeatherSampler();
		var strip = new WeatherStrip();
		float[] from = new float[VECTOR_SIZE];
		float[] to = new float[VECTOR_SIZE];
		int rain = WeatherParam.RAIN.offset();
		var onsets = new ArrayList<Double>();
		var clearings = new ArrayList<Double>();
		var reference = new Reference(single);

		Segment a = timeline.segmentAt(BASE_PHASE);
		Segment b = timeline.next(a);
		while (b.start < BASE_PHASE + 60 * DAY_MS) {
			zone.stateFor(a, from);
			zone.stateFor(b, to);
			double change = to[rain] - from[rain];
			int shape = reference.shape(a, b);
			boolean onset = shape > 0 && change >= .05;
			boolean clearing = shape < 0 && change <= -.05;
			if (onset || clearing) {
				long halfWidth = zone.halfWidthMs(a, b);
				long t10 = Long.MIN_VALUE, t90 = Long.MIN_VALUE;
				for (long t = b.start - halfWidth - 1000; t <= b.start + halfWidth + 1000; t += 1000) {
					sampler.sample(timeline, zone, t, null, strip);
					double fraction = (strip.evaluate(WeatherParam.RAIN, 0) - from[rain]) / change;
					if (t10 == Long.MIN_VALUE && fraction >= .1)
						t10 = t;
					if (fraction >= .9) {
						t90 = t;
						break;
					}
				}
				Assert.assertTrue("rain never completed the transition at " + b, t10 != Long.MIN_VALUE && t90 != Long.MIN_VALUE);
				(onset ? onsets : clearings).add((t90 - t10) / (double) MINUTE_MS);
			}
			a = b;
			b = timeline.next(b);
		}

		Assert.assertTrue("onsets: " + onsets.size(), onsets.size() >= 20);
		Assert.assertTrue("clearings: " + clearings.size(), clearings.size() >= 20);
		double meanOnset = mean(onsets), meanClearing = mean(clearings);
		System.out.printf(Locale.ROOT, "rain 10-90%% rise at onset %.2f min (n = %d), 90-10%% fall at clearing %.2f min (n = %d)%n",
			meanOnset, onsets.size(), meanClearing, clearings.size());
		Assert.assertTrue(String.format(Locale.ROOT,
			"mean rain 10-90%% rise at onset %.2f min (n = %d) should exceed the 90-10%% fall at clearing %.2f min (n = %d)",
			meanOnset, onsets.size(), meanClearing, clearings.size()), meanOnset > meanClearing);
	}

	private static double mean(List<Double> values) {
		double sum = 0;
		for (double v : values)
			sum += v;
		return sum / values.size();
	}

	@Test
	public void easingOrdersCloudsAndPrecipitation() {
		var cloud = WeatherParam.Group.CLOUD;
		var precip = WeatherParam.Group.PRECIP;

		// When rain reaches 10% of its change at onset, clouds are at least half way there
		float onset = solve(p -> precip.ease(1, p), .1f);
		Assert.assertEquals(.1f, precip.ease(1, onset), 1e-4);
		Assert.assertTrue("cloud onset at rain 10%: " + cloud.ease(1, onset), cloud.ease(1, onset) >= .5f);

		// When rain has dropped by 90% at clearing, clouds have cleared by at most a quarter
		float clearing = solve(p -> precip.ease(-1, p), .9f);
		Assert.assertEquals(.9f, precip.ease(-1, clearing), 1e-4);
		Assert.assertTrue("cloud clearing at rain 10%: " + cloud.ease(-1, clearing), cloud.ease(-1, clearing) <= .25f);
	}

	@Test
	public void easingCurvesRunMonotonicallyFromZeroToOne() {
		for (var group : WeatherParam.Group.values()) {
			for (int shape = -1; shape <= 1; shape++) {
				String what = group + " shape " + shape;
				Assert.assertEquals(what, 0, group.ease(shape, 0), 0);
				Assert.assertEquals(what, 1, group.ease(shape, 1), 0);
				Assert.assertEquals(what, 0, group.ease(shape, -.5f), 0);
				Assert.assertEquals(what, 1, group.ease(shape, 1.5f), 0);
				float previous = 0;
				for (int i = 0; i <= 1000; i++) {
					float e = group.ease(shape, i / 1000f);
					Assert.assertTrue(what + " decreases at p = " + i / 1000f, e >= previous);
					previous = e;
				}
			}
		}
	}

	private interface FloatFunction {
		float apply(float x);
	}

	/** Smallest p in [0, 1] with f(p) >= target, for non-decreasing f. */
	private static float solve(FloatFunction f, float target) {
		float lo = 0, hi = 1;
		for (int i = 0; i < 60; i++) {
			float mid = (lo + hi) / 2;
			if (f.apply(mid) < target) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return hi;
	}

	@Test
	public void transitionWindowsNeverOverlap() {
		int checked = 0;
		for (var climate : data.climates.values()) {
			for (var season : SEASONS) {
				if (season != SeasonalTheme.SUMMER && !climate.hasSeasonalLadder(season))
					continue;
				var zone = new WeatherZone(climate, season);
				Segment a = timeline.segmentAt(BASE_PHASE);
				Segment b = timeline.next(a);
				Segment c = timeline.next(b);
				while (b.start < BASE_PHASE + 365 * DAY_MS) {
					long incoming = zone.halfWidthMs(a, b);
					long outgoing = zone.halfWidthMs(b, c);
					Assert.assertTrue(zone + ": empty window at " + b, incoming > 0 && outgoing > 0);
					Assert.assertTrue(
						zone + ": windows of " + incoming + " and " + outgoing + " ms overlap within " + b,
						incoming + outgoing <= b.duration());
					a = b;
					b = c;
					c = timeline.next(c);
					checked++;
				}
			}
		}
		Assert.assertTrue(checked > 100_000);

		// The blended windows in the strip itself: each window ends before the next one begins
		var rng = new Random(0x0E1A);
		var sampler = new WeatherSampler();
		var strip = new WeatherStrip();
		for (var mix : new Mix[] { single, twoZone, threeZone }) {
			for (int n = 0; n < 2000; n++) {
				long phase = randomPhase(rng, n);
				mix.sample(sampler, phase, strip);
				for (int i = 0; i < WeatherStrip.SIZE - 2; i++) {
					float end = strip.edgeDistance[i] + strip.edgeWidth[i] / 2;
					float nextStart = strip.edgeDistance[i + 1] - strip.edgeWidth[i + 1] / 2;
					Assert.assertTrue(mix.name + ": strip windows " + i + " and " + (i + 1) + " overlap at phase " + phase,
						end <= nextStart + .1f);
				}
			}
		}
	}

	@Test
	public void stripDescribesTheSurroundingSegments() {
		var rng = new Random(0x5E6);
		var sampler = new WeatherSampler();
		var strip = new WeatherStrip();
		// Labelled by its minor zone, so labels taken from the wrong zone would show
		var mix = twoZoneMasked;
		var reference = new Reference(mix);
		final int c = WeatherStrip.CENTER;
		int insideWindows = 0;
		for (int n = 0; n < 2000; n++) {
			long phase = n < 1000 ? randomPhase(rng, n) : nearBoundary(rng);
			mix.sample(sampler, phase, strip);
			Assert.assertTrue(strip.segmentStart[c] <= phase && phase < strip.segmentEnd[c]);
			Segment segment = timeline.segmentAt(phase);
			Segment current = segment;
			for (int m = c; m > 0; m--)
				segment = timeline.previous(segment);
			for (int m = 0; m < WeatherStrip.SIZE; m++) {
				Assert.assertEquals(segment.start, strip.segmentStart[m]);
				Assert.assertEquals(segment.end, strip.segmentEnd[m]);
				Assert.assertEquals(segment.seed, strip.seeds[m]);
				Assert.assertSame(typeOf(mix.label, segment), strip.types[m]);
				Assert.assertEquals(intensityOf(mix.label, segment), strip.intensities[m], 1e-6);
				if (m < WeatherStrip.SIZE - 1) {
					Assert.assertEquals(strip.segmentEnd[m], strip.segmentStart[m + 1]);
					double distance = (strip.segmentStart[m + 1] - phase) / MS_PER_UNIT;
					Assert.assertEquals(distance, strip.edgeDistance[m], Math.max(1e-3, Math.abs(distance) * 1e-6));
					double width = 2 * reference.halfWidth(segment, timeline.next(segment)) / MS_PER_UNIT;
					Assert.assertEquals(width, strip.edgeWidth[m], width * 1e-5);
				}
				segment = timeline.next(segment);
			}
			Assert.assertEquals(timeline.driftX(), strip.axisX, 0);
			Assert.assertEquals(timeline.driftZ(), strip.axisZ, 0);

			// Progress through the windows on either side of the camera's segment, as the labels read it at u = 0
			double incoming = reference.halfWidth(timeline.previous(current), current);
			double outgoing = reference.halfWidth(current, timeline.next(current));
			double expectedIncoming = Math.min(1, (phase - current.start + incoming) / (2 * incoming));
			double expectedOutgoing = Math.max(0, (phase - current.end + outgoing) / (2 * outgoing));
			Assert.assertEquals(expectedIncoming, strip.edgeProgress(c - 1, 0), 1e-4);
			Assert.assertEquals(expectedOutgoing, strip.edgeProgress(c, 0), 1e-4);
			if (expectedIncoming < 1 || expectedOutgoing > 0)
				insideWindows++;
		}
		Assert.assertTrue("some cameras should be inside transition windows", insideWindows > 200);
		// Upwind is against the direction fronts travel
		Assert.assertEquals(-1, strip.upwindDistance(strip.axisX, strip.axisZ), 1e-6);
	}

	/** A phase within a few minutes of a segment boundary. */
	private static long nearBoundary(Random rng) {
		long boundary = timeline.segmentAt(BASE_PHASE + (long) (rng.nextDouble() * 365 * DAY_MS)).end;
		return boundary + (long) ((rng.nextDouble() * 2 - 1) * 15 * MINUTE_MS);
	}

	/**
	 * When the camera crosses a segment boundary the strip's entries shift by one, but the profile at every world
	 * position must stay the same, whether the camera moves forward or backward in phase.
	 */
	@Test
	public void stripReindexingPreservesTheProfile() {
		var rng = new Random(0x4E1D);
		float shift = (float) (1 / MS_PER_UNIT);
		for (var mix : new Mix[] { single, twoZone }) {
			var sampler = new WeatherSampler();
			var before = new WeatherStrip();
			var after = new WeatherStrip();
			var forward = new Worst();
			var backward = new Worst();
			for (int n = 0; n < 250; n++) {
				long boundary = timeline.segmentAt(randomPhase(rng, n)).end;

				// Forward: the camera's segment changes from k - 1 to k
				mix.sample(sampler, boundary - 1, before);
				mix.sample(sampler, boundary, after);
				Assert.assertEquals(boundary, after.segmentStart[WeatherStrip.CENTER]);
				Assert.assertEquals(boundary, before.segmentEnd[WeatherStrip.CENTER]);
				for (int m = 0; m < WeatherStrip.SIZE - 1; m++) {
					Assert.assertArrayEquals(before.params[m + 1], after.params[m], 0);
					Assert.assertEquals(before.segmentStart[m + 1], after.segmentStart[m]);
					Assert.assertSame(before.types[m + 1], after.types[m]);
				}
				for (int i = 0; i < WeatherStrip.SIZE - 2; i++) {
					Assert.assertEquals(before.edgeWidth[i + 1], after.edgeWidth[i], 0);
					Assert.assertEquals(before.edgeShape[i + 1], after.edgeShape[i]);
				}
				compareProfiles(before, after, shift, boundary, forward);

				// Backward: from k back to k - 1, reusing the same sampler and strips
				mix.sample(sampler, boundary, after);
				mix.sample(sampler, boundary - 1, before);
				compareProfiles(before, after, shift, boundary, backward);
			}
			forward.assertWithin(mix.name + ", forward", TOLERANCE);
			backward.assertWithin(mix.name + ", backward", TOLERANCE);
		}
	}

	/** A world position u units upwind of the earlier origin is u - shift units upwind of the later one. */
	private static void compareProfiles(WeatherStrip earlier, WeatherStrip later, float shift, long boundary, Worst worst) {
		for (float u = U_MIN; u <= U_MAX; u += GRID_STEP / 2) {
			for (int slot = 0; slot < VECTOR_SIZE; slot++)
				worst.track(Math.abs(earlier.evaluateSlot(slot, u) - later.evaluateSlot(slot, u - shift)), boundary, u, slot);
		}
	}

	@Test
	public void coverageInvariantHolds() throws IOException {
		Assert.assertTrue(coverageUnits(30) >= requiredCoverageUnits());
		Assert.assertEquals(146_880, coverageUnits(30), 1);
		Assert.assertEquals(128_000, requiredCoverageUnits(), 0);
		Assert.assertTrue(coverageUnits(data.timeline.settings.minSegmentMinutes) >= requiredCoverageUnits());

		// Empirically, for every shipped climate and season over a year: wherever the camera is within a segment, the
		// strip reproduces the timeline far enough both ways. Upwind, the strip ends where the window between its last
		// entry and the segment after it begins; downwind, where the window before its first entry ends.
		for (var climate : data.climates.values()) {
			for (var season : SEASONS) {
				if (season != SeasonalTheme.SUMMER && !climate.hasSeasonalLadder(season))
					continue;
				var zone = new WeatherZone(climate, season);
				Segment[] window = new Segment[9]; // k-4 ... k+4
				window[0] = timeline.segmentAt(BASE_PHASE);
				for (int i = 1; i < window.length; i++)
					window[i] = timeline.next(window[i - 1]);
				while (window[4].start < BASE_PHASE + 365 * DAY_MS) {
					Segment k = window[4];
					long upwindEdge = window[7].end - zone.halfWidthMs(window[7], window[8]);
					long downwindEdge = window[1].start + zone.halfWidthMs(window[0], window[1]);
					double upwind = (upwindEdge - (k.end - 1)) / MS_PER_UNIT;
					double downwind = (k.start - downwindEdge) / MS_PER_UNIT;
					Assert.assertTrue(zone + ": upwind coverage " + upwind + " at " + k, upwind >= U_MAX);
					Assert.assertTrue(zone + ": downwind coverage " + downwind + " at " + k, downwind >= -U_MIN);
					System.arraycopy(window, 1, window, 0, window.length - 1);
					window[window.length - 1] = timeline.next(window[window.length - 2]);
				}
			}
		}

		// The loader rejects a minimum segment too short for the strip to cover the visible sky
		JsonObject json = WeatherTestData.loadShippedJson();
		var segmentMinutes = new JsonArray();
		segmentMinutes.add(20);
		segmentMinutes.add(90);
		json.getAsJsonObject("timeline").add("segmentMinutes", segmentMinutes);
		try {
			WeatherData.parse(json);
			Assert.fail("a 20 minute minimum segment should be rejected");
		} catch (IOException ex) {
			Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("minimum segment length"));
		}
	}
}
