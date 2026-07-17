package rs117.hd.tests;

import org.junit.Test;
import rs117.hd.scene.TimeOfDay;
import rs117.hd.utils.AtmosphereUtils;

import static org.junit.Assert.assertArrayEquals;

/**
 * Characterization tests locking in the output of the atmosphere color functions.
 * Golden values were captured from the implementation prior to pre-linearizing
 * the constant keyframe tables; any drift beyond 1e-6 indicates a behavior change.
 */
public class AtmosphereUtilsTest {
	private static double[] angles(double altitudeDegrees) {
		return new double[] { 0, Math.toRadians(altitudeDegrees) };
	}

	@Test
	public void ambientColorMatchesGolden() {
		assertArrayEquals(
			new float[] { 0.165132225f, 0.262250721f, 0.456411064f },
			AtmosphereUtils.getAmbientColorForAngles(angles(-8)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.225462720f, 0.299400598f, 0.547009230f },
			AtmosphereUtils.getAmbientColorForAngles(angles(0)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.370255947f, 0.388560295f, 0.764444828f },
			AtmosphereUtils.getAmbientColorForAngles(angles(12)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.513126791f, 0.547581077f, 1.000000000f },
			AtmosphereUtils.getAmbientColorForAngles(angles(30)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.485149980f, 0.672443211f, 1.000000000f },
			AtmosphereUtils.getAmbientColorForAngles(angles(60)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.485149980f, 0.672443211f, 1.000000000f },
			AtmosphereUtils.getAmbientColorForAngles(angles(85)), 1e-6f
		);
	}

	@Test
	public void skyColorMatchesGolden() {
		assertArrayEquals(
			new float[] { 0.156218588f, 0.156218588f, 0.213644534f },
			AtmosphereUtils.getSkyColorForAngles(angles(-8)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.337469906f, 0.337469906f, 0.449223995f },
			AtmosphereUtils.getSkyColorForAngles(angles(0)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.486051321f, 0.486051321f, 0.641196847f },
			AtmosphereUtils.getSkyColorForAngles(angles(12)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.645360351f, 0.691880226f, 0.866488039f },
			AtmosphereUtils.getSkyColorForAngles(angles(30)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.725490212f, 0.839215696f, 0.999999940f },
			AtmosphereUtils.getSkyColorForAngles(angles(60)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.725490212f, 0.839215696f, 0.999999940f },
			AtmosphereUtils.getSkyColorForAngles(angles(85)), 1e-6f
		);
	}

	@Test
	public void directionalLightMatchesGolden() {
		TimeOfDay tod = new TimeOfDay();
		assertArrayEquals(
			new float[] { 0.116922617f, 0.096754856f, 0.066591375f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(-8)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.116922617f, 0.096754856f, 0.066591375f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(0)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.331188440f, 0.209243685f, 0.103225976f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(12)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 1.356104612f, 0.747321188f, 0.278463453f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(30)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 3.834468603f, 2.048453808f, 0.702207565f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(60)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 5.035998344f, 2.679252863f, 0.907642066f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(85)), 1e-6f
		);
	}
}
