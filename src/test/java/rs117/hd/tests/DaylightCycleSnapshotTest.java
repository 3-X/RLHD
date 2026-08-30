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
	public void sunAnglesAreCachedWithinAFrameAndInvalidatedByUpdate() throws ReflectiveOperationException {
		DaylightCycleManager daylightCycleManager = new DaylightCycleManager();
		setInjectedField(daylightCycleManager, "plugin", new HdPlugin());
		setInjectedField(daylightCycleManager, "environmentManager", new EnvironmentManager());

		// update() is the per-frame entry point: it pins the instant every getter
		// derives from and drops the previous frame's astronomy snapshot.
		daylightCycleManager.update();
		float[] first = daylightCycleManager.getSunAngles();
		float[] second = daylightCycleManager.getSunAngles();
		assertSame("within one frame, getSunAngles must return the cached array", first, second);

		daylightCycleManager.update();
		float[] third = daylightCycleManager.getSunAngles();
		assertNotSame("update must invalidate the cache", first, third);
	}

	private static void setInjectedField(Object target, String name, Object value) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
