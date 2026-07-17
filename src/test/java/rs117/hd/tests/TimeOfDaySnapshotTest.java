package rs117.hd.tests;

import org.junit.Test;
import rs117.hd.scene.TimeOfDay;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class TimeOfDaySnapshotTest {
	@Test
	public void sunAnglesAreCachedWithinAFrameAndInvalidatedByBeginFrame() {
		TimeOfDay tod = new TimeOfDay();
		tod.beginFrame();
		double[] first = tod.getSunAngles();
		double[] second = tod.getSunAngles();
		assertSame("within one frame, getSunAngles must return the cached array", first, second);

		tod.beginFrame();
		double[] third = tod.getSunAngles();
		assertNotSame("beginFrame must invalidate the cache", first, third);
	}
}
