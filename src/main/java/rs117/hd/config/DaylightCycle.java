package rs117.hd.config;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import rs117.hd.utils.HDUtils;

@RequiredArgsConstructor
public enum DaylightCycle {
	OFF("Off", false, true, false, null, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	DEFAULT("Default", true, true, false, null, true, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real-Time", false, true, false, null, false, false),
	// Sun just after sunrise, matching the former date-based mode at the equator.
	DAWN("Dawn", false, false, false, HDUtils.sunAngles(7.8f, 90.2f), false, false),
	DAY("Day", false, false, false, HDUtils.sunAngles(52, 235), false, false),
	SUNSET("Sunset", false, false, false, HDUtils.sunAngles(0, 272), false, false),
	TWILIGHT("Twilight", false, false, false, HDUtils.sunAngles(-2.5f, 270), false, false),
	// Keeps the sun below the south-west horizon, opposite the default static moon position.
	NIGHT("Night", false, false, false, HDUtils.sunAngles(-15, 210), false, true),
	// Moving sun and moon driven by the configured Custom duration and night duration.
	CUSTOM("Custom", false, false, true, null, false, false),
	;

	private final String name;
	public final boolean usesUtcSyncedTime;
	/** Whether moon calculations use the current cycle instant directly. */
	public final boolean usesCurrentInstantForMoon;
	/**
	 * Whether the Custom night duration applies.
	 */
	public final boolean usesCustomNightDuration;
	/** Fixed {altitude, azimuth} angles in radians, or null to use astronomical angles. */
	@Nullable
	public final float[] fixedSunAngles;
	public final boolean forcesNorthernHemisphere;
	public final boolean permanentNight;

	@Override
	public String toString() {
		return name;
	}
}
