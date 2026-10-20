package rs117.hd.tests.weather;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.WeatherManager;
import rs117.hd.scene.weather.Climate;
import rs117.hd.scene.weather.ClimateLadder;
import rs117.hd.scene.weather.WeatherClock;
import rs117.hd.scene.weather.WeatherCommands;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.scene.weather.WeatherOverrides;
import rs117.hd.scene.weather.WeatherParam;

/**
 * The parts of {@code ::117hd weather} that need no client: duration parsing, the {@code stats} simulation over the
 * shipped data, and the command handler on a bare {@link WeatherManager} with no weather data loaded.
 */
public class WeatherCommandsTest {
	private static final long SECOND = 1000;
	private static final long MINUTE = 60 * SECOND;
	private static final long HOUR = 60 * MINUTE;
	private static final long DAY = 24 * HOUR;

	/** "CLIMATE / SEASON over N days: X changes per day, largest severity step S (limit L)" */
	private static final Pattern HEADER = Pattern.compile(
		"^(\\S+) / (\\S+) over (\\d+) days: (\\d+\\.\\d) changes per day, largest severity step (\\d+) \\(limit (\\d+)\\)$");
	/** "  TYPE  weight W (P%)  actual A%  mean spell D" */
	private static final Pattern ROW = Pattern.compile(
		"^ {2}(\\S+) +weight +(\\d+\\.\\d) \\( *(\\d+\\.\\d)%\\) +actual +(\\d+\\.\\d)% +mean spell (.+)$");

	// ------------------------------------------------------------------------------------------------------------
	// parseDuration
	// ------------------------------------------------------------------------------------------------------------

	@Test
	public void parseDurationUnits() {
		assertDuration(5_400_000, "+90m");
		assertDuration(-7_200_000, "-2h");
		assertDuration(86_400_000, "1d");
		assertDuration(30_000, "30s");
		assertDuration(45 * MINUTE, "45");
		assertDuration(5_400_000, "1.5h");
		assertDuration(0, "0");
		assertDuration(-12 * HOUR, "-0.5d");
		assertDuration(135_000, "2.25m");
		assertDuration(1_500, "1.5s");
		// Surrounding whitespace and upper case are accepted
		assertDuration(5_400_000, " 90M ");
		assertDuration(DAY, "+1D");
		assertDuration(365 * DAY, "365d");
		assertDuration(-365 * DAY, "-365d");
	}

	@Test
	public void parseDurationRejectsInvalidInput() {
		String[] invalid = {
			"", " ", "\t", "abc", "m", "+", "-", "+m", "90x", "1.5.5h", "1,5h", "--5m", "+-5m", "5mm", "h5", "5 m",
			"NaN", "nan", "NaNm", "Infinity", "-Infinity", "Infinityh",
		};
		for (String text : invalid)
			Assert.assertNull("'" + text + "' should be rejected", WeatherCommands.parseDuration(text));
	}

	@Test
	public void parseDurationRejectsAbsurdlyLargeValues() {
		String[] absurd = {
			"100000d", "-100000d", "9999999999h", "99999999999m", "1000000000000000000000000000000s",
			"9".repeat(400), "9".repeat(400) + "d", "-" + "9".repeat(400) + "s",
		};
		for (String text : absurd)
			Assert.assertNull("'" + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "' should be rejected",
				WeatherCommands.parseDuration(text));
	}

	// ------------------------------------------------------------------------------------------------------------
	// stats simulation
	// ------------------------------------------------------------------------------------------------------------

	@Test
	public void simulateEveryShippedClimateAndSeason() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		int simulations = 0;
		for (Climate climate : data.climates.values()) {
			for (SeasonalTheme season : SeasonalTheme.values()) {
				List<String> lines = WeatherCommands.simulate(data, climate, season, 365);
				// Over a year the time shares follow the weights closely (offline worst case over many start dates
				// was 2.4 points); the simulation starts today, so allow for any start date
				checkSimulation(climate, season, 365, lines, 5.0);
				simulations++;
			}
		}
		Assert.assertEquals(14 * 4, simulations);
	}

	@Test
	public void simulateUsesSeasonalLaddersAndFallsBackToTheDefault() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		Assert.assertEquals(List.of("CLEAR", "FOG", "PARTLY_CLOUDY", "OVERCAST", "SNOW", "BLIZZARD"),
			rowNames(WeatherCommands.simulate(data, data.climate("TEMPERATE"), SeasonalTheme.WINTER, 30)));
		Assert.assertEquals(List.of("CLEAR", "FOG", "PARTLY_CLOUDY", "OVERCAST", "DRIZZLE", "RAIN", "THUNDERSTORM"),
			rowNames(WeatherCommands.simulate(data, data.climate("TEMPERATE"), SeasonalTheme.SUMMER, 30)));
		// TEMPERATE_GLOOMY overrides autumn but inherits TEMPERATE's winter
		Assert.assertEquals(List.of("CLEAR", "FOG", "PARTLY_CLOUDY", "OVERCAST", "SNOW", "BLIZZARD"),
			rowNames(WeatherCommands.simulate(data, data.climate("TEMPERATE_GLOOMY"), SeasonalTheme.WINTER, 30)));
		// The desert has no seasonal ladders, so winter uses the default one
		List<String> desertWinter = WeatherCommands.simulate(data, data.climate("DESERT"), SeasonalTheme.WINTER, 30);
		Assert.assertEquals(List.of("CLEAR", "PARTLY_CLOUDY", "HAZE", "SANDSTORM"), rowNames(desertWinter));
		Assert.assertEquals("  Never: rain, snow, ash", desertWinter.get(desertWinter.size() - 1));
	}

	@Test
	public void simulateShortRuns() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		for (Climate climate : data.climates.values()) {
			List<String> lines = WeatherCommands.simulate(data, climate, SeasonalTheme.SUMMER, 1);
			// A single day can't be expected to match the weights, but must still be well-formed
			checkSimulation(climate, SeasonalTheme.SUMMER, 1, lines, 100);
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// Command handling on a bare WeatherManager (no client, no weather data)
	// ------------------------------------------------------------------------------------------------------------

	@Test
	public void unknownOrMissingActionShowsUsage() {
		var wm = new WeatherManager();
		Assert.assertEquals(List.of(WeatherCommands.USAGE), WeatherCommands.handle(wm, new String[] { "weather" }));
		Assert.assertEquals(List.of(WeatherCommands.USAGE), WeatherCommands.handle(wm, new String[] { "weather", "frobnicate" }));
		// Actions and units are case-insensitive
		assertReply(wm, "Shifted the weather clock by 1h 30m", "TIME", "+90M");
	}

	@Test
	public void timeCommands() {
		var wm = new WeatherManager();
		WeatherClock clock = wm.getClock();

		assertReply(wm, "Shifted the weather clock by 1h 30m", "time", "+90m");
		Assert.assertEquals(90 * MINUTE, clock.getEffectiveOffset(System.currentTimeMillis()));
		assertReply(wm, "Shifted the weather clock by -2h", "time", "-2h");
		Assert.assertEquals(-30 * MINUTE, clock.getEffectiveOffset(System.currentTimeMillis()));

		for (String bad : new String[] { "soon", "NaN", "Infinity", "100000d", "1,5h" }) {
			assertReply(wm, "Invalid duration", "time", bad);
			Assert.assertEquals(-30 * MINUTE, clock.getEffectiveOffset(System.currentTimeMillis()));
		}
		assertReply(wm, "Usage: ::117hd weather time", "time");

		assertReply(wm, "follows real time again", "time", "reset");
		Assert.assertTrue(clock.isRealTime(System.currentTimeMillis()));
	}

	@Test
	public void speedCommands() {
		var wm = new WeatherManager();
		WeatherClock clock = wm.getClock();

		assertReply(wm, "x60.00", "speed", "60");
		Assert.assertEquals(60, clock.getScale(), 0);
		for (String bad : new String[] { "NaN", "Infinity", "-Infinity", "-1", "fast" }) {
			assertReply(wm, "The speed must be a number between 0 and 3600", "speed", bad);
			Assert.assertEquals("'speed " + bad + "' must leave the clock alone", 60, clock.getScale(), 0);
		}
		assertReply(wm, "x3600.00", "speed", "100000");
		Assert.assertEquals(WeatherClock.MAX_SCALE, clock.getScale(), 0);
		assertReply(wm, "x0.00", "speed", "0");
		Assert.assertEquals(0, clock.getScale(), 0);
		assertReply(wm, "Usage: ::117hd weather speed", "speed");

		// 'speed reset' restores the rate but keeps the time gained, which 'time reset' then clears
		var lapse = new WeatherManager();
		WeatherClock lapseClock = lapse.getClock();
		reply(lapse, "time", "+1h");
		reply(lapse, "speed", "60");
		assertReply(lapse, "real-time speed again", "speed", "reset");
		Assert.assertEquals(1, lapseClock.getScale(), 0);
		long now = System.currentTimeMillis();
		Assert.assertTrue(lapseClock.getEffectiveOffset(now) >= HOUR);
		Assert.assertFalse(lapseClock.isRealTime(now));
		assertReply(lapse, "follows real time again", "time", "reset");
		Assert.assertTrue(lapseClock.isRealTime(System.currentTimeMillis()));
		Assert.assertEquals(1, lapseClock.getScale(), 0);
	}

	@Test
	public void windCommands() {
		var wm = new WeatherManager();
		WeatherOverrides overrides = wm.getOverrides();

		assertReply(wm, "Wind overridden: 90 degrees, strength 50.0, speed 15.0", "wind", "90", "50");
		Assert.assertTrue(overrides.windOverride);
		Assert.assertEquals(Math.PI / 2, overrides.windAngle, 1e-5);
		Assert.assertEquals(50, overrides.windStrength, 0);
		Assert.assertEquals("the default speed", 15, overrides.windSpeed, 0);

		assertReply(wm, "Wind overridden: -90 degrees, strength 20.0, speed 7.5", "wind", "-90", "20", "7.5");
		Assert.assertEquals("angles wrap into [0, 2pi)", 3 * Math.PI / 2, overrides.windAngle, 1e-5);
		Assert.assertEquals(7.5, overrides.windSpeed, 0);

		String[][] invalid = {
			{ "wind", "NaN", "50" }, { "wind", "90", "NaN" }, { "wind", "90", "Infinity" }, { "wind", "east", "50" },
			{ "wind", "90", "-1" }, { "wind", "90", "50", "-3" }, { "wind", "90", "50", "NaN" },
			// Values that would overflow a float or poison the wind offsets
			{ "wind", "0", "50", "1e39" }, { "wind", "0", "50", "1e20" }, { "wind", "0", "1e39" }, { "wind", "0", "301" },
			{ "wind", "0", "50", "121" },
		};
		for (String[] args : invalid) {
			assertReply(wm, "The angle must be a number", args);
			Assert.assertEquals(3 * Math.PI / 2, overrides.windAngle, 1e-5);
			Assert.assertEquals(20, overrides.windStrength, 0);
			Assert.assertEquals(7.5, overrides.windSpeed, 0);
		}
		assertReply(wm, "Usage: ::117hd weather wind", "wind", "90");

		// Huge angles wrap in double precision instead of overflowing
		assertReply(wm, "Wind overridden", "wind", "1e39", "20", "7.5");
		Assert.assertTrue("the angle is finite", Float.isFinite(overrides.windAngle));
		Assert.assertTrue("the angle is wrapped", overrides.windAngle >= 0 && overrides.windAngle < 2 * Math.PI);
		assertReply(wm, "Wind overridden: -90 degrees, strength 20.0, speed 7.5", "wind", "-90", "20", "7.5");

		assertReply(wm, "The weather controls the wind again", "wind", "auto");
		Assert.assertFalse(overrides.windOverride);
	}

	@Test
	public void seasonClimateAndToggleCommands() {
		var wm = new WeatherManager();
		WeatherOverrides overrides = wm.getOverrides();

		assertReply(wm, "Forcing the weather season AUTUMN", "season", "fall");
		Assert.assertEquals(SeasonalTheme.AUTUMN, overrides.forcedSeason);
		Assert.assertEquals(SeasonalTheme.AUTUMN, wm.getSeason());
		assertReply(wm, "Unknown season: spring", "season", "spring");
		Assert.assertEquals(SeasonalTheme.AUTUMN, overrides.forcedSeason);
		assertReply(wm, "Following the seasonal theme again", "season", "auto");
		Assert.assertNull(overrides.forcedSeason);
		Assert.assertEquals(SeasonalTheme.SUMMER, wm.getSeason());

		// No data is loaded, so no climate name is known
		assertReply(wm, "Unknown climate: ARCTIC", "climate", "ARCTIC");
		Assert.assertNull(overrides.forcedClimate);
		assertReply(wm, "Following the bound climate again", "climate", "auto");

		assertReply(wm, "Weather disabled", "off");
		Assert.assertTrue(overrides.disabled);
		Assert.assertEquals("Weather is disabled (::117hd weather on)", WeatherCommands.info(wm));
		assertReply(wm, "Weather enabled", "on");
		Assert.assertFalse(overrides.disabled);
		Assert.assertEquals("Weather isn't active here", WeatherCommands.info(wm));

		// 'reset' clears every override and the clock
		reply(wm, "season", "winter");
		reply(wm, "wind", "10", "10");
		reply(wm, "off");
		reply(wm, "time", "+1d");
		reply(wm, "speed", "5");
		Assert.assertTrue(overrides.any());
		assertReply(wm, "Cleared all weather overrides", "reset");
		Assert.assertFalse(overrides.any());
		Assert.assertNull(overrides.forcedSeason);
		Assert.assertFalse(overrides.windOverride);
		Assert.assertFalse(overrides.disabled);
		Assert.assertEquals(1, wm.getClock().getScale(), 0);
		Assert.assertTrue(wm.getClock().isRealTime(System.currentTimeMillis()));
	}

	@Test
	public void commandsNeedingWeatherDataFailGracefully() {
		var wm = new WeatherManager();
		assertReply(wm, "No weather data is loaded", "force", "RAIN");
		assertReply(wm, "No weather data is loaded", "force", "auto");
		assertReply(wm, "No weather data is loaded", "stats");
		assertReply(wm, "No weather data is loaded", "stats", "DESERT", "WINTER", "30");
		assertReply(wm, "isn't running here", "next");
		assertReply(wm, "Unknown weather type: RAIN", "next", "RAIN");
		assertReply(wm, "No forecast available here", "forecast");
		assertReply(wm, "Usage: ::117hd weather forecast", "forecast", "-3");
		assertReply(wm, "Usage: ::117hd weather forecast", "forecast", "NaN");
		assertReply(wm, "Weather isn't active here", "info");
		Assert.assertFalse(WeatherCommands.describe(wm).isEmpty());
	}

	// ------------------------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------------------------

	private static void assertDuration(long expectedMs, String text) {
		Assert.assertEquals("'" + text + "'", Long.valueOf(expectedMs), WeatherCommands.parseDuration(text));
	}

	/** Check the shape of a stats simulation and that its reported time shares are close to the ladder weights. */
	private static void checkSimulation(Climate climate, SeasonalTheme season, int days, List<String> lines, double tolerancePercent) {
		String where = climate.name + "/" + season + " over " + days + " days";
		Assert.assertFalse(where + ": no output", lines.isEmpty());
		for (String line : lines)
			Assert.assertFalse(where + ": blank line in " + lines, line.trim().isEmpty());

		Matcher header = HEADER.matcher(lines.get(0));
		Assert.assertTrue(where + ": unexpected header: " + lines.get(0), header.matches());
		Assert.assertEquals(climate.name, header.group(1));
		Assert.assertEquals(season.name(), header.group(2));
		Assert.assertEquals(days, Integer.parseInt(header.group(3)));
		int largestStep = Integer.parseInt(header.group(5));
		int limit = Integer.parseInt(header.group(6));
		Assert.assertEquals(where, climate.maxSeverityStep, limit);
		Assert.assertTrue(where + ": largest severity step " + largestStep + " exceeds the limit " + limit, largestStep <= limit);

		ClimateLadder ladder = climate.ladderFor(season);
		int expectedLines = 1 + ladder.types.length + (climate.forbidden.isEmpty() ? 0 : 1);
		Assert.assertEquals(where + ": " + lines, expectedLines, lines.size());

		double totalActual = 0;
		for (int i = 0; i < ladder.types.length; i++) {
			String line = lines.get(1 + i);
			Matcher row = ROW.matcher(line);
			Assert.assertTrue(where + ": unexpected row: '" + line + "'", row.matches());
			Assert.assertEquals(where, ladder.types[i].name, row.group(1));
			double weightShare = 100 * ladder.weights[i] / ladder.totalWeight;
			Assert.assertEquals(where + ": weight of " + row.group(1), ladder.weights[i], Double.parseDouble(row.group(2)), 0.051);
			Assert.assertEquals(where + ": weight share of " + row.group(1), weightShare, Double.parseDouble(row.group(3)), 0.051);
			double actual = Double.parseDouble(row.group(4));
			Assert.assertEquals(where + ": actual share of " + row.group(1) + " vs its weight", weightShare, actual, tolerancePercent);
			totalActual += actual;
		}
		// Each share is rounded to 0.1%
		Assert.assertEquals(where + ": actual shares must add up to 100%", 100, totalActual, 0.05 * ladder.types.length + 1e-9);

		if (ladder.types.length == 1) {
			Assert.assertEquals(where + ": a locked climate never changes type", "0.0", header.group(4));
			Assert.assertEquals(0, largestStep);
		}
		if (!climate.forbidden.isEmpty()) {
			String never = lines.get(lines.size() - 1);
			Assert.assertTrue(where + ": " + never, never.startsWith("  Never: "));
			for (WeatherParam param : climate.forbidden)
				Assert.assertTrue(where + ": " + never + " should list " + param.key, never.contains(param.key));
		}
	}

	private static List<String> rowNames(List<String> lines) {
		List<String> names = new ArrayList<>();
		for (String line : lines) {
			Matcher row = ROW.matcher(line);
			if (row.matches())
				names.add(row.group(1));
		}
		return names;
	}

	private static List<String> reply(WeatherManager wm, String... args) {
		String[] full = new String[args.length + 1];
		full[0] = "weather";
		System.arraycopy(args, 0, full, 1, args.length);
		List<String> out = WeatherCommands.handle(wm, full);
		Assert.assertFalse("'" + String.join(" ", full) + "' gave no reply", out.isEmpty());
		for (String line : out)
			Assert.assertFalse("'" + String.join(" ", full) + "' threw: " + line, line.startsWith("Weather command failed"));
		return out;
	}

	private static void assertReply(WeatherManager wm, String expected, String... args) {
		List<String> out = reply(wm, args);
		Assert.assertTrue("'" + String.join(" ", args) + "' replied " + out + ", expected a line containing \"" + expected + "\"",
			out.stream().anyMatch(line -> line.contains(expected)));
	}
}
