package rs117.hd.scene.daylight_cycle;

public final class DaylightCycleState {
	public float[] fixedSunAnglesOverride;
	public float[] fixedMoonAngles;
	public float[] sunAngles;
	public float[] moonAngles;
	public float[] sunDirection;
	public float[] moonDirection;
	/** Solar direction used to orient the moon terminator. */
	public float[] moonSunDirection;
	/** Optical lunar libration in radians: {longitude, latitude}. */
	public float[] moonLibration;
	public float[] celestialPole;
	public float celestialRotation;
	public float moonIllumination;
	public float moonAltitudeDegrees;
	public float moonAltitudeDegreesForLighting;
	public boolean hidesMoon;
	public float auroraStrength;
}
