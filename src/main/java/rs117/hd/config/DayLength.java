package rs117.hd.config;

import lombok.RequiredArgsConstructor;

/**
 * Controls the real-time share of a Dynamic cycle spent in daylight. The full
 * cycle still takes {@code cycleDurationMinutes}; the cycle clock is warped so
 * the favored period advances more slowly.
 * <p>
 * Dynamic applies this to the sun and moving moon. Fixed-sun modes apply it
 * only to an unlocked moon; Real Time and Synced Days ignore it.
 */
@RequiredArgsConstructor
public enum DayLength
{
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
