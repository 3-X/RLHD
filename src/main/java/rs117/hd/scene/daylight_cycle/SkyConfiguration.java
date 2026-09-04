package rs117.hd.scene.daylight_cycle;

import com.google.gson.annotations.JsonAdapter;
import javax.annotation.Nullable;
import rs117.hd.scene.environments.Environment.SkyGradient;
import rs117.hd.scene.environments.Environment.SkyLightingProfile;
import rs117.hd.utils.GsonUtils.DegreesToRadians;

/**
 * Fully resolved sky data inherited from a named preset.
 */
public class SkyConfiguration {
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] sunAngles;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] moonAngles;
	public boolean hideSun;
	public boolean hideMoon;
	public boolean permanentNight;
	@Nullable
	public SkyGradient gradient;
	@Nullable
	public SkyLightingProfile lighting;

	@Nullable
	public float[] getSunAngles() {
		return sunAngles;
	}
}
