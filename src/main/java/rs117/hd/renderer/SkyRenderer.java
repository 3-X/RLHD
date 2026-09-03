package rs117.hd.renderer;

import java.io.IOException;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.SkyShaderProgram;
import rs117.hd.opengl.shader.StarShaderProgram;
import rs117.hd.opengl.uniforms.UBOGlobal;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.DaylightCycleManager;
import rs117.hd.scene.daylight_cycle.SkyLighting;
import rs117.hd.scene.daylight_cycle.StarField;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.utils.MathUtils.*;

@Singleton
public class SkyRenderer {
	private static final float[] BLACK = { 0, 0, 0 };

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private DaylightCycleManager daylightCycleManager;

	@Inject
	private SkyLighting skyLighting;

	@Inject
	private StarField starField;

	@Inject
	private SkyShaderProgram skyProgram;

	@Inject
	private StarShaderProgram starProgram;

	private final CommandBuffer commandBuffer = new CommandBuffer("Sky");
	private final RenderState localRenderState = new RenderState();
	private boolean shouldRenderSky;

	public void initialize() {
		commandBuffer.setFrameTimer(frameTimer);
		commandBuffer.reset();
		starField.initialize();
	}

	public void destroy() {
		starField.destroy();
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		skyProgram.compile(includes);
		starField.initializeShaders(includes);
		starProgram.compile(includes);
		starField.resetStarfield();
	}

	public void destroyShaders() {
		skyProgram.destroy();
		starField.destroyShaders();
		starProgram.destroy();
	}

	public void processConfigChanges(Set<String> keys) {
		if (keys.contains(KEY_NEBULAS))
			starField.resetStarfield();
		if (keys.contains(KEY_STARS))
			commandBuffer.reset();
	}

	public void update(UBOGlobal uboGlobal) {
		shouldRenderSky = daylightCycleManager.isCycleActive();
		skyLighting.update(uboGlobal, shouldRenderSky);
		if (shouldRenderSky)
			updateCommandBuffer();
	}

	public boolean shouldRender() {
		return shouldRenderSky && skyProgram.isValid();
	}

	private boolean canRenderSky(boolean hasVanillaSkybox) {
		return shouldRender() && !plugin.orthographicProjection && !hasVanillaSkybox;
	}

	public void clear(boolean hasVanillaSkybox) {
		frameTimer.begin(Timer.CLEAR_SCENE);

		glClearDepth(0);

		if (canRenderSky(hasVanillaSkybox)) {
			glClear(GL_DEPTH_BUFFER_BIT);
		} else {
			float[] fogColor = hasVanillaSkybox ? BLACK : skyLighting.getFogColorSrgb();
			float[] gammaCorrectedFogColor = pow(fogColor, plugin.getGammaCorrection());
			glClearColor(
				gammaCorrectedFogColor[0],
				gammaCorrectedFogColor[1],
				gammaCorrectedFogColor[2],
				1f
			);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		}

		frameTimer.end(Timer.CLEAR_SCENE);
	}

	public void renderTo(CommandBuffer target) {
		target.ExecuteSubCommandBuffer(commandBuffer);
	}

	public void render() {
		clear(false);
		if (canRenderSky(false))
			commandBuffer.execute(localRenderState);
	}

	private void updateCommandBuffer() {
		boolean starfieldChanged = starField.update();
		if (!starfieldChanged && !commandBuffer.isEmpty())
			return;

		commandBuffer.reset();
		commandBuffer.PushTimer(Timer.RENDER_SKY);
		commandBuffer.SetShader(skyProgram);
		commandBuffer.DepthMask(false);
		commandBuffer.BindVertexArray(plugin.vaoTri);
		commandBuffer.DrawArrays(GL_TRIANGLES, 0, 3);

		if (config.enableStarMap() && starProgram.isValid() && starField.getVaoStars() != 0) {
			commandBuffer.SetShader(starProgram);
			commandBuffer.Enable(GL_PROGRAM_POINT_SIZE);
			if (!GL_CAPS.forwardCompatible)
				commandBuffer.Enable(GL20.GL_POINT_SPRITE);
			commandBuffer.Enable(GL_BLEND);
			commandBuffer.BlendFunc(GL_ONE, GL_ONE, GL_ONE, GL_ONE);
			commandBuffer.BindVertexArray(starField.getVaoStars());
			commandBuffer.DrawArrays(GL_POINTS, 0, starField.starCount);
			commandBuffer.BlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
			commandBuffer.Disable(GL_BLEND);
			if (!GL_CAPS.forwardCompatible)
				commandBuffer.Disable(GL20.GL_POINT_SPRITE);
			commandBuffer.Disable(GL_PROGRAM_POINT_SIZE);
		}

		commandBuffer.DepthMask(true);
		commandBuffer.PopTimer(Timer.RENDER_SKY);
	}
}
