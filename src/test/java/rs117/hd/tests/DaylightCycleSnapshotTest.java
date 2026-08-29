package rs117.hd.tests;

import org.junit.Test;
import rs117.hd.scene.DaylightCycleManager;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class DaylightCycleSnapshotTest {
	@Test
	public void sunAnglesAreCachedWithinAFrameAndInvalidatedByUpdate() {
		DaylightCycleManager tod = new DaylightCycleManager();
		// update() is the per-frame entry point: it pins the instant every getter
		// derives from and drops the previous frame's astronomy snapshot.
		tod.update();
		float[] first = tod.getSunAngles();
		float[] second = tod.getSunAngles();
		assertSame("within one frame, getSunAngles must return the cached array", first, second);

		tod.update();
		float[] third = tod.getSunAngles();
		assertNotSame("update must invalidate the cache", first, third);
	}
}
