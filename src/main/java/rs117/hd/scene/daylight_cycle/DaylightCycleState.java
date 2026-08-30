package rs117.hd.scene.daylight_cycle;

/**
 * The celestial state shared by all daylight-cycle lighting work in one frame.
 *
 * <p>DaylightCycleManager resolves and mutates this once at the end of its update. Array
 * fields are shared with the manager, so consumers must treat them as read-only.
 */
final class DaylightCycleState {
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
