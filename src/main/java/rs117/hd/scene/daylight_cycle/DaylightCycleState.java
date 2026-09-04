package rs117.hd.scene.daylight_cycle;

import javax.annotation.Nullable;

public final class DaylightCycleState {
	@Nullable
	public float[] skySunAngles;
	@Nullable
	public float[] skyMoonAngles;
	public float[] sunAngles;
	public float[] moonAngles;
	public float[] sunDirection;
	public float[] moonDirection;
	public float[] moonPhaseLightDirection;
	public boolean moonPhaseReversed;
	public float[] moonLibration;
	public float[] celestialPole;
	public float celestialRotation;
	public float moonIllumination;
	public float moonAltitudeDegrees;
	public boolean hidesMoon;
	public boolean hidesSun;
	public float auroraStrength;
}
