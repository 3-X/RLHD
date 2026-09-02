package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import rs117.hd.utils.HDUtils;

@RequiredArgsConstructor
@Getter
public enum DaylightCycle {
	// Moving sun and moon driven by the configured cycle duration and Day Length.
	DYNAMIC("Dynamic", false, false, false, false, false, true, Double.NaN, false, 0, 0, false, false, false, false, false, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real Time", false, true, false, true, false, false, Double.NaN, false, 0, 0, false, false, false, false, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	SYNCED_DAYS("Synced Days", false, false, true, true, false, false, Double.NaN, false, 0, 0, true, false, false, false, false, false),
	// 6.65h (just after sunrise). Reproduces the old date-based Fixed Dawn at the equator.
	FIXED_DAWN("Fixed Dawn", true, false, false, false, false, true, 6.65, false, 7.8f, 90.2f, false, false, false, true, false, false),
	// 14h (mid-afternoon), matching the cycle-off sun: altitude 52°, azimuth 235°.
	FIXED_MIDDAY("Fixed Midday", true, false, false, false, false, true, 14, true, 52, 235, false, false, false, true, false, false),
	// 18.1h. Sun on the horizon in the west.
	FIXED_SUNSET("Fixed Sunset", true, false, false, false, false, true, 18.1, false, 0, 272, false, false, false, true, false, false),
	// 18.3h. Sun just below the horizon - Fixed Sunset's position before it moved onto it.
	FIXED_TWILIGHT("Fixed Twilight", true, false, false, false, false, true, 18.3, false, -2.5f, 270, false, false, false, true, false, false),
	// 0h (midnight). Only its negative altitude matters; Always Night shares this sun position.
	FIXED_NIGHT("Fixed Night", true, false, false, false, false, true, 0, false, -88, 261.1f, false, true, true, false, false, true),
	// Keeps the sun down, but unlike Fixed Night leaves the moon moving and phased normally.
	ALWAYS_NIGHT("Always Night", true, false, false, false, true, true, 0, false, -88, 261.1f, false, false, false, false, true, true),
	;

	private final String name;
	public final boolean isFixed;
	public final boolean usesLocalTime;
	public final boolean usesUtcSyncedTime;
	/** Whether moon calculations use the current cycle instant directly. */
	public final boolean usesCurrentInstantForMoon;
	/** Whether celestial calculations use the dynamic simulated date. */
	public final boolean usesDynamicCelestialDate;
	/**
	 * Whether Day Length warps a moving moon's simulated cycle.
	 */
	public final boolean usesDayLengthForMoon;
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
	/**
	 * Locks the moon disk and moon-shadow direction to Fixed Night's position.
	 */
	private final boolean locksMoonPosition;
	/**
	 * Uses a full moon unless an environment or config phase lock takes priority.
	 */
	private final boolean locksMoonIllumination;
	private final boolean hidesMoon;
	/**
	 * Uses Fixed Night's moon altitude for lighting only.
	 */
	private final boolean usesFixedMoonAltitudeForLighting;
	private final boolean permanentNight;

	public float[] getFixedSunAngles() {
		return HDUtils.sunAngles(fixedSunAltitudeDegrees, fixedSunAzimuthDegrees);
	}

	@Override
	public String toString() {
		return name;
	}
}
