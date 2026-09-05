package rs117.hd.config;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle {
	OFF("Off", null, false, false, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	DEFAULT("Default", null, true, true, false, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real-Time", null, false, false, false, false),
	DAWN("Dawn", "DAWN", false, true, true, false),
	SUNRISE("Sunrise", "SUNRISE", false, true, true, false),
	DAY("Day", "DAY", false, true, true, false),
	SUNSET("Sunset", "SUNSET", false, true, true, false),
	DUSK("Dusk", "DUSK", false, true, true, false),
	NIGHT("Night", "NIGHT", false, true, true, false),
	// Moving sun and moon driven by the configured Custom duration and night duration.
	CUSTOM("Custom", null, false, false, false, true),
	;

	private final String name;
	/** Named fixed sky preset, or null to use astronomical angles. */
	@Nullable
	public final String skyPreset;
	public final boolean forcesNorthernHemisphere;
	/** Use Default's UTC-synchronised simulated time. */
	public final boolean usesDefaultCycleTime;
	/** Apply this cycle's preset angles to the actual sun and moon phase. */
	public final boolean usesPresetSunAngles;
	/** Apply the configured night-duration warp to accumulated cycle time. */
	public final boolean usesCustomNightDuration;

	@Override
	public String toString() {
		return name;
	}
}
