package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum DaylightCycle
{
	// Fixed-mode sun angles are stored as pre-rotated { azimuth, altitude } degrees.
	//
	// The sky direction calculation rotates a fixed azimuth by 180° relative to an
	// environment-file azimuth. Environment overrides apply that compensation when
	// loaded; these built-in values skip that path, so their literals already include it.
	// To convert an environment-file azimuth to one of these constants, add 180°.
	// These angles are empirical - verify changes in-game rather than deriving them.

	// Moving sun and moon driven by the configured cycle duration and day length.
	DYNAMIC("Dynamic", ClockSource.SIMULATED, Double.NaN, false, 0, 0, false, false, false, false, false, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real Time", ClockSource.LOCAL_TIME, Double.NaN, false, 0, 0, false, false, false, false, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	SYNCED_DAYS("Synced Days", ClockSource.UTC_SYNCED, Double.NaN, false, 0, 0, true, false, false, false, false, false),
	// 6.65h (just after sunrise). Reproduces the old date-based Fixed Dawn at the equator.
	FIXED_DAWN("Fixed Dawn", ClockSource.FIXED, 6.65, false, -89.8, 7.8, false, false, false, true, false, false),
	// 14h (mid-afternoon). Matches the static sun used when the cycle is OFF
	// (Environment.DEFAULT_SUN_ANGLES = altitude 52°, azimuth 235°). Azimuth 55°
	// (= 235° - 180°) makes the cycle-on shadow yaw equal the cycle-off yaw.
	FIXED_MIDDAY("Fixed Midday", ClockSource.FIXED, 14, true, 55, 52, false, false, false, true, false, false),
	// 18.1h. Sun on the horizon in the west. Authored as environment-file angles [0, 272],
	// so the stored azimuth is 272° + 180° = 452° = 92° (mod 360).
	FIXED_SUNSET("Fixed Sunset", ClockSource.FIXED, 18.1, false, 92, 0, false, false, false, true, false, false),
	// 18.3h. Sun just below the horizon - Fixed Sunset's position before it moved onto it.
	FIXED_TWILIGHT("Fixed Twilight", ClockSource.FIXED, 18.3, false, 90, -2.5, false, false, false, true, false, false),
	// 0h (midnight). The azimuth is irrelevant here: the sun is not rendered; only its
	// negative altitude matters for night detection and shadow fade. Always Night shares it.
	FIXED_NIGHT("Fixed Night", ClockSource.FIXED, 0, false, 81.1, -88, false, true, true, false, false, true),
	// Keeps the sun down, but unlike Fixed Night leaves the moon moving and phased normally.
	ALWAYS_NIGHT("Always Night", ClockSource.FIXED, 0, false, 81.1, -88, false, false, false, false, true, true),
	;

	private final String name;
	private final ClockSource clockSource;
	/**
	 * Fixed hour on the selected epoch; NaN for moving modes.
	 */
	private final double fixedHour;
	private final boolean usesSolsticeEpoch;
	/**
	 * Pre-rotated angles used by fixed modes; ignored by moving modes.
	 */
	private final double fixedSunAzimuthDegrees;
	private final double fixedSunAltitudeDegrees;
	/**
	 * UTC-synced skies always use the northern latitude so they match for all players.
	 */
	private final boolean forcesNorthernHemisphere;
	/**
	 * Locks the moon disk and moon-shadow direction to the Fixed Night position.
	 */
	private final boolean locksMoonPosition;
	/**
	 * Uses a permanently full moon unless an environment/config phase lock takes priority.
	 */
	private final boolean locksMoonIllumination;
	/**
	 * Hides the moon disk in locked daytime skies.
	 */
	private final boolean hidesMoon;
	/**
	 * Uses the Fixed Night moon altitude for light-only calculations.
	 */
	private final boolean usesFixedMoonAltitudeForLighting;
	/**
	 * Keeps the sun permanently below the horizon.
	 */
	private final boolean permanentNight;

	public boolean isFixed() {
		return clockSource == ClockSource.FIXED;
	}

	public boolean usesLocalTime() {
		return clockSource == ClockSource.LOCAL_TIME;
	}

	public boolean usesUtcSyncedTime() {
		return clockSource == ClockSource.UTC_SYNCED;
	}

	public double[] getFixedSunAngles() {
		return new double[] { Math.toRadians(fixedSunAzimuthDegrees), Math.toRadians(fixedSunAltitudeDegrees) };
	}

	@Override
	public String toString() {
		return name;
	}

	public enum ClockSource {
		SIMULATED,
		LOCAL_TIME,
		UTC_SYNCED,
		FIXED,
	}
}
