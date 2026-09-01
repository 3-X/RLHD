package rs117.hd.scene.lights;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.GsonUtils;

import static rs117.hd.utils.MathUtils.*;

/** Solar-altitude schedules in degrees. */
@Slf4j
public class LightSchedule {
	public static final float DEFAULT_RANDOM_OFFSET = 2.7f;

	public Turn turn = Turn.ON;
	public Range[] during;
	public float randomOffset = DEFAULT_RANDOM_OFFSET;

	public enum Turn {
		ON,
		OFF
	}

	public enum Phase {
		// The old 0-.22 LightTimeOfDay range, expressed as sun altitudes.
		DAWN(5, -2, Range.Mode.ASCENDING),
		DAY(-2, 5, Range.Mode.BOTH),
		DUSK(5, -2, Range.Mode.DESCENDING),
		NIGHT(5, -2, Range.Mode.BOTH),
		// The old .65-1 LightTimeOfDay range, expressed as sun altitudes.
		DEEP_NIGHT(-8.8f, -18, Range.Mode.BOTH),
		TWILIGHT;

		final float from;
		final float through;
		final Range.Mode mode;

		Phase(float from, float through, Range.Mode mode) {
			this.from = from;
			this.through = through;
			this.mode = mode;
		}

		Phase() {
			from = through = Float.NaN;
			mode = null;
		}
	}

	public static class Range {
		public enum Mode {
			BOTH,
			ASCENDING,
			DESCENDING
		}

		public final float from;
		public final float through;
		public final Mode mode;

		private Range(float from, float through, Mode mode) {
			this.from = from;
			this.through = through;
			this.mode = mode;
		}
	}

	/** Return this schedule's [0, 1] activation factor at the current sun altitude. */
	public float getActivation(float sunAltitude, boolean sunDescending, float offset) {
		sunAltitude -= offset;
		float rangeActivation = 0;
		for (Range range : during)
			rangeActivation = max(rangeActivation, getRangeActivation(range, sunAltitude, sunDescending));
		return turn == Turn.ON ? rangeActivation : 1 - rangeActivation;
	}

	public boolean normalize() {
		return during != null && during.length > 0;
	}

	private static float getRangeActivation(Range range, float sunAltitude, boolean sunDescending) {
		float transition = 1 - smoothstep(range.through, range.from, sunAltitude);
		switch (range.mode) {
			case BOTH:
				return transition;
			case ASCENDING:
				return sunDescending ? 0 : 4 * transition * (1 - transition);
			case DESCENDING:
				return sunDescending ? 4 * transition * (1 - transition) : 0;
		}
		throw new IllegalStateException("Unhandled light schedule range mode: " + range.mode);
	}

	public static class Adapter extends TypeAdapter<LightSchedule> {
		@Override
		public LightSchedule read(JsonReader in) throws IOException {
			var schedule = new LightSchedule();
			if (in.peek() == JsonToken.STRING) {
				schedule.during = readPhaseRanges(in.nextString(), in);
				return schedule;
			}

			in.beginObject();
			while (in.hasNext()) {
				switch (in.nextName()) {
					case "turn":
						schedule.turn = Turn.valueOf(in.nextString());
						break;
					case "during":
						schedule.during = readRanges(in);
						break;
					case "randomOffset":
						schedule.randomOffset = (float) in.nextDouble();
						if (schedule.randomOffset < 0)
							throw new IOException("Schedule randomOffset must not be negative");
						break;
					default:
						throw new IOException("Unknown light schedule property: " + in.getPath());
				}
			}
			in.endObject();

			return schedule;
		}

		private static Range[] readRanges(JsonReader in) throws IOException {
			if (in.peek() != JsonToken.BEGIN_ARRAY)
				return readRange(in);

			var ranges = new ArrayList<Range>();
			in.beginArray();
			while (in.hasNext())
				for (Range range : readRange(in))
					ranges.add(range);
			in.endArray();
			return ranges.toArray(Range[]::new);
		}

		private static Range[] readRange(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.STRING)
				return readPhaseRanges(in.nextString(), in);

			if (in.peek() != JsonToken.BEGIN_OBJECT)
				throw new IOException("Expected a named phase or { from, through } range at " + in.getPath());

			Float from = null;
			Float through = null;
			in.beginObject();
			while (in.hasNext()) {
				switch (in.nextName()) {
					case "from":
						from = readTime(in, true);
						break;
					case "through":
						through = readTime(in, false);
						break;
					default:
						throw new IOException("Unknown light schedule range property: " + in.getPath());
				}
			}
			in.endObject();

			if (from == null || through == null)
				return new Range[0];
			return new Range[] { new Range(from, through, Range.Mode.BOTH) };
		}

		private static Range[] readPhaseRanges(String value, JsonReader in) throws IOException {
			try {
				Phase phase = Phase.valueOf(value);
				if (phase == Phase.TWILIGHT)
					return new Range[] {
						new Range(Phase.DAWN.from, Phase.DAWN.through,
							Phase.DAWN.mode),
						new Range(Phase.DUSK.from, Phase.DUSK.through,
							Phase.DUSK.mode)
					};
				return new Range[] { new Range(phase.from, phase.through, phase.mode) };
			} catch (IllegalArgumentException ex) {
				throw new IOException("Unknown light schedule phase '" + value + "' at " + in.getPath(), ex);
			}
		}

		private static Float readTime(JsonReader in, boolean from) throws IOException {
			if (in.peek() == JsonToken.STRING) {
				String value = in.nextString();
				try {
					Phase phase = Phase.valueOf(value);
					if (phase == Phase.TWILIGHT) {
						log.warn("TWILIGHT cannot be used as a schedule endpoint at {}; dropping range", GsonUtils.location(in));
						return null;
					}
					return from ? phase.from : phase.through;
				} catch (IllegalArgumentException ex) {
					throw new IOException("Unknown light schedule phase '" + value + "' at " + in.getPath(), ex);
				}
			}

			float altitude = (float) in.nextDouble();
			if (altitude < -90 || altitude > 90)
				throw new IOException("Light schedule altitudes must be between -90 and 90 degrees");
			return altitude;
		}

		@Override
		public void write(JsonWriter out, LightSchedule schedule) throws IOException {
			Phase phase = getPhase(schedule.during);
			if (phase != null && schedule.turn == Turn.ON && schedule.randomOffset == DEFAULT_RANDOM_OFFSET) {
				out.value(phase.name());
				return;
			}

			out.beginObject();
			if (schedule.turn != Turn.ON) {
				out.name("turn").value(schedule.turn.name());
			}
			out.name("during");
			writeRanges(out, schedule.during, phase);
			if (schedule.randomOffset != DEFAULT_RANDOM_OFFSET)
				out.name("randomOffset").value(schedule.randomOffset);
			out.endObject();
		}

		private static void writeRanges(JsonWriter out, Range[] ranges, Phase phase) throws IOException {
			if (phase != null) {
				out.value(phase.name());
			} else if (ranges.length == 1) {
				writeRange(out, ranges[0]);
			} else {
				out.beginArray();
				for (Range range : ranges)
					writeRange(out, range);
				out.endArray();
			}
		}

		private static void writeRange(JsonWriter out, Range range) throws IOException {
			Phase phase = getPhase(range);
			if (phase != null) {
				out.value(phase.name());
				return;
			}

			out.beginObject();
			out.name("from").value(range.from);
			out.name("through").value(range.through);
			out.endObject();
		}

		private static Phase getPhase(Range[] ranges) {
			if (ranges.length == 1)
				return getPhase(ranges[0]);
			if (ranges.length != 2)
				return null;

			Phase first = getPhase(ranges[0]);
			Phase second = getPhase(ranges[1]);
			if (first == Phase.DAWN && second == Phase.DUSK || first == Phase.DUSK && second == Phase.DAWN)
				return Phase.TWILIGHT;

			return null;
		}

		private static Phase getPhase(Range range) {
			for (Phase phase : Phase.values()) {
				if (phase != Phase.TWILIGHT &&
					range.from == phase.from && range.through == phase.through &&
					range.mode == phase.mode)
					return phase;
			}
			return null;
		}
	}
}
