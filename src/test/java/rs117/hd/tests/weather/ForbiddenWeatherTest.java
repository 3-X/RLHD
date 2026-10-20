package rs117.hd.tests.weather;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.weather.Climate;
import rs117.hd.scene.weather.ClimateLadder;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.scene.weather.WeatherParam;
import rs117.hd.scene.weather.WeatherTimeline;
import rs117.hd.scene.weather.WeatherType;
import rs117.hd.scene.weather.WeatherZone;
import rs117.hd.utils.GsonUtils;

import static rs117.hd.scene.weather.WeatherConstants.EPOCH_MS;

public class ForbiddenWeatherTest {
	/** AUTOMATIC is resolved to SUMMER by the manager, but a zone built with it must be just as safe. */
	private static final SeasonalTheme[] ALL_SEASONS = SeasonalTheme.values();
	private static final SeasonalTheme[] SEASONS = { SeasonalTheme.SUMMER, SeasonalTheme.AUTUMN, SeasonalTheme.WINTER };
	private static final long START = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
	private static final int SEGMENTS = 100_000;
	private static final Gson GSON = GsonUtils.wrap(new Gson());

	/** A small, valid weather.json. Single quotes are swapped for double quotes before parsing. */
	private static final String BASE =
		"{"
		+ "'defaultClimate': 'TEST',"
		+ "'timeline': { 'name': 'TEST', 'segmentMinutes': [ 30, 90 ], 'volatility': 0.3, 'driftAngle': 40 },"
		+ "'types': ["
		+ "  { 'name': 'CLEAR', 'severity': 0, 'onsetMinutes': 20, 'clearMinutes': 20, 'params': { 'cloudCover': [ 0, 0.1 ] } },"
		+ "  { 'name': 'OVERCAST', 'severity': 2, 'onsetMinutes': 30, 'clearMinutes': 25, 'params': { 'cloudCover': [ 0.85, 1 ] } },"
		+ "  { 'name': 'DRIZZLE', 'severity': 2, 'onsetMinutes': 25, 'clearMinutes': 20, 'params': { 'cloudCover': 1, 'rain': [ 0.1, 0.2 ] } },"
		+ "  { 'name': 'RAIN', 'severity': 3, 'onsetMinutes': 20, 'clearMinutes': 15, 'params': { 'cloudCover': 1, 'rain': [ 0.35, 0.8 ] } },"
		+ "  { 'name': 'SNOW', 'severity': 3, 'onsetMinutes': 25, 'clearMinutes': 20, 'params': { 'cloudCover': 1, 'snow': [ 0.3, 0.7 ] } }"
		+ "],"
		+ "'climates': ["
		+ "  { 'name': 'TEST', 'wind': { 'angle': 40, 'speed': 15, 'strength': 50 },"
		+ "    'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ], [ 'RAIN', 6 ] ] }"
		+ "],"
		+ "'bindings': [ { 'climate': 'TEST', 'aabbs': [ [ 3200, 3200, 3263, 3263 ] ] } ]"
		+ "}";

	private static JsonObject json(String text) {
		return GSON.fromJson(text.replace('\'', '"'), JsonObject.class);
	}

	/** The base data plus an extra climate named BAD, which nothing binds, so its own errors are the only ones. */
	private static JsonObject withClimate(String climate) {
		JsonObject root = json(BASE);
		root.getAsJsonArray("climates").add(json(climate));
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

	private static void assertSingleError(String message) {
		Assert.assertTrue("expected exactly one error:\n" + message, message.startsWith("Invalid weather data (1 error)"));
	}

	private static List<WeatherZone> allZones(WeatherData data) {
		List<WeatherZone> zones = new ArrayList<>();
		for (Climate climate : data.climates.values())
			for (SeasonalTheme season : ALL_SEASONS)
				zones.add(new WeatherZone(climate, season));
		return zones;
	}

	// ---------------------------------------------------------------------------------------------
	// Shipped data
	// ---------------------------------------------------------------------------------------------

	@Test
	public void forbiddenParamsAreZeroInEveryReachableState() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		List<WeatherZone> zones = allZones(data);
		WeatherTimeline timeline = new WeatherTimeline(data.timeline.settings);

		// The slot mask and the parameter list must agree
		int forbiddenSlots = 0;
		for (Climate climate : data.climates.values()) {
			for (int slot = 0; slot < WeatherParam.VECTOR_SIZE; slot++) {
				boolean listed = climate.forbidden.contains(WeatherParam.ofSlot(slot));
				Assert.assertEquals(climate.name + " slot " + slot, listed, climate.forbidsSlot(slot));
				if (listed)
					forbiddenSlots++;
			}
		}
		Assert.assertTrue("the shipped climates should forbid something", forbiddenSlots > 0);

		float[] out = new float[WeatherParam.VECTOR_SIZE];
		Map<WeatherZone, Set<WeatherType>> reached = new LinkedHashMap<>();
		for (WeatherZone zone : zones)
			reached.put(zone, new HashSet<>());
		List<String> violations = new ArrayList<>();
		long checks = 0;

		WeatherTimeline.Segment segment = timeline.segmentAt(START);
		for (int n = 0; n < SEGMENTS; n++) {
			for (WeatherZone zone : zones) {
				zone.stateFor(segment, out);
				reached.get(zone).add(zone.typeFor(segment));
				for (int slot = 0; slot < out.length; slot++) {
					if (!zone.climate.forbidsSlot(slot))
						continue;
					checks++;
					if (out[slot] != 0 && violations.size() < 25) {
						violations.add(String.format(Locale.ROOT, "%s: %s = %s in %s (%s, intensity %.3f)",
							zone, WeatherParam.ofSlot(slot).key, out[slot], segment, zone.typeFor(segment), zone.intensityFor(segment)));
					}
				}
			}
			segment = timeline.next(segment);
		}
		Assert.assertTrue(checks >= (long) SEGMENTS * forbiddenSlots);
		Assert.assertTrue(String.join("\n", violations), violations.isEmpty());

		// The simulation is long enough to visit every rung of every ladder, and nothing else
		for (var entry : reached.entrySet()) {
			WeatherZone zone = entry.getKey();
			Set<WeatherType> expected = new HashSet<>(Arrays.asList(zone.ladder.types));
			Assert.assertEquals(zone.toString(), expected, entry.getValue());
		}
	}

	@Test
	public void userRequirementExclusions() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		List<String> problems = new ArrayList<>();

		String[] wet = { "SNOW", "SLEET", "BLIZZARD", "RAIN", "DRIZZLE", "THUNDERSTORM" };
		String[] frozen = { "SNOW", "SLEET", "BLIZZARD" };
		String[] liquid = { "RAIN", "DRIZZLE", "SLEET", "THUNDERSTORM" };

		checkNeverReaches(data, "DESERT", ALL_SEASONS, wet, problems);
		checkNeverReaches(data, "DESERT_DEEP", ALL_SEASONS, wet, problems);
		checkNeverReaches(data, "TROPICAL", ALL_SEASONS, frozen, problems);
		checkNeverReaches(data, "SAVANNA", ALL_SEASONS, frozen, problems);
		checkNeverReaches(data, "VOLCANIC", ALL_SEASONS, frozen, problems);
		checkNeverReaches(data, "ARCTIC", ALL_SEASONS, liquid, problems);
		checkNeverReaches(data, "TEMPERATE", new SeasonalTheme[] { SeasonalTheme.SUMMER, SeasonalTheme.AUTUMN }, new String[] { "SNOW", "BLIZZARD" }, problems);

		// Rain and snow are forbidden outright in both deserts, and are exactly 0 in every simulated state
		WeatherTimeline timeline = new WeatherTimeline(data.timeline.settings);
		float[] out = new float[WeatherParam.VECTOR_SIZE];
		for (String name : new String[] { "DESERT", "DESERT_DEEP" }) {
			Climate climate = data.climate(name);
			Assert.assertNotNull(name, climate);
			for (WeatherParam param : new WeatherParam[] { WeatherParam.RAIN, WeatherParam.SNOW })
				if (!climate.forbids(param))
					problems.add(name + " must forbid " + param.key);
			for (SeasonalTheme season : ALL_SEASONS) {
				WeatherZone zone = new WeatherZone(climate, season);
				WeatherTimeline.Segment segment = timeline.segmentAt(START);
				for (int n = 0; n < SEGMENTS; n++) {
					zone.stateFor(segment, out);
					if (out[WeatherParam.RAIN.offset()] != 0 || out[WeatherParam.SNOW.offset()] != 0) {
						problems.add(String.format(Locale.ROOT, "%s: rain %s, snow %s in %s",
							zone, out[WeatherParam.RAIN.offset()], out[WeatherParam.SNOW.offset()], segment));
						break;
					}
					segment = timeline.next(segment);
				}
			}
		}

		Assert.assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	/** Checks the ladders in effect for the seasons, and also a long simulation of each zone. */
	private static void checkNeverReaches(WeatherData data, String climateName, SeasonalTheme[] seasons, String[] typeNames, List<String> problems) {
		Climate climate = data.climate(climateName);
		Assert.assertNotNull(climateName, climate);
		List<WeatherType> excluded = new ArrayList<>();
		for (String typeName : typeNames) {
			WeatherType type = data.type(typeName);
			Assert.assertNotNull(typeName, type);
			excluded.add(type);
		}

		if (seasons == ALL_SEASONS)
			for (WeatherType type : excluded)
				if (climate.reaches(type))
					problems.add(climateName + " can reach " + type + " in some season");

		WeatherTimeline timeline = new WeatherTimeline(data.timeline.settings);
		for (SeasonalTheme season : seasons) {
			ClimateLadder ladder = climate.ladderFor(season);
			for (WeatherType type : excluded)
				if (ladder.contains(type))
					problems.add(climateName + "/" + season + " has " + type + " in its ladder");

			WeatherZone zone = new WeatherZone(climate, season);
			WeatherTimeline.Segment segment = timeline.segmentAt(START);
			for (int n = 0; n < SEGMENTS; n++) {
				WeatherType type = zone.typeFor(segment);
				if (excluded.contains(type)) {
					problems.add(zone + " produced " + type + " in " + segment);
					break;
				}
				segment = timeline.next(segment);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Loader
	// ---------------------------------------------------------------------------------------------

	@Test
	public void syntheticBaseLoads() throws IOException {
		WeatherData data = load(json(BASE));
		Assert.assertEquals(5, data.types.size());
		Assert.assertEquals(1, data.climates.size());
		Assert.assertEquals(1, data.bindings.size());
		Assert.assertEquals("TEST", data.resolve(3222, 3218, 0).climateName);
	}

	@Test
	public void loaderRejectsForbiddenViolation() throws IOException {
		String message = loadError(withClimate(
			"{ 'name': 'BAD', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 }, 'forbid': [ 'snow' ],"
			+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ], [ 'SNOW', 6 ] ] }"));
		assertSingleError(message);
		Assert.assertTrue(message, message.contains("BAD"));
		Assert.assertTrue(message, message.contains("'snow'"));
		Assert.assertTrue(message, message.contains("SNOW"));
	}

	@Test
	public void loaderRejectsForbiddenViolationInSeasonalLadder() throws IOException {
		String message = loadError(withClimate(
			"{ 'name': 'BAD', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 }, 'forbid': [ 'snow' ],"
			+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ], [ 'RAIN', 6 ] ],"
			+ "  'seasons': { 'WINTER': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ], [ 'SNOW', 6 ] ] } }"));
		assertSingleError(message);
		Assert.assertTrue(message, message.contains("BAD") && message.contains("'snow'") && message.contains("SNOW"));
	}

	@Test
	public void loaderRejectsForbiddenViolationFromOverride() throws IOException {
		// CLEAR has no rain of its own, but this climate's override adds some
		String message = loadError(withClimate(
			"{ 'name': 'BAD', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 }, 'forbid': [ 'rain' ],"
			+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ] ],"
			+ "  'overrides': { 'CLEAR': { 'rain': [ 0, 0.05 ] } } }"));
		assertSingleError(message);
		Assert.assertTrue(message, message.contains("BAD") && message.contains("'rain'") && message.contains("CLEAR"));
	}

	@Test
	public void loaderRejectsForbiddenViolationInheritedFromParent() throws IOException {
		// Like DESERT_DEEP, the child declares no forbid list of its own and inherits its parent's
		JsonObject root = withClimate(
			"{ 'name': 'DRY', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 }, 'forbid': [ 'snow', 'rain' ],"
			+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ] ] }");
		root.getAsJsonArray("climates").add(json(
			"{ 'name': 'BAD', 'parent': 'DRY', 'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ], [ 'SNOW', 6 ] ] }"));
		String message = loadError(root);
		assertSingleError(message);
		Assert.assertTrue(message, message.contains("BAD") && message.contains("'snow'") && message.contains("SNOW"));
		Assert.assertFalse(message, message.contains("'DRY'"));
	}

	@Test
	public void overrideRemovingForbiddenParamIsAccepted() throws IOException {
		// DRIZZLE normally rains, but this climate overrides it to a dry drizzle
		WeatherData data = load(withClimate(
			"{ 'name': 'BAD', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 }, 'forbid': [ 'rain' ],"
			+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'DRIZZLE', 8 ] ],"
			+ "  'overrides': { 'DRIZZLE': { 'rain': 0 } } }"));
		Climate climate = data.climate("BAD");
		Assert.assertNotNull(climate);
		Assert.assertTrue(climate.forbids(WeatherParam.RAIN));
		Assert.assertEquals(0, climate.mild(data.type("DRIZZLE"))[WeatherParam.RAIN.offset()], 0);
		Assert.assertEquals(0, climate.severe(data.type("DRIZZLE"))[WeatherParam.RAIN.offset()], 0);

		WeatherZone zone = new WeatherZone(climate, SeasonalTheme.SUMMER);
		WeatherTimeline timeline = data.timeline;
		float[] out = new float[WeatherParam.VECTOR_SIZE];
		boolean sawDrizzle = false;
		WeatherTimeline.Segment segment = timeline.segmentAt(START);
		for (int n = 0; n < 2_000; n++) {
			zone.stateFor(segment, out);
			Assert.assertEquals(segment.toString(), 0, out[WeatherParam.RAIN.offset()], 0);
			sawDrizzle |= zone.typeFor(segment) == data.type("DRIZZLE");
			segment = timeline.next(segment);
		}
		Assert.assertTrue(sawDrizzle);
	}

	@Test
	public void onlyAbsenceParametersAreForbiddable() {
		Set<String> forbiddable = new HashSet<>();
		for (WeatherParam param : WeatherParam.VALUES)
			if (param.forbiddable)
				forbiddable.add(param.key);
		Assert.assertEquals(Set.of("rain", "snow", "ash", "dust", "storminess", "haze", "fog"), forbiddable);
		for (WeatherParam param : WeatherParam.VALUES) {
			if (param.forbiddable) {
				Assert.assertEquals(param.key, WeatherParam.Kind.SCALAR, param.kind);
				Assert.assertEquals(param.key + " must default to absent", 0, param.defaultValue(0), 0);
				Assert.assertEquals(param.key, 0, param.min, 0);
			}
		}
	}

	@Test
	public void loaderRejectsNonForbiddableParams() throws IOException {
		Set<WeatherParam> rejected = EnumSet.noneOf(WeatherParam.class);
		for (WeatherParam param : WeatherParam.VALUES) {
			if (param.forbiddable)
				continue;
			String message = loadError(withClimate(
				"{ 'name': 'BAD', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 }, 'forbid': [ '" + param.key + "' ],"
				+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ] ] }"));
			assertSingleError(message);
			Assert.assertTrue(message, message.contains("BAD"));
			Assert.assertTrue(message, message.contains("'" + param.key + "'"));
			Assert.assertTrue(message, message.contains("cannot be forbidden"));
			rejected.add(param);
		}
		Assert.assertTrue(rejected.contains(WeatherParam.CLOUD_SCALE));
		Assert.assertTrue(rejected.contains(WeatherParam.WIND_STRENGTH));
		Assert.assertTrue(rejected.contains(WeatherParam.WIND_SPEED));
		Assert.assertTrue(rejected.contains(WeatherParam.CLOUD_SPEED));

		// Unknown names are rejected too
		String message = loadError(withClimate(
			"{ 'name': 'BAD', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 }, 'forbid': [ 'lava' ],"
			+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ] ] }"));
		assertSingleError(message);
		Assert.assertTrue(message, message.contains("BAD") && message.contains("lava"));
	}

	@Test
	public void everyForbiddableParamIsAcceptedWhenAbsent() throws IOException {
		StringBuilder forbid = new StringBuilder();
		for (WeatherParam param : WeatherParam.VALUES) {
			if (!param.forbiddable)
				continue;
			if (forbid.length() > 0)
				forbid.append(", ");
			forbid.append('\'').append(param.key).append('\'');
		}
		// CLEAR and OVERCAST only use cloud cover in the base data
		WeatherData data = load(withClimate(
			"{ 'name': 'BAD', 'wind': { 'angle': 0, 'speed': 10, 'strength': 10 }, 'forbid': [ " + forbid + " ],"
			+ "  'ladder': [ [ 'CLEAR', 10 ], [ 'OVERCAST', 8 ] ] }"));
		Climate climate = data.climate("BAD");
		Assert.assertNotNull(climate);
		for (WeatherParam param : WeatherParam.VALUES)
			Assert.assertEquals(param.key, param.forbiddable, climate.forbids(param));
	}
}
