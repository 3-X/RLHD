package rs117.hd.scene.daylight_cycle;

import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.experimental.Accessors;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.uniforms.UBOGlobal;
import rs117.hd.opengl.uniforms.UBOSky;
import rs117.hd.scene.DaylightCycleManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.ColorUtils.linearToSrgb;
import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.ColorUtils.srgbToLinear;
import static rs117.hd.utils.MathUtils.*;

/** Converts {@link DaylightCycleManager}'s celestial state into scene and sky lighting. */
@Singleton
public class SkyLighting {
	private static final float SUN_SHADOW_CUTOFF_DEG = 2;
	private static final float SUN_SHADOW_MIDPOINT_DEG = 12;
	private static final float SUN_SHADOW_FULL_DEG = 15;
	private static final float SUN_SHADOW_MIDPOINT_VISIBILITY = .6f;
	private static final float SUN_SHADOW_DAYTIME_FLOOR = .9f;

	private static final float MOON_HORIZON_CUTOFF_DEG = -10;
	private static final float MIN_MOON_ILLUMINATION = .01f;
	private static final float MOON_ELEVATION_FADE_START_DEG = -10;
	private static final float MOON_ELEVATION_FADE_END_DEG = 20;
	private static final float MOON_SHADOW_STRENGTH = .2f;
	// Square-root phase response keeps waxing and waning shadows visible.
	private static final float MOON_SHADOW_PHASE_EXPONENT = .5f;
	// Preserve part of the authored ambient floor even under a full moon.
	private static final float MIN_BRIGHTNESS_BOOST_RESIDUAL = .2f;
	private static final float MAX_MOON_COLOR_INFLUENCE = .8f;
	private static final float MOON_INFLUENCE_AT_HORIZON = .05f;
	private static final float MOON_TINT_SUN_START_DEG = 5;
	private static final float MOON_TINT_SUN_END_DEG = -15;
	// Environments scale this subtle base tint with nightSkyColorStrength.
	private static final float NIGHT_SKY_TINT_SCALE = .05f;
	private static final float SKY_FILL_FADE_END_DEG = 45;

	private static final float[] NIGHT_SKY_LINEAR = rgb(5, 7, 15);
	private static final float[] SKY_LUMA_WEIGHTS = { .2126f, .7152f, .0722f };

	// {sun altitude degrees, sRGB}; rows must remain sorted.
	private static final float[][] ZENITH_KEYFRAMES = {
		srgbKeyframe(-30, 0x010104),
		srgbKeyframe(-15, 0x03040A),
		srgbKeyframe(-8, 0x2D2346),
		srgbKeyframe(-3, 0x503C64),
		srgbKeyframe(0, 0x645078),
		srgbKeyframe(5, 0x788CB4),
		srgbKeyframe(15, 0x6496C8),
		srgbKeyframe(30, 0x5A91C8),
		srgbKeyframe(50, 0x558CC3),
		srgbKeyframe(90, 0x5087BE),
	};

	private static final float[][] HORIZON_KEYFRAMES = {
		srgbKeyframe(-30, 0x010205),
		srgbKeyframe(-15, 0x04050C),
		srgbKeyframe(-8, 0x3C2D41),
		srgbKeyframe(-3, 0x8C5046),
		srgbKeyframe(0, 0xDC8250),
		srgbKeyframe(5, 0xE6AA78),
		srgbKeyframe(10, 0xC8B4A0),
		srgbKeyframe(20, 0xAAAFB9),
		srgbKeyframe(30, 0x96A5BE),
		srgbKeyframe(50, 0x8CA0BE),
		srgbKeyframe(90, 0x879BB9),
	};

	private static final float[][] SUN_GLOW_KEYFRAMES = {
		srgbKeyframe(-30, 0x000000),
		srgbKeyframe(-10, 0x140A1E),
		srgbKeyframe(-5, 0x50283C),
		srgbKeyframe(-2, 0xB45032),
		srgbKeyframe(0, 0xFF9650),
		srgbKeyframe(5, 0xFFC882),
		srgbKeyframe(15, 0xFFE6B4),
		srgbKeyframe(30, 0xFFFADC),
		srgbKeyframe(50, 0xFFFFF0),
		srgbKeyframe(90, 0xFFFFFA),
	};

	// Sun altitude in degrees mapped to color temperature in kelvin.
	private static final float[][] DIRECTIONAL_TEMPERATURE_KEYFRAMES = {
		{ 3, 2500 }, { 5, 2600 }, { 10, 3000 }, { 15, 3300 }, { 20, 3600 },
		{ 30, 4000 }, { 40, 4300 }, { 50, 4750 }, { 60, 5250 }, { 70, 5500 },
		{ 80, 5750 }, { 90, 6000 }
	};

	// Procedural ambient colors in { sunAltitudeDegrees, linearR, linearG, linearB }.
	private static final float[][] AMBIENT_COLOR_KEYFRAMES = {
		linearKeyframe(-5, 113, 140, 180),
		linearKeyframe(25, 192, 185, 255),
		linearKeyframe(40, 185, 214, 255),
	};

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private DaylightCycleManager daylightCycleManager;

	// Frame lighting; colors are linear except fogColorSrgb.
	public final float[] directionalColor = new float[3];
	public final float[] ambientColor = new float[3];
	public final float[] fogColorSrgb = new float[3];
	public final float[] waterColor = new float[3];
	public float directionalStrength;
	public float ambientStrength;
	public float effectiveDirectionalStrength;

	@Getter
	@Accessors(fluent = true)
	private boolean shouldRenderSky;

	// One outdoor-sky sample per environment, frame, and minimum brightness.
	private OutdoorSkySample cachedOutdoorSkySample;
	private Environment cachedOutdoorSkyEnvironment;
	private int cachedOutdoorSkyMinBrightness;
	private int cachedOutdoorSkyFrame = -1;

	public void initialize() {
		disableSky();
	}

	public void update(UBOGlobal uboGlobal) {
		seedFromEnvironment();

		boolean wasActive = shouldRenderSky;
		shouldRenderSky = daylightCycleManager.isCycleActive();
		if (shouldRenderSky) {
			updateSky();
		} else if (wasActive) {
			disableSky();
		}

		updateGlobalUbo(uboGlobal);
	}

	private void seedFromEnvironment() {
		copyTo(directionalColor, environmentManager.currentDirectionalColor);
		copyTo(ambientColor, environmentManager.currentAmbientColor);
		copyTo(waterColor, environmentManager.currentWaterColor);
		copyTo(fogColorSrgb, ColorUtils.linearToSrgb(environmentManager.currentFogColor));
		directionalStrength = environmentManager.currentDirectionalStrength;
		ambientStrength = environmentManager.currentAmbientStrength;
	}

	private void updateGlobalUbo(UBOGlobal ubo) {
		ubo.fogColor.set(fogColorSrgb);

		float[] waterColorHsv = ColorUtils.srgbToHsv(waterColor);
		ubo.waterColorLight.set(linearToSrgb(ColorUtils.hsvToSrgb(
			waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .8f
		)));
		ubo.waterColorMid.set(linearToSrgb(ColorUtils.hsvToSrgb(
			waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .45f
		)));
		ubo.waterColorDark.set(linearToSrgb(ColorUtils.hsvToSrgb(
			waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .05f
		)));

		float effectiveAmbientStrength = ambientStrength;
		effectiveDirectionalStrength = directionalStrength;
		if (config.useLegacyBrightness()) {
			float factor = (float) config.legacyBrightness() / 20;
			effectiveAmbientStrength *= factor;
			effectiveDirectionalStrength *= factor;
		}
		ubo.ambientStrength.set(effectiveAmbientStrength);
		ubo.ambientColor.set(ambientColor);
		ubo.lightStrength.set(effectiveDirectionalStrength);
		ubo.lightColor.set(directionalColor);
	}

	/** Derive this frame's lighting from the sun and moon. */
	private void updateSky() {
		var state = daylightCycleManager.getState();
		copyTo(directionalColor, getRegionalDirectionalLight(state, directionalColor));
		copyTo(ambientColor, getRegionalAmbientLight(state, ambientColor));

		float brightnessMultiplier = getBrightnessMultiplier(state, plugin.configMinimumBrightness);
		float baseDirectionalStrength = directionalStrength;
		// The cycle controls night brightness instead of seasonal ambient strength.
		ambientStrength = brightnessMultiplier;

		float sunAltDeg = state.sunAngles[0] * RAD_TO_DEG;
		float moonAltDeg = state.moonAltitudeDegrees;
		float moonIllumination = state.moonIllumination;
		float[][] sky = getSkyGradientColors(
			state,
			fogColorSrgb,
			environmentManager.currentSunStrength,
			environmentManager.currentSunriseSunsetStrength,
			environmentManager.currentSkyColorTakeoverAngle
		);

		// Lighting can enforce a minimum moon phase without changing the visible disk.
		float litMoonIllumination = max(moonIllumination, environmentManager.currentMinMoonIllumination);
		float shadowVisibility = computeShadowVisibility(sunAltDeg, moonAltDeg, litMoonIllumination);
		float moonInfluence = computeMoonInfluence(sunAltDeg, moonAltDeg, litMoonIllumination);
		baseDirectionalStrength = applyMoonLighting(sky, moonInfluence, baseDirectionalStrength);

		directionalStrength =
			baseDirectionalStrength * brightnessMultiplier * environmentManager.currentSunlightStrength;
		// Horizon color doubles as fog so geometry meets the skybox.
		copyTo(fogColorSrgb, sky[1]);
		copyTo(waterColor, ColorUtils.srgbToLinear(sky[1]));

		applyAmbientFloor(moonAltDeg, moonIllumination);
		applySkyFill(sunAltDeg, shadowVisibility);
		updateSkyUbo(state, sky, moonIllumination);
	}

	private float applyMoonLighting(float[][] sky, float moonInfluence, float baseDirectionalStrength) {
		if (moonInfluence == 0)
			return baseDirectionalStrength;

		mix(directionalColor, environmentManager.currentMoonLightColor, moonInfluence);
		tintNightSky(sky, moonInfluence);
		// Cap color influence without capping configured moonlight strength.
		float strengthBlend = min(1, moonInfluence / MAX_MOON_COLOR_INFLUENCE);
		return mix(baseDirectionalStrength, environmentManager.currentMoonDirectionalStrength, strengthBlend);
	}

	private void applyAmbientFloor(float moonAltDeg, float moonIllumination) {
		// Use true illumination so the floor replaces only light missing from the sky.
		float boostFraction = MIN_BRIGHTNESS_BOOST_RESIDUAL +
			(1 - MIN_BRIGHTNESS_BOOST_RESIDUAL) * (1 - moonPresence(moonAltDeg, moonIllumination));
		float boostedFloor = plugin.configMinimumBrightness / 100f *
			(1 + environmentManager.currentMinBrightnessBoost * boostFraction);
		ambientStrength = max(ambientStrength, boostedFloor);
	}

	private void applySkyFill(float sunAltDeg, float shadowVisibility) {
		float skyFill = 1 - smoothstep(0, SKY_FILL_FADE_END_DEG, sunAltDeg);
		add(ambientColor, ambientColor, multiply(directionalColor, (1 - shadowVisibility) * skyFill));
		directionalStrength *= shadowVisibility;
	}

	private float[] getRegionalDirectionalLight(DaylightCycleState state, float[] regionalDirectionalColor) {
		float[] sunAngles = state.sunAngles;
		float[] dynamicLight = getDirectionalLightForAngles(sunAngles);
		return mixColor(dynamicLight, regionalDirectionalColor, regionalBlendFactor(sunAngles[0] * RAD_TO_DEG));
	}

	private float[] getRegionalAmbientLight(DaylightCycleState state, float[] regionalAmbientColor) {
		float[] sunAngles = state.sunAngles;
		float[] dynamicAmbient = getAmbientColorForAngles(sunAngles);
		return mixColor(dynamicAmbient, regionalAmbientColor, regionalBlendFactor(sunAngles[0] * RAD_TO_DEG));
	}

	/** Return {zenith, horizon, sun glow} sRGB after regional and night-sky blending. */
	private float[][] getSkyGradientColors(
		DaylightCycleState state,
		float[] regionalFogColor,
		float sunStrength,
		float sunriseSunsetStrength,
		float skyColorTakeoverAngle
	) {
		float sunAltitude = state.sunAngles[0] * RAD_TO_DEG;
		// Bind twilight suppression to daytime takeover to avoid a blue gap after sunrise.
		float takeover = max(0, skyColorTakeoverAngle);
		float[] regionalLin = regionalFogColor != null ? srgbToLinear(regionalFogColor) : null;

		float[] zenith = interpolateSrgb(sunAltitude, ZENITH_KEYFRAMES);
		float[] horizon = interpolateSrgb(sunAltitude, HORIZON_KEYFRAMES);
		float[] sunGlow = interpolateSrgb(sunAltitude, SUN_GLOW_KEYFRAMES);

		// Suppress procedural sunset colors in dark regional environments.
		if (regionalLin != null && sunStrength < 1) {
			float window = sunAltitude >= 0 ? 1 : smoothstep(-25, 0, sunAltitude);
			float suppression = (1 - sunStrength) * window;
			if (suppression > 0) {
				float[] target = mixColor(regionalLin, NIGHT_SKY_LINEAR, smoothstep(5, -5, sunAltitude));
				blendTowards(zenith, target, suppression);
				blendTowards(horizon, target, suppression);
				fadeOut(sunGlow, suppression);
			}
		}

		// Preserve strongly authored regional skies through twilight.
		if (regionalLin != null && sunriseSunsetStrength < 1) {
			float window = sunAltitude < 0
				? smoothstep(-15, 0, sunAltitude)
				: takeover == 0 ? 0 : smoothstep(takeover, 0, sunAltitude);
			float suppression = (1 - sunriseSunsetStrength) * window;
			if (suppression > 0) {
				blendTowards(zenith, regionalLin, suppression);
				blendTowards(horizon, regionalLin, suppression);
				fadeOut(sunGlow, suppression);
			}
		}

		// Hand the daytime sky to the environment.
		if (regionalLin != null) {
			float blend = sunAltitude < 0 ? 0 : takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitude);
			if (blend > 0) {
				blendTowards(zenith, regionalLin, blend);
				blendTowards(horizon, regionalLin, blend);
			}
		}

		// Use a common deep-night base before moon tint and stars.
		float nightBlend = smoothstep(0, -15, sunAltitude);
		if (nightBlend > 0) {
			blendTowards(zenith, NIGHT_SKY_LINEAR, nightBlend);
			blendTowards(horizon, NIGHT_SKY_LINEAR, nightBlend);
		}

		return new float[][] { linearToSrgb(zenith), linearToSrgb(horizon), linearToSrgb(sunGlow) };
	}

	private float[] getReferenceHorizonColor(float[] regionalFogColor) {
		return regionalFogColor != null
			? regionalFogColor
			: linearToSrgb(interpolateSrgb(90, HORIZON_KEYFRAMES));
	}

	private float getBrightnessMultiplier(DaylightCycleState state, int minimumBrightness) {
		float sunAltitudeDegrees = state.sunAngles[0] * RAD_TO_DEG;
		float minBrightness = minimumBrightness / 100f;
		float horizonBrightness = minBrightness + .10f;

		if (sunAltitudeDegrees <= -18)
			return minBrightness;
		if (sunAltitudeDegrees <= -5) {
			float twilightBrightness = minBrightness + .07f;
			return mix(minBrightness, twilightBrightness, smoothstep(-18, -5, sunAltitudeDegrees));
		}
		if (sunAltitudeDegrees <= 5) {
			float twilightBrightness = minBrightness + .07f;
			float earlyDayBrightness = horizonBrightness + .05f;
			return mix(twilightBrightness, earlyDayBrightness, smoothstep(-5, 5, sunAltitudeDegrees));
		}

		float earlyDayBrightness = horizonBrightness + .05f;
		float sineAt5 = sin(5 * DEG_TO_RAD);
		float normalizedSine = max(0, (sin(sunAltitudeDegrees * DEG_TO_RAD) - sineAt5) / (1 - sineAt5));
		return mix(earlyDayBrightness, 1.2f, normalizedSine);
	}

	/** Apply the sampled outdoor sky to an opted-in light. */
	public void updateOutdoorLight(Light light, int[] worldPos, int minimumBrightness) {
		copyTo(light.color, light.def.color);
		// Apply outdoor light through cave openings even when the local environment has no cycle.
		if (!light.def.followDayNight || !plugin.configEnableDayNightCycle)
			return;

		DaylightCycleState state = daylightCycleManager.getState();
		OutdoorSkySample sky = sampleOutdoorSky(state, worldPos, minimumBrightness);
		float[] authoredColor = light.def.color;
		float defLuma = dot(authoredColor, SKY_LUMA_WEIGHTS);
		float noonLuma = dot(sky.noonHorizonLinear, SKY_LUMA_WEIGHTS);
		float[] lightColor = copy(sky.horizonLinear);
		float sunAltDeg = state.sunAngles[0] * RAD_TO_DEG;

		float moonStrengthFloor = 0;
		if (sunAltDeg < 5) {
			float moonAltDeg = state.moonAltitudeDegreesForLighting;
			float moonIllumination = state.moonIllumination;
			if (moonAltDeg > -5 && moonIllumination > .01f) {
				float sunFade = saturate((5 - sunAltDeg) / 10);
				float moonElevation = saturate((moonAltDeg + 5) / 25);
				float moonElevationSmooth = moonElevation * moonElevation * (3 - 2 * moonElevation);
				float moonBlend = moonIllumination * .25f * moonElevationSmooth * sunFade;
				lightColor = mix(lightColor, environmentManager.currentMoonLightColor, moonBlend);
				moonStrengthFloor = moonIllumination * .12f * moonElevationSmooth;
			}
		}

		if (sunAltDeg > 0) {
			float desaturation = smoothstep(0, 90, sunAltDeg) * .75f;
			float luma = dot(lightColor, SKY_LUMA_WEIGHTS);
			mix(lightColor, lightColor, luma, desaturation);
		}

		// Restore the authored color only at midday.
		float horizonLuma = dot(lightColor, SKY_LUMA_WEIGHTS);
		float middayFactor = smoothstep(15, 30, sunAltDeg);
		if (middayFactor > 0)
			lightColor = mix(lightColor, authoredColor, middayFactor);

		copyTo(light.color, lightColor);
		float peakScale = defLuma / max(noonLuma, 1e-4f);
		float timeScale = max(min(horizonLuma / max(noonLuma, 1e-4f), 1) * sky.brightnessMultiplier, moonStrengthFloor);
		light.strength *= mix(peakScale * timeScale, 1, middayFactor);
	}

	private OutdoorSkySample sampleOutdoorSky(DaylightCycleState state, int[] worldPos, int minimumBrightness) {
		Environment environment = environmentManager.getOutdoorEnvironment(worldPos);
		if (environment == cachedOutdoorSkyEnvironment && plugin.frame == cachedOutdoorSkyFrame
			&& minimumBrightness == cachedOutdoorSkyMinBrightness)
			return cachedOutdoorSkySample;

		float[] regionalFogSrgb = environmentManager.getOutdoorRegionalFogSrgb(environment);
		float[][] skyGradient = getSkyGradientColors(
			state,
			regionalFogSrgb,
			environment.sunStrength,
			environment.sunriseSunsetStrength,
			environment.skyColorTakeoverAngle
		);
		OutdoorSkySample sample = new OutdoorSkySample(
			ColorUtils.srgbToLinear(skyGradient[1]),
			ColorUtils.srgbToLinear(getReferenceHorizonColor(regionalFogSrgb)),
			getBrightnessMultiplier(state, minimumBrightness)
		);
		cachedOutdoorSkySample = sample;
		cachedOutdoorSkyEnvironment = environment;
		cachedOutdoorSkyMinBrightness = minimumBrightness;
		cachedOutdoorSkyFrame = plugin.frame;
		return sample;
	}

	private static float[] srgbKeyframe(float altitudeDegrees, int srgb) {
		return vec(
			altitudeDegrees,
			((srgb >> 16) & 0xFF) / 255f,
			((srgb >> 8) & 0xFF) / 255f,
			(srgb & 0xFF) / 255f
		);
	}

	private static float[] linearKeyframe(float altitudeDegrees, int red, int green, int blue) {
		float[] linear = rgb(new Color(red, green, blue));
		return vec(altitudeDegrees, linear[0], linear[1], linear[2]);
	}

	private static float[] getDirectionalLightForAngles(float[] sunAngles) {
		float[] directionalLight = multiply(ColorUtils.colorTemperatureToLinearRgb(4100), .1f);
		if (sunAngles[0] >= 0) {
			float temperature = interpolate(sunAngles[0] * RAD_TO_DEG, DIRECTIONAL_TEMPERATURE_KEYFRAMES);
			float strength = sin(sunAngles[0]);
			strength *= strength * 3;
			add(directionalLight, directionalLight, multiply(ColorUtils.colorTemperatureToLinearRgb(temperature), strength));
		}
		return directionalLight;
	}

	private static float[] getAmbientColorForAngles(float[] sunAngles) {
		return interpolateLinear(sunAngles[0] * RAD_TO_DEG, AMBIENT_COLOR_KEYFRAMES);
	}

	private static float regionalBlendFactor(float sunAltitudeDegrees) {
		if (sunAltitudeDegrees >= 30)
			return 1;
		if (sunAltitudeDegrees >= 15)
			return .75f + (sunAltitudeDegrees - 15) / 15 * .25f;
		if (sunAltitudeDegrees >= 5)
			return .5f + (sunAltitudeDegrees - 5) / 10 * .25f;
		if (sunAltitudeDegrees >= 0)
			return .3f + sunAltitudeDegrees / 5 * .2f;
		return max(0, .3f + sunAltitudeDegrees / 10 * .3f);
	}

	private static float interpolate(float x, float[][] keyframes) {
		int end = keyframes.length - 1;
		int i = 0;
		while (i < end && x > keyframes[i + 1][0])
			i++;
		if (i == end)
			return keyframes[end][1];
		float[] from = keyframes[i];
		float[] to = keyframes[i + 1];
		return mix(from[1], to[1], clamp((x - from[0]) / (to[0] - from[0]), 0, 1));
	}

	private static float[] interpolateSrgb(float x, float[][] keyframes) {
		int end = keyframes.length - 1;
		int i = 0;
		while (i < end && x > keyframes[i + 1][0])
			i++;
		float[] from = keyframes[i];
		if (i == end)
			return srgbToLinear(slice(from, 1));
		float[] to = keyframes[i + 1];
		float t = clamp((x - from[0]) / (to[0] - from[0]), 0, 1);
		return mix(
			srgbToLinear(slice(from, 1)),
			srgbToLinear(slice(to, 1)),
			t
		);
	}

	private static float[] interpolateLinear(float x, float[][] keyframes) {
		int end = keyframes.length - 1;
		int i = 0;
		while (i < end && x > keyframes[i + 1][0])
			i++;
		float[] from = keyframes[i];
		if (i == end)
			return slice(from, 1);
		float[] to = keyframes[i + 1];
		float t = clamp((x - from[0]) / (to[0] - from[0]), 0, 1);
		return mix(slice(from, 1), slice(to, 1), t);
	}

	private static void blendTowards(float[] dst, float[] src, float t) {
		mix(dst, dst, src, t);
	}

	private static void fadeOut(float[] dst, float t) {
		multiply(dst, dst, 1 - t);
	}

	private float computeShadowVisibility(float sunAltDeg, float moonAltDeg, float moonIllumination) {
		return sunAltDeg > SUN_SHADOW_CUTOFF_DEG
			? getSunShadowVisibility(sunAltDeg)
			: getMoonShadowVisibility(sunAltDeg, moonAltDeg, moonIllumination);
	}

	private static float getSunShadowVisibility(float sunAltitude) {
		if (sunAltitude <= SUN_SHADOW_MIDPOINT_DEG) {
			return (sunAltitude - SUN_SHADOW_CUTOFF_DEG) /
				(SUN_SHADOW_MIDPOINT_DEG - SUN_SHADOW_CUTOFF_DEG) * SUN_SHADOW_MIDPOINT_VISIBILITY;
		}
		if (sunAltitude <= SUN_SHADOW_FULL_DEG) {
			return mix(
				SUN_SHADOW_MIDPOINT_VISIBILITY,
				SUN_SHADOW_DAYTIME_FLOOR,
				(sunAltitude - SUN_SHADOW_MIDPOINT_DEG) / (SUN_SHADOW_FULL_DEG - SUN_SHADOW_MIDPOINT_DEG)
			);
		}
		return clamp(sin(sunAltitude * DEG_TO_RAD), SUN_SHADOW_DAYTIME_FLOOR, 1);
	}

	private float getMoonShadowVisibility(float sunAltitude, float moonAltitude, float moonIllumination) {
		float moonBaseShadow = 0;
		if (isMoonLighting(moonAltitude, moonIllumination)) {
			moonBaseShadow =
				pow(moonIllumination, MOON_SHADOW_PHASE_EXPONENT) * MOON_SHADOW_STRENGTH *
				moonElevationFade(moonAltitude) * environmentManager.currentMoonShadowStrength;
		}
		// moonShadowStrength also feeds ambient and sky-fill complements.
		return saturate(smoothstep(SUN_SHADOW_CUTOFF_DEG, MOON_TINT_SUN_END_DEG, sunAltitude) * moonBaseShadow);
	}

	private float computeMoonInfluence(float sunAltDeg, float moonAltDeg, float moonIllumination) {
		if (sunAltDeg >= MOON_TINT_SUN_START_DEG || !isMoonLighting(moonAltDeg, moonIllumination))
			return 0;

		float influence = sunAltDeg >= 0
			? smoothstep(MOON_TINT_SUN_START_DEG, 0, sunAltDeg) * MOON_INFLUENCE_AT_HORIZON
			: mix(MOON_INFLUENCE_AT_HORIZON, MAX_MOON_COLOR_INFLUENCE,
				smoothstep(0, MOON_TINT_SUN_END_DEG, sunAltDeg));
		return influence * moonElevationFade(moonAltDeg) * moonIllumination;
	}

	private void tintNightSky(float[][] sky, float moonInfluence) {
		float[] nightSkyColor = environmentManager.currentNightSkyColor;
		float skyTint = min(1, moonInfluence * NIGHT_SKY_TINT_SCALE * environmentManager.currentNightSkyColorStrength);
		mix(sky[0], sky[0], nightSkyColor, skyTint);
		mix(sky[1], sky[1], nightSkyColor, skyTint);
	}

	private void updateSkyUbo(DaylightCycleState state, float[][] sky, float moonIllumination) {
		UBOSky ubo = plugin.uboSky;
		ubo.skyGradientEnabled.set(1);
		ubo.skyZenithColor.set(sky[0]);
		ubo.skyHorizonColor.set(sky[1]);
		ubo.skySunColor.set(sky[2]);
		ubo.skySunDir.set(state.sunDirection);
		ubo.skyMoonDir.set(state.moonDirection);
		ubo.skyMoonColor.set(environmentManager.currentMoonColor);
		ubo.skyMoonIllumination.set(moonIllumination);
		// Environments can force the moon, but locked daytime modes still hide it.
		boolean moonEnabled = config.enableMoon() || environmentManager.forceMoonActive();
		ubo.moonVisibility.set(!state.hidesMoon && moonEnabled ? environmentManager.currentMoonVisibility : 0);
		ubo.moonSizeMult.set(environmentManager.currentMoonSizeMult);
		ubo.starHorizonHeight.set(environmentManager.currentStarHorizonHeight);
		ubo.starVisibility.set(config.enableStarMap() ? environmentManager.currentStarVisibility : 0);
		// An all-int ternary selects the wrong uniform setter.
		ubo.nebulaVisibility.set(config.enableNebulas() ? environmentManager.currentNebulaVisibility : 0f);
		ubo.auroraVisibility.set(state.auroraStrength * environmentManager.currentAuroraVisibility);
		ubo.upload();
	}

	private void disableSky() {
		UBOSky ubo = plugin.uboSky;
		ubo.skyGradientEnabled.set(0);
		ubo.skyMoonDir.set(0, 0, 0);
		ubo.skyMoonColor.set(0, 0, 0);
		ubo.skyMoonIllumination.set(0);
		ubo.starVisibility.set(1);
		ubo.nebulaVisibility.set(0);
		ubo.auroraVisibility.set(0);
		ubo.moonSizeMult.set(1);
		ubo.starHorizonHeight.set(1);
		ubo.upload();
	}

	private static boolean isMoonLighting(float moonAltDeg, float moonIllumination) {
		return moonAltDeg > MOON_HORIZON_CUTOFF_DEG && moonIllumination > MIN_MOON_ILLUMINATION;
	}

	private static float moonPresence(float moonAltDeg, float moonIllumination) {
		return isMoonLighting(moonAltDeg, moonIllumination)
			? clamp(moonIllumination * moonElevationFade(moonAltDeg), 0, 1)
			: 0;
	}

	private static float moonElevationFade(float moonAltDeg) {
		return smoothstep(MOON_ELEVATION_FADE_START_DEG, MOON_ELEVATION_FADE_END_DEG, moonAltDeg);
	}

	private static float[] mixColor(float[] a, float[] b, float t) {
		return mix(a, b, t);
	}

	private static final class OutdoorSkySample {
		private final float[] horizonLinear;
		private final float[] noonHorizonLinear;
		private final float brightnessMultiplier;

		private OutdoorSkySample(float[] horizonLinear, float[] noonHorizonLinear, float brightnessMultiplier) {
			this.horizonLinear = horizonLinear;
			this.noonHorizonLinear = noonHorizonLinear;
			this.brightnessMultiplier = brightnessMultiplier;
		}
	}
}
