package rs117.hd.scene.weather;

/**
 * Tuning constants shared by the weather controller. Several of these form a contract with future
 * consumers (clouds), so changing them changes what every player sees.
 */
public final class WeatherConstants {
	private WeatherConstants() {}

	/** Length of one timeline epoch in phase milliseconds. Every epoch boundary is also a segment boundary. */
	public static final long EPOCH_MS = 86_400_000L;
	/** 2020-01-01T00:00Z, the time origin of the steering flow and gust noise. */
	public static final long TIME_ORIGIN_MS = 1_577_836_800_000L;

	/** The strip holds the segments k-3 ... k+3 around the camera's segment k. */
	public static final int STRIP_SIZE = 7;
	public static final int STRIP_CENTER = STRIP_SIZE / 2;

	/** Speed at which weather systems travel along the drift axis, in local units per weather-second (0.25 tiles/s). */
	public static final float FRONT_SPEED = 32;
	/**
	 * Cloud contract: clouds must be fully faded out at this horizontal distance from the focal point, in local units
	 * (625 tiles). Chosen for a cloud deck roughly 3000-4000 local units above the camera.
	 */
	public static final float FRONT_VISIBLE_DISTANCE = 80_000;
	/** Cirrus thickens this far upwind of the main deck, in local units (25 weather-minutes). */
	public static final float HIGH_DECK_LEAD_DISTANCE = 48_000;
	public static final float HIGH_DECK_LEAD_FACTOR = .85f;
	/** Transition windows are at most this fraction of either neighbouring segment, so they can never overlap. */
	public static final float MAX_HALF_WIDTH_FRACTION = .45f;
	/** Guaranteed strip coverage on either side of the camera, measured in minimum segment lengths. */
	public static final float COVERAGE_SEGMENTS = STRIP_CENTER - MAX_HALF_WIDTH_FRACTION;
	/** Window length for boundaries where the weather type stays the same and only the intensity drifts. */
	public static final float SAME_TYPE_BLEND_MINUTES = 20;

	public static final float CLIMATE_BLEND_SECONDS = 30;
	public static final float FORBID_MASK_SECONDS = 3;
	/** Matches EnvironmentManager's transition duration. */
	public static final float EXPOSURE_RAMP_SECONDS = 3;
	/** Matches EnvironmentManager's SKIP_TRANSITION_DISTANCE, which is private. */
	public static final int SNAP_DISTANCE_TILES = 41;
	/** A weather clock discontinuity larger than this (e.g. the system clock changing) forces a snap. */
	public static final long CLOCK_JUMP_SNAP_MS = 600_000L;

	/** Tile at which the timeline phase equals the weather clock (near Lumbridge). Purely cosmetic. */
	public static final int PHASE_ORIGIN_X = 3200;
	public static final int PHASE_ORIGIN_Y = 3200;

	public static final float WIND_STRENGTH_MAX = 100;
	public static final float WIND_SPEED_MAX = 40;
	/** Fraction of the steering flow's deviation applied to the surface wind angle (up to about +-16 degrees). */
	public static final float VEER_FRACTION = .4f;
	/**
	 * Fraction of pi beyond which windAlign fades out, as the upper wind approaches the direction directly opposite the
	 * surface wind, where the shortest arc changes side. It starts past the shipped geometry: the desert's 270 degree
	 * baseline under the 40 degree drift stays within about 154 degrees (0.855 pi) of the upper wind.
	 */
	public static final float WIND_ALIGN_FADE_START = .9f;
	public static final float GUST_STRENGTH_DEPTH = .5f;
	public static final float GUST_SPEED_DEPTH = .25f;
	public static final double GUST_PERIOD_A = 4;
	public static final double GUST_PERIOD_B = 1.7;

	/** Rebase the uploaded legacy wind offset on a scene change once it exceeds this many local units. */
	public static final double LEGACY_OFFSET_SOFT_REBASE = 4096;
	/** Forced rebase, before float precision visibly degrades the wind animation. */
	public static final double LEGACY_OFFSET_HARD_REBASE = 1 << 20;

	public static final double[] STEERING_PERIOD_HOURS = { 6.1, 14.3, 37.7 };
	/** Local units per second. Keeps the steering speed within roughly 11.5 - 52.4 u/s and its deviation under 40 degrees. */
	public static final double[] STEERING_AMPLITUDE = { 9, 6.5, 5 };
	public static final int[] STEERING_SIGN = { 1, -1, 1 };

	public static final int FORECAST_MAX_HOURS = 72;
	public static final int MAX_ZONES = 8;

	/** Upwind distance guaranteed to be covered by the strip, given the shortest possible segment. */
	public static float coverageUnits(float minSegmentMinutes) {
		return minSegmentMinutes * 60 * FRONT_SPEED * COVERAGE_SEGMENTS;
	}

	/** Upwind distance the strip must cover for the cloud contract to hold, including the high-deck lead. */
	public static float requiredCoverageUnits() {
		return FRONT_VISIBLE_DISTANCE + HIGH_DECK_LEAD_DISTANCE;
	}
}
