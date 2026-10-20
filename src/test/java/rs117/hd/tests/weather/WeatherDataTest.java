package rs117.hd.tests.weather;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.weather.Climate;
import rs117.hd.scene.weather.ClimateBinding;
import rs117.hd.scene.weather.ClimateLadder;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.scene.weather.WeatherType;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;

public class WeatherDataTest {
	private static final Gson GSON = GsonUtils.wrap(new Gson());
	private static final int GRID_STEP = 8;

	/** x, y, the expected climate (NONE = sheltered, null = unbound) and a description. All on plane 0. */
	private static final Object[][] SPOT_CHECKS = {
		{ 3222, 3218, "TEMPERATE", "Lumbridge" },
		{ 3212, 3428, "TEMPERATE", "Varrock" },
		{ 3300, 3230, "DESERT", "Al Kharid" },
		{ 3359, 2970, "DESERT", "Pollnivneach" },
		{ 3260, 2900, "DESERT_DEEP", "deep desert" },
		{ 3493, 3489, "SWAMP", "Canifis" },
		{ 2660, 3660, "SUBARCTIC", "Rellekka" },
		{ 2780, 3860, "ARCTIC", "Trollweiss" },
		{ 3008, 3477, "ARCTIC", "Ice Mountain" },
		{ 2899, 3544, "TEMPERATE", "Burthorpe" },
		{ 2760, 3178, "TROPICAL", "Brimhaven" },
		{ 2856, 3168, "VOLCANIC", "Karamja volcano" },
		{ 1680, 3110, "SAVANNA", "Fortis" },
		{ 3090, 3560, "WASTELAND", "Edgeville Wilderness" },
		{ 3200, 3950, "WILDERNESS_DEEP", "deep Wilderness" },
		{ 2960, 3900, "ARCTIC", "Frozen Waste Plateau" },
		{ 3047, 2850, "LOCKED_TEMPEST", "Tempoross" },
		{ 1630, 4000, "LOCKED_BLIZZARD", "Wintertodt" },
		{ 1650, 3800, "TEMPERATE_GLOOMY", "Arceuus" },
		{ 1740, 3600, "TEMPERATE", "Hosidius" },
		{ 1250, 3630, "SWAMP", "Kebos" },
		{ 3810, 3815, "VOLCANIC", "Fossil Island volcano" },
		{ 2140, 4830, "NONE", "Cosmic altar" },
		{ 1950, 7100, "NONE", "player-owned house" },
		{ 3420, 4060, "WASTELAND", "Daimon's Crater" },
		{ 3560, 4000, "SUBARCTIC", "Lithkren" },
		{ 3264, 6080, "TEMPERATE", "Prifddinas" },
		{ 2300, 4150, "SUBARCTIC", "northern sea" },
		{ 2200, 2500, "TROPICAL", "southern sea" },
		{ 2140, 5020, "SWAMP", "Temple Trekking instance" },
		{ 2208, 4830, null, "Death altar" },
		{ 3200, 9800, null, "cave" },
	};

	/** A small, valid weather.json. Single quotes are swapped for double quotes before parsing. */
	private static final String BASE =
		"{"
		+ "'defaultClimate': 'TEST',"
		+ "'timeline': { 'name': 'TEST', 'segmentMinutes': [ 30, 90 ], 'volatility': 0.3, 'driftAngle': 40 },"
		+ "'types': ["
		+ "  { 'name': 'CLEAR', 'severity': 0, 'onsetMinutes': 20, 'clearMinutes': 20, 'params': { 'cloudCover': [ 0, 0.1 ] } },"
		+ "  { 'name': 'OVERCAST', 'severity': 2, 'onsetMinutes': 30, 'clearMinutes': 25, 'params': { 'cloudCover': [ 0.85, 1 ] } },"
		+ "  { 'name': 'RAIN', 'severity': 3, 'onsetMinutes': 20, 'clearMinutes': 15, 'params': { 'cloudCover': 1, 'rain': [ 0.35, 0.8 ] } }"
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

	private static String loadError(JsonObject root) throws IOException {
		try {
			WeatherData.parse(root).bind(WeatherTestData.areas(), true);
		} catch (IOException ex) {
			return ex.getMessage();
		}
		Assert.fail("Expected the weather data to be rejected");
		return null;
	}

	/** Every area defined in areas.json, without the ALL and NONE areas the plugin appends. */
	private static List<Area> definedAreas() throws IOException {
		List<Area> list = new ArrayList<>();
		for (Area area : WeatherTestData.areas())
			if (area != Area.ALL && area != Area.NONE)
				list.add(area);
		return list;
	}

	private static List<String> rawBindingAreaNames(JsonObject root, int binding) {
		List<String> names = new ArrayList<>();
		JsonObject obj = root.getAsJsonArray("bindings").get(binding).getAsJsonObject();
		JsonArray areas = obj.getAsJsonArray("areas");
		if (areas != null)
			for (JsonElement e : areas)
				names.add(e.getAsString());
		return names;
	}

	// ---------------------------------------------------------------------------------------------
	// Shipped data
	// ---------------------------------------------------------------------------------------------

	@Test
	public void shippedJsonLoadsWithoutErrors() throws IOException {
		JsonObject root = WeatherTestData.loadShippedJson();
		WeatherData.Definitions definitions = WeatherData.parse(root);
		WeatherData data = definitions.bind(WeatherTestData.areas(), true);

		Assert.assertNotNull(data.timeline);
		Assert.assertNotNull(data.steering);
		Assert.assertEquals(root.getAsJsonArray("types").size(), data.types.size());
		Assert.assertEquals(root.getAsJsonArray("climates").size(), data.climates.size());
		Assert.assertEquals(root.get("defaultClimate").getAsString(), data.defaultClimate.name);
		// No binding was dropped for covering nothing
		int bindings = root.getAsJsonArray("bindings").size();
		Assert.assertEquals(bindings, data.bindings.size());
		for (int i = 0; i < bindings; i++) {
			ClimateBinding binding = data.bindings.get(i);
			Assert.assertEquals(i, binding.index);
			Assert.assertNotNull(binding.toString(), binding.area);
			Assert.assertTrue(binding.toString(), binding.area.aabbs.length > 0);
			Assert.assertEquals(binding.toString(), binding.isNone(), Climate.NONE.equals(binding.climateName));
			if (!binding.isNone())
				Assert.assertSame(binding.toString(), data.climate(binding.climateName), binding.climate);
		}

		// The test helper's cached copy matches
		WeatherData shipped = WeatherTestData.shipped();
		Assert.assertEquals(data.types.keySet(), shipped.types.keySet());
		Assert.assertEquals(data.climates.keySet(), shipped.climates.keySet());
		Assert.assertEquals(data.bindings.size(), shipped.bindings.size());
	}

	/** The plugin binds strictly in development mode, where Area.normalize also rejects out-of-range boxes. */
	@Test
	public void shippedDataBindsInDevelopmentMode() throws IOException {
		WeatherData.Definitions definitions = WeatherData.parse(WeatherTestData.loadShippedJson());
		Area[] areas = WeatherTestData.areas();
		boolean development = Props.DEVELOPMENT;
		Props.DEVELOPMENT = true;
		WeatherData data;
		try {
			data = definitions.bind(areas, true);
		} finally {
			Props.DEVELOPMENT = development;
		}
		Assert.assertEquals(WeatherTestData.loadShippedJson().getAsJsonArray("bindings").size(), data.bindings.size());
	}

	@Test
	public void allBindingAreaNamesResolve() throws IOException {
		Set<String> defined = new HashSet<>();
		for (Area area : definedAreas())
			defined.add(area.name);

		JsonObject root = WeatherTestData.loadShippedJson();
		List<String> missing = new ArrayList<>();
		int references = 0;
		for (int i = 0; i < root.getAsJsonArray("bindings").size(); i++) {
			for (String name : rawBindingAreaNames(root, i)) {
				references++;
				if ("ALL".equals(name) || "NONE".equals(name) || !defined.contains(name))
					missing.add("binding #" + i + ": " + name);
			}
		}
		Assert.assertTrue("no area references found", references > 0);
		Assert.assertTrue("unknown area names:\n" + String.join("\n", missing), missing.isEmpty());

		// The bound data keeps every name
		WeatherData data = WeatherTestData.shipped();
		for (ClimateBinding binding : data.bindings)
			Assert.assertEquals(rawBindingAreaNames(root, binding.index), Arrays.asList(binding.areaNames));
	}

	@Test
	public void everyTypeAndClimateReferenced() throws IOException {
		WeatherData data = WeatherTestData.shipped();

		Set<WeatherType> usedTypes = new HashSet<>();
		for (Climate climate : data.climates.values())
			for (ClimateLadder ladder : climate.ladders())
				usedTypes.addAll(Arrays.asList(ladder.types));
		List<String> unusedTypes = new ArrayList<>();
		for (WeatherType type : data.types.values())
			if (!usedTypes.contains(type))
				unusedTypes.add(type.name);
		Assert.assertTrue("weather types no climate uses: " + unusedTypes, unusedTypes.isEmpty());

		Set<Climate> boundClimates = new HashSet<>();
		for (ClimateBinding binding : data.bindings)
			if (binding.climate != null)
				boundClimates.add(binding.climate);
		List<String> unboundClimates = new ArrayList<>();
		for (Climate climate : data.climates.values())
			if (!boundClimates.contains(climate))
				unboundClimates.add(climate.name);
		Assert.assertTrue("climates no binding uses: " + unboundClimates, unboundClimates.isEmpty());

		// The loader warns about exactly these, so the shipped file must load without warnings
		Assert.assertEquals(List.of(), WeatherData.parse(WeatherTestData.loadShippedJson()).warnings);
	}

	/**
	 * Scans every binding's own area on a grid at plane 0 (plus each box's centre and far corner, so boxes smaller than
	 * the grid are sampled too), and requires each binding to be the first match somewhere. A binding that never wins
	 * is fully shadowed by earlier bindings.
	 */
	@Test
	public void everyBindingWinsAtLeastOneGridPoint() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		List<String> shadowed = new ArrayList<>();
		for (ClimateBinding binding : data.bindings) {
			Map<String, Integer> winners = new TreeMap<>();
			int samples = 0;
			boolean wins = false;
			scan:
			for (AABB aabb : binding.area.aabbs) {
				if (aabb.minZ > 0 || aabb.maxZ < 0)
					continue;
				for (int x : samples(aabb.minX, aabb.maxX)) {
					for (int y : samples(aabb.minY, aabb.maxY)) {
						ClimateBinding winner = data.resolve(x, y, 0);
						Assert.assertNotNull("a point inside " + binding + " resolved to nothing: " + x + ", " + y, winner);
						Assert.assertTrue(winner.index <= binding.index);
						samples++;
						if (winner == binding) {
							wins = true;
							break scan;
						}
						winners.merge(winner.toString(), 1, Integer::sum);
					}
				}
			}
			if (!wins) {
				shadowed.add(String.format(Locale.ROOT, "%s %s (%s): %d plane-0 samples, all claimed by %s",
					binding, Arrays.toString(binding.areaNames), binding.description, samples, winners));
			}
		}
		Assert.assertTrue("bindings that never win:\n" + String.join("\n", shadowed), shadowed.isEmpty());
	}

	/**
	 * A finer version of the check above: every area a binding lists must give that binding at least one point. An area
	 * entirely claimed by earlier bindings is dead data, and usually means the author expected a climate there that
	 * never applies.
	 */
	@Test
	public void everyListedAreaTakesEffect() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		List<Area> areas = definedAreas();
		List<String> dead = new ArrayList<>();
		for (ClimateBinding binding : data.bindings) {
			for (String name : binding.areaNames) {
				Area area = null;
				for (Area a : areas) {
					if (a.name.equals(name)) {
						area = a;
						break;
					}
				}
				Assert.assertNotNull(name, area);
				Map<String, Integer> winners = new TreeMap<>();
				if (!winsInside(data, binding, area, winners))
					dead.add(String.format(Locale.ROOT, "%s lists '%s', but every sampled point of it is claimed by %s",
						binding, name, winners));
			}
		}
		Assert.assertTrue("area entries that never take effect:\n" + String.join("\n", dead), dead.isEmpty());
	}

	/** Whether the binding wins at some sampled point of the area, trying plane 0 first and then the upper planes. */
	private static boolean winsInside(WeatherData data, ClimateBinding binding, Area area, Map<String, Integer> winners) {
		for (int plane = 0; plane < 4; plane++) {
			for (AABB aabb : area.aabbs) {
				if (aabb.minZ > plane || aabb.maxZ < plane)
					continue;
				for (int x : samples(aabb.minX, aabb.maxX)) {
					for (int y : samples(aabb.minY, aabb.maxY)) {
						ClimateBinding winner = data.resolve(x, y, plane);
						if (winner == binding)
							return true;
						if (plane == 0)
							winners.merge(String.valueOf(winner), 1, Integer::sum);
					}
				}
			}
		}
		return false;
	}

	private static int[] samples(int min, int max) {
		Set<Integer> values = new LinkedHashSet<>();
		for (int v = min; v <= max; v += GRID_STEP)
			values.add(v);
		values.add((min + max) >>> 1);
		values.add(max);
		return values.stream().mapToInt(Integer::intValue).toArray();
	}

	@Test
	public void spotChecks() throws IOException {
		WeatherData data = WeatherTestData.shipped();
		List<String> failures = new ArrayList<>();
		for (Object[] check : SPOT_CHECKS) {
			int x = (int) check[0];
			int y = (int) check[1];
			String expected = (String) check[2];
			ClimateBinding binding = data.resolve(x, y, 0);
			String actual = binding == null ? null : binding.climateName;
			if (!Objects.equals(expected, actual))
				failures.add(String.format(Locale.ROOT, "(%d, %d) %s: expected %s, got %s",
					x, y, check[3], expected == null ? "unbound" : expected, describe(data, x, y)));
		}
		Assert.assertTrue(failures.size() + " spot checks failed:\n" + String.join("\n", failures), failures.isEmpty());
	}

	/** The winning binding, every binding containing the point and every area from areas.json containing it. */
	private static String describe(WeatherData data, int x, int y) throws IOException {
		ClimateBinding winner = data.resolve(x, y, 0);
		List<String> bindings = new ArrayList<>();
		for (ClimateBinding binding : data.bindings) {
			if (!binding.contains(x, y, 0))
				continue;
			List<String> via = new ArrayList<>();
			for (String name : binding.areaNames)
				for (Area area : definedAreas())
					if (area.name.equals(name) && area.containsPoint(false, x, y, 0))
						via.add(name);
			bindings.add(binding + (via.isEmpty() ? " (aabbs/regions)" : " via " + via));
		}
		List<String> areas = new ArrayList<>();
		for (Area area : definedAreas())
			if (area.containsPoint(false, x, y, 0))
				areas.add(area.name);
		return (winner == null ? "unbound" : winner.climateName + " from " + winner + " (" + winner.description + ")")
			+ "\n      bindings containing it: " + bindings
			+ "\n      areas containing it: " + areas;
	}

	// ---------------------------------------------------------------------------------------------
	// Loader behaviour
	// ---------------------------------------------------------------------------------------------

	@Test
	public void syntheticBaseLoads() throws IOException {
		WeatherData data = WeatherData.parse(json(BASE)).bind(WeatherTestData.areas(), true);
		Assert.assertEquals(1, data.bindings.size());
		Assert.assertEquals("TEST", data.resolve(3222, 3218, 0).climateName);
		Assert.assertNull(data.resolve(3100, 3100, 0));
	}

	@Test
	public void loaderReportsAllErrorsAtOnce() throws IOException {
		JsonObject root = json(BASE);
		// An unused weather type with an invalid severity
		root.getAsJsonArray("types").add(json(
			"{ 'name': 'BROKEN', 'severity': 9, 'onsetMinutes': 20, 'clearMinutes': 20, 'params': {} }"));
		// An unbound climate with a typo in a key
		root.getAsJsonArray("climates").add(json(
			"{ 'name': 'TYPO', 'wind': { 'angle': 40, 'speed': 15, 'strength': 50 }, 'colour': 'red',"
			+ "  'ladder': [ [ 'CLEAR', 1 ] ] }"));
		// A binding that covers nothing
		root.getAsJsonArray("bindings").add(json("{ 'climate': 'TEST' }"));

		String message = loadError(root);
		Assert.assertTrue(message, message.startsWith("Invalid weather data (3 errors)"));
		Assert.assertTrue(message, message.contains("BROKEN") && message.contains("severity"));
		Assert.assertTrue(message, message.contains("TYPO") && message.contains("colour"));
		Assert.assertTrue(message, message.contains("binding #1"));
	}

	@Test
	public void loaderReportsTypeAndLadderErrorsTogether() throws IOException {
		JsonObject root = json(BASE);
		// An out-of-range parameter in a type nothing uses, and an unknown type in an unbound climate's ladder
		root.getAsJsonArray("types").add(json(
			"{ 'name': 'WILD', 'severity': 1, 'onsetMinutes': 20, 'clearMinutes': 20, 'params': { 'cloudCover': 7 } }"));
		root.getAsJsonArray("climates").add(json(
			"{ 'name': 'LOST', 'wind': { 'angle': 40, 'speed': 15, 'strength': 50 }, 'ladder': [ [ 'CLEAR', 1 ], [ 'MIST', 1 ] ] }"));

		String message = loadError(root);
		Assert.assertTrue(message, message.startsWith("Invalid weather data (2 errors)"));
		Assert.assertTrue(message, message.contains("WILD") && message.contains("cloudCover"));
		Assert.assertTrue(message, message.contains("LOST") && message.contains("MIST"));
	}

	@Test
	public void timelineErrorDoesNotHideClimateErrors() throws IOException {
		JsonObject root = json(BASE);
		// An invalid volatility, and an independent typo in an unbound climate
		root.getAsJsonObject("timeline").addProperty("volatility", 0.9);
		root.getAsJsonArray("climates").add(json(
			"{ 'name': 'TYPO', 'wind': { 'angle': 40, 'speed': 15, 'strength': 50 }, 'colour': 'red',"
			+ "  'ladder': [ [ 'CLEAR', 1 ] ] }"));

		String message = loadError(root);
		Assert.assertTrue(message, message.contains("volatility"));
		Assert.assertTrue("the climate error must be reported alongside the timeline error:\n" + message,
			message.contains("TYPO") && message.contains("colour"));
		// The valid climate TEST must not be reported as unknown just because the timeline is broken
		Assert.assertFalse("spurious cascade errors:\n" + message, message.contains("unknown climate 'TEST'"));
		Assert.assertTrue(message, message.startsWith("Invalid weather data (2 errors)"));
	}

	@Test
	public void strictBindRejectsUnknownArea() throws IOException {
		JsonObject root = json(BASE);
		root.getAsJsonArray("bindings").add(json(
			"{ 'climate': 'TEST', 'areas': [ 'KINGDOM_OF_MISTHALIN_REGION', 'NO_SUCH_AREA_117' ] }"));
		WeatherData.Definitions definitions = WeatherData.parse(root);
		try {
			definitions.bind(WeatherTestData.areas(), true);
			Assert.fail("Expected strict binding to reject an unknown area name");
		} catch (IOException ex) {
			Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("NO_SUCH_AREA_117"));
			Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("binding #1"));
			Assert.assertFalse(ex.getMessage(), ex.getMessage().contains("KINGDOM_OF_MISTHALIN_REGION"));
		}
	}

	@Test
	public void lenientBindOnlyWarnsAboutUnknownArea() throws IOException {
		JsonObject root = json(BASE);
		// Binding #1 keeps its known area, binding #2 is left with nothing
		root.getAsJsonArray("bindings").add(json(
			"{ 'climate': 'TEST', 'areas': [ 'KINGDOM_OF_MISTHALIN_REGION', 'NO_SUCH_AREA_117' ] }"));
		root.getAsJsonArray("bindings").add(json("{ 'climate': 'TEST', 'areas': [ 'ANOTHER_MISSING_AREA_117' ] }"));
		WeatherData.Definitions definitions = WeatherData.parse(root);

		Logger logger = (Logger) LoggerFactory.getLogger(WeatherData.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level previousLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		WeatherData data;
		try {
			data = definitions.bind(WeatherTestData.areas(), false);
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
		}

		Assert.assertEquals(2, data.bindings.size());
		Assert.assertEquals(0, data.bindings.get(0).index);
		Assert.assertEquals(1, data.bindings.get(1).index);
		// The known area still binds: Varrock is in Misthalin but outside binding #0's box
		Assert.assertEquals(0, data.resolve(3222, 3218, 0).index);
		ClimateBinding varrock = data.resolve(3212, 3428, 0);
		Assert.assertNotNull(varrock);
		Assert.assertEquals(1, varrock.index);
		// Nothing else got bound
		Assert.assertNull(data.resolve(3200, 9800, 0));

		List<String> warnings = new ArrayList<>();
		for (ILoggingEvent event : appender.list)
			if (event.getLevel() == Level.WARN)
				warnings.add(event.getFormattedMessage());
		String all = String.join("\n", warnings);
		Assert.assertTrue("expected a warning about the unknown area:\n" + all, all.contains("NO_SUCH_AREA_117"));
		Assert.assertTrue("expected a warning about the unknown area:\n" + all, all.contains("ANOTHER_MISSING_AREA_117"));
		Assert.assertTrue("expected a warning about the empty binding:\n" + all, all.contains("binding #2"));
	}

	@Test
	public void bindingsResolveInOrder() throws IOException {
		JsonObject root = json(BASE);
		root.getAsJsonArray("climates").add(json(
			"{ 'name': 'OTHER', 'wind': { 'angle': 40, 'speed': 15, 'strength': 50 }, 'ladder': [ [ 'CLEAR', 1 ] ] }"));
		// Overlaps binding #0 entirely, so it only wins outside it
		root.getAsJsonArray("bindings").add(json("{ 'climate': 'OTHER', 'aabbs': [ [ 3100, 3100, 3300, 3300 ] ] }"));
		root.getAsJsonArray("bindings").add(json("{ 'climate': 'NONE', 'aabbs': [ [ 3000, 3000, 3400, 3400 ] ] }"));
		WeatherData data = WeatherData.parse(root).bind(WeatherTestData.areas(), true);

		Assert.assertEquals("TEST", data.resolve(3230, 3230, 0).climateName);
		Assert.assertEquals("OTHER", data.resolve(3150, 3150, 0).climateName);
		ClimateBinding sheltered = data.resolve(3050, 3050, 0);
		Assert.assertTrue(sheltered.isNone());
		Assert.assertNull(sheltered.climate);
		Assert.assertNull(data.resolve(2900, 3050, 0));
		// Boxes given as four numbers cover every plane
		Assert.assertEquals("TEST", data.resolve(3230, 3230, 3).climateName);
	}

	@Test
	public void spotCheckTableIsDistinct() {
		// Guards against copy-paste slips in the table itself
		Map<String, Object[]> seen = new LinkedHashMap<>();
		for (Object[] check : SPOT_CHECKS)
			Assert.assertNull("duplicate spot check " + check[0] + ", " + check[1], seen.put(check[0] + "," + check[1], check));
		Assert.assertEquals(32, SPOT_CHECKS.length);
	}
}
