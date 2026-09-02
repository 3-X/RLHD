package rs117.hd.scene.lights;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import javax.annotation.Nullable;
import rs117.hd.utils.GsonUtils;

/**
 * A seasonal overworld sample, optionally from an explicit world position.
 */
public class OutdoorLighting {
	@Nullable
	public final int[] sampleWorldPos;

	private OutdoorLighting(@Nullable int[] sampleWorldPos) {
		this.sampleWorldPos = sampleWorldPos;
	}

	public static class Adapter extends TypeAdapter<OutdoorLighting> {
		@Override
		public OutdoorLighting read(JsonReader in) throws IOException {
			String location = GsonUtils.location(in);
			switch (in.peek()) {
				case NULL:
					in.nextNull();
					return null;
				case BOOLEAN:
					return in.nextBoolean() ? new OutdoorLighting(null) : null;
				case BEGIN_ARRAY:
					int[] sampleWorldPos = new int[3];
					in.beginArray();
					for (int i = 0; i < sampleWorldPos.length; i++) {
						if (!in.hasNext())
							throw new IOException(
								"Outdoor lighting sample position must contain three coordinates at " + GsonUtils.location(in));
						sampleWorldPos[i] = in.nextInt();
					}
					if (in.hasNext())
						throw new IOException(
							"Outdoor lighting sample position must contain exactly three coordinates at " + GsonUtils.location(in));
					in.endArray();
					return new OutdoorLighting(sampleWorldPos);
				default:
					throw new IOException("Expected a boolean or [ worldX, worldY, plane ] at " + location);
			}
		}

		@Override
		public void write(JsonWriter out, OutdoorLighting outdoorLighting) throws IOException {
			if (outdoorLighting == null) {
				out.nullValue();
			} else if (outdoorLighting.sampleWorldPos == null) {
				out.value(true);
			} else {
				out.beginArray();
				for (int coordinate : outdoorLighting.sampleWorldPos)
					out.value(coordinate);
				out.endArray();
			}
		}
	}
}
