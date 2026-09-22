package rs117.hd.config;

import lombok.RequiredArgsConstructor;

/**
 * REALISTIC follows {@link MoonBehavior}; other values lock the illuminated fraction.
 */
@RequiredArgsConstructor
public enum MoonPhase {
	REALISTIC(-1f, false),
	NEW_MOON(0.0f, true),
	CRESCENT(0.25f, true),
	HALF_MOON(0.5f, true),
	GIBBOUS(0.75f, true),
	FULL_MOON(0.99f, true),
	;

	public final float illumination; // -1 for REALISTIC; otherwise 0..1.
	public final boolean isLocked;
}
