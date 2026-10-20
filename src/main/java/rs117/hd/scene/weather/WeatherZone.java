package rs117.hd.scene.weather;

import javax.annotation.Nullable;
import rs117.hd.config.SeasonalTheme;

import static rs117.hd.scene.weather.WeatherConstants.*;

/**
 * A climate in a given season, optionally with a developer-forced weather type. Maps timeline segments to
 * weather types, intensities and parameter states.
 */
public final class WeatherZone {
	public final String key;
	public final Climate climate;
	public final SeasonalTheme season;
	public final ClimateLadder ladder;
	@Nullable
	public final WeatherType forcedType;
	@Nullable
	public final WeatherType heldType;
	public final float forcedIntensity;
	public final long forcedFromPhase;

	public WeatherZone(Climate climate, SeasonalTheme season) {
		this(climate, season, null, null, Float.NaN, Long.MIN_VALUE);
	}

	public WeatherZone(
		Climate climate,
		SeasonalTheme season,
		@Nullable WeatherType forcedType,
		@Nullable WeatherType heldType,
		float forcedIntensity,
		long forcedFromPhase
	) {
		this.climate = climate;
		this.season = season;
		this.ladder = climate.ladderFor(season);
		this.forcedType = forcedType;
		this.heldType = forcedType == null ? null : heldType;
		this.forcedIntensity = forcedIntensity;
		this.forcedFromPhase = forcedFromPhase;
		this.key = key(climate, season, forcedType, this.heldType, forcedIntensity, forcedFromPhase);
	}

	public static String key(
		Climate climate,
		SeasonalTheme season,
		@Nullable WeatherType forcedType,
		@Nullable WeatherType heldType,
		float forcedIntensity,
		long forcedFromPhase
	) {
		String key = climate.name + "/" + season;
		if (forcedType != null) {
			key += "/F:" + forcedType.name + "@" + forcedFromPhase;
			if (heldType != null)
				key += "/H:" + heldType.name;
			if (!Float.isNaN(forcedIntensity))
				key += "/I:" + forcedIntensity;
		}
		return key;
	}

	public boolean isForced() {
		return forcedType != null;
	}

	private boolean isOverridden(WeatherTimeline.Segment segment) {
		return forcedType != null && (segment.start >= forcedFromPhase || heldType != null);
	}

	public WeatherType typeFor(WeatherTimeline.Segment segment) {
		if (forcedType != null) {
			if (segment.start >= forcedFromPhase)
				return forcedType;
			if (heldType != null)
				return heldType;
		}
		return ladder.typeAt(segment.latent);
	}

	public float intensityFor(WeatherTimeline.Segment segment) {
		if (isOverridden(segment)) {
			if (segment.start >= forcedFromPhase && !Float.isNaN(forcedIntensity))
				return forcedIntensity;
			return (float) segment.latent;
		}
		return (float) ladder.intensity(segment.latent, ladder.bin(segment.latent));
	}

	/** Write the segment's pure (untransitioned) parameter state into out. */
	public void stateFor(WeatherTimeline.Segment segment, float[] out) {
		WeatherType type = typeFor(segment);
		float intensity = intensityFor(segment);
		float[] mild = climate.mild(type);
		float[] severe = climate.severe(type);
		for (int i = 0; i < out.length; i++)
			out[i] = mild[i] + (severe[i] - mild[i]) * intensity;
	}

	/** Half the length of the transition window centred on the boundary between two consecutive segments. */
	public long halfWidthMs(WeatherTimeline.Segment a, WeatherTimeline.Segment b) {
		WeatherType from = typeFor(a);
		WeatherType to = typeFor(b);
		float minutes;
		if (from == to) {
			minutes = SAME_TYPE_BLEND_MINUTES;
		} else if (to.severity > from.severity) {
			minutes = to.onsetMinutes;
		} else if (to.severity < from.severity) {
			minutes = from.clearMinutes;
		} else {
			minutes = (from.clearMinutes + to.onsetMinutes) / 2;
		}
		long halfWidth = (long) (minutes * 30_000);
		long cap = (long) (MAX_HALF_WIDTH_FRACTION * Math.min(a.duration(), b.duration()));
		return Math.min(halfWidth, cap);
	}

	@Override
	public String toString() {
		return key;
	}
}
