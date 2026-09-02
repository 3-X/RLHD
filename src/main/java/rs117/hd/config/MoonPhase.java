package rs117.hd.config;

import lombok.RequiredArgsConstructor;

/**
 * DYNAMIC follows {@link MoonBehavior}; other values lock the illuminated fraction.
 */
@RequiredArgsConstructor
public enum MoonPhase {
	DYNAMIC("Dynamic", -1f),
	FULL_MOON("Full Moon", 1.0f),
	GIBBOUS("Gibbous", 0.75f),
	HALF_MOON("Half Moon", 0.5f),
	CRESCENT("Crescent", 0.25f),
	NEW_MOON("New Moon", 0.0f),
	;

	private final String name;
	public final float illumination; // -1 for DYNAMIC; otherwise 0..1.

	public boolean isLocked() {
		return this != DYNAMIC;
	}

	@Override
	public String toString() {
		return name;
	}
}
