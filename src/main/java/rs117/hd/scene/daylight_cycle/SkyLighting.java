package rs117.hd.scene.daylight_cycle;

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
import rs117.hd.scene.environments.Environment.Keyframe;
import rs117.hd.scene.environments.Environment.SkyGradient;
import rs117.hd.scene.environments.Environment.SkyLightingProfile;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.ColorUtils.linearSrgbLuma;
import static rs117.hd.utils.ColorUtils.linearToSrgb;
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
	// Preserve part of the authored ambient floor even under a full moon.
	private static final float MIN_BRIGHTNESS_BOOST_RESIDUAL = .2f;
	private static final float MAX_MOON_COLOR_INFLUENCE = .8f;
	private static final float MOON_INFLUENCE_AT_HORIZON = .05f;
	private static final float MOON_TINT_SUN_START_DEG = 5;
	private static final float MOON_TINT_SUN_END_DEG = -15;
	// Environments scale this subtle base tint with nightSkyColorStrength.
	private static final float NIGHT_SKY_TINT_SCALE = .05f;
	private static final float SKY_FILL_FADE_END_DEG = 45;
	private static final float MAX_OUTDOOR_LIGHT_SCALE = 4;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private DaylightCycleManager daylightCycleManager;

	@Inject
	private EnvironmentManager environmentManager;

	public final float[] directionalColor = new float[3];
	public final float[] ambientColor = new float[3];
	public final float[] waterColor = new float[3];
	public final float[] fogColorSrgb = new float[3];
	public float directionalStrength;
	public float ambientStrength;
	public float effectiveDirectionalStrength;

	@Getter
	@Accessors(fluent = true)
	private boolean shouldRenderSky;

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

	private void updateSky() {
		var state = daylightCycleManager.getState();
		SkyLightingProfile profile = environmentManager.getSkyLighting();
		float[] sunAngles = state.sunAngles;
		float sunAltDeg = sunAngles[0] * RAD_TO_DEG;
		float regionalBlend = interpolate(sunAltDeg, profile.regionalBlend)[0];
		mix(directionalColor, getDirectionalLightForAngles(sunAngles, profile), directionalColor, regionalBlend);
		mix(ambientColor, interpolate(sunAltDeg, profile.ambientColor), ambientColor, regionalBlend);

		float brightnessMultiplier = getBrightnessMultiplier(state, plugin.configMinimumBrightness, profile);
		float baseDirectionalStrength = directionalStrength;
		// The cycle controls night brightness instead of seasonal ambient strength
		ambientStrength = brightnessMultiplier;

		float moonAltDeg = state.moonAltitudeDegrees;
		float moonIllumination = state.moonIllumination;
		float[][] sky = getSkyGradientColors(
			state,
			environmentManager.getSkyGradient(),
			profile,
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
		// Horizon color doubles as fog so geometry meets the sky.
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

	/** Return {zenith, horizon, sun glow} sRGB after regional and night-sky blending. */
	private float[][] getSkyGradientColors(
		DaylightCycleState state,
		SkyGradient profile,
		SkyLightingProfile lightingProfile,
		float[] regionalFogColor,
		float sunStrength,
		float sunriseSunsetStrength,
		float skyColorTakeoverAngle
	) {
		float sunAltitude = state.sunAngles[0] * RAD_TO_DEG;
		// Bind twilight suppression to daytime takeover to avoid a blue gap after sunrise.
		float takeover = max(0, skyColorTakeoverAngle);
		float[] regionalLin = regionalFogColor != null ? srgbToLinear(regionalFogColor) : null;

		float[] zenith = interpolate(sunAltitude, profile.zenith);
		float[] horizon = interpolate(sunAltitude, profile.horizon);
		float[] sunGlow = interpolate(sunAltitude, profile.sunGlow);

		// Suppress procedural sunset colors in dark regional environments.
		if (regionalLin != null && sunStrength < 1) {
			float window = sunAltitude >= 0 ? 1 : smoothstep(-25, 0, sunAltitude);
			float suppression = (1 - sunStrength) * window;
			if (suppression > 0) {
				float[] target = mix(regionalLin, lightingProfile.nightSkyColor, smoothstep(5, -5, sunAltitude));
				blendSky(zenith, horizon, target, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}

		// Preserve strongly authored regional skies through twilight.
		if (regionalLin != null && sunriseSunsetStrength < 1) {
			float window = sunAltitude < 0
				? smoothstep(-15, 0, sunAltitude)
				: takeover == 0 ? 0 : smoothstep(takeover, 0, sunAltitude);
			float suppression = (1 - sunriseSunsetStrength) * window;
			if (suppression > 0) {
				blendSky(zenith, horizon, regionalLin, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}

		// Hand the daytime sky to the environment.
		if (regionalLin != null) {
			float blend = sunAltitude < 0 ? 0 : takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitude);
			if (blend > 0) {
				blendSky(zenith, horizon, regionalLin, blend);
			}
		}

		// Use a common deep-night base before moon tint and stars.
		float nightBlend = smoothstep(0, -15, sunAltitude);
		if (nightBlend > 0) {
			blendSky(zenith, horizon, lightingProfile.nightSkyColor, nightBlend);
		}

		return new float[][] { linearToSrgb(zenith), linearToSrgb(horizon), linearToSrgb(sunGlow) };
	}

	private float getBrightnessMultiplier(DaylightCycleState state, int minimumBrightness, SkyLightingProfile profile) {
		float sunAltitudeDegrees = state.sunAngles[0] * RAD_TO_DEG;
		float minBrightness = minimumBrightness / 100f;
		var curve = profile.brightness;
		float horizonBrightness = minBrightness + curve.horizonBoost;

		if (sunAltitudeDegrees <= curve.nightAltitude)
			return minBrightness;
		if (sunAltitudeDegrees <= curve.twilightAltitude) {
			float twilightBrightness = minBrightness + curve.twilightBoost;
			return mix(minBrightness, twilightBrightness, smoothstep(curve.nightAltitude, curve.twilightAltitude, sunAltitudeDegrees));
		}
		if (sunAltitudeDegrees <= curve.horizonAltitude) {
			float twilightBrightness = minBrightness + curve.twilightBoost;
			float earlyDayBrightness = horizonBrightness + curve.earlyDayBoost;
			return mix(twilightBrightness, earlyDayBrightness, smoothstep(curve.twilightAltitude, curve.horizonAltitude, sunAltitudeDegrees));
		}

		float earlyDayBrightness = horizonBrightness + curve.earlyDayBoost;
		float sineAtHorizon = sin(curve.horizonAltitude * DEG_TO_RAD);
		float normalizedSine = max(0, (sin(sunAltitudeDegrees * DEG_TO_RAD) - sineAtHorizon) / (1 - sineAtHorizon));
		return mix(earlyDayBrightness, curve.daytimeStrength, normalizedSine);
	}

	/** Apply the sampled outdoor sky to an opted-in light. */
	public void updateOutdoorLight(Light light, int[] worldPos, int minimumBrightness) {
		copyTo(light.color, light.def.color);
		// Apply outdoor light through cave openings even when the local environment has no cycle.
		if (!light.def.followDayNight || !plugin.configDaylightCycle)
			return;

		DaylightCycleState state = daylightCycleManager.getState();
		OutdoorSkySample sky = sampleOutdoorSky(state, worldPos, minimumBrightness);
		float[] authoredColor = light.def.color;
		float defLuma = linearSrgbLuma(authoredColor);
		float noonLuma = linearSrgbLuma(sky.noonHorizonLinear);
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
			float luma = linearSrgbLuma(lightColor);
			mix(lightColor, lightColor, vec(luma), desaturation);
		}

		// Restore the authored color only at midday.
		float horizonLuma = linearSrgbLuma(lightColor);
		float middayFactor = smoothstep(15, 30, sunAltDeg);
		if (middayFactor > 0)
			lightColor = mix(lightColor, authoredColor, middayFactor);

		copyTo(light.color, lightColor);
		float peakScale = defLuma / max(noonLuma, 1e-4f);
		float timeScale = max(min(horizonLuma / max(noonLuma, 1e-4f), 1) * sky.brightnessMultiplier, moonStrengthFloor);
		float outdoorLightScale = peakScale * timeScale;
		if (outdoorLightScale > 1) {
			float scaleRange = MAX_OUTDOOR_LIGHT_SCALE - 1;
			outdoorLightScale = 1 + scaleRange * (1 - exp(-(outdoorLightScale - 1) / scaleRange));
		}
		light.strength *= mix(outdoorLightScale, 1, middayFactor);
	}

	private OutdoorSkySample sampleOutdoorSky(DaylightCycleState state, int[] worldPos, int minimumBrightness) {
		Environment environment = environmentManager.getOutdoorEnvironment(worldPos);
		if (environment == cachedOutdoorSkyEnvironment && plugin.frame == cachedOutdoorSkyFrame
			&& minimumBrightness == cachedOutdoorSkyMinBrightness)
			return cachedOutdoorSkySample;

		float[] regionalFogSrgb = environmentManager.getOutdoorRegionalFogSrgb(environment);
		SkyGradient profile = environmentManager.getSkyGradient(environment);
		float[][] skyGradient = getSkyGradientColors(
			state,
			profile,
			environmentManager.getSkyLighting(),
			regionalFogSrgb,
			environment.sunStrength,
			environment.sunriseSunsetStrength,
			environment.skyColorTakeoverAngle
		);
		OutdoorSkySample sample = new OutdoorSkySample(
			ColorUtils.srgbToLinear(skyGradient[1]),
			regionalFogSrgb != null
				? ColorUtils.srgbToLinear(regionalFogSrgb)
				: interpolate(90, profile.horizon),
			getBrightnessMultiplier(state, minimumBrightness, environmentManager.getSkyLighting())
		);
		cachedOutdoorSkySample = sample;
		cachedOutdoorSkyEnvironment = environment;
		cachedOutdoorSkyMinBrightness = minimumBrightness;
		cachedOutdoorSkyFrame = plugin.frame;
		return sample;
	}

	private static float[] getDirectionalLightForAngles(float[] sunAngles, SkyLightingProfile profile) {
		float[] directionalLight = multiply(
			ColorUtils.colorTemperatureToLinearRgb(profile.directionalBaseTemperature),
			profile.directionalBaseStrength
		);
		if (sunAngles[0] >= 0) {
			float temperature = interpolate(sunAngles[0] * RAD_TO_DEG, profile.directionalTemperature)[0];
			float strength = sin(sunAngles[0]);
			strength *= strength * 3;
			add(directionalLight, directionalLight, multiply(ColorUtils.colorTemperatureToLinearRgb(temperature), strength));
		}
		return directionalLight;
	}

	private static float[] interpolate(float x, Keyframe[] keyframes) {
		int end = keyframes.length - 1;
		int i = 0;
		while (i < end && x > keyframes[i + 1].altitude)
			i++;
		Keyframe from = keyframes[i];
		return i == keyframes.length - 1
			? copy(from.values())
			: mix(from.values(), keyframes[i + 1].values(), getKeyframeBlend(x, from, keyframes[i + 1]));
	}

	private static float getKeyframeBlend(float x, Keyframe from, Keyframe to) {
		return clamp((x - from.altitude) / (to.altitude - from.altitude), 0, 1);
	}

	private static void blendSky(float[] zenith, float[] horizon, float[] color, float t) {
		mix(zenith, zenith, color, t);
		mix(horizon, horizon, color, t);
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
				// Square-root phase response keeps waxing and waning shadows visible.
				sqrt(moonIllumination) * MOON_SHADOW_STRENGTH *
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
		// Sky shaders invert Y when mapping celestial directions to view space.
		ubo.skyCelestialPole.set(
			state.celestialPole[0],
			-state.celestialPole[1],
			state.celestialPole[2]
		);
		ubo.skyCelestialRotation.set(state.celestialRotation);
		ubo.skyMoonDir.set(state.moonDirection);
		ubo.skyMoonColor.set(environmentManager.currentMoonColor);
		ubo.skyMoonIllumination.set(moonIllumination);
		ubo.skyMoonSunDir.set(state.moonSunDirection);
		ubo.skyMoonLibration.set(state.moonLibration);
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
		ubo.skyMoonSunDir.set(0, 0, 0);
		ubo.skyMoonLibration.set(0, 0);
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
