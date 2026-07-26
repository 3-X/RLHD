package rs117.hd.scene;

import java.awt.Color;
import java.nio.FloatBuffer;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE_CUBE_MAP;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_RGBA16F;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_NEBULA;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.utils.HDUtils.randomPointOnSphere;

/**
 * Generates a fixed list of stars once at startup so they can be drawn as point
 * sprites (cost scales with star count) rather than searched for per sky pixel
 * (cost scales with screen pixels). Mirrors the two-layer look the procedural
 * starfield used: a sparse layer of bright, larger stars over a dense layer of
 * dim, small ones, with a power-law brightness distribution and stellar tints.
 * A third layer thickens the star density inside the nebula clouds, sampled from
 * the same field the nebula itself is baked from (see {@link NebulaField}).
 */
@Slf4j
@Singleton
public final class StarField {
	private static final Color[] STAR_COLORS = {
		new Color(1.0f, 0.7f, 0.45f),  // warm orange
		new Color(1.0f, 0.9f, 0.65f),  // golden yellow
		new Color(1.0f, 0.95f, 0.85f), // pale warm white
		new Color(1.0f, 1.0f, 1.0f),   // neutral white
		new Color(0.85f, 0.92f, 1.0f), // pale blue-white
		new Color(0.70f, 0.80f, 1.0f)  // cool blue
	};

	// Standard OpenGL cubemap face orientation: {forward, right, up} per face,
	// where dir = normalize(forward + u*right + v*up) for u,v in [-1, 1].
	private static final float[][][] NEBULA_CUBE_FACES = {
		{ { 1, 0, 0 }, { 0, 0, -1 }, { 0, -1, 0 } }, // +X
		{ { -1, 0, 0 }, { 0, 0, 1 }, { 0, -1, 0 } }, // -X
		{ { 0, 1, 0 }, { 1, 0, 0 }, { 0, 0, 1 } },   // +Y
		{ { 0, -1, 0 }, { 1, 0, 0 }, { 0, 0, -1 } }, // -Y
		{ { 0, 0, 1 }, { 1, 0, 0 }, { 0, -1, 0 } },  // +Z
		{ { 0, 0, -1 }, { -1, 0, 0 }, { 0, -1, 0 } } // -Z
	};

	// Per-vertex layout written to the VBO, in floats:
	//   position.xyz (unit direction), size, brightness, color.rgb, speed => 9 floats
	// speed scales the celestial rotation so the layers parallax (depth effect).
	private static final int FLOATS_PER_STAR = 10;

	// Star counts per layer. The procedural field had ~18-24% of cells populated
	// across grids of scale 80 and 200; these counts reproduce a similar on-sky
	// density without being tied to screen resolution.
	private static final int BRIGHT_STAR_COUNT = 350;   // layer 0: sparse/bright/large
	private static final int DIM_STAR_COUNT = 2200;     // layer 1: dense/dim/small
	private static final int CLUSTER_STAR_COUNT = 1600; // layer 2: clustered
	private static final int MAX_STAR_COUNT = BRIGHT_STAR_COUNT + DIM_STAR_COUNT + CLUSTER_STAR_COUNT;

	// Exponent applied to the normalized nebula density when it is used as a star
	// acceptance probability. It does NOT change how many stars are placed (the
	// sampler always fills CLUSTER_STAR_COUNT) — only where they land. Higher
	// values pack stars into the brightest cores; lower values spread the same
	// stars across the cloud's full extent, including its wispy fringes.
	//
	// It can't go too low: the field is faintly nonzero over a large share of the
	// sky, most of it far too dim to see, so sampling proportional to raw density
	// (exponent 1) leaks stars into that invisible tail and the clustering starts
	// to wash out. Measured at CLUSTER_STAR_COUNT stars, per exponent (the split
	// is essentially count-independent — it is a property of the density field):
	//
	//   exp   stars in bright cloud   stars in dim tail   clumping vs uniform
	//   1.00         60%                    12%                 1.49x
	//   1.25         68%                     8%                 1.60x
	//   1.50         73%                     6%                 1.69x
	//   1.75         78%                     4%                 1.79x
	//   2.50         87%                     2%                 1.95x
	//
	// 1.50 favours tracing the nebula's SHAPE (its outline and wisps) over merely
	// tracking its brightest spots, which is what makes the layer read as "denser
	// stars where the nebula is" rather than as discrete clumps. Going below ~1.25
	// starts to visibly dissolve the clustering as the dim-tail share climbs.
	private static final float CLUSTER_DENSITY_CONTRAST = 1.5f;

	// Rejection sampling budget per requested cluster star. Real cost is ~an order
	// of magnitude below this; the wide margin means retuning the nebula (or the
	// contrast exponent) can only thin the cluster layer, never stall startup.
	private static final int MAX_SAMPLE_ATTEMPTS_PER_STAR = 200;

	private static final int NEBULA_CUBE_MAP_RESOLUTION = 512;

	private static final long SEED = 0x117D511A5L;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ZoneRenderer zoneRenderer;

	private final Random random = new Random(SEED);

	private int texNebulaCubemap = 0;
	private int fboNebulaBake = 0;

	@Getter
	private int vaoStars = 0;

	@Getter
	public int starCount;

	private GLBuffer vboStars;

	private boolean starfieldGenerated = false;

	public void initialize() {
		starfieldGenerated = false;

		vaoStars = glGenVertexArrays();
		glBindVertexArray(vaoStars);

		vboStars = new GLBuffer("Stars::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		vboStars.initialize(FLOATS_PER_STAR * MAX_STAR_COUNT);
		vboStars.bind();

		int stride = FLOATS_PER_STAR * Float.BYTES;
		// location 0: dir.xyz, 1: size, 2: brightness, 3: color.rgba, 4: speed
		glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 3L * Float.BYTES);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 4L * Float.BYTES);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 5L * Float.BYTES);
		glEnableVertexAttribArray(3);
		glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 9L * Float.BYTES);
		glEnableVertexAttribArray(4);

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		fboNebulaBake = glGenFramebuffers();
		texNebulaCubemap = glGenTextures();

		glActiveTexture(TEXTURE_UNIT_NEBULA);
		glBindTexture(GL_TEXTURE_CUBE_MAP, texNebulaCubemap);

		for (int face = 0; face < 6; face++)
			glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL_RGBA16F,
				NEBULA_CUBE_MAP_RESOLUTION,
				NEBULA_CUBE_MAP_RESOLUTION, 0, GL_RGBA, GL_FLOAT, 0);

		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
	}

	public void resetStarfield() { starfieldGenerated = false; }

	public boolean generateStarField() {
		if(starfieldGenerated)
			return false;

		starfieldGenerated = true;
		random.setSeed(SEED);

		FloatBuffer vertexData = BufferUtils.createFloatBuffer(MAX_STAR_COUNT * FLOATS_PER_STAR);

		// Layer 0: bright, sparse, larger, full rotation speed (the "near" layer).
		// Layer 1: dim, dense, smaller, rotating ~30% slower for a parallax depth feel.
		// Layer 2: stars sampled from the nebula density field, so they read as a
		// higher star density inside the clouds. Same speed as the bright layer so
		// they don't visibly drift away from the nebula they were placed in.
		generateLayer(vertexData, BRIGHT_STAR_COUNT, 1.2f, 1.0f, 1.0f);
		generateLayer(vertexData, DIM_STAR_COUNT, 0.4f, 0.8f, 0.7f);

		if(config.enableNebulas())
			generateNebulaClusteredLayer(vertexData, CLUSTER_STAR_COUNT, 0.5f, 0.5f, 1.0f);

		starCount = vertexData.position() / FLOATS_PER_STAR;
		vboStars.upload(vertexData.flip());

		var bakeShader = zoneRenderer.nebulaBakeProgram;
		if (fboNebulaBake == 0 || texNebulaCubemap == 0 || !bakeShader.isValid() || !config.enableNebulas())
			return true;

		final RenderState renderState = zoneRenderer.renderState;
		renderState.framebuffer.set(GL_FRAMEBUFFER, fboNebulaBake);
		renderState.viewport.set(0, 0, NEBULA_CUBE_MAP_RESOLUTION, NEBULA_CUBE_MAP_RESOLUTION);
		renderState.vao.setVao(plugin.vaoTri);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_CULL_FACE);
		renderState.apply();

		plugin.uboSkybox.upload();

		zoneRenderer.nebulaBakeProgram.use();
		for (int face = 0; face < 6; face++) {
			bakeShader.uniFaceForward.set(NEBULA_CUBE_FACES[face][0]);
			bakeShader.uniFaceRight.set(NEBULA_CUBE_FACES[face][1]);
			bakeShader.uniFaceUp.set(NEBULA_CUBE_FACES[face][2]);

			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, texNebulaCubemap, 0);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		zoneRenderer.renderState.reset();
		return true;
	}

	public void destroy() {
		if (vboStars != null)
			vboStars.destroy();
		vboStars = null;

		if (vaoStars != 0)
			glDeleteVertexArrays(vaoStars);
		vaoStars = 0;

		if (fboNebulaBake != 0)
			glDeleteFramebuffers(fboNebulaBake);
		fboNebulaBake = 0;

		if (texNebulaCubemap != 0)
			glDeleteTextures(texNebulaCubemap);
		texNebulaCubemap = 0;

		starfieldGenerated = false;
	}

	private void generateLayer(FloatBuffer vertexBuffer, int count, float maxBrightness, float sizeScale, float speed) {
		final float[] center = new float[3];
		for (int i = 0; i < count; i++) {
			randomPointOnSphere(random, center);
			writeStar(vertexBuffer, center[0], center[1], center[2], maxBrightness, sizeScale, speed, 1.0f);
		}
	}

	/**
	 * Places the clustered star layer by rejection-sampling the nebula density
	 * field, so these stars land inside the visible clouds and read as a higher
	 * star density within them rather than as discrete clusters.
	 * <p>
	 * This previously scattered stars into a dozen Gaussian blobs at random sphere
	 * points and then biased the nebula toward those blobs. That coupling ran the
	 * wrong way: the blobs were round and fixed-radius, so they could never take
	 * the nebula's filamentary shape. Now the nebula field is the single source of
	 * truth and the stars follow it, so the clustering inherits the clouds' wispy
	 * outline for free.
	 * <p>
	 * Acceptance probability is the normalized density raised to
	 * {@link #CLUSTER_DENSITY_CONTRAST}, concentrating stars toward the bright
	 * cores of the filaments instead of spreading them evenly across everywhere
	 * the nebula is faintly nonzero.
	 */
	private void generateNebulaClusteredLayer(
		FloatBuffer vertexBuffer, int count, float maxBrightness, float sizeScale, float speed
	) {
		final float[] dir = new float[3];

		int placed = 0;
		int attempts = 0;
		// Most uniform sphere samples land outside the nebula and are rejected, so
		// cap total attempts: an unusually sparse field (or a future retune that
		// shrinks the nebula) then degrades to fewer cluster stars rather than
		// stalling startup.
		final int maxAttempts = count * MAX_SAMPLE_ATTEMPTS_PER_STAR;

		while (placed < count && attempts < maxAttempts) {
			attempts++;

			randomPointOnSphere(random, dir);

			float density = NebulaField.density(dir[0], dir[1], dir[2]);
			if (density <= 0)
				continue;

			float p = density / NebulaField.MAX_EXPECTED_DENSITY;
			if (p > 1)
				p = 1;
			p = (float) Math.pow(p, CLUSTER_DENSITY_CONTRAST);

			if (random.nextFloat() >= p)
				continue;

			writeStar(vertexBuffer, dir[0], dir[1], dir[2], maxBrightness, sizeScale, speed, 0.5f);
			placed++;
		}

		if (placed < count)
			log.debug("Nebula star clusters: placed {} of {} stars in {} attempts", placed, count, attempts);
	}

	private void writeStar(FloatBuffer vertexBuffer, float dx, float dy, float dz, float maxBrightness, float sizeScale, float speed, float alpha) {
		// Power-law brightness: many dim, few bright (matches pow(seed, 2.5)).
		float brightnessSeed = random.nextFloat();
		float brightness = (float) Math.pow(brightnessSeed, 2.5) * maxBrightness;

		// Per-star size variation, skewed toward the small end (squaring the
		// random factor biases most stars small with only a few larger ones),
		// scaled per layer. Range ~0.4x to 1.0x.
		float sizeSeed = random.nextFloat();
		float size = (0.4f + sizeSeed * sizeSeed * 0.6f) * sizeScale;

		// Stellar color tint by population fraction (same bands as the shader).
		final Color starColor = STAR_COLORS[random.nextInt(STAR_COLORS.length)];

		vertexBuffer
			.put(dx)
			.put(dy)
			.put(dz)
			.put(size)
			.put(brightness)
			.put(starColor.getRed() / 255.0f)
			.put(starColor.getGreen() / 255.0f)
			.put(starColor.getBlue() / 255.0f)
			.put(alpha)
			.put(speed);
	}
}