package rs117.hd.scene.weather;

import javax.annotation.Nullable;

import static rs117.hd.scene.weather.WeatherConstants.*;

/**
 * Linearly cross-fades between weather zones (e.g. when walking across a climate border or when the season
 * changes). The target's weight rises by dt / seconds each step while the others fall, so a blend always
 * completes within the blend time, from any starting state.
 */
public final class ZoneBlend {
	public final WeatherZone[] zones = new WeatherZone[MAX_ZONES];
	public final float[] weights = new float[MAX_ZONES];
	public int count;
	@Nullable
	public WeatherZone target;

	public void setTarget(WeatherZone zone) {
		target = zone;
		ensureTargetPresent();
	}

	private void ensureTargetPresent() {
		if (target == null)
			return;
		for (int i = 0; i < count; i++)
			if (zones[i] == target)
				return;

		if (count == MAX_ZONES) {
			// Drop the zone contributing the least
			int lowest = 0;
			for (int i = 1; i < count; i++)
				if (weights[i] < weights[lowest])
					lowest = i;
			removeAt(lowest);
		}
		zones[count] = target;
		weights[count] = 0;
		count++;
	}

	public void step(float dt, float seconds) {
		if (target == null) {
			clear();
			return;
		}
		ensureTargetPresent();

		float delta = seconds > 0 ? Math.max(0, dt) / seconds : 1;
		float sum = 0;
		for (int i = 0; i < count; i++) {
			if (zones[i] == target) {
				weights[i] = Math.min(1, weights[i] + delta);
			} else {
				weights[i] = Math.max(0, weights[i] - delta);
			}
			sum += weights[i];
		}

		if (!(sum > 0) || !Float.isFinite(sum)) {
			snap();
			return;
		}
		for (int i = 0; i < count; i++)
			weights[i] /= sum;

		// Prune finished zones, but never the target, even while its weight is still zero
		for (int i = count - 1; i >= 0; i--)
			if (zones[i] != target && weights[i] <= 0)
				removeAt(i);
	}

	public void snap() {
		clearArrays();
		if (target == null) {
			count = 0;
			return;
		}
		zones[0] = target;
		weights[0] = 1;
		count = 1;
	}

	public void clear() {
		clearArrays();
		count = 0;
		target = null;
	}

	private void clearArrays() {
		for (int i = 0; i < MAX_ZONES; i++) {
			zones[i] = null;
			weights[i] = 0;
		}
	}

	private void removeAt(int index) {
		for (int i = index; i < count - 1; i++) {
			zones[i] = zones[i + 1];
			weights[i] = weights[i + 1];
		}
		count--;
		zones[count] = null;
		weights[count] = 0;
	}

	/** The zone with the largest weight. Ties go to the target. */
	@Nullable
	public WeatherZone dominant() {
		WeatherZone best = null;
		float bestWeight = -1;
		for (int i = 0; i < count; i++) {
			float w = weights[i];
			if (w > bestWeight || w == bestWeight && zones[i] == target) {
				best = zones[i];
				bestWeight = w;
			}
		}
		return best != null ? best : target;
	}

	public float weightOf(WeatherZone zone) {
		for (int i = 0; i < count; i++)
			if (zones[i] == zone)
				return weights[i];
		return 0;
	}

	public boolean isSettled() {
		return count == 1 && zones[0] == target;
	}
}
