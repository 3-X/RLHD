package rs117.hd.scene.daylight_cycle;

/** Mutable per-frame sky snapshot. Only {@code SkyManager} may update it. */
public final class SkyState {
	public SkyConfiguration fromConfiguration;
	public SkyConfiguration toConfiguration;
	public float configurationTransition;
	public float moonDirectionalStrength;
	public float[] sunAngles;
	/** Sun angles used to evaluate the visible sky and its lighting. */
	public float[] skySunAngles;
	public float[] moonAngles;
	/** The sun while above the horizon, otherwise the moon while above it. */
	public float[] shadowAngles;
	public float[] sunDirection;
	public float[] skySunDirection;
	public float[] moonDirection;
	public float[] moonPhaseLightDirection;
	public boolean moonPhaseReversed;
	public float[] moonLibration;
	public float[] celestialPole;
	public float celestialRotation;
	public float moonIllumination;
	public float sunAltitudeDegrees;
	public float skySunAltitudeDegrees;
	public float moonAltitudeDegrees;
	public boolean hidesMoon;
	public boolean hidesSun;
	public boolean permanentNight;
	public float auroraStrength;
}
