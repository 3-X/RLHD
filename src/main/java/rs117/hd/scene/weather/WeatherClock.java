package rs117.hd.scene.weather;

/**
 * The weather clock: wall-clock UTC, plus an optional developer offset and time-lapse scale. Weather never
 * follows the day/night cycle's sky time, so custom cycle lengths can't desynchronize players.
 */
public final class WeatherClock {
	public static final double MAX_SCALE = 3600;

	private long anchorClock;
	private long anchorWall;
	private double scale = 1;
	private long offsetMs;

	public long now(long wallMs) {
		return anchorClock + (long) ((wallMs - anchorWall) * scale) + offsetMs;
	}

	/** Change the rate of time without a discontinuity. 0 pauses the weather. */
	public void setScale(double newScale, long wallMs) {
		if (!Double.isFinite(newScale))
			throw new IllegalArgumentException("The clock scale must be finite");
		long base = now(wallMs) - offsetMs;
		anchorClock = base;
		anchorWall = wallMs;
		scale = Math.max(0, Math.min(MAX_SCALE, newScale));
	}

	public void addOffset(long deltaMs) {
		offsetMs += deltaMs;
	}

	public void reset() {
		anchorClock = 0;
		anchorWall = 0;
		scale = 1;
		offsetMs = 0;
	}

	public double getScale() {
		return scale;
	}

	/** How far ahead of (or behind) real time the weather clock currently is. */
	public long getEffectiveOffset(long wallMs) {
		return now(wallMs) - wallMs;
	}

	public boolean isRealTime(long wallMs) {
		return scale == 1 && getEffectiveOffset(wallMs) == 0;
	}
}
