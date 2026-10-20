package rs117.hd.scene.weather;

import javax.annotation.Nullable;

import static rs117.hd.scene.weather.WeatherConstants.*;

/**
 * Builds a {@link WeatherStrip} from the timeline and a blend of zones without allocating.
 * <p>
 * Every zone shares the same timeline, so the segment boundaries are identical for all of them; only the
 * per-segment states and window widths differ. Window half-widths are averaged by zone weight and the edge
 * shape is taken from the label zone, which makes zone mixing commute with easing: the strip evaluated at
 * any upwind distance u equals the true blended weather at phase + u / FRONT_SPEED.
 */
public final class WeatherSampler {
	private final WeatherTimeline.Segment[] segments = new WeatherTimeline.Segment[STRIP_SIZE];
	private final float[] scratch = new float[WeatherParam.VECTOR_SIZE];

	/**
	 * @param zones      zones to mix
	 * @param weights    zone weights, summing to 1
	 * @param count      number of zones
	 * @param labelZone  zone providing type labels and edge shapes, normally the dominant zone
	 * @param phase      timeline phase at the strip origin
	 * @param forbidMask per-slot multipliers for forbidden parameters, or null
	 */
	public void sample(
		WeatherTimeline timeline,
		WeatherZone[] zones,
		float[] weights,
		int count,
		WeatherZone labelZone,
		long phase,
		@Nullable float[] forbidMask,
		WeatherStrip strip
	) {
		final int center = STRIP_CENTER;
		segments[center] = timeline.segmentAt(phase);
		for (int i = center - 1; i >= 0; i--)
			segments[i] = timeline.previous(segments[i + 1]);
		for (int i = center + 1; i < STRIP_SIZE; i++)
			segments[i] = timeline.next(segments[i - 1]);

		for (int m = 0; m < STRIP_SIZE; m++) {
			var segment = segments[m];
			float[] out = strip.params[m];
			java.util.Arrays.fill(out, 0);
			for (int z = 0; z < count; z++) {
				float w = weights[z];
				if (w <= 0)
					continue;
				zones[z].stateFor(segment, scratch);
				for (int i = 0; i < out.length; i++)
					out[i] += w * scratch[i];
			}
			if (forbidMask != null)
				for (int i = 0; i < out.length; i++)
					out[i] *= forbidMask[i];

			strip.seeds[m] = segment.seed;
			strip.types[m] = labelZone.typeFor(segment);
			strip.intensities[m] = labelZone.intensityFor(segment);
			strip.segmentStart[m] = segment.start;
			strip.segmentEnd[m] = segment.end;
		}

		for (int i = 0; i < STRIP_SIZE - 1; i++) {
			var a = segments[i];
			var b = segments[i + 1];
			double halfWidth = 0;
			for (int z = 0; z < count; z++)
				if (weights[z] > 0)
					halfWidth += weights[z] * zones[z].halfWidthMs(a, b);

			int severityChange = strip.types[i + 1].severity - strip.types[i].severity;
			double latentChange = b.latent - a.latent;
			strip.edgeShape[i] = severityChange > 0 ? 1 : severityChange < 0 ? -1 :
				latentChange > 1e-9 ? 1 : latentChange < -1e-9 ? -1 : 0;
			strip.edgeDistance[i] = (float) ((b.start - phase) * (double) FRONT_SPEED / 1000);
			strip.edgeWidth[i] = (float) (2 * halfWidth * FRONT_SPEED / 1000);
		}

		strip.axisX = timeline.driftX();
		strip.axisZ = timeline.driftZ();
		strip.valid = true;
	}

	/** Sample a single zone, e.g. for forecasts or points away from the camera. */
	public void sample(WeatherTimeline timeline, WeatherZone zone, long phase, @Nullable float[] forbidMask, WeatherStrip strip) {
		singleZone[0] = zone;
		sample(timeline, singleZone, SINGLE_WEIGHT, 1, zone, phase, forbidMask, strip);
		singleZone[0] = null;
	}

	private final WeatherZone[] singleZone = new WeatherZone[1];
	private static final float[] SINGLE_WEIGHT = { 1 };
}
