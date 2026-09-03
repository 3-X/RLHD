package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import rs117.hd.utils.HDUtils;

@RequiredArgsConstructor
@Getter
public enum DaylightCycle {
	OFF("Off", false, false, false, true, false, Double.NaN, false, 0, 0, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	DEFAULT("Default", false, false, true, true, false, Double.NaN, false, 0, 0, true, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real-Time", false, true, false, true, false, Double.NaN, false, 0, 0, false, false),
	// 6.65h (just after sunrise). Reproduces the old date-based Fixed Dawn at the equator.
	DAWN("Dawn", true, false, false, false, false, 6.65, false, 7.8f, 90.2f, false, false),
	// 14h (mid-afternoon), matching the cycle-off sun: altitude 52°, azimuth 235°.
	DAY("Day", true, false, false, false, false, 14, true, 52, 235, false, false),
	// 18.1h. Sun on the horizon in the west.
	SUNSET("Sunset", true, false, false, false, false, 18.1, false, 0, 272, false, false),
	// 18.3h. Sun just below the horizon - Sunset's position before it moved onto it.
	TWILIGHT("Twilight", true, false, false, false, false, 18.3, false, -2.5f, 270, false, false),
	// Keeps the sun down while the moon continues to move and change phase.
	NIGHT("Night", true, false, false, false, false, 0, false, -88, 261.1f, false, true),
	// Moving sun and moon driven by the configured Custom duration and night duration.
	CUSTOM("Custom", false, false, false, false, true, Double.NaN, false, 0, 0, false, false),
	;

	private final String name;
	public final boolean isFixed;
	public final boolean usesLocalTime;
	public final boolean usesUtcSyncedTime;
	/** Whether moon calculations use the current cycle instant directly. */
	public final boolean usesCurrentInstantForMoon;
	/**
	 * Whether the Custom night duration applies.
	 */
	public final boolean usesCustomNightDuration;
	/**
	 * Fixed hour on the selected epoch, or NaN for moving modes.
	 */
	private final double fixedHour;
	private final boolean usesSolsticeEpoch;
	/**
	 * Fixed {altitude, azimuth} angles; ignored by moving modes.
	 */
	private final float fixedSunAltitudeDegrees;
	private final float fixedSunAzimuthDegrees;
	private final boolean forcesNorthernHemisphere;
	private final boolean permanentNight;

	public float[] getFixedSunAngles() {
		return HDUtils.sunAngles(fixedSunAltitudeDegrees, fixedSunAzimuthDegrees);
	}

	@Override
	public String toString() {
		return name;
	}
}
