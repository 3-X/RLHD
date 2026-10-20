package rs117.hd.scene.weather;

import javax.annotation.Nullable;
import rs117.hd.config.SeasonalTheme;

/**
 * Developer overrides set through {@code ::117hd weather} commands. Kept in memory only, so they survive
 * plugin restarts within a client session but are never saved to the config.
 */
public final class WeatherOverrides {
	/** Weather type rolled in from {@link #forcedFromPhase} onward. */
	@Nullable
	public String forcedTypeName;
	/** Weather type held for every segment before {@link #forcedFromPhase}, or null to keep natural weather. */
	@Nullable
	public String heldTypeName;
	/** Fixed intensity for the forced type, or NaN to let it vary naturally. */
	public float forcedIntensity = Float.NaN;
	public long forcedFromPhase = Long.MIN_VALUE;

	@Nullable
	public String forcedClimate;
	@Nullable
	public SeasonalTheme forcedSeason;

	public boolean windOverride;
	/** Radians, using the environment windAngle convention. */
	public float windAngle;
	public float windStrength;
	public float windSpeed;

	/** Kill switch: publish the environment's wind unchanged, as if the weather controller didn't exist. */
	public boolean disabled;

	public boolean any() {
		return forcedTypeName != null || forcedClimate != null || forcedSeason != null || windOverride || disabled;
	}

	public void clearForcedType() {
		forcedTypeName = null;
		heldTypeName = null;
		forcedIntensity = Float.NaN;
		forcedFromPhase = Long.MIN_VALUE;
	}

	public void clearAll() {
		clearForcedType();
		forcedClimate = null;
		forcedSeason = null;
		windOverride = false;
		disabled = false;
	}
}
