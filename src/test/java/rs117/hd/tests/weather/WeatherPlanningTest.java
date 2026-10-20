package rs117.hd.tests.weather;

import java.util.Random;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.WeatherManager;
import rs117.hd.scene.weather.WeatherParam;
import rs117.hd.scene.weather.WeatherSampler;
import rs117.hd.scene.weather.WeatherStrip;
import rs117.hd.scene.weather.WeatherTimeline;
import rs117.hd.scene.weather.WeatherType;
import rs117.hd.scene.weather.WeatherZone;

import static rs117.hd.scene.weather.WeatherConstants.*;

/**
 * The cloud-facing contract (sky formula), and the planning behind the developer tools the cloud work relies on:
 * forced roll-ins, jumps to the next front, and forecasts.
 */
public class WeatherPlanningTest {
	private static final long BASE_PHASE = 1_767_225_600_000L; // 2026-01-01T00:00Z
	private static final long DAY_MS = 86_400_000L;

	@Test
	public void skyFormulaMatchesTheLocalParamsAndOnlyAddsTheCirrusLead() throws Exception {
		var data = WeatherTestData.shipped();
		var zone = new WeatherZone(data.climate("TEMPERATE"), SeasonalTheme.SUMMER);
		var strip = new WeatherStrip();
		var sampler = new WeatherSampler();
		int high = WeatherParam.HIGH_CLOUD_COVER.offset();
		Random rng = new Random(0x117_1001L);
		boolean leadSeen = false;
		for (int i = 0; i < 300; i++) {
			long phase = BASE_PHASE + (long) (rng.nextDouble() * 60 * DAY_MS);
			sampler.sample(data.timeline, zone, phase, null, strip);
			for (int slot = 0; slot < WeatherParam.VECTOR_SIZE; slot++)
				Assert.assertEquals("local equals the sky formula at u = 0", strip.evaluateSkySlot(slot, 0), strip.evaluateLocalSlot(slot), 0);
			for (float u = -FRONT_VISIBLE_DISTANCE; u <= FRONT_VISIBLE_DISTANCE; u += 4000) {
				for (int slot = 0; slot < WeatherParam.VECTOR_SIZE; slot++) {
					float bare = strip.evaluateSlot(slot, u);
					float sky = strip.evaluateSkySlot(slot, u);
					if (slot == high) {
						Assert.assertTrue("the cirrus lead only raises high cloud cover", sky >= bare);
						Assert.assertEquals(Math.max(bare, HIGH_DECK_LEAD_FACTOR * strip.evaluateSlot(slot, u + HIGH_DECK_LEAD_DISTANCE)), sky, 0);
						leadSeen |= sky > bare + .01f;
					} else {
						Assert.assertEquals("other parameters are the bare strip", bare, sky, 0);
					}
				}
			}
		}
		Assert.assertTrue("the cirrus lead was exercised", leadSeen);
	}

	@Test
	public void forcedRollInShowsExactlyOneFrontOnTheHorizon() throws Exception {
		var data = WeatherTestData.shipped();
		String[][] cases = { { "TEMPERATE", "CLEAR", "THUNDERSTORM" }, { "TEMPERATE", "OVERCAST", "CLEAR" }, { "DESERT", "CLEAR", "SANDSTORM" },
			{ "ARCTIC", "PARTLY_CLOUDY", "BLIZZARD" }, { "TEMPERATE", "RAIN", "SNOW" } };
		var sampler = new WeatherSampler();
		var strip = new WeatherStrip();
		Random rng = new Random(0x117_1002L);
		for (String[] c : cases) {
			var climate = data.climate(c[0]);
			WeatherType held = data.type(c[1]), type = data.type(c[2]);
			for (int i = 0; i < 200; i++) {
				long phase = BASE_PHASE + (long) (rng.nextDouble() * 365 * DAY_MS);
				float intensity = i % 2 == 0 ? Float.NaN : rng.nextFloat();
				var plan = WeatherManager.planRollIn(data.timeline, climate, SeasonalTheme.SUMMER, type, held, intensity, phase);
				Assert.assertNotNull(plan);
				Assert.assertTrue("the clock only moves forward", plan.horizonPhase >= phase);
				Assert.assertTrue("it never jumps further than needed",
					plan.horizonPhase - phase <= 4 * (long) (data.timeline.settings.maxSegmentMinutes * 60_000));

				var zone = new WeatherZone(climate, SeasonalTheme.SUMMER, type, held, intensity, plan.boundaryStart);
				sampler.sample(data.timeline, zone, plan.horizonPhase, null, strip);
				int fronts = 0;
				for (int e = 0; e < WeatherStrip.SIZE - 1; e++) {
					if (strip.types[e] == strip.types[e + 1])
						continue;
					fronts++;
					float leading = strip.edgeDistance[e] - .5f * strip.edgeWidth[e];
					Assert.assertEquals(String.join("/", c) + ": the front's leading edge is on the horizon", FRONT_VISIBLE_DISTANCE, leading, 1);
					Assert.assertSame(held, strip.types[e]);
					Assert.assertSame(type, strip.types[e + 1]);
				}
				Assert.assertEquals(String.join("/", c) + ": exactly one front in the strip", 1, fronts);
				Assert.assertSame("the held type is overhead", held, strip.types[WeatherStrip.CENTER]);
			}
		}
	}

	@Test
	public void jumpPutsTheNextChangeOnTheHorizon() throws Exception {
		var data = WeatherTestData.shipped();
		var sampler = new WeatherSampler();
		var strip = new WeatherStrip();
		Random rng = new Random(0x117_1003L);
		for (String name : new String[] { "TEMPERATE", "DESERT", "TROPICAL", "SWAMP" }) {
			var zone = new WeatherZone(data.climate(name), SeasonalTheme.SUMMER);
			for (int i = 0; i < 200; i++) {
				long phase = BASE_PHASE + (long) (rng.nextDouble() * 365 * DAY_MS);
				WeatherType wanted = i % 3 == 0 ? zone.ladder.types[zone.ladder.types.length - 1] : null;
				var plan = WeatherManager.planJump(data.timeline, zone, phase, wanted);
				if (plan == null) {
					Assert.assertNotNull("only a specific type may be absent for 72 hours", wanted);
					continue;
				}
				Assert.assertTrue(plan.horizonPhase >= phase);
				Assert.assertNotSame(plan.from, plan.to);
				if (wanted != null)
					Assert.assertSame(wanted, plan.to);

				sampler.sample(data.timeline, zone, plan.horizonPhase, null, strip);
				boolean found = false;
				for (int e = 0; e < WeatherStrip.SIZE - 1; e++) {
					if (strip.segmentStart[e + 1] != plan.boundaryStart)
						continue;
					found = true;
					Assert.assertSame(plan.from, strip.types[e]);
					Assert.assertSame(plan.to, strip.types[e + 1]);
					Assert.assertEquals(name + ": the leading edge is on the horizon", FRONT_VISIBLE_DISTANCE,
						strip.edgeDistance[e] - .5f * strip.edgeWidth[e], 1);
				}
				Assert.assertTrue(name + ": the planned boundary is within the strip", found);
			}
		}
	}

	@Test
	public void forecastSpellsAreContiguousAndReportTrueIntensityRanges() throws Exception {
		var data = WeatherTestData.shipped();
		var zone = new WeatherZone(data.climate("TEMPERATE"), SeasonalTheme.SUMMER);
		Random rng = new Random(0x117_1004L);
		for (int i = 0; i < 100; i++) {
			long phase = BASE_PHASE + (long) (rng.nextDouble() * 365 * DAY_MS);
			long offset = rng.nextInt(10_000_000);
			long duration = 72 * 3_600_000L;
			var spells = WeatherManager.forecast(new WeatherTimeline(data.timeline.settings), zone, phase, offset, duration);
			Assert.assertFalse(spells.isEmpty());
			Assert.assertEquals("starts now", phase + offset, spells.get(0).startClock);
			Assert.assertEquals("ends at the horizon", phase + duration + offset, spells.get(spells.size() - 1).endClock);

			var timeline = new WeatherTimeline(data.timeline.settings);
			for (int s = 0; s < spells.size(); s++) {
				var spell = spells.get(s);
				if (s > 0) {
					Assert.assertEquals("contiguous", spells.get(s - 1).endClock, spell.startClock);
					Assert.assertNotSame("consecutive spells differ", spells.get(s - 1).type, spell.type);
				}
				float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
				for (var seg = timeline.segmentAt(spell.startClock - offset); seg.start < spell.endClock - offset; seg = timeline.next(seg)) {
					Assert.assertSame("every segment in the spell has its type", spell.type, zone.typeFor(seg));
					float intensity = zone.intensityFor(seg);
					min = Math.min(min, intensity);
					max = Math.max(max, intensity);
				}
				Assert.assertEquals("true minimum", min, spell.intensityMin, 0);
				Assert.assertEquals("true maximum", max, spell.intensityMax, 0);
			}
		}
	}

	@Test
	public void loaderReportsCoverageAndVolatilityErrorsTogether() throws Exception {
		var root = WeatherTestData.loadShippedJson();
		var timeline = root.getAsJsonObject("timeline");
		var minutes = new com.google.gson.JsonArray();
		minutes.add(20);
		minutes.add(240);
		timeline.add("segmentMinutes", minutes);
		timeline.addProperty("volatility", .1);
		try {
			rs117.hd.scene.weather.WeatherData.parse(root);
			Assert.fail("expected the timeline to be rejected");
		} catch (java.io.IOException ex) {
			String message = ex.getMessage();
			Assert.assertTrue(message, message.contains("minimum segment length"));
			Assert.assertTrue(message, message.contains("too low"));
			// Both are reported in one pass, rather than one per reload
		}

		// A ladder buffer error is reported alongside a coverage error
		root = WeatherTestData.loadShippedJson();
		minutes = new com.google.gson.JsonArray();
		minutes.add(20);
		minutes.add(90);
		root.getAsJsonObject("timeline").add("segmentMinutes", minutes);
		var ladder = new com.google.gson.JsonArray();
		var clear = new com.google.gson.JsonArray();
		clear.add("CLEAR");
		clear.add(20);
		var storm = new com.google.gson.JsonArray();
		storm.add("THUNDERSTORM");
		storm.add(4);
		ladder.add(clear);
		ladder.add(storm);
		var climate = root.getAsJsonArray("climates").get(0).getAsJsonObject();
		climate.add("ladder", ladder);
		climate.remove("seasons");
		try {
			rs117.hd.scene.weather.WeatherData.parse(root);
			Assert.fail("expected the data to be rejected");
		} catch (java.io.IOException ex) {
			String message = ex.getMessage();
			Assert.assertTrue(message, message.contains("minimum segment length"));
			Assert.assertTrue(message, message.contains("severity steps apart"));
		}
	}
}
