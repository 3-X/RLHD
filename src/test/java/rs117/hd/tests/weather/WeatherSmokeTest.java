package rs117.hd.tests.weather;

import org.junit.Assert;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.scene.weather.WeatherStrip;
import rs117.hd.scene.weather.WeatherZone;
import rs117.hd.scene.weather.WeatherSampler;

public class WeatherSmokeTest {
	@Test
	public void shippedDataLoadsAndSamples() throws Exception {
		var data = WeatherTestData.shipped();
		Assert.assertEquals(15, data.types.size());
		Assert.assertEquals(14, data.climates.size());
		Assert.assertEquals(19, data.bindings.size());

		var lumbridge = data.resolve(3222, 3218, 0);
		Assert.assertNotNull(lumbridge);
		Assert.assertEquals("TEMPERATE", lumbridge.climateName);

		var zone = new WeatherZone(lumbridge.climate, SeasonalTheme.SUMMER);
		var strip = new WeatherStrip();
		new WeatherSampler().sample(data.timeline, zone, System.currentTimeMillis(), null, strip);
		Assert.assertTrue(strip.valid);
		Assert.assertNotNull(strip.types[WeatherStrip.CENTER]);
	}
}
