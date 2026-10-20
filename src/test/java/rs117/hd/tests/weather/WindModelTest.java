package rs117.hd.tests.weather;

import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.weather.SteeringFlow;
import rs117.hd.scene.weather.WeatherParam;
import rs117.hd.scene.weather.WeatherState;
import rs117.hd.scene.weather.WindModel;

import static rs117.hd.scene.weather.WeatherConstants.*;
import static rs117.hd.utils.MathUtils.TWO_PI;
import static rs117.hd.utils.MathUtils.mod;

/**
 * Surface wind: weather modulation on top of the baseline, shortest-arc angle blending, gusts, the wind offsets and
 * their rebasing, and the baseline easing that replaced the double-blended environment wind.
 * <p>
 * Expected angles are computed independently in double precision and compared along the shortest arc.
 */
public class WindModelTest {
	private static final double TAU = 2 * Math.PI;
	private static final double DEG = Math.PI / 180;
	/** 2026-01-01T00:00Z. */
	private static final long WALL_2026 = 1_767_225_600_000L;
	private static final long DAY_MS = 86_400_000L;
	/** The targetKey bits WeatherManager passes to updateBaseline: exposed to the weather, environment authors no wind. */
	private static final int KEY_EXPOSED = 1;
	private static final int KEY_UNAUTHORED = 2;
	private static final float FRAME = 1 / 60f;

	private static final int WIND_STRENGTH = WeatherParam.WIND_STRENGTH.offset();
	private static final int WIND_SPEED = WeatherParam.WIND_SPEED.offset();
	private static final int GUSTINESS = WeatherParam.GUSTINESS.offset();
	private static final int WIND_ALIGN = WeatherParam.WIND_ALIGN.offset();

	// ---------------------------------------------------------------------------------------------------------------
	// modulate
	// ---------------------------------------------------------------------------------------------------------------

	@Test
	public void exposureZeroReproducesTheBaselineExactly() {
		Random rng = new Random(0x117_0001L);
		var out = new WeatherState.SurfaceWind();
		for (int i = 0; i < 20_000; i++) {
			// Un-normalised angles, and speeds and strengths well above the maxima
			float baseAngle = (rng.nextFloat() * 6 - 3) * TWO_PI;
			float baseSpeed = rng.nextFloat() * 1.5f * WIND_SPEED_MAX;
			float baseStrength = rng.nextFloat() * 2.5f * WIND_STRENGTH_MAX;
			float ceiling = 64 + rng.nextFloat() * 4000;
			float[] params = randomParams(rng);
			float gust = rng.nextFloat();
			var upper = upperWind(rng.nextFloat() * TWO_PI, (float) ((rng.nextDouble() * 2 - 1) * 40 * DEG));

			WindModel.modulate(out, baseAngle, baseSpeed, baseStrength, ceiling, 0, params, upper, gust);

			int sample = i;
			Supplier<String> ctx = () -> String.format(Locale.ROOT,
				"sample %d: angle=%s speed=%s strength=%s gust=%s upper=(%s, dev %s) params=%s",
				sample, baseAngle, baseSpeed, baseStrength, gust, upper.angle, upper.deviation, describeWind(params));
			float expectedAngle = WindModel.wrapAngle(baseAngle);
			assertExact("windAngle", expectedAngle, out.windAngle, ctx);
			assertExact("windSpeed", baseSpeed, out.windSpeed, ctx);
			assertExact("windStrength", baseStrength, out.windStrength, ctx);
			assertExact("windCeiling", ceiling, out.windCeiling, ctx);
			assertExact("windDirectionX", (float) Math.cos(expectedAngle), out.windDirectionX, ctx);
			assertExact("windDirectionZ", (float) Math.sin(expectedAngle), out.windDirectionZ, ctx);
			assertExact("effective gustiness", 0, out.gustiness, ctx);
			assertExact("published baseAngle", expectedAngle, out.baseAngle, ctx);
			assertExact("published baseSpeed", baseSpeed, out.baseSpeed, ctx);
			assertExact("published baseStrength", baseStrength, out.baseStrength, ctx);
		}
	}

	@Test
	public void windAlignTurnsTheSurfaceWindTowardTheUpperWind() {
		var out = new WeatherState.SurfaceWind();

		// The critique's case: a desert baseline of 270 degrees under an upper wind of 40 degrees, deviating +20
		// degrees from the drift direction
		var upper = upperWind((float) (60 * DEG), (float) (20 * DEG));
		double veered = 270 + VEER_FRACTION * 20;
		WindModel.modulate(out, (float) (270 * DEG), 15, 14, 1280, 1, windParams(1, 1, 0, 0), upper, .5f);
		assertAngle("windAlign 0 keeps the veered baseline", veered * DEG, out.windAngle, 1e-5);
		WindModel.modulate(out, (float) (270 * DEG), 15, 14, 1280, 1, windParams(1, 1, 0, 1), upper, .5f);
		assertAngle("windAlign 1 gives the upper wind angle", 60 * DEG, out.windAngle, 1e-5);
		WindModel.modulate(out, (float) (270 * DEG), 15, 14, 1280, 1, windParams(1, 1, 0, .5f), upper, .5f);
		assertAngle("windAlign 0.5 turns halfway, along the shortest arc", mixAngleRef(veered * DEG, 60 * DEG, .5),
			out.windAngle, 1e-5);
		// Both the veer and the alignment are scaled by exposure
		WindModel.modulate(out, (float) (270 * DEG), 15, 14, 1280, .5f, windParams(1, 1, 0, 1), upper, .5f);
		assertAngle("exposure 0.5 halves the veer and the alignment",
			mixAngleRef((270 + .5 * VEER_FRACTION * 20) * DEG, 60 * DEG, .5), out.windAngle, 1e-5);

		// Randomised, with upper winds from a real steering flow
		Random rng = new Random(0x117_0002L);
		var flow = new SteeringFlow(0x5EED_0001L, (float) (40 * DEG));
		var steering = new WeatherState.UpperWind();
		for (int i = 0; i < 5_000; i++) {
			flow.evaluate(WALL_2026 + (long) (rng.nextDouble() * 365 * DAY_MS), steering);
			float baseAngle = (rng.nextFloat() * 4 - 2) * TWO_PI;
			float baseSpeed = rng.nextFloat() * WIND_SPEED_MAX;
			float baseStrength = rng.nextFloat() * WIND_STRENGTH_MAX;
			float[] params = randomParams(rng);
			float gust = rng.nextFloat();
			String ctx = "sample " + i + ", baseline " + baseAngle + ", upper " + steering.angle + " (dev " + steering.deviation + ")";

			params[WIND_ALIGN] = 1;
			WindModel.modulate(out, baseAngle, baseSpeed, baseStrength, 1280, 1, params, steering, gust);
			double veeredAngle = baseAngle + VEER_FRACTION * (double) steering.deviation;
			double toUpper = angleDiffRef(steering.angle, veeredAngle);
			if (Math.abs(toUpper) <= WIND_ALIGN_FADE_START * Math.PI) {
				assertAngle(ctx + ": windAlign 1", steering.angle, out.windAngle, 1e-5);
			} else {
				// Near the antipode the alignment fades out, so the wind stays between the veered baseline and the upper wind
				double turned = angleDiffRef(out.windAngle, veeredAngle);
				Assert.assertTrue(ctx + ": turns toward the upper wind, never past it",
					Math.signum(turned) == Math.signum(toUpper) || Math.abs(turned) < 1e-4);
				Assert.assertTrue(ctx + ": turns less than the full way", Math.abs(turned) <= Math.abs(toUpper) + 1e-4);
			}
			assertDirectionMatchesAngle(ctx, out);

			params[WIND_ALIGN] = 0;
			WindModel.modulate(out, baseAngle, baseSpeed, baseStrength, 1280, 1, params, steering, gust);
			assertAngle(ctx + ": windAlign 0", baseAngle + VEER_FRACTION * (double) steering.deviation, out.windAngle, 1e-5);
			assertDirectionMatchesAngle(ctx, out);
			Assert.assertEquals(ctx + ": the published baseline angle is the un-veered baseline",
				WindModel.wrapAngle(baseAngle), out.baseAngle, 0);
		}
	}

	@Test
	public void windAlignNeverFlipsWhenTheUpperWindIsOpposite() {
		// Sweeping the upper wind across the point opposite the baseline must not flip the surface wind around
		var out = new WeatherState.SurfaceWind();
		for (float align : new float[] { .1f, .3f, .5f, .9f, 1 }) {
			for (double baseDeg : new double[] { 200, 225, 240, 10 }) {
				float base = (float) (baseDeg * DEG);
				float previous = Float.NaN;
				for (double offset = 150; offset <= 210; offset += .01) {
					var upper = upperWind((float) ((baseDeg + offset) * DEG), 0);
					WindModel.modulate(out, base, 15, 50, 1280, 1, windParams(1, 1, 0, align), upper, .5f);
					if (!Float.isNaN(previous)) {
						double step = Math.abs(angleDiffRef(out.windAngle, previous)) / DEG;
						Assert.assertTrue(String.format("align %.1f, base %.0f, offset %.2f: stepped %.3f degrees", align, baseDeg, offset, step),
							step < .15);
					}
					previous = out.windAngle;
				}
			}
		}
	}

	@Test
	public void windAlignMatchesTheShortestArcAwayFromTheAntipode() {
		var out = new WeatherState.SurfaceWind();
		Random rng = new Random(0x117_0005L);
		for (int i = 0; i < 20_000; i++) {
			float base = rng.nextFloat() * TWO_PI;
			float upperAngle = rng.nextFloat() * TWO_PI;
			float align = rng.nextFloat();
			float exposure = rng.nextFloat();
			var upper = upperWind(upperAngle, 0);
			double toUpper = angleDiffRef(upperAngle, base);
			if (Math.abs(toUpper) > WIND_ALIGN_FADE_START * Math.PI)
				continue;
			WindModel.modulate(out, base, 15, 50, 1280, exposure, windParams(1, 1, 0, align), upper, .5f);
			assertAngle("sample " + i, mixAngleRef(base, upperAngle, exposure * align), out.windAngle, 2e-5);
		}
	}

	@Test
	public void shippedDesertWindFollowsTheFrontsWithTheRealSteeringFlow() {
		// The desert's 270 degree baseline under the 40 degree drift never gets close enough to the antipode to fade,
		// so sandstorms fully align with the upper wind
		var out = new WeatherState.SurfaceWind();
		var flow = new SteeringFlow(0x5EED_0007L, (float) (40 * DEG));
		var upper = new WeatherState.UpperWind();
		float base = (float) (270 * DEG);
		for (long t = 0; t < 365 * DAY_MS; t += 60_000) {
			flow.evaluate(WALL_2026 + t, upper);
			double veered = base + VEER_FRACTION * (double) upper.deviation;
			for (float align : new float[] { .75f, .9f, 1 }) {
				WindModel.modulate(out, base, 15, 66, 1280, 1, windParams(2, 1.8f, .8f, align), upper, .5f);
				assertAngle("t=" + t + ", align " + align, mixAngleRef(veered, upper.angle, align), out.windAngle, 2e-5);
			}
		}
	}

	@Test
	public void wrapAngleNeverReturnsTwoPi() {
		Assert.assertEquals(0, WindModel.wrapAngle(-1e-8f), 0);
		Assert.assertEquals(0, WindModel.wrapAngle(TWO_PI), 0);
		Assert.assertEquals(0, WindModel.mixAngle(1.0118648f, -1.374042E-7f, 1), 0);
		Random rng = new Random(0x117_0006L);
		for (int i = 0; i < 100_000; i++) {
			float a = (rng.nextFloat() * 2 - 1) * 1e-5f + (rng.nextInt(9) - 4) * TWO_PI;
			float wrapped = WindModel.wrapAngle(a);
			Assert.assertTrue("wrapAngle(" + a + ") = " + wrapped, wrapped >= 0 && wrapped < TWO_PI);
		}
	}

	@Test
	public void offsetsRecoverFromNonFiniteWind() {
		var model = new WindModel();
		var wind = new WeatherState.SurfaceWind();
		wind.set(0, Float.POSITIVE_INFINITY, 50, 1280);
		model.integrate(wind, 1 / 60f, false);
		wind.set(Float.NaN, 15, 50, 1280);
		model.integrate(wind, 1 / 60f, false);
		wind.set(0, 15, 50, 1280);
		float before = wind.windOffset;
		for (int i = 0; i < 600; i++)
			model.integrate(wind, 1 / 60f, i == 300);
		Assert.assertTrue("the offset is finite", Float.isFinite(wind.windOffset));
		Assert.assertTrue("the direction-aware offsets are finite", Float.isFinite(wind.windOffsetX) && Float.isFinite(wind.windOffsetZ));
		Assert.assertEquals("the offset advances normally again", 150, wind.windOffset - before, .1);

		// Offsets too large to advance in double precision are reset
		wind.windOffsetExact = 1e18;
		model.integrate(wind, 1 / 60f, false);
		Assert.assertTrue("huge offsets are reset", Math.abs(wind.windOffsetExact) < 1);
	}

	@Test
	public void strengthAndSpeedAreClampedButNeverBelowTheBaseline() {
		var out = new WeatherState.SurfaceWind();
		var upper = upperWind(0, 0);
		// The largest multipliers with full gustiness, at the gust peak: 85 x 3 x 1.5 and 30 x 3 x 1.25
		float[] storm = windParams(3, 3, 1, 0);
		WindModel.modulate(out, 0, 30, 85, 1280, 1, storm, upper, 1);
		Assert.assertEquals("storm strength is clamped at the maximum", WIND_STRENGTH_MAX, out.windStrength, 0);
		Assert.assertEquals("storm speed is clamped at the maximum", WIND_SPEED_MAX, out.windSpeed, 0);

		// A baseline above the maximum is never cut down to the maximum
		WindModel.modulate(out, 0, 55, 150, 1280, 1, storm, upper, 1);
		Assert.assertEquals("a strength baseline above the maximum is kept", 150, out.windStrength, 0);
		Assert.assertEquals("a speed baseline above the maximum is kept", 55, out.windSpeed, 0);

		// Calm weather may still scale such a baseline down; that is the multiplier, not the clamp
		WindModel.modulate(out, 0, 55, 150, 1280, 1, windParams(.5f, .5f, 0, 0), upper, .5f);
		Assert.assertEquals(75, out.windStrength, 1e-4);
		Assert.assertEquals(27.5, out.windSpeed, 1e-4);

		// Randomised: never negative, never above max(maximum, baseline), and moving monotonically from the baseline
		// to the fully exposed value as exposure increases
		Random rng = new Random(0x117_0003L);
		for (int i = 0; i < 20_000; i++) {
			float baseSpeed = rng.nextFloat() * 2 * WIND_SPEED_MAX;
			float baseStrength = rng.nextFloat() * 2 * WIND_STRENGTH_MAX;
			float[] params = randomParams(rng);
			float gust = rng.nextFloat();
			var up = upperWind(rng.nextFloat() * TWO_PI, (float) ((rng.nextDouble() * 2 - 1) * 40 * DEG));

			WindModel.modulate(out, 0, baseSpeed, baseStrength, 1280, 1, params, up, gust);
			float fullSpeed = out.windSpeed;
			float fullStrength = out.windStrength;
			for (int k = 0; k <= 10; k++) {
				float exposure = k / 10f;
				WindModel.modulate(out, 0, baseSpeed, baseStrength, 1280, exposure, params, up, gust);
				String ctx = String.format(Locale.ROOT, "sample %d, exposure %.1f: base speed %s, base strength %s, gust %s, %s",
					i, exposure, baseSpeed, baseStrength, gust, describeWind(params));
				assertWithin(ctx + ": strength", out.windStrength, 0, Math.max(WIND_STRENGTH_MAX, baseStrength));
				assertWithin(ctx + ": speed", out.windSpeed, 0, Math.max(WIND_SPEED_MAX, baseSpeed));
				assertWithin(ctx + ": strength between baseline and full exposure", out.windStrength,
					Math.min(baseStrength, fullStrength), Math.max(baseStrength, fullStrength));
				assertWithin(ctx + ": speed between baseline and full exposure", out.windSpeed,
					Math.min(baseSpeed, fullSpeed), Math.max(baseSpeed, fullSpeed));
			}
		}
	}

	@Test
	public void gustinessScalesTheGustModulation() {
		var out = new WeatherState.SurfaceWind();
		var upper = upperWind(0, 0);
		float[] gusty = windParams(1.5f, 2, 1, 0);
		// An average gust leaves just the multipliers: 40 x 1.5 and 10 x 2
		WindModel.modulate(out, 0, 10, 40, 1280, 1, gusty, upper, .5f);
		Assert.assertEquals(60, out.windStrength, 1e-4);
		Assert.assertEquals(20, out.windSpeed, 1e-4);
		// Gust peaks and lulls swing strength by +-GUST_STRENGTH_DEPTH and speed by +-GUST_SPEED_DEPTH
		WindModel.modulate(out, 0, 10, 40, 1280, 1, gusty, upper, 1);
		Assert.assertEquals(60 * (1 + GUST_STRENGTH_DEPTH), out.windStrength, 1e-4);
		Assert.assertEquals(20 * (1 + GUST_SPEED_DEPTH), out.windSpeed, 1e-4);
		Assert.assertEquals("the gust envelope is published", 1, out.gust, 0);
		Assert.assertEquals("the effective gustiness is published", 1, out.gustiness, 0);
		WindModel.modulate(out, 0, 10, 40, 1280, 1, gusty, upper, 0);
		Assert.assertEquals(60 * (1 - GUST_STRENGTH_DEPTH), out.windStrength, 1e-4);
		Assert.assertEquals(20 * (1 - GUST_SPEED_DEPTH), out.windSpeed, 1e-4);
		// Without gustiness, gusts change nothing
		for (float gust : new float[] { 0, .3f, 1 }) {
			WindModel.modulate(out, 0, 10, 40, 1280, 1, windParams(1.5f, 2, 0, 0), upper, gust);
			Assert.assertEquals("gust " + gust + " without gustiness", 60, out.windStrength, 1e-4);
			Assert.assertEquals("gust " + gust + " without gustiness", 20, out.windSpeed, 1e-4);
		}
		// The published gustiness is scaled by exposure
		WindModel.modulate(out, 0, 10, 40, 1280, .5f, gusty, upper, 1);
		Assert.assertEquals(.5f, out.gustiness, 1e-6);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Angles
	// ---------------------------------------------------------------------------------------------------------------

	@Test
	public void mixAngleTakesTheShortestArc() {
		assertAngle("350 -> 10 at 0.5", 0, WindModel.mixAngle(rad(350), rad(10), .5f), 1e-5);
		assertAngle("10 -> 350 at 0.5", 0, WindModel.mixAngle(rad(10), rad(350), .5f), 1e-5);
		assertAngle("270 -> 40 at 0.5", 335 * DEG, WindModel.mixAngle(rad(270), rad(40), .5f), 1e-5);
		assertAngle("40 -> 270 at 0.5", 335 * DEG, WindModel.mixAngle(rad(40), rad(270), .5f), 1e-5);
		assertAngle("270 -> 40 at 0", 270 * DEG, WindModel.mixAngle(rad(270), rad(40), 0), 1e-5);
		assertAngle("270 -> 40 at 1", 40 * DEG, WindModel.mixAngle(rad(270), rad(40), 1), 1e-5);
		assertAngle("un-normalised -90 -> 400 at 0.5", 335 * DEG, WindModel.mixAngle(rad(-90), rad(400), .5f), 1e-5);
		assertAngle("270 -> 40 at 0.25", 302.5 * DEG, WindModel.mixAngle(rad(270), rad(40), .25f), 1e-5);
		assertAngle("270 -> 40 at 0.75", 7.5 * DEG, WindModel.mixAngle(rad(270), rad(40), .75f), 1e-5);

		// Randomised: the result moves linearly along the shorter arc and is always normalised
		Random rng = new Random(0x117_0004L);
		for (int i = 0; i < 50_000; i++) {
			float from = (float) ((rng.nextDouble() * 6 - 3) * TAU);
			float to = (float) ((rng.nextDouble() * 6 - 3) * TAU);
			if (Math.abs(signedDiff(to, from)) > Math.PI - 1e-3)
				continue; // (nearly) opposite: either way round is equally short
			float t = rng.nextFloat();
			float mixed = WindModel.mixAngle(from, to, t);
			assertAngle("sample " + i + ": mixAngle(" + from + ", " + to + ", " + t + ")",
				mixAngleRef(from, to, t), mixed, 1e-5);
		}
	}

	@Test
	public void environmentInterpolateTakesTheShortestArc() {
		Environment from = Environment.DEFAULT.copy();
		from.windAngle = rad(270);
		Environment to = Environment.DEFAULT.copy();
		to.windAngle = rad(40);
		Environment current = Environment.DEFAULT.copy();

		current.interpolate(from, to, .5f);
		assertAngle("interpolate(270, 40, 0.5)", 335 * DEG, current.windAngle, 1e-5);
		current.interpolate(to, from, .5f);
		assertAngle("interpolate(40, 270, 0.5)", 335 * DEG, current.windAngle, 1e-5);

		// The whole transition stays on the 130 degree arc through north-east, never sweeping 230 degrees round
		// through the south
		for (int i = 0; i <= 100; i++) {
			float t = i / 100f;
			current.interpolate(from, to, t);
			assertAngle("interpolate(270, 40, " + t + ")", (270 + 130 * t) * DEG, current.windAngle, 2e-5);
		}

		// The other wind fields still interpolate linearly
		from.windStrength = 50;
		to.windStrength = 10;
		from.windSpeed = 14;
		to.windSpeed = 22;
		current.interpolate(from, to, .25f);
		Assert.assertEquals(40, current.windStrength, 1e-4);
		Assert.assertEquals(16, current.windSpeed, 1e-4);

		// copyTo (and copy) keep the direction; normalised angles are kept exactly
		for (double degrees : new double[] { 0, 40, 270, 359.5, -90, 400, -705, 1000 }) {
			Environment source = Environment.DEFAULT.copy();
			source.windAngle = rad(degrees);
			Environment target = Environment.DEFAULT.copy();
			target.windAngle = rad(123);
			source.copyTo(target);
			assertAngle("copyTo with windAngle " + degrees, source.windAngle, target.windAngle, 1e-5);
			assertAngle("copy with windAngle " + degrees, source.windAngle, source.copy().windAngle, 1e-5);
			if (degrees >= 0 && degrees < 360)
				Assert.assertEquals("copyTo with windAngle " + degrees, source.windAngle, target.windAngle, 0);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Gusts
	// ---------------------------------------------------------------------------------------------------------------

	@Test
	public void gustIsDeterministicBoundedAndVariesOverTime() {
		Random rng = new Random(0x117_0005L);
		for (int i = 0; i < 100_000; i++) {
			// Within 10 years either side of 2026, including times before the noise origin
			long wallMs = WALL_2026 + (long) ((rng.nextDouble() * 2 - 1) * 3650 * DAY_MS);
			long seed = i % 4 == 0 ? 0 : rng.nextLong();
			float a = WindModel.gust(wallMs, seed);
			float b = WindModel.gust(wallMs, seed);
			Assert.assertEquals("gust(" + wallMs + ", " + seed + ") is deterministic",
				Float.floatToRawIntBits(a), Float.floatToRawIntBits(b));
			Assert.assertTrue("gust(" + wallMs + ", " + seed + ") = " + a + " is outside [0, 1]", a >= 0 && a <= 1);
		}

		// Sampled at 60 fps for an hour: it spans most of its range, changes within every 10-second window, and never
		// jumps between consecutive frames
		long seed = 0x6A5EEDL;
		long frameMs = 16;
		float min = 1, max = 0;
		float previous = WindModel.gust(WALL_2026, seed);
		double largestStep = 0;
		float windowMin = previous, windowMax = previous;
		double smallestWindowRange = Double.POSITIVE_INFINITY;
		for (long ms = frameMs; ms <= 3_600_000; ms += frameMs) {
			float g = WindModel.gust(WALL_2026 + ms, seed);
			min = Math.min(min, g);
			max = Math.max(max, g);
			largestStep = Math.max(largestStep, Math.abs(g - previous));
			previous = g;
			windowMin = Math.min(windowMin, g);
			windowMax = Math.max(windowMax, g);
			if (ms % 10_000 < frameMs) {
				smallestWindowRange = Math.min(smallestWindowRange, windowMax - windowMin);
				windowMin = windowMax = g;
			}
		}
		Assert.assertTrue("gusts should span most of [0, 1] over an hour, but ranged " + min + " - " + max, max - min > .5);
		Assert.assertTrue("gusts should change within every 10 s, smallest range " + smallestWindowRange, smallestWindowRange > .01);
		Assert.assertTrue("gusts should be smooth between frames, largest step " + largestStep, largestStep < .05);

		// Different timeline seeds give different gust patterns
		int differing = 0;
		for (int i = 0; i < 1000; i++) {
			long wallMs = WALL_2026 + i * 1000L;
			if (WindModel.gust(wallMs, seed) != WindModel.gust(wallMs, seed + 1))
				differing++;
		}
		Assert.assertTrue("different seeds should give different gusts, but only " + differing + "/1000 differ", differing > 900);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Offsets
	// ---------------------------------------------------------------------------------------------------------------

	@Test
	public void offsetsAreFrameRateIndependent() {
		// Constant wind integrates to speed x time at any frame rate
		for (int fps : new int[] { 30, 144 }) {
			var model = new WindModel();
			var wind = new WeatherState.SurfaceWind();
			model.resetOffsets(wind);
			wind.set(rad(30), 15, 50, 1280);
			for (int f = 0; f < 60 * fps; f++)
				model.integrate(wind, 1f / fps, false);
			assertRelative("constant wind at " + fps + " fps", 900, wind.windOffset, 1e-3);
			assertRelative("constant wind X at " + fps + " fps", 900 * Math.cos(30 * DEG), wind.windOffsetX, 1e-3);
			assertRelative("constant wind Z at " + fps + " fps", 900 * Math.sin(30 * DEG), wind.windOffsetZ, 1e-3);
		}

		// Gusting, veering storm wind: 60 s at 30 fps and at 144 fps agree within 0.1%
		double[] slow = integrateStorm(30);
		double[] fast = integrateStorm(144);
		double distance = Math.max(slow[0], fast[0]);
		Assert.assertTrue("the storm wind should actually move the offsets, got " + distance, distance > 500);
		String[] names = { "windOffset", "windOffsetX", "windOffsetZ", "windOffsetExact" };
		for (int i = 0; i < names.length; i++) {
			double difference = Math.abs(slow[i] - fast[i]);
			Assert.assertTrue(String.format(Locale.ROOT, "%s differs between 30 fps (%.4f) and 144 fps (%.4f) by %.4f%%",
				names[i], slow[i], fast[i], 100 * difference / distance), difference < 1e-3 * distance);
		}
	}

	@Test
	public void offsetRebaseRules() {
		var model = new WindModel();
		var wind = new WeatherState.SurfaceWind();
		model.resetOffsets(wind);
		wind.set(rad(30), 1000, 50, 1280); // 1000 local units per second, so the thresholds are reached quickly

		// No rebase below the soft threshold, even on a scene change
		model.integrate(wind, 4, false);
		assertOffsets("4 s", wind, 4000);
		model.integrate(wind, .05f, true);
		assertOffsets("a scene change at 4050 must not rebase", wind, 4050);

		// Above the soft threshold, nothing rebases without a scene change, and the float offset keeps increasing
		model.integrate(wind, 1, false);
		assertOffsets("no scene change at 5050", wind, 5050);

		// A scene change above the soft threshold rebases the scalar and both direction-aware offsets together
		double exact = wind.windOffsetExact;
		double exactX = wind.windOffsetExactX;
		double exactZ = wind.windOffsetExactZ;
		model.integrate(wind, FRAME, true);
		Assert.assertEquals("windOffset after a soft rebase", 0, wind.windOffset, 0);
		Assert.assertEquals("windOffsetX after a soft rebase", 0, wind.windOffsetX, 0);
		Assert.assertEquals("windOffsetZ after a soft rebase", 0, wind.windOffsetZ, 0);
		double distance = 1000 * (double) FRAME;
		Assert.assertEquals("the exact offset is never rebased", exact + distance, wind.windOffsetExact, 1e-9);
		Assert.assertEquals("the exact X offset is never rebased", exactX + wind.windDirectionX * distance, wind.windOffsetExactX, 1e-9);
		Assert.assertEquals("the exact Z offset is never rebased", exactZ + wind.windDirectionZ * distance, wind.windOffsetExactZ, 1e-9);

		// The offsets grow again from the new origin, still consistent with each other
		model.integrate(wind, .5f, false);
		assertOffsets("0.5 s after the rebase", wind, 500);
		// The soft threshold is measured from the last rebase, so another scene change right away does nothing
		model.integrate(wind, .5f, true);
		assertOffsets("a scene change 1000 units after the rebase", wind, 1000);
		// Negative frame times never move the offsets backwards
		model.integrate(wind, -1, true);
		assertOffsets("a negative frame time", wind, 1000);

		// Far above the soft threshold without scene changes, the float offset increases continuously
		float previous = wind.windOffset;
		for (int i = 0; i < 500; i++) {
			model.integrate(wind, .5f, false);
			// Within float precision, which is 1/64 unit at 250,000
			Assert.assertEquals("step " + i + " without a scene change", previous + 500.0, wind.windOffset,
				2 * Math.ulp(wind.windOffset) + 1e-3);
			previous = wind.windOffset;
		}
		Assert.assertTrue(wind.windOffset > 200_000);
		model.integrate(wind, FRAME, true);
		Assert.assertEquals("a scene change far above the soft threshold rebases", 0, wind.windOffset, 0);

		// resetOffsets clears the rebase origin along with the offsets
		model.integrate(wind, 1, false);
		model.resetOffsets(wind);
		Assert.assertEquals(0, wind.windOffsetExact, 0);
		Assert.assertEquals(0, wind.windOffset, 0);
		model.integrate(wind, 1, false);
		assertOffsets("1 s after resetOffsets", wind, 1000);
		Assert.assertEquals(wind.windOffsetExact, wind.windOffset, 1e-3);
	}

	/** Renderers upload SurfaceWind before the first update, so its defaults must be valid (a zero direction or ceiling gives NaN). */
	@Test
	public void surfaceWindDefaultsMatchTheDefaultEnvironment() {
		var wind = new WeatherState.SurfaceWind();
		Assert.assertEquals(Environment.DEFAULT.windAngle, wind.windAngle, 0);
		Assert.assertEquals(Math.cos(wind.windAngle), wind.windDirectionX, 1e-6);
		Assert.assertEquals(Math.sin(wind.windAngle), wind.windDirectionZ, 1e-6);
		Assert.assertEquals(Environment.DEFAULT.windSpeed, wind.windSpeed, 0);
		Assert.assertEquals(Environment.DEFAULT.windStrength, wind.windStrength, 0);
		Assert.assertEquals(Environment.DEFAULT.windCeiling, wind.windCeiling, 0);
		Assert.assertTrue(wind.windCeiling > 0);
		Assert.assertEquals(0, wind.windOffset, 0);
	}

	@Test
	public void hardRebaseIsForcedWithoutASceneChange() {
		var model = new WindModel();
		var wind = new WeatherState.SurfaceWind();
		model.resetOffsets(wind);
		wind.set(rad(200), WIND_SPEED_MAX, 50, 1280);
		float dt = .1f;
		double step = wind.windSpeed * (double) dt;

		// Run realistic frames without any scene change up to just below 2^20: nothing rebases and the uploaded
		// float tracks the exact offset
		float previous = 0;
		int frames = 0;
		boolean decreased = false;
		while (wind.windOffsetExact + step < LEGACY_OFFSET_HARD_REBASE) {
			model.integrate(wind, dt, false);
			frames++;
			if (wind.windOffset < previous)
				decreased = true;
			previous = wind.windOffset;
		}
		Assert.assertFalse("the float offset must never decrease before the hard rebase", decreased);
		Assert.assertTrue("expected roughly 262,000 frames, got " + frames, frames > 260_000);
		Assert.assertEquals("no rebase below 2^20", wind.windOffsetExact, wind.windOffset, .125);
		Assert.assertEquals(wind.windOffsetExactX, wind.windOffsetX, .125);
		Assert.assertEquals(wind.windOffsetExactZ, wind.windOffsetZ, .125);

		// The frame that crosses 2^20 forces a rebase of all three offsets, still without a scene change
		model.integrate(wind, dt, false);
		Assert.assertTrue(wind.windOffsetExact - LEGACY_OFFSET_HARD_REBASE > 0);
		Assert.assertEquals("windOffset after the hard rebase", 0, wind.windOffset, 0);
		Assert.assertEquals("windOffsetX after the hard rebase", 0, wind.windOffsetX, 0);
		Assert.assertEquals("windOffsetZ after the hard rebase", 0, wind.windOffsetZ, 0);

		model.integrate(wind, dt, false);
		assertOffsets("one frame after the hard rebase", wind, step);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Baseline easing (regression for the double-blended environment wind)
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Crossing from an environment authoring 50 to one authoring no wind, where the climate supplies 50, must not dip.
	 * The double blend weighted the environment by (1 - p)^2 and gave 37.5 halfway through.
	 */
	@Test
	public void constantTargetAcrossAnEnvironmentTransitionDoesNotDip() {
		var model = new WindModel();
		double now = 100;
		model.updateBaseline(rad(40), 15, 50, KEY_EXPOSED, 1, 1, now, true);
		for (int f = 0; f < 30; f++)
			model.updateBaseline(rad(40), 15, 50, KEY_EXPOSED, 1, 1, now += FRAME, false);
		assertBaseline("settled", model, 40 * DEG, 15, 50);

		// Environment transition 2: the authored environment's wind is replaced by the identical climate wind, and
		// the target key flips (the unauthored bit) on the same frame
		int frames = 180;
		for (int f = 0; f <= frames + 30; f++) {
			float progress = environmentProgress(f, frames);
			model.updateBaseline(rad(40), 15, 50, KEY_EXPOSED | KEY_UNAUTHORED, 2, progress, now += FRAME, false);
			String ctx = "frame " + f + ", progress " + progress;
			Assert.assertEquals(ctx + ": strength must stay at 50", 50, model.baseStrength, 1e-5);
			Assert.assertEquals(ctx + ": speed must stay at 15", 15, model.baseSpeed, 1e-5);
			assertAngle(ctx + ": angle must stay at 40", 40 * DEG, model.baseAngle, 1e-5);
		}

		// The same holds for a transition between two different authored environments with the same wind
		for (int f = 0; f <= frames; f++) {
			float progress = environmentProgress(f, frames);
			model.updateBaseline(rad(40), 15, 50, KEY_EXPOSED, 3, progress, now += FRAME, false);
			Assert.assertEquals("transition 3, frame " + f, 50, model.baseStrength, 1e-5);
		}
	}

	@Test
	public void targetChangeAcrossAnEnvironmentTransitionFollowsTheProgressExactly() {
		var model = new WindModel();
		double now = 50;
		model.updateBaseline(rad(270), 14, 50, KEY_EXPOSED, 1, 1, now, true);
		model.updateBaseline(rad(270), 14, 50, KEY_EXPOSED, 1, 1, now += FRAME, false);

		int frames = 180;
		for (int f = 0; f <= frames; f++) {
			float p = environmentProgress(f, frames);
			model.updateBaseline(rad(40), 20, 0, KEY_EXPOSED, 2, p, now += FRAME, false);
			String ctx = "frame " + f + ", progress " + p;
			// mix(50, 0, p) and mix(14, 20, p), evaluated exactly as MathUtils.mix does
			Assert.assertEquals(ctx + ": strength", 50 * (1 - p) + 0 * p, model.baseStrength, 0);
			Assert.assertEquals(ctx + ": speed", 14 * (1 - p) + 20 * p, model.baseSpeed, 0);
			assertAngle(ctx + ": angle", mixAngleRef(270 * DEG, 40 * DEG, p), model.baseAngle, 1e-5);
		}
		assertBaseline("transition complete", model, 40 * DEG, 20, 0);

		// Lockstep follows the environment's progress, not the clock: holding the progress for ten seconds holds the
		// baseline too
		model.updateBaseline(rad(270), 14, 50, KEY_EXPOSED, 3, .5f, now += FRAME, false);
		for (int f = 0; f < 600; f++)
			model.updateBaseline(rad(270), 14, 50, KEY_EXPOSED, 3, .5f, now += FRAME, false);
		Assert.assertEquals("held at progress 0.5", 25, model.baseStrength, 1e-5);
		Assert.assertEquals("held at progress 0.5", 17, model.baseSpeed, 1e-5);
		assertAngle("held at progress 0.5", 335 * DEG, model.baseAngle, 1e-5);
		model.updateBaseline(rad(270), 14, 50, KEY_EXPOSED, 3, 1, now += FRAME, false);
		assertBaseline("progress 1", model, 270 * DEG, 14, 50);
		for (int f = 0; f < 10; f++)
			model.updateBaseline(rad(270), 14, 50, KEY_EXPOSED, 3, 1, now += FRAME, false);
		assertBaseline("after the transition", model, 270 * DEG, 14, 50);

		// A transition interrupting another eases on from the displayed value, without a jump
		model.updateBaseline(rad(40), 20, 0, KEY_EXPOSED, 4, .4f, now += FRAME, false);
		float interrupted = model.baseStrength;
		Assert.assertEquals(50 * (1 - .4f), interrupted, 1e-4);
		model.updateBaseline(rad(100), 10, 80, KEY_EXPOSED, 5, 0, now += FRAME, false);
		Assert.assertEquals("a new transition starts from the displayed value", interrupted, model.baseStrength, 0);
		model.updateBaseline(rad(100), 10, 80, KEY_EXPOSED, 5, .5f, now += FRAME, false);
		Assert.assertEquals(interrupted + (80 - interrupted) * .5f, model.baseStrength, 1e-4);
	}

	@Test
	public void targetKeyChangeWithoutTransitionRampsOverTheExposureRampTime() {
		var model = new WindModel();
		double now = 1000;
		model.updateBaseline(rad(40), 15, 50, KEY_EXPOSED, 7, 1, now, true);
		model.updateBaseline(rad(40), 15, 50, KEY_EXPOSED, 7, 1, now += FRAME, false);

		// Stepping indoors: the key changes, the environment does not transition
		double start = now + FRAME;
		int frames = (int) Math.ceil(EXPOSURE_RAMP_SECONDS * 60) + 60;
		for (int f = 0; f <= frames; f++) {
			now = start + f / 60.0;
			model.updateBaseline(rad(100), 10, 0, 0, 7, 1, now, false);
			double x = Math.min(1, Math.max(0, (now - start) / EXPOSURE_RAMP_SECONDS));
			double s = x * x * (3 - 2 * x);
			String ctx = String.format(Locale.ROOT, "%.3f s into the ramp", now - start);
			Assert.assertEquals(ctx + ": strength", 50 * (1 - s), model.baseStrength, 1e-4);
			Assert.assertEquals(ctx + ": speed", 15 + (10 - 15) * s, model.baseSpeed, 1e-4);
			assertAngle(ctx + ": angle", (40 + 60 * s) * DEG, model.baseAngle, 1e-5);
		}

		// Halfway through a ramp, the smoothstep is exactly halfway too
		model.updateBaseline(rad(100), 10, 80, KEY_EXPOSED, 7, 1, start = now + FRAME, false);
		model.updateBaseline(rad(100), 10, 80, KEY_EXPOSED, 7, 1, start + EXPOSURE_RAMP_SECONDS / 2, false);
		Assert.assertEquals("halfway through the ramp", 40, model.baseStrength, 1e-4);
		// Flipping the key back mid-ramp eases on from the displayed value, without a jump
		float displayed = model.baseStrength;
		now = start + EXPOSURE_RAMP_SECONDS / 2 + FRAME;
		model.updateBaseline(rad(100), 10, 0, 0, 7, 1, now, false);
		Assert.assertEquals("a key change mid-ramp starts from the displayed value", displayed, model.baseStrength, 0);
		model.updateBaseline(rad(100), 10, 0, 0, 7, 1, now + EXPOSURE_RAMP_SECONDS, false);
		Assert.assertEquals("the second ramp completes", 0, model.baseStrength, 0);

		// Once settled, continuous target changes (such as the climate cross-fade) are followed directly
		model.updateBaseline(rad(100), 10, 12.5f, 0, 7, 1, now + EXPOSURE_RAMP_SECONDS + FRAME, false);
		Assert.assertEquals(12.5, model.baseStrength, 0);
	}

	@Test
	public void snapJumpsStraightToTheTarget() {
		var model = new WindModel();
		double now = 10;
		// The first update after construction or resetBaseline snaps
		model.updateBaseline(rad(-90), 12, 33, KEY_EXPOSED, 1, 0, now, false);
		Assert.assertEquals("first update", WindModel.wrapAngle(rad(-90)), model.baseAngle, 0);
		Assert.assertEquals("first update", 12, model.baseSpeed, 0);
		Assert.assertEquals("first update", 33, model.baseStrength, 0);

		// Snap in the middle of a lockstep transition
		model.updateBaseline(rad(40), 20, 0, KEY_EXPOSED, 2, .3f, now += FRAME, false);
		Assert.assertEquals("mid-transition", 33 * (1 - .3f), model.baseStrength, 1e-4);
		model.updateBaseline(rad(40), 20, 0, KEY_EXPOSED, 2, .35f, now += FRAME, true);
		assertBaseline("snap during a lockstep transition", model, 40 * DEG, 20, 0);
		Assert.assertEquals(WindModel.wrapAngle(rad(40)), model.baseAngle, 0);
		// The rest of that transition does not resume easing from the old snapshot
		model.updateBaseline(rad(40), 20, 0, KEY_EXPOSED, 2, .6f, now += FRAME, false);
		assertBaseline("after the snap", model, 40 * DEG, 20, 0);

		// Snap in the middle of a timed ramp
		model.updateBaseline(rad(200), 8, 90, 0, 2, 1, now += FRAME, false);
		model.updateBaseline(rad(200), 8, 90, 0, 2, 1, now += 1, false);
		Assert.assertTrue("mid-ramp", model.baseStrength > 5 && model.baseStrength < 85);
		model.updateBaseline(rad(200), 8, 90, 0, 2, 1, now += FRAME, true);
		assertBaseline("snap during a timed ramp", model, 200 * DEG, 8, 90);
		model.updateBaseline(rad(200), 8, 90, 0, 2, 1, now += FRAME, false);
		assertBaseline("after the snap", model, 200 * DEG, 8, 90);

		// A snap together with a new transition (e.g. a teleport) does not start a lockstep ramp afterwards
		model.updateBaseline(rad(300), 30, 5, KEY_EXPOSED, 9, 1, now += FRAME, true);
		model.updateBaseline(rad(300), 30, 5, KEY_EXPOSED, 9, 0, now += FRAME, false);
		assertBaseline("after a snapping transition", model, 300 * DEG, 30, 5);

		// resetBaseline makes the next update snap
		model.resetBaseline();
		model.updateBaseline(rad(10), 5, 7, 0, 9, .5f, now += FRAME, false);
		assertBaseline("after resetBaseline", model, 10 * DEG, 5, 7);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------------------------

	/** Integrate 60 s of gusting, veering storm wind at the given frame rate, the way WeatherManager does each frame. */
	private static double[] integrateStorm(int fps) {
		var model = new WindModel();
		var wind = new WeatherState.SurfaceWind();
		model.resetOffsets(wind);
		long seed = 0x117_57A7L;
		var flow = new SteeringFlow(seed, (float) (40 * DEG));
		var upper = new WeatherState.UpperWind();
		float[] params = windParams(1.6f, 1.8f, 1, .3f);
		float dt = 1f / fps;
		for (int f = 0; f < 60 * fps; f++) {
			long wallMs = WALL_2026 + Math.round(f * 1000.0 / fps);
			flow.evaluate(wallMs, upper);
			WindModel.modulate(wind, rad(10), 14, 40, 1280, 1, params, upper, WindModel.gust(wallMs, seed));
			model.integrate(wind, dt, false);
		}
		return new double[] { wind.windOffset, wind.windOffsetX, wind.windOffsetZ, wind.windOffsetExact };
	}

	/** EnvironmentManager's eased transition progress after the given number of 60 fps frames. */
	private static float environmentProgress(int frame, int frames) {
		float x = Math.min(1, frame / (float) frames);
		return x * x * (3 - 2 * x);
	}

	private static float rad(double degrees) {
		return (float) (degrees * DEG);
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

	private static double angleDiffRef(double a, double b) {
		double d = (a - b) % (2 * Math.PI);
		if (d >= Math.PI)
			d -= 2 * Math.PI;
		if (d < -Math.PI)
			d += 2 * Math.PI;
		return d;
	}

	private static double mixAngleRef(double from, double to, double t) {
		return wrap(from + signedDiff(to, from) * t);
	}

	private static void assertAngle(String message, double expected, float actual, double tolerance) {
		Assert.assertTrue(message + ": angle " + actual + " is not normalised to [0, 2pi]", actual >= 0 && actual <= TWO_PI);
		double error = Math.abs(signedDiff(actual, expected));
		if (!(error <= tolerance)) {
			Assert.fail(String.format(Locale.ROOT, "%s: expected %.5f deg but was %.5f deg (off by %.3g deg)",
				message, Math.toDegrees(wrap(expected)), Math.toDegrees(wrap(actual)), Math.toDegrees(error)));
		}
	}

	private static void assertDirectionMatchesAngle(String message, WeatherState.SurfaceWind wind) {
		Assert.assertEquals(message + ": windDirectionX", Math.cos(wind.windAngle), wind.windDirectionX, 1e-6);
		Assert.assertEquals(message + ": windDirectionZ", Math.sin(wind.windAngle), wind.windDirectionZ, 1e-6);
	}

	private static void assertBaseline(String message, WindModel model, double angle, float speed, float strength) {
		assertAngle(message + ": baseAngle", angle, model.baseAngle, 1e-5);
		Assert.assertEquals(message + ": baseSpeed", speed, model.baseSpeed, 0);
		Assert.assertEquals(message + ": baseStrength", strength, model.baseStrength, 0);
	}

	/** All three uploaded offsets, which are rebased together, for wind with a constant direction. */
	private static void assertOffsets(String message, WeatherState.SurfaceWind wind, double expected) {
		double tolerance = Math.max(1e-3, expected * 1e-6);
		Assert.assertEquals(message + ": windOffset", expected, wind.windOffset, tolerance);
		Assert.assertEquals(message + ": windOffsetX", expected * wind.windDirectionX, wind.windOffsetX, tolerance);
		Assert.assertEquals(message + ": windOffsetZ", expected * wind.windDirectionZ, wind.windOffsetZ, tolerance);
	}

	private static void assertRelative(String message, double expected, double actual, double relative) {
		Assert.assertEquals(message, expected, actual, Math.abs(expected) * relative);
	}

	private static void assertExact(String what, float expected, float actual, Supplier<String> context) {
		// == treats -0 and 0 as equal, and fails on NaN
		if (!(expected == actual))
			Assert.fail(what + ": expected exactly " + expected + " but was " + actual + " (" + context.get() + ")");
	}

	private static void assertWithin(String message, float value, float min, float max) {
		// mix() may round one ulp past its endpoints
		float slack = 1e-4f * Math.max(1, Math.abs(max));
		if (!(value >= min - slack && value <= max + slack))
			Assert.fail(message + ": " + value + " is outside [" + min + ", " + max + "]");
	}

	private static WeatherState.UpperWind upperWind(float angle, float deviation) {
		var upper = new WeatherState.UpperWind();
		upper.angle = angle;
		upper.deviation = deviation;
		upper.directionX = (float) Math.cos(angle);
		upper.directionZ = (float) Math.sin(angle);
		return upper;
	}

	private static float[] windParams(float strength, float speed, float gustiness, float align) {
		float[] params = WeatherParam.defaultVector();
		params[WIND_STRENGTH] = strength;
		params[WIND_SPEED] = speed;
		params[GUSTINESS] = gustiness;
		params[WIND_ALIGN] = align;
		return params;
	}

	/** Every slot within its parameter's range; the wind parameters are often at their extremes. */
	private static float[] randomParams(Random rng) {
		float[] params = new float[WeatherParam.VECTOR_SIZE];
		for (int slot = 0; slot < params.length; slot++) {
			var param = WeatherParam.ofSlot(slot);
			float u = rng.nextFloat();
			if (param.group == WeatherParam.Group.WIND && rng.nextInt(4) == 0)
				u = rng.nextBoolean() ? 0 : 1;
			params[slot] = param.min + u * (param.max - param.min);
		}
		return params;
	}

	private static String describeWind(float[] params) {
		return String.format(Locale.ROOT, "windStrength=%s windSpeed=%s gustiness=%s windAlign=%s",
			params[WIND_STRENGTH], params[WIND_SPEED], params[GUSTINESS], params[WIND_ALIGN]);
	}
}
