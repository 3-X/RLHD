package rs117.hd.scene.daylight_cycle;

/** Per-frame celestial state. DaylightCycleManager mutates it; consumers treat arrays as read-only. */
final class DaylightCycleState {
	// Environment {altitude, azimuth} overrides; fixedMoonAngles also supports Always Night.
	float[] fixedSunAnglesOverride;
	float[] fixedMoonAngles;
	float[] sunAngles;
	float[] moonAngles;
	float[] sunDirection;
	float[] moonDirection;
	float moonIllumination;
	float moonAltitudeDegrees;
	float moonAltitudeDegreesForLighting;
	boolean hidesMoon;
	float auroraStrength;
}
