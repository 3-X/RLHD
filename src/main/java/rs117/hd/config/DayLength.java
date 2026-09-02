package rs117.hd.config;

import lombok.RequiredArgsConstructor;

/**
 * Controls the daylight share of Dynamic cycles. Fixed-sun modes apply it only to an unlocked moon.
 */
@RequiredArgsConstructor
public enum DayLength {
	NORMAL("Normal", .5f),
	LONG_DAYS("Long Days", .7f),
	LONGER_DAYS("Longer Days", .85f),
	LONG_NIGHTS("Long Nights", .3f),
	LONGER_NIGHTS("Longer Nights", .15f),
	;

	private final String name;
	public final float dayFraction;

	@Override
	public String toString() {
		return name;
	}
}
