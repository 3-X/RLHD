package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MoonBehavior
{
	REALISTIC("Realistic", false),
	NIGHT_SYNCED("Night Synced", true),
	;

	private final String name;
	public final boolean usesNightSyncedMoon;

	@Override
	public String toString() {
		return name;
	}
}
