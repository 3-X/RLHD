package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum SunStyle {
	OLD_SCHOOL("Old School"),
	ARTISTIC("Artistic"),
	;

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
