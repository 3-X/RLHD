package rs117.hd.config;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle {
	OFF("Off", null, false, false, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	DEFAULT("Default", null, true, false, false, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real-Time", null, false, false, false, false),
	DAWN("Dawn", "DAWN", false, false, true, false),
	SUNRISE("Sunrise", "SUNRISE", false, false, true, false),
	DAY("Day", "DAY", false, false, true, false),
	SUNSET("Sunset", "SUNSET", false, false, true, false),
	DUSK("Dusk", "DUSK", false, false, true, false),
	// Keeps the sun below the south-west horizon, opposite the default static moon position.
	NIGHT("Night", "NIGHT", false, true, true, false),
	// Moving sun and moon driven by the configured Custom duration and night duration.
	CUSTOM("Custom", null, false, false, true, true),
	;

	private final String name;
	/** Named fixed sky preset, or null to use astronomical angles. */
	@Nullable
	public final String skyPreset;
	public final boolean forcesNorthernHemisphere;
	public final boolean permanentNight;
	/** Use the accumulated custom/static cycle time for moon and aurora timing. */
	public final boolean usesAccumulatedCycleTime;
	/** Apply the configured night-duration warp to accumulated cycle time. */
	public final boolean usesCustomNightDuration;

	@Override
	public String toString() {
		return name;
	}
}
