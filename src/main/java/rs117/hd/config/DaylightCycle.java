package rs117.hd.config;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle {
	OFF("Off", false, true, false, null, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	DEFAULT("Default", true, true, false, null, true, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real-Time", false, true, false, null, false, false),
	DAWN("Dawn", false, false, false, "DAWN", false, false),
	// Sun just after sunrise, matching the former date-based mode at the equator.
	SUNRISE("Sunrise", false, false, false, "SUNRISE", false, false),
	DAY("Day", false, false, false, "DAY", false, false),
	SUNSET("Sunset", false, false, false, "SUNSET", false, false),
	DUSK("Dusk", false, false, false, "DUSK", false, false),
	// Keeps the sun below the south-west horizon, opposite the default static moon position.
	NIGHT("Night", false, false, false, "NIGHT", false, true),
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
	/** Named fixed sky preset, or null to use astronomical angles. */
	@Nullable
	public final String skyPreset;
	public final boolean forcesNorthernHemisphere;
	public final boolean permanentNight;

	@Override
	public String toString() {
		return name;
	}
}
