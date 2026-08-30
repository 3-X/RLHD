package rs117.hd.tests;

import com.google.gson.Gson;
import java.lang.reflect.Method;
import org.junit.Test;
import rs117.hd.config.MoonPhase;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.daylight_cycle.SkyLighting;
import rs117.hd.scene.environments.Environment;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static rs117.hd.utils.MathUtils.*;

/**
 * Characterization tests locking in DaylightCycleLighting's procedural lighting colors.
 * Golden values were captured from the implementation prior to pre-linearizing
 * the constant keyframe tables; any drift beyond 1e-6 indicates a behavior change.
 */
public class SkyLightingTest {
	private static float[] angles(float altitudeDegrees) {
		return new float[] { altitudeDegrees * DEG_TO_RAD, 0 };
	}

	private static float[] invokeColorHelper(String name, float altitudeDegrees) throws ReflectiveOperationException {
		Method method = SkyLighting.class.getDeclaredMethod(name, float[].class);
		method.setAccessible(true);
		return (float[]) method.invoke(null, (Object) angles(altitudeDegrees));
	}

	@Test
	public void ambientColorMatchesGolden() throws ReflectiveOperationException {
		assertArrayEquals(
			new float[] { 0.165132225f, 0.262250721f, 0.456411064f },
			invokeColorHelper("getAmbientColorForAngles", -8), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.225462720f, 0.299400598f, 0.547009230f },
			invokeColorHelper("getAmbientColorForAngles", 0), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.370255947f, 0.388560295f, 0.764444828f },
			invokeColorHelper("getAmbientColorForAngles", 12), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.513126791f, 0.547581077f, 1.000000000f },
			invokeColorHelper("getAmbientColorForAngles", 30), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.485149980f, 0.672443211f, 1.000000000f },
			invokeColorHelper("getAmbientColorForAngles", 60), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.485149980f, 0.672443211f, 1.000000000f },
			invokeColorHelper("getAmbientColorForAngles", 85), 1e-6f
		);
	}

	@Test
	public void directionalLightMatchesGolden() throws ReflectiveOperationException {
		assertArrayEquals(
			new float[] { 0.116922617f, 0.096754856f, 0.066591375f },
			invokeColorHelper("getDirectionalLightForAngles", -8), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.116922617f, 0.096754856f, 0.066591375f },
			invokeColorHelper("getDirectionalLightForAngles", 0), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.302544773f, 0.215248346f, 0.120878309f },
			invokeColorHelper("getDirectionalLightForAngles", 12), 1e-6f
		);
		assertArrayEquals(
			new float[] { 1.009382844f, 0.819268167f, 0.548119307f },
			invokeColorHelper("getDirectionalLightForAngles", 30), 1e-6f
		);
		assertArrayEquals(
			new float[] { 2.343648672f, 2.354422808f, 2.118747473f },
			invokeColorHelper("getDirectionalLightForAngles", 60), 1e-6f
		);
		assertArrayEquals(
			new float[] { 2.864768744f, 3.122953415f, 3.115890741f },
			invokeColorHelper("getDirectionalLightForAngles", 85), 1e-6f
		);
	}

	@Test
	public void lightingMustNotAliasEnvironmentColors() throws ReflectiveOperationException {
		EnvironmentManager environmentManager = new EnvironmentManager();
		environmentManager.currentDirectionalColor = new float[] { .1f, .2f, .3f };
		environmentManager.currentAmbientColor = new float[] { .4f, .5f, .6f };
		environmentManager.currentWaterColor = new float[] { .7f, .8f, .9f };
		environmentManager.currentFogColor = new float[] { .15f, .25f, .35f };
		environmentManager.currentDirectionalStrength = 2.5f;
		environmentManager.currentAmbientStrength = 1.5f;

		SkyLighting lighting = new SkyLighting();
		var environmentManagerField = SkyLighting.class.getDeclaredField("environmentManager");
		environmentManagerField.setAccessible(true);
		environmentManagerField.set(lighting, environmentManager);
		Method seedFromEnvironment = SkyLighting.class.getDeclaredMethod("seedFromEnvironment");
		seedFromEnvironment.setAccessible(true);
		seedFromEnvironment.invoke(lighting);

		assertNotSame(environmentManager.currentDirectionalColor, lighting.directionalColor);
		assertNotSame(environmentManager.currentAmbientColor, lighting.ambientColor);
		assertNotSame(environmentManager.currentWaterColor, lighting.waterColor);
		assertArrayEquals(new float[] { .1f, .2f, .3f }, lighting.directionalColor, 0);
		assertArrayEquals(new float[] { .4f, .5f, .6f }, lighting.ambientColor, 0);
		assertArrayEquals(new float[] { .7f, .8f, .9f }, lighting.waterColor, 0);
		assertEquals(2.5f, lighting.directionalStrength, 0);
		assertEquals(1.5f, lighting.ambientStrength, 0);

		lighting.directionalColor[0] = .999f;
		lighting.ambientColor[0] = .999f;
		lighting.waterColor[0] = .999f;

		assertArrayEquals(new float[] { .1f, .2f, .3f }, environmentManager.currentDirectionalColor, 0);
		assertArrayEquals(new float[] { .4f, .5f, .6f }, environmentManager.currentAmbientColor, 0);
		assertArrayEquals(new float[] { .7f, .8f, .9f }, environmentManager.currentWaterColor, 0);
	}

	@Test
	public void allIntTernaryBindsTheIntOverload() {
		boolean enabled = true;
		assertEquals("int", overloadPickedFor(enabled ? 1 : 0));
		assertEquals("float", overloadPickedFor(enabled ? 1f : 0f));

		float value = .5f;
		assertEquals("float", overloadPickedFor(enabled ? value : 0));
	}

	private static String overloadPickedFor(int value) {
		return "int";
	}

	private static String overloadPickedFor(float value) {
		return "float";
	}

	@Test
	public void environmentForceMoonPhaseParsesEveryConfigValue() {
		Gson gson = new Gson();
		for (MoonPhase phase : MoonPhase.values()) {
			Environment environment = gson.fromJson("{\"forceMoonPhase\": \"" + phase.name() + "\"}", Environment.class);
			assertEquals(phase, environment.forceMoonPhase);
		}

		assertNull(gson.fromJson("{}", Environment.class).forceMoonPhase);
		assertNull(gson.fromJson("{\"moonPhase\": \"FULL_MOON\"}", Environment.class).forceMoonPhase);
	}

	@Test
	public void environmentMoonDirectionalStrengthDefaultsToDirectionalStrength() {
		Gson gson = new Gson();
		Environment unset = gson.fromJson("{\"directionalStrength\": 0.8}", Environment.class).normalize();
		assertEquals(.8f, unset.moonDirectionalStrength, 0);

		Environment set = gson
			.fromJson("{\"directionalStrength\": 0.8, \"moonDirectionalStrength\": 0.2}", Environment.class)
			.normalize();
		assertEquals(.2f, set.moonDirectionalStrength, 0);
		assertEquals(.8f, set.directionalStrength, 0);

		Environment zero = gson
			.fromJson("{\"directionalStrength\": 0.8, \"moonDirectionalStrength\": 0}", Environment.class)
			.normalize();
		assertEquals(0, zero.moonDirectionalStrength, 0);
	}

	@Test
	public void environmentMoonShadowFieldsDefaultToPreviousBehavior() {
		Gson gson = new Gson();
		Environment unset = gson.fromJson("{}", Environment.class).normalize();
		assertEquals(1, unset.moonShadowStrength, 0);
		assertEquals(0, unset.minMoonIllumination, 0);

		Environment set = gson
			.fromJson("{\"moonShadowStrength\": 3, \"minMoonIllumination\": 0.35}", Environment.class)
			.normalize();
		assertEquals(3, set.moonShadowStrength, 0);
		assertEquals(.35f, set.minMoonIllumination, 0);

		Environment zero = gson.fromJson("{\"moonShadowStrength\": 0}", Environment.class).normalize();
		assertEquals(0, zero.moonShadowStrength, 0);
	}

	@Test
	public void nightBoostTreatsNewMoonAndSetMoonAlike() {
		float newMoonHigh = moonPresence(60, 0);
		float fullMoonSet = moonPresence(-20, 1);
		float fullMoonHigh = moonPresence(60, 1);

		assertEquals(0, newMoonHigh, 0);
		assertEquals(0, fullMoonSet, 0);
		assertEquals(newMoonHigh, fullMoonSet, 0);
		assertEquals(1, fullMoonHigh, 1e-6);
		assertTrue(moonPresence(60, .5f) > moonPresence(60, .25f));
		assertTrue(moonPresence(30, 1) > moonPresence(0, 1));
		assertTrue(moonPresence(-9, 1) < .05f);
	}

	@Test
	public void fullMoonKeepsPartOfTheBrightnessBoost() {
		float newMoon = boostFraction(60, 0);
		float fullMoonHigh = boostFraction(60, 1);

		assertEquals(1, newMoon, 1e-6);
		assertEquals(.2f, fullMoonHigh, 1e-6);
		assertTrue(boostFraction(60, .25f) > fullMoonHigh);
		assertTrue(boostFraction(60, 1) < boostFraction(0, 1));
		assertEquals(newMoon, boostFraction(-20, 1), 0);
	}

	@Test
	public void moonShadowPhaseResponseIsCompressed() {
		assertEquals(0, phaseShadowFactor(0), 0);
		assertEquals(1, phaseShadowFactor(1), 1e-6);
		assertTrue(phaseShadowFactor(.5f) > .65f);
		assertTrue(phaseShadowFactor(.25f) > .4f);
		assertTrue(phaseShadowFactor(.75f) > phaseShadowFactor(.5f));
		assertTrue(phaseShadowFactor(.5f) > phaseShadowFactor(.25f));
		assertTrue(phaseShadowFactor(.75f) < 1);
	}

	@Test
	public void moonPhaseAdvancesOnlyWhereMoonlightHasFadedOut() {
		float phaseAdvanceAltitudeDegrees = -10 * DEG_TO_RAD * RAD_TO_DEG;
		assertTrue(phaseAdvanceAltitudeDegrees <= -10);
		assertEquals(0, moonPresence(phaseAdvanceAltitudeDegrees, .5f), 0);
		assertEquals(0, moonPresence(phaseAdvanceAltitudeDegrees, 1), 0);
		assertTrue(moonPresence(-.001, .5f) > 0);
	}

	@Test
	public void environmentForceMoonActiveParses() {
		Gson gson = new Gson();
		assertTrue(gson.fromJson("{\"forceMoonActive\": true}", Environment.class).forceMoonActive);
		assertFalse(gson.fromJson("{\"forceMoonActive\": false}", Environment.class).forceMoonActive);
		assertFalse(gson.fromJson("{}", Environment.class).forceMoonActive);
	}

	private static float phaseShadowFactor(float illumination) {
		return (float) Math.pow(illumination, .5f);
	}

	private static float moonPresence(double moonAltitudeDegrees, float moonIllumination) {
		if (moonAltitudeDegrees <= -10 || moonIllumination <= .01f)
			return 0;
		float t = saturate((float) ((moonAltitudeDegrees + 10) / 30));
		return saturate(moonIllumination * (t * t * (3 - 2 * t)));
	}

	private static float boostFraction(double moonAltitudeDegrees, float moonIllumination) {
		return .2f + .8f * (1 - moonPresence(moonAltitudeDegrees, moonIllumination));
	}
}
