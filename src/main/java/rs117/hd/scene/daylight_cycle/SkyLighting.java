package rs117.hd.scene.daylight_cycle;

import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.uniforms.UBOSkybox;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.ColorUtils.linearToSrgb;
import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.ColorUtils.srgbToLinear;
import static rs117.hd.utils.MathUtils.*;

/**
 * Converts the current celestial state into scene lighting.
 *
 * <p>{@link DaylightCycleManager} owns the clock, cycle mode, and sun/moon positions. This class owns the
 * lighting policy built on top of those positions: procedural sky colors, regional blending,
 * brightness, and the response of outdoor lights. For ZoneRenderer it also owns the resolved
 * frame lighting and skybox UBO upload, so the renderer consumes one coherent cycle result.
 */
@Singleton
public class SkyLighting {
	// Sun altitude below which the moon begins taking over shadows and light.
	private static final float SUN_SHADOW_CUTOFF_DEG = 2;
	private static final float SUN_SHADOW_MIDPOINT_DEG = 12;
	private static final float SUN_SHADOW_FULL_DEG = 15;
	private static final float SUN_SHADOW_MIDPOINT_VISIBILITY = .6f;
	private static final float SUN_SHADOW_DAYTIME_FLOOR = .9f;

	// Moon altitude below which the moon neither casts light nor shadows.
	private static final float MOON_HORIZON_CUTOFF_DEG = -10;
	private static final float MIN_MOON_ILLUMINATION = .01f;
	private static final float MOON_ELEVATION_FADE_START_DEG = -10;
	private static final float MOON_ELEVATION_FADE_END_DEG = 20;
	private static final float MOON_SHADOW_STRENGTH = .2f;
	// A square root keeps shadows readable through waxing and waning phases without making a
	// new moon cast any; shadow presence follows directional quality more than brightness.
	private static final float MOON_SHADOW_PHASE_EXPONENT = .5f;
	// Moonlight never fully replaces an area's requested ambient night floor, even overhead.
	private static final float MIN_BRIGHTNESS_BOOST_RESIDUAL = .2f;
	private static final float MAX_MOON_COLOR_INFLUENCE = .8f;
	private static final float MOON_INFLUENCE_AT_HORIZON = .05f;
	private static final float MOON_TINT_SUN_START_DEG = 5;
	private static final float MOON_TINT_SUN_END_DEG = -15;
	// The base tint is deliberately subtle; environments scale it with nightSkyColorStrength.
	private static final float NIGHT_SKY_TINT_SCALE = .05f;
	private static final float SKY_FILL_FADE_END_DEG = 45;

	// Pre-linearized deep-night sky color.
	// Read-only: every consumer only reads components into fresh blend arrays.
	private static final float[] NIGHT_SKY_LINEAR = rgb(5, 7, 15);
	private static final float[] SKY_LUMA_WEIGHTS = { .2126f, .7152f, .0722f };

	// Sky color keyframe tables, as { sunAltitudeDegrees, sRGB 0xRRGGBB }. Read-only
	// constant data; interpolateSrgb only reads them and returns a fresh linear float[] per call.
	// Rows must stay sorted by ascending altitude.
	private static final float[][] ZENITH_KEYFRAMES = {
		srgbKeyframe(-30, 0x010104), // Deep night - near black
		srgbKeyframe(-15, 0x03040A), // Late night
		srgbKeyframe(-8, 0x2D2346), // Early twilight - purple tint
		srgbKeyframe(-3, 0x503C64), // Twilight
		srgbKeyframe(0, 0x645078), // Horizon sun
		srgbKeyframe(5, 0x788CB4), // Early sunrise
		srgbKeyframe(15, 0x6496C8), // Morning
		srgbKeyframe(30, 0x5A91C8), // Mid-morning
		srgbKeyframe(50, 0x558CC3), // Midday
		srgbKeyframe(90, 0x5087BE), // High noon
	};

	private static final float[][] HORIZON_KEYFRAMES = {
		srgbKeyframe(-30, 0x010205), // Deep night - near black
		srgbKeyframe(-15, 0x04050C), // Late night
		srgbKeyframe(-8, 0x3C2D41), // Early twilight
		srgbKeyframe(-3, 0x8C5046), // Twilight - orange/red
		srgbKeyframe(0, 0xDC8250), // Sunrise/sunset - golden
		srgbKeyframe(5, 0xE6AA78), // Early morning golden
		srgbKeyframe(10, 0xC8B4A0), // Morning warm
		srgbKeyframe(20, 0xAAAFB9), // Late morning
		srgbKeyframe(30, 0x96A5BE), // Midday haze
		srgbKeyframe(50, 0x8CA0BE), // Afternoon
		srgbKeyframe(90, 0x879BB9), // High noon
	};

	private static final float[][] SUN_GLOW_KEYFRAMES = {
		srgbKeyframe(-30, 0x000000), // No glow at night
		srgbKeyframe(-10, 0x140A1E), // Very faint purple
		srgbKeyframe(-5, 0x50283C), // Purple/pink
		srgbKeyframe(-2, 0xB45032), // Deep orange/red
		srgbKeyframe(0, 0xFF9650), // Bright orange
		srgbKeyframe(5, 0xFFC882), // Golden yellow
		srgbKeyframe(15, 0xFFE6B4), // Warm white
		srgbKeyframe(30, 0xFFFADC), // Nearly white
		srgbKeyframe(50, 0xFFFFF0), // White with slight warmth
		srgbKeyframe(90, 0xFFFFFA), // Pure white
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

	// The lighting values a frame is built from. Seeded from the environment, then the cycle
	// overwrites the values it drives. Colors are linear except fogColorSrgb, which matches the
	// skybox. The arrays are owned and reused by this service, never aliasing environment state.
	public final float[] directionalColor = new float[3];
	public final float[] ambientColor = new float[3];
	public final float[] fogColorSrgb = new float[3];
	public final float[] waterColor = new float[3];
	public float directionalStrength;
	public float ambientStrength;

	// The resolved environment, current frame, and minimum brightness fully determine the
	// outdoor sky sample. This keeps one shared sample per environment per frame.
	private OutdoorSkySample cachedOutdoorSkySample;
	private Environment cachedOutdoorSkyEnvironment;
	private int cachedOutdoorSkyMinBrightness;
	private int cachedOutdoorSkyFrame = -1;

	/** Copy the environment's current lighting in as this frame's starting point. */
	public void seedFromEnvironment() {
		copyTo(directionalColor, environmentManager.currentDirectionalColor);
		copyTo(ambientColor, environmentManager.currentAmbientColor);
		copyTo(waterColor, environmentManager.currentWaterColor);
		copyTo(fogColorSrgb, ColorUtils.linearToSrgb(environmentManager.currentFogColor));
		directionalStrength = environmentManager.currentDirectionalStrength;
		ambientStrength = environmentManager.currentAmbientStrength;
	}

	/** Derive the frame's lighting from the current sun and moon, then upload the skybox UBO. */
	public void computeCycleLighting() {
		var state = daylightCycleManager.getState();
		copyTo(directionalColor, getRegionalDirectionalLight(state, environmentManager.currentDirectionalColor));
		copyTo(ambientColor, getRegionalAmbientLight(state, environmentManager.currentAmbientColor));

		float brightnessMultiplier = getBrightnessMultiplier(state, plugin.configMinimumBrightness);
		float baseDirectionalStrength = environmentManager.currentDirectionalStrength;
		// Ignore seasonal ambientStrength while the cycle is active: its brightness response alone
		// controls how dark nights get, rather than competing with the authored seasonal values.
		ambientStrength = brightnessMultiplier;

		float sunAltDeg = state.sunAngles[1] * RAD_TO_DEG;
		float moonAltDeg = state.moonAltitudeDegrees;
		float moonIllumination = state.moonIllumination;
		float[][] sky = getSkyGradientColors(
			state,
			ColorUtils.linearToSrgb(environmentManager.currentFogColor),
			environmentManager.currentSunStrength,
			environmentManager.currentSunriseSunsetStrength,
			environmentManager.currentSkyColorTakeoverAngle
		);

		// Lighting can keep a new moon present; the visible disk still uses the true phase.
		float litMoonIllumination = max(moonIllumination, environmentManager.currentMinMoonIllumination);
		float shadowVisibility = computeShadowVisibility(sunAltDeg, moonAltDeg, litMoonIllumination);
		float moonInfluence = computeMoonInfluence(sunAltDeg, moonAltDeg, litMoonIllumination);
		baseDirectionalStrength = applyMoonLighting(sky, moonInfluence, baseDirectionalStrength);

		directionalStrength =
			baseDirectionalStrength * brightnessMultiplier * environmentManager.currentSunlightStrength;
		// The horizon color doubles as fog, so geometry meets the skybox seamlessly there.
		copyTo(fogColorSrgb, sky[1]);
		copyTo(waterColor, ColorUtils.srgbToLinear(sky[1]));

		applyAmbientFloor(moonAltDeg, moonIllumination);
		applySkyFill(sunAltDeg, shadowVisibility);
		uploadSkyUniforms(state, sky, moonIllumination);
	}

	private float applyMoonLighting(float[][] sky, float moonInfluence, float baseDirectionalStrength) {
		if (moonInfluence == 0)
			return baseDirectionalStrength;

		mix(directionalColor, environmentManager.currentMoonLightColor, moonInfluence);
		tintNightSky(sky, moonInfluence);
		// Color influence is capped, but a fully active moon can still reach its configured strength.
		float strengthBlend = min(1, moonInfluence / MAX_MOON_COLOR_INFLUENCE);
		return mix(baseDirectionalStrength, environmentManager.currentMoonDirectionalStrength, strengthBlend);
	}

	private void applyAmbientFloor(float moonAltDeg, float moonIllumination) {
		// The floor replaces moonlight the sky genuinely lacks, so it uses the raw illumination
		// rather than the environment's lighting-only minimum.
		float boostFraction = MIN_BRIGHTNESS_BOOST_RESIDUAL +
			(1 - MIN_BRIGHTNESS_BOOST_RESIDUAL) * (1 - moonPresence(moonAltDeg, moonIllumination));
		float boostedFloor = plugin.configMinimumBrightness / 100f *
			(1 + environmentManager.currentMinBrightnessBoost * boostFraction);
		ambientStrength = max(ambientStrength, boostedFloor);
	}

	private void applySkyFill(float sunAltDeg, float shadowVisibility) {
		// Sky fill is strongest at night and twilight, then fades out under a high, harsh sun.
		float skyFill = 1 - smoothstep(0, SKY_FILL_FADE_END_DEG, sunAltDeg);
		add(ambientColor, ambientColor, multiply(directionalColor, (1 - shadowVisibility) * skyFill));
		directionalStrength *= shadowVisibility;
	}

	/** The directional color from the cycle, blended toward the area's authored color by day. */
	private float[] getRegionalDirectionalLight(DaylightCycleState state, float[] regionalDirectionalColor) {
		float[] sunAngles = state.sunAngles;
		float[] dynamicLight = getDirectionalLightForAngles(sunAngles);
		return mixColor(dynamicLight, regionalDirectionalColor, regionalBlendFactor(sunAngles[1] * RAD_TO_DEG));
	}

	/** The ambient color from the cycle, using the same regional blend as directional light. */
	private float[] getRegionalAmbientLight(DaylightCycleState state, float[] regionalAmbientColor) {
		float[] sunAngles = state.sunAngles;
		float[] dynamicAmbient = getAmbientColorForAngles(sunAngles);
		return mixColor(dynamicAmbient, regionalAmbientColor, regionalBlendFactor(sunAngles[1] * RAD_TO_DEG));
	}

	/**
	 * Sky gradient colors for the current time as { zenith, horizon, sunGlow } in sRGB.
	 * Procedural keyframes are adjusted in sequence by regional sun suppression, regional
	 * sunrise/sunset suppression, the daytime regional takeover, then the generic night sky.
	 */
	private float[][] getSkyGradientColors(
		DaylightCycleState state,
		float[] regionalFogColor,
		float sunStrength,
		float sunriseSunsetStrength,
		float skyColorTakeoverAngle
	) {
		float sunAltitude = state.sunAngles[1] * RAD_TO_DEG;
		// Keep the twilight-suppression window and daytime takeover bound together. Otherwise
		// raw blue keyframes leak through between them after sunrise.
		float takeover = max(0, skyColorTakeoverAngle);
		float[] regionalLin = regionalFogColor != null ? srgbToLinear(regionalFogColor) : null;

		float[] zenith = interpolateSrgb(sunAltitude, ZENITH_KEYFRAMES);
		float[] horizon = interpolateSrgb(sunAltitude, HORIZON_KEYFRAMES);
		float[] sunGlow = interpolateSrgb(sunAltitude, SUN_GLOW_KEYFRAMES);

		// 1. Suppress the procedural sunset for dark regional environments. Below the horizon,
		// blend the suppression target toward the generic night sky to avoid a hard color jump.
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

		// 2. Preserve a strongly authored regional sky through its twilight window.
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

		// 3. Hand the daytime sky over to the environment's regional color.
		if (regionalLin != null) {
			float blend = sunAltitude < 0 ? 0 : takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitude);
			if (blend > 0) {
				blendTowards(zenith, regionalLin, blend);
				blendTowards(horizon, regionalLin, blend);
			}
		}

		// 4. Resolve deep night to a common base, leaving downstream moon tint and stars in charge.
		float nightBlend = smoothstep(0, -15, sunAltitude);
		if (nightBlend > 0) {
			blendTowards(zenith, NIGHT_SKY_LINEAR, nightBlend);
			blendTowards(horizon, NIGHT_SKY_LINEAR, nightBlend);
		}

		return new float[][] { linearToSrgb(zenith), linearToSrgb(horizon), linearToSrgb(sunGlow) };
	}

	/** Reference horizon color at peak daytime, in the same sRGB space as the sky gradient. */
	private float[] getReferenceHorizonColor(float[] regionalFogColor) {
		return regionalFogColor != null
			? regionalFogColor
			: linearToSrgb(interpolateSrgb(90, HORIZON_KEYFRAMES));
	}

	/** Brightness response driven by sun altitude, including the user's minimum brightness. */
	private float getBrightnessMultiplier(DaylightCycleState state, int minimumBrightness) {
		float sunAltitudeDegrees = state.sunAngles[1] * RAD_TO_DEG;
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

	/**
	 * Apply the current sky's color and strength response to an outdoor light. Light definitions
	 * remain the source of the authored daytime color; this only applies the day/night response
	 * for definitions that opt in.
	 */
	public void applyOutdoorLightLighting(Light light, int[] worldPos, int minimumBrightness) {
		copyTo(light.color, light.def.color);
		// Outdoor lights retain the day/night response underground, so cave openings can be lit
		// by the outdoor sky. This intentionally checks only the user setting rather than the
		// current environment's cycleActive state.
		if (!light.def.followDayNight || !plugin.configEnableDayNightCycle)
			return;

		DaylightCycleState state = daylightCycleManager.getState();
		OutdoorSkySample sky = sampleOutdoorSky(state, worldPos, minimumBrightness);
		float[] authoredColor = light.def.color;
		float defLuma = dot(authoredColor, SKY_LUMA_WEIGHTS);
		float noonLuma = dot(sky.noonHorizonLinear, SKY_LUMA_WEIGHTS);
		float[] lightColor = copy(sky.horizonLinear);
		float sunAltDeg = state.sunAngles[1] * RAD_TO_DEG;

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

		// High sun produces whiter, less saturated outdoor light.
		if (sunAltDeg > 0) {
			float desaturation = smoothstep(0, 90, sunAltDeg) * .75f;
			float luma = dot(lightColor, SKY_LUMA_WEIGHTS);
			for (int i = 0; i < 3; i++)
				lightColor[i] = mix(lightColor[i], luma, desaturation);
		}

		// Only at midday does an opt-in light return to its authored color; sunrise, sunset,
		// and night are filtered through the sampled outdoor sky instead.
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
		return new float[] {
			altitudeDegrees,
			((srgb >> 16) & 0xFF) / 255f,
			((srgb >> 8) & 0xFF) / 255f,
			(srgb & 0xFF) / 255f
		};
	}

	private static float[] linearKeyframe(float altitudeDegrees, int red, int green, int blue) {
		float[] linear = rgb(new Color(red, green, blue));
		return new float[] { altitudeDegrees, linear[0], linear[1], linear[2] };
	}

	private static float[] getDirectionalLightForAngles(float[] sunAngles) {
		float[] directionalLight = multiply(ColorUtils.colorTemperatureToLinearRgb(4100), .1f);
		if (sunAngles[1] >= 0) {
			float temperature = interpolate(sunAngles[1] * RAD_TO_DEG, DIRECTIONAL_TEMPERATURE_KEYFRAMES);
			float strength = sin(sunAngles[1]);
			strength *= strength * 3;
			add(directionalLight, directionalLight, multiply(ColorUtils.colorTemperatureToLinearRgb(temperature), strength));
		}
		return directionalLight;
	}

	private static float[] getAmbientColorForAngles(float[] sunAngles) {
		return interpolateLinear(sunAngles[1] * RAD_TO_DEG, AMBIENT_COLOR_KEYFRAMES);
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
			return srgbToLinear(new float[] { from[1], from[2], from[3] });
		float[] to = keyframes[i + 1];
		float t = clamp((x - from[0]) / (to[0] - from[0]), 0, 1);
		return mix(
			srgbToLinear(new float[] { from[1], from[2], from[3] }),
			srgbToLinear(new float[] { to[1], to[2], to[3] }),
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
			return new float[] { from[1], from[2], from[3] };
		float[] to = keyframes[i + 1];
		float t = clamp((x - from[0]) / (to[0] - from[0]), 0, 1);
		return new float[] { mix(from[1], to[1], t), mix(from[2], to[2], t), mix(from[3], to[3], t) };
	}

	private static void blendTowards(float[] dst, float[] src, float t) {
		for (int i = 0; i < 3; i++)
			dst[i] = mix(dst[i], src[i], t);
	}

	private static void fadeOut(float[] dst, float t) {
		for (int i = 0; i < 3; i++)
			dst[i] *= 1 - t;
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
		// Clamp because unbounded moonShadowStrength feeds the ambient and sky-fill complements.
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
		for (int i = 0; i < 3; i++) {
			sky[0][i] = mix(sky[0][i], nightSkyColor[i], skyTint);
			sky[1][i] = mix(sky[1][i], nightSkyColor[i], skyTint);
		}
	}

	private void uploadSkyUniforms(DaylightCycleState state, float[][] sky, float moonIllumination) {
		UBOSkybox ubo = plugin.uboSkybox;
		ubo.skyGradientEnabled.set(1);
		ubo.skyZenithColor.set(sky[0]);
		ubo.skyHorizonColor.set(sky[1]);
		ubo.skySunColor.set(sky[2]);
		ubo.skySunDir.set(state.sunDirection);
		ubo.skyMoonDir.set(state.moonDirection);
		ubo.skyMoonColor.set(environmentManager.currentMoonColor);
		ubo.skyMoonIllumination.set(moonIllumination);
		// An environment can force the moon for a cutscene, but locked daytime modes still hide it.
		boolean moonEnabled = config.enableMoon() || environmentManager.forceMoonActive();
		ubo.moonVisibility.set(!state.hidesMoon && moonEnabled ? environmentManager.currentMoonVisibility : 0);
		ubo.moonSizeMult.set(environmentManager.currentMoonSizeMult);
		ubo.starHorizonHeight.set(environmentManager.currentStarHorizonHeight);
		ubo.starVisibility.set(config.enableStarMap() ? environmentManager.currentStarVisibility : 0);
		// The float literal is intentional: an all-int ternary binds the wrong uniform setter.
		ubo.nebulaVisibility.set(config.enableNebulas() ? environmentManager.currentNebulaVisibility : 0f);
		ubo.auroraVisibility.set(state.auroraStrength * environmentManager.currentAuroraVisibility);
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
		float[] result = new float[3];
		for (int i = 0; i < 3; i++)
			result[i] = mix(a[i], b[i], t);
		return result;
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
