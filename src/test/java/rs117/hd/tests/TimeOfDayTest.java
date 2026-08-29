package rs117.hd.tests;

import java.lang.reflect.Method;
import org.junit.Test;
import rs117.hd.scene.TimeOfDay;

import static org.junit.Assert.assertArrayEquals;
import static rs117.hd.utils.MathUtils.*;

/**
 * Characterization tests locking in TimeOfDay's procedural lighting colors.
 * Golden values were captured from the implementation prior to pre-linearizing
 * the constant keyframe tables; any drift beyond 1e-6 indicates a behavior change.
 */
public class TimeOfDayTest {
	private static float[] angles(float altitudeDegrees) {
		return new float[] { 0, altitudeDegrees * DEG_TO_RAD };
	}

	private static float[] invokeColorHelper(String name, float altitudeDegrees) throws ReflectiveOperationException {
		Method method = TimeOfDay.class.getDeclaredMethod(name, float[].class);
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
}
