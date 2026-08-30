package rs117.hd.scene.daylight_cycle;

/**
 * The celestial state shared by all daylight-cycle lighting work in one frame.
 *
 * <p>DaylightCycleManager owns mutation. Array fields reference its per-frame astronomy
 * cache rather than copying it, so consumers must treat them as read-only.
 */
public final class DaylightCycleState {
	float[] sunAngles;
	float[] sunDirection;
	float[] moonDirection;
	float moonIllumination;
	float moonAltitudeDegrees;
	float moonAltitudeDegreesForLighting;
	boolean hidesMoon;
	float auroraStrength;

	boolean resolved;
}
