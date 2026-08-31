package rs117.hd.scene.environments;

import com.google.gson.annotations.JsonAdapter;
import java.util.Objects;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonPhase;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionPredicate;
import rs117.hd.utils.GsonUtils.DegreesToRadians;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.ColorUtils.SrgbToLinearAdapter;
import static rs117.hd.utils.ColorUtils.SrgbAdapter;
import static rs117.hd.utils.ColorUtils.rgb;

@Slf4j
@Setter(value = AccessLevel.PRIVATE)
public class Environment {
	public static final float[] DEFAULT_SUN_ANGLES = HDUtils.sunAngles(52, 235);
	public static final Environment DEFAULT = new Environment()
		.setKey("DEFAULT")
		.setArea(Area.ALL)
		.setFogColor(rgb("#000000"))
		.setWaterColor(rgb("#66eaff"))
		.setSunAngles(DEFAULT_SUN_ANGLES)
		.normalize();
	public static final Environment NONE = new Environment()
		.setKey("NONE")
		.setFogColor(rgb("#ff00ff"))
		.normalize();

	public static Environment OVERWORLD, AUTUMN, WINTER;

	public String key;
	@JsonAdapter(AreaManager.Adapter.class)
	public Area area = Area.NONE;
	public boolean isOverworld = false;
	public boolean isPohTheme = false;
	public boolean isUnderwater = false;
	public boolean force = false;
	public boolean allowSkyOverride = true;
	public boolean allowRoofShadows = true;
	public boolean lightningEffects = false;
	public boolean instantTransition = false;
	@Nullable
	@JsonAdapter(ExpressionParser.PredicateAdapter.class)
	public ExpressionPredicate varbitCondition;
	@Nullable
	@JsonAdapter(ExpressionParser.PredicateAdapter.class)
	public ExpressionPredicate varpCondition;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] ambientColor = rgb("#ffffff");
	public float ambientStrength = 1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] directionalColor = rgb("#ffffff");
	public float directionalStrength = .25f;
	public float moonDirectionalStrength = -1;
	public float moonShadowStrength = 1;
	public float minMoonIllumination = 0;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonLightColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] nightSkyColor;
	public float nightSkyColorStrength = 1;
	@Nullable
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] waterColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] waterCausticsColor;
	public float waterCausticsStrength = -1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] underglowColor = rgb("#000000");
	public float underglowStrength = 0;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] sunAngles; // horizontal coordinate system, in radians
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] fixedSunAngles;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] fixedMoonAngles;
	@Nullable
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] fogColor;
	public float fogDepth = 25;
	public int groundFogStart = -200;
	public int groundFogEnd = -500;
	public float groundFogOpacity = 0;
	@JsonAdapter(DegreesToRadians.class)
	public float windAngle = 0.0f;
	public float windSpeed = 15.0f;
	public float windStrength = 0.0f;
	public float windCeiling = 1280.0f;
	@Nullable
	public DaylightCycle cycleMode;
	@Nullable
	public MoonPhase forceMoonPhase;
	public boolean forceMoonActive = false;
	public float starVisibility = 1;
	public float moonVisibility = 1;
	public float auroraVisibility = -1;
	public float moonSizeMult = 1;
	public float starHorizonHeight = 1;
	public float sunStrength = 1;
	public float sunriseSunsetStrength = 1;
	public float skyColorTakeoverAngle = 40;
	@Nullable
	public SkyGradient skyGradient;
	public float sunlightStrength = 1;
	public float minBrightnessBoost = 0;
	public boolean hideVanillaSkyboxes = false;
	public boolean forceHideNebulas = false;

	public Environment normalize() {
		if (area != Area.ALL && area != Area.NONE) {
			isOverworld = Area.OVERWORLD.intersects(area);
			// Certain nullable fields will fall back to using the current overworld theme's values later,
			// but for environments that aren't part of the overworld, we want to fall back to the default
			// (underground) environment's values for any unspecified fields
			if (!isOverworld && DEFAULT != null) {
				sunAngles = Objects.requireNonNullElse(sunAngles, DEFAULT.sunAngles);
				fogColor = Objects.requireNonNullElse(fogColor, DEFAULT.fogColor);
				waterColor = Objects.requireNonNullElse(waterColor, DEFAULT.waterColor);
			}
		}

		if (sunAngles != null)
			sunAngles = HDUtils.ensureArrayLength(sunAngles, 2);
		if (fixedSunAngles != null)
			fixedSunAngles = HDUtils.ensureArrayLength(fixedSunAngles, 2);
		if (fixedMoonAngles != null)
			fixedMoonAngles = HDUtils.ensureArrayLength(fixedMoonAngles, 2);

		// Default moon color to slightly cool white (~8000K)
		if (moonColor == null)
			moonColor = ColorUtils.colorTemperatureToLinearRgb(8000);

		// When no distinct moonlight color is given, the cast light matches the
		// moon disk (moonColor) - preserving the original single-color behavior.
		if (moonLightColor == null)
			moonLightColor = moonColor;

		// When no distinct night-sky color is given, the sky matches the moon
		// disk (moonColor) - preserving the original single-color behavior.
		if (nightSkyColor == null)
			nightSkyColor = moonColor;

		// When no distinct moonlight strength is given, moonlight is as strong as
		// sunlight - preserving the original behavior, where directionalStrength drove both.
		if (moonDirectionalStrength == -1)
			moonDirectionalStrength = directionalStrength;

		// Base water caustics on directional lighting by default
		if (waterCausticsColor == null)
			waterCausticsColor = directionalColor;
		if (waterCausticsStrength == -1)
			waterCausticsStrength = directionalStrength;

		// When aurora visibility isn't specified, fall back to star visibility so
		// hiding stars also hides auroras (the original coupled behavior). An explicit
		// value decouples the two.
		if (auroraVisibility == -1)
			auroraVisibility = starVisibility;
		return this;
	}

	@Override
	public String toString() {
		if (key != null)
			return key;
		return area.name;
	}

	public static class SkyGradient {
		public Keyframe[] zenith;
		public Keyframe[] horizon;
		public Keyframe[] sunGlow;

		public static class Keyframe {
			public float altitude;
			@JsonAdapter(SrgbAdapter.class)
			public float[] color;
		}
	}
}
