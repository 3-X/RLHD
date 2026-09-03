package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MoonBehavior {
	DISABLED(false, false, true, false),
	REALISTIC(false, false, false, false),
	MIRRORED(true, false, false, false),
	STATIC(false, true, false, false),
	CUSTOM(false, false, false, true),
	;

	public final boolean mirrorsSun;
	public final boolean isStatic;
	public final boolean isDisabled;
	public final boolean usesCustomCycleDuration;
}
