package rs117.hd.scene;

/**
 * CPU-side evaluation of the same nebula density field that
 * {@code utils/starfield.glsl} bakes into the nebula cubemap.
 * <p>
 * The clustered star layer is placed by rejection-sampling this field, so dense
 * star regions coincide with the visible clouds and simply read as "more stars
 * where the nebula is" rather than as discrete round clusters. That only works
 * if this evaluates to the SAME values as the shader, so everything here is a
 * literal port: the hash reproduces GLSL's {@code floatBitsToUint} plus wrapping
 * uint arithmetic exactly (Java int overflow wraps identically; only the final
 * conversion to float has to treat the bits as unsigned), and the fBm octave
 * counts, frequencies, offsets and smoothstep edges are copied verbatim from
 * {@code proceduralNebula()}.
 * <p>
 * IMPORTANT: if the nebula math in {@code proceduralNebula()} changes, the
 * constants here must change with it, or the stars will drift out of alignment
 * with the clouds. {@link #density} mirrors that function up to (but not
 * including) the color tinting, which does not affect placement.
 */
final class NebulaField {
	private NebulaField() {}

	/** Port of GLSL {@code sf_hash}: single value in [0,1) from a 3D coordinate. */
	private static float hash(float px, float py, float pz) {
		int x = Float.floatToRawIntBits(px);
		int y = Float.floatToRawIntBits(py);
		int z = Float.floatToRawIntBits(pz);

		// Java int arithmetic wraps exactly like GLSL uint arithmetic; only the
		// shifts must be logical (>>>) and the final widening unsigned.
		int h = x * 1664525 + y * 1013904223 + z;
		h ^= h >>> 16;
		h *= 0x7feb352d;
		h ^= h >>> 15;
		h *= 0x846ca68b;
		h ^= h >>> 16;

		return (float) ((h & 0xFFFFFFFFL) * (1.0 / 4294967296.0));
	}

	/** Port of GLSL {@code sf_noise}: quintic-interpolated 3D value noise. */
	private static float noise(float px, float py, float pz) {
		float ix = (float) Math.floor(px);
		float iy = (float) Math.floor(py);
		float iz = (float) Math.floor(pz);

		float fx = px - ix;
		float fy = py - iy;
		float fz = pz - iz;

		// Quintic interpolant (6t^5 - 15t^4 + 10t^3).
		fx = fx * fx * fx * (fx * (fx * 6.0f - 15.0f) + 10.0f);
		fy = fy * fy * fy * (fy * (fy * 6.0f - 15.0f) + 10.0f);
		fz = fz * fz * fz * (fz * (fz * 6.0f - 15.0f) + 10.0f);

		float c000 = hash(ix, iy, iz);
		float c100 = hash(ix + 1, iy, iz);
		float c010 = hash(ix, iy + 1, iz);
		float c110 = hash(ix + 1, iy + 1, iz);
		float c001 = hash(ix, iy, iz + 1);
		float c101 = hash(ix + 1, iy, iz + 1);
		float c011 = hash(ix, iy + 1, iz + 1);
		float c111 = hash(ix + 1, iy + 1, iz + 1);

		float x00 = c000 + (c100 - c000) * fx;
		float x10 = c010 + (c110 - c010) * fx;
		float x01 = c001 + (c101 - c001) * fx;
		float x11 = c011 + (c111 - c011) * fx;

		float y0 = x00 + (x10 - x00) * fy;
		float y1 = x01 + (x11 - x01) * fy;

		return y0 + (y1 - y0) * fz;
	}

	/** Port of GLSL {@code sf_fbm}. */
	private static float fbm(float px, float py, float pz, int octaves) {
		float sum = 0;
		float amp = 0.5f;
		float norm = 0;
		for (int o = 0; o < octaves; o++) {
			sum += amp * noise(px, py, pz);
			norm += amp;
			px *= 2.02f;
			py *= 2.02f;
			pz *= 2.02f;
			amp *= 0.5f;
		}
		return sum / norm;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = (x - edge0) / (edge1 - edge0);
		t = t < 0 ? 0 : (t > 1 ? 1 : t);
		return t * t * (3.0f - 2.0f * t);
	}

	/**
	 * Scalar nebula density along {@code dir} (expected to be unit length),
	 * matching {@code proceduralNebula()}'s {@code nebulaIntensity} before color
	 * tinting. Zero wherever the shader early-outs, so sampling against it can
	 * never place a star on empty sky. Not normalized to any fixed range; callers
	 * treat it as a relative weight (see {@link #MAX_EXPECTED_DENSITY}).
	 */
	static float density(float dx, float dy, float dz) {
		// Domain warp (frequency 2.0, 1 octave).
		float wx = dx + (fbm(dx * 2.0f + 11.3f, dy * 2.0f + 11.3f, dz * 2.0f + 11.3f, 1) - 0.5f) * 0.9f;
		float wy = dy + (fbm(dx * 2.0f + 47.1f, dy * 2.0f + 47.1f, dz * 2.0f + 47.1f, 1) - 0.5f) * 0.9f;
		float wz = dz + (fbm(dx * 2.0f + 83.7f, dy * 2.0f + 83.7f, dz * 2.0f + 83.7f, 1) - 0.5f) * 0.9f;

		// Broad cloud regions.
		float region = fbm(wx * 2.5f + 50.0f, wy * 2.5f + 50.0f, wz * 2.5f + 50.0f, 3);
		region = smoothstep(0.45f, 0.78f, region);
		if (region <= 0)
			return 0;

		// Finer filamentary structure inside the regions.
		float wisps = fbm(wx * 9.0f + 100.0f, wy * 9.0f + 100.0f, wz * 9.0f + 100.0f, 3);
		wisps = smoothstep(0.35f, 0.75f, wisps);

		// High-frequency texture for graininess near the bright cores.
		float detail = fbm(wx * 28.0f + 200.0f, wy * 28.0f + 200.0f, wz * 28.0f + 200.0f, 2);

		return region * (0.55f + 0.45f * wisps) * (0.6f + 0.4f * detail) * 1.9f;
	}

	/**
	 * Practical upper bound on {@link #density}, used to normalize it into a [0,1]
	 * acceptance probability. The analytic maximum is 1.9 (region, wisps and detail
	 * all at 1), but all three peaking together is vanishingly rare, so dividing by
	 * the analytic max would reject nearly everything. This is the density that
	 * reads as "solidly inside a bright part of the cloud"; at or above it, stars
	 * are accepted outright.
	 */
	static final float MAX_EXPECTED_DENSITY = 1.0f;
}
