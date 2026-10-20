package rs117.hd.tests.weather;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Arrays;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.WeatherManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.weather.WeatherData;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;

import static rs117.hd.utils.ResourcePath.path;

/**
 * Loads the shipped areas.json and weather.json the same way the plugin does, without a client.
 */
public final class WeatherTestData {
	private static WeatherData data;
	private static Area[] areas;

	private WeatherTestData() {}

	public static synchronized Area[] areas() throws IOException {
		if (areas == null) {
			Props.set("rlhd.resource-path", "src/main/resources");
			Area[] loaded = path(AreaManager.class, "areas.json").loadJson(GsonUtils.wrap(new Gson()), Area[].class);
			Area[] all = Arrays.copyOf(loaded, loaded.length + 2);
			all[all.length - 2] = Area.ALL;
			all[all.length - 1] = Area.NONE;
			AreaManager.AREAS = all;
			for (Area area : loaded)
				area.normalize();
			for (Area area : loaded) {
				if ("OVERWORLD".equals(area.name)) {
					Area.OVERWORLD = area;
					break;
				}
			}
			areas = all;
		}
		return areas;
	}

	public static synchronized WeatherData shipped() throws IOException {
		if (data == null) {
			Area[] all = areas();
			JsonObject root = loadShippedJson();
			data = WeatherData.parse(root).bind(all, true);
		}
		return data;
	}

	public static JsonObject loadShippedJson() throws IOException {
		Props.set("rlhd.resource-path", "src/main/resources");
		return path(WeatherManager.class, "weather.json").loadJson(GsonUtils.wrap(new Gson()), JsonObject.class);
	}

	/** Parse weather data from a JSON string, binding against the shipped areas. */
	public static WeatherData parse(String json) throws IOException {
		Area[] all = areas();
		return WeatherData.parse(GsonUtils.wrap(new Gson()).fromJson(json, JsonObject.class)).bind(all, true);
	}
}
