package rs117.hd.scene.weather;

import javax.annotation.Nullable;
import rs117.hd.config.SeasonalTheme;

/**
 * The weather controller's per-frame output, mutated in place on the client thread. Renderers and future
 * effects read it through {@code WeatherManager.getState()}.
 * <p>
 * {@link #params} are the weather parameters at the camera focal point, identical to
 * {@code strip.evaluateSkySlot(slot, 0)}. The strip applies the focal point's climate blend to the whole sky; see
 * {@link WeatherStrip} for the cloud contract.
 * <p>
 * {@link #exposure} says how much of that weather actually reaches the focal point. It is 0 indoors, underground, in
 * sheltered realms, and wherever the target environment isn't an overworld one, is underwater or has a fixed sun.
 * Instances resolve to their template tiles, so instanced copies of bound outdoor areas (e.g. Temple Trekking via
 * MORYTANIA) can be exposed. Exposure is deliberately not multiplied into the parameters; consumers should apply it
 * themselves, e.g. to cloud alpha or precipitation density.
 */
public final class WeatherState {
	/** Whether weather data is loaded and the controller is running. When false, only the baseline wind is published. */
	public boolean active;
	/** Incremented on every successful weather.json load. Consumers should rebuild derived caches. */
	public int dataVersion;
	/** Incremented whenever consumers should snap to the new state instead of easing into it. */
	public long discontinuityId;
	/** Weather clock: UTC milliseconds, plus any developer offset or time-lapse. */
	public long clockMillis;
	/** Timeline phase at the focal point, in milliseconds. */
	public long phaseMillis;
	/** 0 to 1: how exposed the focal point is to the weather. */
	public float exposure;
	public SeasonalTheme season = SeasonalTheme.SUMMER;

	/** Focal point tile (x, y, plane). */
	public final int[] worldPoint = new int[3];
	/**
	 * Strip origin (u = 0): the camera focal point, not the camera, in world local units (tile * 128 + sub-tile offset).
	 * originX is east (scene +X) and originY is north (scene +Z, the "z" of the strip axis and wind directions). Outside
	 * instances it equals sceneBase * 128 + the scene-local focal point. In instances it is the centre of the template
	 * tile under the focal point, so it moves in whole-tile steps, and rotated template chunks aren't accounted for.
	 */
	public double originX, originY;
	/** Index of the binding containing the focal point, or -1 when unbound. */
	public int bindingIndex = -1;
	/** The binding's climate name, {@link Climate#NONE}, or null when unbound. */
	@Nullable
	public String bindingClimate;
	/** Climate of the dominant zone. Never NONE; sheltered areas keep the previous climate's weather evolving. */
	@Nullable
	public Climate climate;

	@Nullable
	public WeatherType type, previousType, nextType;
	/** Position within the current type's [mild, severe] range. */
	public float intensity;
	/** Current segment bounds, in timeline phase milliseconds. */
	public long segmentStartPhase, segmentEndPhase;
	/**
	 * Phase at which the leading edge of the next type change's transition window reaches the focal point, or
	 * Long.MAX_VALUE if no change lies within the strip. It is at or before {@link #phaseMillis} during the first half of
	 * a window already over the focal point, so clamp {@code nextChangePhase - phaseMillis} at 0 for a time until arrival.
	 */
	public long nextChangePhase = Long.MAX_VALUE;
	/** Progress through the transition window currently over the focal point, or 0 when none. */
	public float transitionProgress;
	/** Shape of that window: +1 worsening, -1 clearing, 0 neutral. */
	public int transitionShape;

	public final float[] params = WeatherParam.defaultVector();
	/** Convenience: saturate(cloudCover * (1 - exp(-cloudThickness))). */
	public float overcast;
	/** Convenience: saturate(rain + snow + ash + dust). */
	public float precipitation;

	public final WeatherStrip strip = new WeatherStrip();
	public final SurfaceWind surfaceWind = new SurfaceWind();
	public final UpperWind upperWind = new UpperWind();

	public float get(WeatherParam param) {
		return params[param.offset()];
	}

	public float get(WeatherParam param, int component) {
		return params[param.offset() + component];
	}

	/**
	 * Wind near the ground, using the same conventions as the environment wind fields and the legacy renderer's
	 * wind uniforms. Renderers should upload these values rather than reading environment wind directly.
	 */
	public static final class SurfaceWind {
		/** Radians, downwind. 0 = east (+X), counter-clockwise toward north (+Z), in [0, 2pi). */
		public float windAngle;
		public float windDirectionX = 1, windDirectionZ;
		/** Gust noise scroll rate, local units per second. */
		public float windSpeed = 15;
		/** Displacement amplitude, local units. */
		public float windStrength;
		/** Height in local units at which the wind reaches full strength. */
		public float windCeiling = 1280;
		/** Scalar gust offset, rebased to stay precise as a float. Upload this to the wind offset uniforms. */
		public float windOffset;
		/** Direction-aware gust offsets (integral of direction * speed), rebased together with windOffset. */
		public float windOffsetX, windOffsetZ;
		/** Exact, never rebased integrals. */
		public double windOffsetExact, windOffsetExactX, windOffsetExactZ;
		/** Current gust envelope 0..1 and the gust depth parameter. */
		public float gust, gustiness;
		/** Baseline wind before weather modulation, and how much of it came from the climate (debug). */
		public float baseAngle, baseSpeed, baseStrength, climateShare;

		public void set(float angle, float speed, float strength, float ceiling) {
			windAngle = angle;
			windDirectionX = (float) Math.cos(angle);
			windDirectionZ = (float) Math.sin(angle);
			windSpeed = speed;
			windStrength = strength;
			windCeiling = ceiling;
		}
	}

	/** The cloud-level steering wind. */
	public static final class UpperWind {
		/** Radians, downwind, same convention as {@link SurfaceWind#windAngle}. */
		public float angle;
		public float directionX = 1, directionZ;
		/** Local units per second. */
		public float speed = WeatherConstants.FRONT_SPEED;
		/** Signed angle from the timeline's drift direction, in radians. */
		public float deviation;
		/**
		 * Per-client integral of the downwind cloud feature drift (upper wind direction * speed * the focal point's cloudSpeed
		 * * dt * clock scale), in local units (east, north). Features are displaced by +offset, so sample cloud noise at
		 * (worldPos - offset). Every formation in view drifts at the focal point's rate. Wrap by your noise period.
		 */
		public double cloudOffsetX, cloudOffsetZ;
	}
}
