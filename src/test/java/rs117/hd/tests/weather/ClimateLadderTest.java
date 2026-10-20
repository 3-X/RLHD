package rs117.hd.tests.weather;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.weather.Climate;
import rs117.hd.scene.weather.ClimateLadder;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.scene.weather.WeatherTimeline;
import rs117.hd.scene.weather.WeatherType;
import rs117.hd.scene.weather.WeatherZone;
import rs117.hd.utils.GsonUtils;

import static rs117.hd.scene.weather.WeatherConstants.EPOCH_MS;

public class ClimateLadderTest {
	private static final SeasonalTheme[] SEASONS = { SeasonalTheme.SUMMER, SeasonalTheme.AUTUMN, SeasonalTheme.WINTER };
	/** A fixed, epoch-aligned starting phase, so every run simulates the same stretch of the shared timeline. */
	private static final long START = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
	private static final int FIVE_YEARS_DAYS = 5 * 365;
	private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

	private static WeatherType type(String name, int severity) {
		return WeatherType.synthetic(name, severity, 20, 20, Map.of());
	}

	private static ClimateLadder ladder(WeatherType[] types, double... weights) {
		return ClimateLadder.of(types, weights);
	}

	private static List<String> validate(ClimateLadder ladder, double volatility, int maxStep) {
		List<String> errors = new ArrayList<>();
		ladder.validate(volatility, maxStep, "test", errors);
		return errors;
	}

	private static List<Double> numbersIn(String message) {
		List<Double> numbers = new ArrayList<>();
		Matcher m = NUMBER.matcher(message);
		while (m.find())
			numbers.add(Double.parseDouble(m.group()));
		return numbers;
	}

	private static boolean containsNumber(List<Double> numbers, double expected) {
		for (double n : numbers)
			if (Math.abs(n - expected) < 1e-9)
				return true;
		return false;
	}

	private static WeatherTimeline freshTimeline() throws IOException {
		// A private instance, so the segment cache isn't shared with other tests
		return new WeatherTimeline(WeatherTestData.shipped().timeline.settings);
	}

	/** A small, valid weather.json. Single quotes are swapped for double quotes before parsing. */
	private static final String BASE =
		"{"
		+ "'defaultClimate': 'TEST',"
		+ "'timeline': { 'name': 'TEST', 'segmentMinutes': [ 30, 90 ], 'volatility': 0.3, 'driftAngle': 40 },"
		+ "'types': ["
		+ "  { 'name': 'CLEAR', 'severity': 0, 'onsetMinutes': 20, 'clearMinutes': 20, 'params': { 'cloudCover': [ 0, 0.1 ] } },"
		+ "  { 'name': 'OVERCAST', 'severity': 2, 'onsetMinutes': 30, 'clearMinutes': 25, 'params': { 'cloudCover': [ 0.85, 1 ] } },"
		+ "  { 'name': 'THUNDERSTORM', 'severity': 4, 'onsetMinutes': 25, 'clearMinutes': 12, 'params': { 'cloudCover': 1, 'rain': [ 0.7, 1 ] } }"
		+ "],"
		+ "'climates': ["
		+ "  { 'name': 'TEST', 'wind': { 'angle': 40, 'speed': 15, 'strength': 50 },"
		+ "    'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ], [ 'THUNDERSTORM', 6 ] ] }"
		+ "],"
		+ "'bindings': [ { 'climate': 'TEST', 'aabbs': [ [ 3200, 3200, 3263, 3263 ] ] } ]"
		+ "}";
	private static final Gson GSON = GsonUtils.wrap(new Gson());

	/** The base data plus an extra, unbound climate, so the extra climate's own errors are the only ones. */
	private static JsonObject withClimate(String climate) {
		JsonObject root = GSON.fromJson(BASE.replace('\'', '"'), JsonObject.class);
		root.getAsJsonArray("climates").add(GSON.fromJson(climate.replace('\'', '"'), JsonObject.class));
		return root;
	}

	private static WeatherData load(JsonObject root) throws IOException {
		return WeatherData.parse(root).bind(WeatherTestData.areas(), true);
	}

	private static String loadError(JsonObject root) throws IOException {
		try {
			load(root);
		} catch (IOException ex) {
			return ex.getMessage();
		}
		Assert.fail("Expected the weather data to be rejected");
		return null;
	}

	// ---------------------------------------------------------------------------------------------
	// Bins and intensity
	// ---------------------------------------------------------------------------------------------

	@Test
	public void binAndIntensityEdges() {
		WeatherType a = type("A", 0);
		WeatherType b = type("B", 1);
		ClimateLadder ladder = ladder(new WeatherType[] { a, b }, 1, 3);

		Assert.assertEquals(4, ladder.totalWeight, 0);
		Assert.assertEquals(3, ladder.edges.length);
		Assert.assertEquals(0, ladder.edges[0], 0);
		Assert.assertEquals(.25, ladder.edges[1], 0);
		Assert.assertEquals(1, ladder.edges[2], 0);

		Assert.assertEquals(0, ladder.bin(0));
		Assert.assertEquals(0, ladder.intensity(0, 0), 0);

		Assert.assertEquals(0, ladder.bin(.2499));
		Assert.assertEquals(.2499 / .25, ladder.intensity(.2499, 0), 1e-12);

		Assert.assertEquals(1, ladder.bin(.25));
		Assert.assertEquals(0, ladder.intensity(.25, 1), 0);

		Assert.assertEquals(1, ladder.bin(.625));
		Assert.assertEquals(.5, ladder.intensity(.625, 1), 1e-12);

		Assert.assertEquals(1, ladder.bin(1 - 1e-9));
		Assert.assertEquals(1, ladder.intensity(1 - 1e-9, 1), 1e-8);
		Assert.assertTrue(ladder.intensity(1 - 1e-9, 1) < 1);

		Assert.assertSame(a, ladder.typeAt(0));
		Assert.assertSame(a, ladder.typeAt(.2499));
		Assert.assertSame(b, ladder.typeAt(.25));
		Assert.assertSame(b, ladder.typeAt(1 - 1e-9));
	}

	@Test
	public void binMatchesEdgesForEveryShippedLadder() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		Random random = new Random(117);
		for (Climate climate : data.climates.values()) {
			for (ClimateLadder ladder : climate.ladders()) {
				int n = ladder.types.length;
				Assert.assertEquals(climate.name, 0, ladder.edges[0], 0);
				Assert.assertEquals(climate.name, 1, ladder.edges[n], 0);
				for (int i = 0; i < n; i++) {
					Assert.assertTrue(climate.name + ": edges must increase", ladder.edges[i + 1] > ladder.edges[i]);
					// Exactly at a lower edge: start of that bin with zero intensity
					Assert.assertEquals(climate.name + " edge " + i, i, ladder.bin(ladder.edges[i]));
					Assert.assertEquals(climate.name + " edge " + i, 0, ladder.intensity(ladder.edges[i], i), 1e-12);
					// Each bin's width is its normalized weight
					Assert.assertEquals(climate.name + " width " + i,
						ladder.weights[i] / ladder.totalWeight, ladder.edges[i + 1] - ladder.edges[i], 1e-12);
				}
				for (int k = 0; k < 10_000; k++) {
					double s = random.nextDouble();
					int bin = ladder.bin(s);
					String ctx = String.format(Locale.ROOT, "%s s=%.17f bin=%d", climate.name, s, bin);
					Assert.assertTrue(ctx, bin >= 0 && bin < n);
					Assert.assertTrue(ctx, ladder.edges[bin] <= s && s < ladder.edges[bin + 1]);
					double intensity = ladder.intensity(s, bin);
					Assert.assertTrue(ctx, intensity >= 0 && intensity <= 1);
					Assert.assertSame(ctx, ladder.types[bin], ladder.typeAt(s));
				}
			}
		}
	}

	@Test
	public void ladderFactoryRejectsBadWeights() {
		WeatherType[] two = { type("A", 0), type("B", 1) };
		assertThrowsIae(() -> ClimateLadder.of(two, new double[] { 1 }));
		assertThrowsIae(() -> ClimateLadder.of(new WeatherType[0], new double[0]));
		assertThrowsIae(() -> ClimateLadder.of(two, new double[] { 1, 0 }));
		assertThrowsIae(() -> ClimateLadder.of(two, new double[] { 1, -2 }));
		assertThrowsIae(() -> ClimateLadder.of(two, new double[] { 1, Double.NaN }));
		assertThrowsIae(() -> ClimateLadder.of(two, new double[] { 1, Double.POSITIVE_INFINITY }));
	}

	private static void assertThrowsIae(Runnable r) {
		try {
			r.run();
			Assert.fail("Expected an IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Validator
	// ---------------------------------------------------------------------------------------------

	@Test
	public void validatorRejectsUnbufferedJump() {
		ClimateLadder ladder = ladder(new WeatherType[] { type("CLEAR", 0), type("THUNDERSTORM", 4) }, 20, 4);
		for (int maxStep : new int[] { 2, 3 }) {
			List<String> errors = validate(ladder, .3, maxStep);
			Assert.assertEquals("maxStep " + maxStep + ": " + errors, 1, errors.size());
			String message = errors.get(0);
			Assert.assertTrue(message, message.contains("CLEAR"));
			Assert.assertTrue(message, message.contains("THUNDERSTORM"));
			// Stated in the ladder's own weight units: 0.3 of a total weight of 24 is 7.2, and nothing lies between them
			List<Double> numbers = numbersIn(message);
			Assert.assertTrue("required weight 7.2 missing: " + message, containsNumber(numbers, 7.2));
			Assert.assertTrue("total weight 24 missing: " + message, containsNumber(numbers, 24));
			Assert.assertTrue("actual weight 0 missing: " + message, containsNumber(numbers, 0));
		}
	}

	@Test
	public void validatorAcceptsBufferedJump() {
		WeatherType[] types = { type("CLEAR", 0), type("OVERCAST", 2), type("THUNDERSTORM", 4) };
		// OVERCAST holds 8 / 24 = 0.333 of the latent range between the two extremes
		Assert.assertEquals(List.of(), validate(ladder(types, 10, 8, 6), .3, 2));
		// A buffer exactly equal to the volatility is enough: 3 / 10 = 0.3
		Assert.assertEquals(List.of(), validate(ladder(types, 4, 3, 3), .3, 2));
	}

	@Test
	public void validatorRejectsThinBuffer() {
		WeatherType[] types = { type("CLEAR", 0), type("OVERCAST", 2), type("THUNDERSTORM", 4) };
		// 6 / 22 = 0.273 < 0.3
		List<String> errors = validate(ladder(types, 10, 6, 6), .3, 2);
		Assert.assertEquals(errors.toString(), 1, errors.size());
		String message = errors.get(0);
		Assert.assertTrue(message, message.contains("CLEAR") && message.contains("THUNDERSTORM"));
		Assert.assertFalse("adjacent steps of 2 are allowed: " + message, message.contains("OVERCAST"));
		List<Double> numbers = numbersIn(message);
		Assert.assertTrue(message, containsNumber(numbers, 6.6)); // 0.3 * 22
		Assert.assertTrue(message, containsNumber(numbers, 22));
		Assert.assertTrue(message, containsNumber(numbers, 6));
	}

	@Test
	public void validatorRejectsDecreasingSeverity() {
		WeatherType overcast = type("OVERCAST", 2);
		WeatherType clear = type("CLEAR", 0);
		List<String> errors = validate(ladder(new WeatherType[] { overcast, clear }, 5, 5), .3, 2);
		Assert.assertEquals(errors.toString(), 1, errors.size());
		String message = errors.get(0);
		Assert.assertTrue(message, message.contains("OVERCAST") && message.contains("CLEAR"));
		Assert.assertTrue(message, message.toLowerCase(Locale.ROOT).contains("decrease"));

		// Equal severities are fine
		Assert.assertEquals(List.of(), validate(ladder(new WeatherType[] { type("PARTLY_CLOUDY", 1), type("HAZE", 1) }, 1, 1), .3, 2));
	}

	@Test
	public void loaderAppliesValidatorWithTimelineVolatility() throws IOException {
		// Default ladder: the message reaches the user in weight units, with the climate as context
		String message = loadError(withClimate(
			"{ 'name': 'JUMPY', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 },"
			+ "  'ladder': [ [ 'CLEAR', 20 ], [ 'THUNDERSTORM', 4 ] ] }"));
		Assert.assertTrue(message, message.startsWith("Invalid weather data (1 error)"));
		Assert.assertTrue(message, message.contains("JUMPY") && message.contains("CLEAR") && message.contains("THUNDERSTORM"));
		List<Double> numbers = numbersIn(message);
		Assert.assertTrue(message, containsNumber(numbers, 7.2));
		Assert.assertTrue(message, containsNumber(numbers, 24));

		// Seasonal ladders are validated too
		message = loadError(withClimate(
			"{ 'name': 'JUMPY', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 },"
			+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ], [ 'THUNDERSTORM', 6 ] ],"
			+ "  'seasons': { 'WINTER': [ [ 'CLEAR', 20 ], [ 'THUNDERSTORM', 4 ] ] } }"));
		Assert.assertTrue(message, message.startsWith("Invalid weather data (1 error)"));
		Assert.assertTrue(message, message.contains("JUMPY") && message.contains("WINTER"));

		// So is the ordering
		message = loadError(withClimate(
			"{ 'name': 'JUMPY', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 },"
			+ "  'ladder': [ [ 'OVERCAST', 5 ], [ 'CLEAR', 5 ] ] }"));
		Assert.assertTrue(message, message.startsWith("Invalid weather data (1 error)"));
		Assert.assertTrue(message, message.contains("JUMPY") && message.toLowerCase(Locale.ROOT).contains("decrease"));

		// The buffer scales with the timeline's volatility: 0.25 of 24 is 6, so OVERCAST 6 between them is enough
		JsonObject root = withClimate(
			"{ 'name': 'JUMPY', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 },"
			+ "  'ladder': [ [ 'CLEAR', 12 ], [ 'OVERCAST', 6 ], [ 'THUNDERSTORM', 6 ] ] }");
		message = loadError(root); // 6 / 24 = 0.25 < 0.3
		Assert.assertTrue(message, message.startsWith("Invalid weather data (1 error)") && message.contains("JUMPY"));
		root.getAsJsonObject("timeline").addProperty("volatility", .25);
		Assert.assertNotNull(load(root).climate("JUMPY"));

		// A climate allowing steps of 4 needs no buffer at all
		Assert.assertNotNull(load(withClimate(
			"{ 'name': 'JUMPY', 'maxSeverityStep': 4, 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 },"
			+ "  'ladder': [ [ 'CLEAR', 20 ], [ 'THUNDERSTORM', 4 ] ] }")).climate("JUMPY"));
	}

	@Test
	public void bundledLaddersPassValidator() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		double volatility = data.timeline.settings.volatility;
		List<String> errors = new ArrayList<>();
		int checked = 0;
		for (Climate climate : data.climates.values()) {
			for (SeasonalTheme season : SEASONS) {
				ClimateLadder ladder = climate.ladderFor(season);
				ladder.validate(volatility, climate.maxSeverityStep, climate.name + "/" + season, errors);
				for (int i = 1; i < ladder.types.length; i++)
					Assert.assertTrue(climate.name + "/" + season, ladder.types[i].severity >= ladder.types[i - 1].severity);
				checked++;
			}
			for (ClimateLadder ladder : climate.ladders())
				ladder.validate(volatility, climate.maxSeverityStep, climate.name, errors);
		}
		Assert.assertEquals(14 * SEASONS.length, checked);
		Assert.assertEquals(String.join("\n", errors), 0, errors.size());
	}

	// ---------------------------------------------------------------------------------------------
	// Long simulations of the shipped timeline
	// ---------------------------------------------------------------------------------------------

	@Test
	public void severityStepNeverExceedsLimit() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		List<Climate> climates = new ArrayList<>(data.climates.values());
		WeatherTimeline timeline = freshTimeline();
		long end = START + FIVE_YEARS_DAYS * EPOCH_MS;

		WeatherZone[][] zones = new WeatherZone[climates.size()][SEASONS.length];
		for (int c = 0; c < climates.size(); c++)
			for (int s = 0; s < SEASONS.length; s++)
				zones[c][s] = new WeatherZone(climates.get(c), SEASONS[s]);

		int[][] worst = new int[climates.size()][SEASONS.length];
		String[][] worstAt = new String[climates.size()][SEASONS.length];
		WeatherTimeline.Segment previous = timeline.segmentAt(START);
		Assert.assertEquals(START, previous.start);
		int boundaries = 0;
		while (previous.end < end) {
			WeatherTimeline.Segment next = timeline.next(previous);
			Assert.assertEquals("segments must tile time", previous.end, next.start);
			boundaries++;
			for (int c = 0; c < climates.size(); c++) {
				for (int s = 0; s < SEASONS.length; s++) {
					WeatherZone zone = zones[c][s];
					WeatherType from = zone.typeFor(previous);
					WeatherType to = zone.typeFor(next);
					int step = Math.abs(to.severity - from.severity);
					if (step > worst[c][s]) {
						worst[c][s] = step;
						worstAt[c][s] = String.format(Locale.ROOT, "%s (%s) -> %s (%s)", previous, from, next, to);
					}
				}
			}
			previous = next;
		}
		Assert.assertTrue("simulated too few boundaries: " + boundaries, boundaries > FIVE_YEARS_DAYS * 16);

		List<String> violations = new ArrayList<>();
		for (int c = 0; c < climates.size(); c++) {
			Climate climate = climates.get(c);
			for (int s = 0; s < SEASONS.length; s++)
				if (worst[c][s] > climate.maxSeverityStep)
					violations.add(String.format(Locale.ROOT, "%s/%s: step %d > %d at %s",
						climate.name, SEASONS[s], worst[c][s], climate.maxSeverityStep, worstAt[c][s]));
		}
		Assert.assertTrue(String.join("\n", violations), violations.isEmpty());
	}

	@Test
	public void timeShareMatchesWeights() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		List<Climate> climates = new ArrayList<>(data.climates.values());
		WeatherTimeline timeline = freshTimeline();
		long end = START + FIVE_YEARS_DAYS * EPOCH_MS;

		double[][][] time = new double[climates.size()][SEASONS.length][];
		for (int c = 0; c < climates.size(); c++)
			for (int s = 0; s < SEASONS.length; s++)
				time[c][s] = new double[climates.get(c).ladderFor(SEASONS[s]).types.length];

		double total = 0;
		WeatherTimeline.Segment segment = timeline.segmentAt(START);
		while (segment.start < end) {
			double duration = Math.min(segment.end, end) - Math.max(segment.start, START);
			total += duration;
			for (int c = 0; c < climates.size(); c++)
				for (int s = 0; s < SEASONS.length; s++)
					time[c][s][climates.get(c).ladderFor(SEASONS[s]).bin(segment.latent)] += duration;
			segment = timeline.next(segment);
		}
		Assert.assertEquals(FIVE_YEARS_DAYS * (double) EPOCH_MS, total, 0);

		List<String> violations = new ArrayList<>();
		double maxDeviation = 0;
		for (int c = 0; c < climates.size(); c++) {
			Climate climate = climates.get(c);
			for (int s = 0; s < SEASONS.length; s++) {
				ClimateLadder ladder = climate.ladderFor(SEASONS[s]);
				for (int i = 0; i < ladder.types.length; i++) {
					double expected = ladder.weights[i] / ladder.totalWeight;
					double actual = time[c][s][i] / total;
					maxDeviation = Math.max(maxDeviation, Math.abs(actual - expected));
					if (Math.abs(actual - expected) > .03)
						violations.add(String.format(Locale.ROOT, "%s/%s %s: share %.4f vs weight %.4f",
							climate.name, SEASONS[s], ladder.types[i], actual, expected));
				}
			}
		}
		Assert.assertTrue("max deviation " + maxDeviation + "\n" + String.join("\n", violations), violations.isEmpty());
	}

	@Test
	public void lockedClimateNeverChangesType() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		Climate blizzard = data.climate("LOCKED_BLIZZARD");
		Assert.assertNotNull("LOCKED_BLIZZARD must exist", blizzard);
		WeatherType expected = data.type("BLIZZARD");
		Assert.assertNotNull(expected);
		assertLocked(blizzard, expected);
	}

	@Test
	public void everySingleRungClimateIsLocked() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		int locked = 0;
		for (Climate climate : data.climates.values()) {
			List<ClimateLadder> ladders = climate.ladders();
			WeatherType only = ladders.get(0).types[0];
			boolean single = true;
			for (ClimateLadder ladder : ladders)
				single &= ladder.types.length == 1 && ladder.types[0] == only;
			if (!single)
				continue;
			assertLocked(climate, only);
			locked++;
		}
		// LOCKED_BLIZZARD, LOCKED_TEMPEST and WILDERNESS_DEEP
		Assert.assertEquals(3, locked);
	}

	private static void assertLocked(Climate climate, WeatherType expected) throws IOException {
		WeatherTimeline timeline = freshTimeline();
		long end = START + 30 * EPOCH_MS;
		for (SeasonalTheme season : SEASONS) {
			WeatherZone zone = new WeatherZone(climate, season);
			double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
			WeatherTimeline.Segment segment = timeline.segmentAt(START);
			int segments = 0;
			while (segment.start < end) {
				Assert.assertSame(climate.name + "/" + season + " at " + segment, expected, zone.typeFor(segment));
				double intensity = zone.intensityFor(segment);
				Assert.assertTrue(climate.name + " intensity " + intensity, intensity >= 0 && intensity <= 1);
				min = Math.min(min, intensity);
				max = Math.max(max, intensity);
				segment = timeline.next(segment);
				segments++;
			}
			Assert.assertTrue(climate.name + ": only " + segments + " segments in 30 days", segments >= 30 * 16);
			Assert.assertTrue(climate.name + "/" + season + ": min intensity " + min, min < .2);
			Assert.assertTrue(climate.name + "/" + season + ": max intensity " + max, max > .8);
		}
	}
}
