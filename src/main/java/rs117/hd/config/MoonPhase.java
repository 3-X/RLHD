package rs117.hd.config;

import lombok.RequiredArgsConstructor;

/**
 * DYNAMIC follows {@link MoonBehavior}; other values lock the illuminated fraction.
 */
@RequiredArgsConstructor
public enum MoonPhase {
	DYNAMIC("Dynamic", -1f, false),
	FULL_MOON("Full Moon", 1.0f, true),
	GIBBOUS("Gibbous", 0.75f, true),
	HALF_MOON("Half Moon", 0.5f, true),
	CRESCENT("Crescent", 0.25f, true),
	NEW_MOON("New Moon", 0.0f, true),
	;

	private final String name;
	public final float illumination; // -1 for DYNAMIC; otherwise 0..1.
	public final boolean isLocked;

	@Override
	public String toString() {
		return name;
	}
}
