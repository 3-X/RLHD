package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MoonBehavior {
	DISABLED("Disabled"),
	REALISTIC("Realistic"),
	// Antipodal to the sun, so the moon is up for exactly as long as the sun is down.
	NIGHT_SYNCED("Night-Synced"),
	STATIC("Static"),
	;

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
