package rs117.hd.scene.weather;

import static rs117.hd.scene.weather.WeatherConstants.*;
import static rs117.hd.utils.MathUtils.*;

/**
 * The weather at the camera focal point along the drift axis: the pure states of the segments k-3 ... k+3 and the
 * transition windows (edges) between them, positioned in local units upwind of the strip origin.
 * <p>
 * The origin (u = 0) is the camera focal point ({@code WeatherState.originX/originY}), not the camera position. For a
 * point offset (dx, dz) from the focal point in local units (east = scene +X, north = scene +Z), the upwind distance is
 * {@code u = -(dx * axisX + dz * axisZ)}. A cloud shader must measure cloud-plane hits from the scene-local
 * {@code plugin.cameraFocalPoint}, not from the camera, or fronts will slide as the camera orbits.
 * <p>
 * Positive u lies in the focal point's future, since weather systems travel downwind: the strip at u is the focal
 * point's weather at phase + u / FRONT_SPEED. {@link #evaluateSkySlot} at u = 0 gives exactly the camera-local
 * parameters, and further upwind it shows the weather that will roll in. A cloud shader should mirror
 * {@link #evaluateSkySlot} (uploading HIGH_DECK_LEAD_DISTANCE and HIGH_DECK_LEAD_FACTOR with the strip), so the sky
 * and the local state always agree. {@link #evaluateSlot} is the bare strip, without the cirrus lead.
 * <p>
 * There is deliberately no spatial climate term. Every entry is the focal point's zone blend (climate and season),
 * masked by the forbid list of the climate being entered. As a result:
 * <ul>
 * <li>The strip projects the focal point's climate over the whole visible sky and varies with position only through u.
 * Looking across a climate border shows the local climate's weather over the neighbouring region.</li>
 * <li>Crossing a climate border, or a season change, cross-fades every entry, i.e. the whole sky, over
 * {@link WeatherConstants#CLIMATE_BLEND_SECONDS} rather than rolling in. Snap events (teleports, instant environment
 * transitions, login, data reloads, clock jumps) and climate changes while unexposed instead jump straight to the new
 * state and increment {@code WeatherState.discontinuityId}.</li>
 * </ul>
 * Per-region skies would need a spatial signal, e.g. strips for neighbouring climates built with
 * {@code WeatherManager.sampleAt}.
 */
public final class WeatherStrip {
	public static final int SIZE = STRIP_SIZE;
	public static final int CENTER = STRIP_CENTER;

	/** Pure segment states, mixed across climate zones and masked by forbidden parameters. */
	public final float[][] params = new float[SIZE][WeatherParam.VECTOR_SIZE];
	/** Per-segment seeds, e.g. for distinct cloud formations. */
	public final int[] seeds = new int[SIZE];
	/**
	 * Weather type labels from the label zone: normally the dominant zone, but the incoming zone while cross-fading into a
	 * climate that forbids the dominant zone's current weather. {@link #intensities} and {@link #edgeShape} follow the
	 * same zone.
	 */
	public final WeatherType[] types = new WeatherType[SIZE];
	/** Position of each segment within its type's [mild, severe] range, from the label zone. */
	public final float[] intensities = new float[SIZE];
	/** Segment bounds in timeline phase milliseconds. */
	public final long[] segmentStart = new long[SIZE];
	public final long[] segmentEnd = new long[SIZE];
	/** Center of each transition window, in local units upwind of the origin. Positive means it hasn't arrived yet. */
	public final float[] edgeDistance = new float[SIZE - 1];
	/** Width of each transition window, in local units. */
	public final float[] edgeWidth = new float[SIZE - 1];
	/** +1 when the weather worsens across the edge, -1 when it clears, 0 when neutral. */
	public final int[] edgeShape = new int[SIZE - 1];
	/** Unit vector (east, north) along which fronts travel, i.e. downwind. */
	public float axisX = 1, axisZ;
	/** True while the strip holds a current sample. Cleared whenever the controller is inactive, like {@code WeatherState.active}. */
	public boolean valid;

	public float evaluateSlot(int slot, float u) {
		var group = WeatherParam.groupOfSlot(slot);
		float v = params[0][slot];
		for (int i = 0; i < SIZE - 1; i++) {
			float width = edgeWidth[i];
			float p = width > 0 ?
				saturate((u - edgeDistance[i] + .5f * width) / width) :
				(u >= edgeDistance[i] ? 1 : 0);
			v = mix(v, params[i + 1][slot], group.ease(edgeShape[i], p));
		}
		return v;
	}

	public float evaluate(WeatherParam param, float u) {
		return evaluateSlot(param.offset(), u);
	}

	public float evaluate(WeatherParam param, int component, float u) {
		return evaluateSlot(param.offset() + component, u);
	}

	/**
	 * Reference formula for the sky at upwind distance u: the bare strip ({@link #evaluateSlot}), plus the high-deck
	 * (cirrus) lead on highCloudCover, which raises it to HIGH_DECK_LEAD_FACTOR times the cover HIGH_DECK_LEAD_DISTANCE
	 * further upwind, so cirrus announces incoming systems. A cloud shader should mirror this. At u = 0 it equals the
	 * camera-local parameters, and the coverage invariant keeps it exact for |u| up to FRONT_VISIBLE_DISTANCE.
	 */
	public float evaluateSkySlot(int slot, float u) {
		float v = evaluateSlot(slot, u);
		if (slot == WeatherParam.HIGH_CLOUD_COVER.offset())
			v = max(v, HIGH_DECK_LEAD_FACTOR * evaluateSlot(slot, u + HIGH_DECK_LEAD_DISTANCE));
		return v;
	}

	public float evaluateSky(WeatherParam param, float u) {
		return evaluateSkySlot(param.offset(), u);
	}

	public float evaluateSky(WeatherParam param, int component, float u) {
		return evaluateSkySlot(param.offset() + component, u);
	}

	/** Focal-point value of a slot: the sky formula at u = 0. */
	public float evaluateLocalSlot(int slot) {
		return evaluateSkySlot(slot, 0);
	}

	public void evaluateLocal(float[] out) {
		for (int slot = 0; slot < WeatherParam.VECTOR_SIZE; slot++)
			out[slot] = evaluateLocalSlot(slot);
	}

	/** Upwind distance of a point offset (dx, dz) from the strip origin, in world local units (east, north). */
	public float upwindDistance(float dx, float dz) {
		return -(dx * axisX + dz * axisZ);
	}

	/** Progress through edge i at upwind distance u, from 0 (old weather) to 1 (new weather). */
	public float edgeProgress(int i, float u) {
		float width = edgeWidth[i];
		if (width <= 0)
			return u >= edgeDistance[i] ? 1 : 0;
		return saturate((u - edgeDistance[i] + .5f * width) / width);
	}

	public void copyFrom(WeatherStrip other) {
		for (int i = 0; i < SIZE; i++) {
			System.arraycopy(other.params[i], 0, params[i], 0, WeatherParam.VECTOR_SIZE);
			seeds[i] = other.seeds[i];
			types[i] = other.types[i];
			intensities[i] = other.intensities[i];
			segmentStart[i] = other.segmentStart[i];
			segmentEnd[i] = other.segmentEnd[i];
		}
		System.arraycopy(other.edgeDistance, 0, edgeDistance, 0, SIZE - 1);
		System.arraycopy(other.edgeWidth, 0, edgeWidth, 0, SIZE - 1);
		System.arraycopy(other.edgeShape, 0, edgeShape, 0, SIZE - 1);
		axisX = other.axisX;
		axisZ = other.axisZ;
		valid = other.valid;
	}
}
