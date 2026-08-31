package rs117.hd.tests;

import java.lang.reflect.Field;
import org.junit.Test;
import rs117.hd.HdPlugin;
import rs117.hd.scene.DaylightCycleManager;
import rs117.hd.scene.EnvironmentManager;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class DaylightCycleSnapshotTest {
	@Test
	public void sunAnglesAreResolvedOncePerUpdate() throws ReflectiveOperationException {
		DaylightCycleManager daylightCycleManager = new DaylightCycleManager();
		setInjectedField(daylightCycleManager, "plugin", new HdPlugin());
		setInjectedField(daylightCycleManager, "environmentManager", new EnvironmentManager());

		// update() is the per-frame entry point: it pins the instant and resolves
		// the complete celestial state before any consumer reads it.
		daylightCycleManager.update();
		float[] first = getSunAngles(daylightCycleManager);
		float[] second = getSunAngles(daylightCycleManager);
		assertSame("within one frame, consumers must share the resolved sun angles", first, second);

		daylightCycleManager.update();
		float[] third = getSunAngles(daylightCycleManager);
		assertNotSame("each update must resolve a fresh sun-angle array", first, third);
	}

	private static void setInjectedField(Object target, String name, Object value) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static float[] getSunAngles(DaylightCycleManager daylightCycleManager) throws ReflectiveOperationException {
		Field stateField = DaylightCycleManager.class.getDeclaredField("state");
		stateField.setAccessible(true);
		Object state = stateField.get(daylightCycleManager);

		Field sunAnglesField = state.getClass().getDeclaredField("sunAngles");
		sunAnglesField.setAccessible(true);
		return (float[]) sunAnglesField.get(state);
	}
}
