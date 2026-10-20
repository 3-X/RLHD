package rs117.hd.scene.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;

import static rs117.hd.HdPlugin.SEED;
import static rs117.hd.scene.weather.WeatherConstants.*;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.murmurHash3;

/**
 * A deterministic sequence of weather segments shared by every climate and every player. Time is split
 * into UTC-aligned epochs, each holding segments of random length. Every segment carries a latent
 * "foulness" value in [0, 1) that follows a reflecting random walk, moving by at most the volatility at
 * each boundary, including across epochs. Any point in time can be looked up directly, so the timeline
 * needs no persistence and can be queried ahead of time.
 */
public final class WeatherTimeline {
	public static final class Settings {
		private static final Set<String> KEYS = Set.of("name", "description", "segmentMinutes", "volatility", "driftAngle");

		/** Seed string. Changing it changes everyone's weather. */
		public final String name;
		public final float minSegmentMinutes, maxSegmentMinutes;
		public final double volatility;
		/** Direction weather systems travel (downwind), in radians. 0 = east, positive rotates toward north. */
		public final float driftAngle;

		public Settings(String name, float minSegmentMinutes, float maxSegmentMinutes, double volatility, float driftAngle) {
			this.name = name;
			this.minSegmentMinutes = minSegmentMinutes;
			this.maxSegmentMinutes = maxSegmentMinutes;
			this.volatility = volatility;
			this.driftAngle = driftAngle;
		}

		/** The timeline's volatility if it is present and valid, otherwise NaN. Lets climates be validated even when other timeline settings aren't. */
		static double peekVolatility(@Nullable JsonObject obj) {
			if (obj == null || !WeatherJson.isNumber(obj.get("volatility")))
				return Double.NaN;
			double volatility = obj.get("volatility").getAsDouble();
			return volatility > 0 && volatility <= .5 ? volatility : Double.NaN;
		}

		@Nullable
		static Settings parse(@Nullable JsonObject obj, List<String> errors) {
			String ctx = "timeline";
			if (obj == null) {
				errors.add(ctx + ": a 'timeline' object is required");
				return null;
			}
			int errorCount = errors.size();
			WeatherJson.checkKeys(obj, KEYS, ctx, errors);
			String name = WeatherJson.getString(obj, "name", ctx, errors);
			if (name == null || name.isEmpty())
				errors.add(ctx + ": 'name' must be a non-empty string");

			float min = 0, max = 0;
			boolean validLengths = false;
			JsonArray segmentMinutes = WeatherJson.getArray(obj, "segmentMinutes", ctx, errors);
			if (segmentMinutes == null || segmentMinutes.size() != 2
				|| !WeatherJson.isNumber(segmentMinutes.get(0)) || !WeatherJson.isNumber(segmentMinutes.get(1))) {
				errors.add(ctx + ": 'segmentMinutes' must be [min, max]");
			} else {
				min = segmentMinutes.get(0).getAsFloat();
				max = segmentMinutes.get(1).getAsFloat();
				validLengths = min >= 10 && min <= max && max <= 240;
				if (!validLengths)
					errors.add(ctx + ": 'segmentMinutes' must satisfy 10 <= min <= max <= 240");
				float covered = coverageUnits(min);
				if (covered < requiredCoverageUnits()) {
					// Round up, so the suggested value itself is accepted
					double required = Math.ceil(requiredCoverageUnits() / (60 * FRONT_SPEED * COVERAGE_SEGMENTS) * 100) / 100;
					errors.add(String.format(Locale.ROOT,
						"%s: the minimum segment length must be at least %.2f minutes for the forecast strip to cover the visible "
						+ "sky (%.0f of %.0f units)",
						ctx, required, covered, requiredCoverageUnits()));
				}
			}

			Double volatility = WeatherJson.getNumber(obj, "volatility", ctx, errors);
			if (volatility == null) {
				errors.add(ctx + ": 'volatility' is required");
			} else if (!(volatility > 0 && volatility <= .5)) {
				errors.add(ctx + ": 'volatility' must be within (0, 0.5]");
			} else if (validLengths) {
				// The walk must always be able to reach the next epoch's starting latent within one epoch
				int minSegments = minSegmentsPerEpoch(min, max);
				if (volatility * (1 + .98 * (minSegments - 1)) < 1) {
					errors.add(String.format(Locale.ROOT,
						"%s: 'volatility' %.3f is too low for epochs of as few as %d segments; it must be at least %.3f",
						ctx, volatility, minSegments, 1 / (1 + .98 * (minSegments - 1))));
				}
			}

			Double driftAngle = WeatherJson.getNumber(obj, "driftAngle", ctx, errors);
			if (driftAngle == null)
				errors.add(ctx + ": 'driftAngle' is required");

			if (errors.size() > errorCount || name == null || volatility == null || driftAngle == null)
				return null;
			// Wrap in double precision first, so huge angles can't overflow when narrowed to float
			float radians = (float) (Math.toRadians(driftAngle) % (2 * Math.PI));
			return new Settings(name, min, max, volatility, WindModel.wrapAngle(radians));
		}
	}

	/** An immutable stretch of timeline phase with a single latent value. */
	public static final class Segment {
		/** Phase milliseconds, start inclusive and end exclusive. */
		public final long start, end;
		public final double latent;
		/** Per-segment seed, e.g. for cloud formation variety. */
		public final int seed;
		public final long epoch;
		public final int index;

		Segment(long start, long end, double latent, int seed, long epoch, int index) {
			this.start = start;
			this.end = end;
			this.latent = latent;
			this.seed = seed;
			this.epoch = epoch;
			this.index = index;
		}

		public long duration() {
			return end - start;
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "Segment[%d.%d %d-%d latent=%.3f]", epoch, index, start, end, latent);
		}
	}

	private static final int CACHE_SIZE = 6;

	public final Settings settings;
	private final long seed;
	private final long minMs, maxMs;
	private final float driftX, driftZ;

	private final long[] cacheKeys = new long[CACHE_SIZE];
	private final long[] cacheStamps = new long[CACHE_SIZE];
	private final Segment[][] cacheValues = new Segment[CACHE_SIZE][];
	private long stamp;

	public WeatherTimeline(Settings settings) {
		this.settings = settings;
		seed = murmurHash3(SEED ^ (long) settings.name.hashCode());
		minMs = (long) (settings.minSegmentMinutes * 60_000);
		maxMs = (long) (settings.maxSegmentMinutes * 60_000);
		if (minMs < 60_000 || maxMs < minMs || maxMs >= EPOCH_MS)
			throw new IllegalArgumentException("Invalid segment lengths: " + settings.minSegmentMinutes + " - " + settings.maxSegmentMinutes);
		if (!(settings.volatility > 0 && settings.volatility <= 1) || !Float.isFinite(settings.driftAngle))
			throw new IllegalArgumentException("Invalid volatility or drift angle");
		driftX = cos(settings.driftAngle);
		driftZ = sin(settings.driftAngle);
	}

	/** Minimum number of segments an epoch can hold for the given segment lengths. */
	static int minSegmentsPerEpoch(float minMinutes, float maxMinutes) {
		long minMs = (long) (minMinutes * 60_000), maxMs = (long) (maxMinutes * 60_000);
		if (maxMs <= 0)
			throw new IllegalArgumentException("The maximum segment length must be positive");
		long t = 0;
		int n = 0;
		while (EPOCH_MS - t > maxMs) {
			n++;
			t += maxMs;
		}
		if (EPOCH_MS - t >= minMs || n == 0)
			n++;
		return n;
	}

	public long seed() {
		return seed;
	}

	/** Unit vector (east, north) along which weather systems travel. */
	public float driftX() {
		return driftX;
	}

	public float driftZ() {
		return driftZ;
	}

	public Segment segmentAt(long phase) {
		Segment[] segments = epoch(Math.floorDiv(phase, EPOCH_MS));
		int lo = 0, hi = segments.length - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (segments[mid].start <= phase) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return segments[lo];
	}

	public Segment next(Segment segment) {
		Segment[] segments = epoch(segment.epoch);
		if (segment.index + 1 < segments.length)
			return segments[segment.index + 1];
		return epoch(segment.epoch + 1)[0];
	}

	public Segment previous(Segment segment) {
		if (segment.index > 0)
			return epoch(segment.epoch)[segment.index - 1];
		Segment[] previous = epoch(segment.epoch - 1);
		return previous[previous.length - 1];
	}

	private Segment[] epoch(long e) {
		long now = ++stamp;
		int oldest = 0;
		for (int i = 0; i < CACHE_SIZE; i++) {
			if (cacheValues[i] != null && cacheKeys[i] == e) {
				cacheStamps[i] = now;
				return cacheValues[i];
			}
			if (cacheValues[i] == null || cacheStamps[i] < cacheStamps[oldest] && cacheValues[oldest] != null)
				oldest = i;
		}
		Segment[] segments = generate(e);
		cacheKeys[oldest] = e;
		cacheValues[oldest] = segments;
		cacheStamps[oldest] = now;
		return segments;
	}

	private static double toUnit(long hash) {
		return (hash >>> 11) * 0x1.0p-53;
	}

	private double startLatent(long e) {
		return toUnit(murmurHash3(seed ^ 0x5EED5EEDL ^ murmurHash3(e)));
	}

	private Segment[] generate(long e) {
		Random rng = new Random(murmurHash3(seed ^ murmurHash3(e)));
		long t = e * EPOCH_MS;
		long end = t + EPOCH_MS;
		long[] starts = new long[(int) (EPOCH_MS / minMs) + 2];
		int n = 0;
		while (end - t > maxMs) {
			starts[n++] = t;
			t += minMs + (long) (rng.nextDouble() * (maxMs - minMs));
		}
		// A remainder shorter than the minimum is merged into the previous segment
		if (end - t >= minMs || n == 0)
			starts[n++] = t;

		double volatility = settings.volatility;
		double[] latent = new double[n];
		latent[0] = startLatent(e);
		double target = startLatent(e + 1);
		for (int k = 1; k < n; k++) {
			double x = latent[k - 1] + (rng.nextDouble() * 2 - 1) * volatility;
			// Reflect at the edges, which never lengthens the move
			if (x < 0) {
				x = -x;
			} else if (x >= 1) {
				x = 2 - x;
			}
			// Stay within reach of the next epoch's starting latent
			double reach = volatility * (n - k) * .98;
			double lo = Math.max(0, target - reach);
			double hi = Math.min(1 - 1e-6, target + reach);
			latent[k] = Math.max(lo, Math.min(hi, x));
		}

		Segment[] segments = new Segment[n];
		for (int k = 0; k < n; k++) {
			long segmentEnd = k + 1 < n ? starts[k + 1] : end;
			segments[k] = new Segment(starts[k], segmentEnd, latent[k], (int) murmurHash3(seed ^ starts[k]), e, k);
		}
		return segments;
	}
}
