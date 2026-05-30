/*
 * Copyright (c) 2019 Abex
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.scene;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.entityhider.EntityHiderConfig;
import net.runelite.client.plugins.entityhider.EntityHiderPlugin;
import rs117.hd.HdPlugin;
import rs117.hd.config.DynamicLights;
import rs117.hd.data.ObjectType;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.scene.lights.Alignment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.scene.lights.LightType;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.HDUtils.isSphereIntersectingFrustum;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class LightManager {
	private static final ResourcePath LIGHTS_PATH = Props
		.getFile("rlhd.lights-path", () -> path(LightManager.class, "lights.json"));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private EntityHiderPlugin entityHiderPlugin;

	private final ArrayList<Light> WORLD_LIGHTS = new ArrayList<>();
	private final ListMultimap<Integer, LightDefinition> NPC_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> OBJECT_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> PROJECTILE_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> GRAPHICS_OBJECT_LIGHTS = ArrayListMultimap.create();

	private final Renderable[] imposterRenderables = new Renderable[2];
	private boolean reloadLights;
	private EntityHiderConfig entityHiderConfig;
	private int currentPlane;

	public void loadConfig(Gson gson, ResourcePath path) {
		LightDefinition[] lights;
		try {
			lights = path.loadJson(gson, LightDefinition[].class);
			if (lights == null) {
				log.warn("Skipping empty lights.json");
				return;
			}
		} catch (IOException ex) {
			log.error("Failed to load lights", ex);
			return;
		}

		clientThread.invoke(() -> {
			WORLD_LIGHTS.clear();
			NPC_LIGHTS.clear();
			OBJECT_LIGHTS.clear();
			PROJECTILE_LIGHTS.clear();
			GRAPHICS_OBJECT_LIGHTS.clear();

			for (LightDefinition lightDef : lights) {
				lightDef.normalize();
				if (lightDef.worldX != null && lightDef.worldY != null) {
					Light light = new Light(lightDef);
					light.worldPoint = new WorldPoint(lightDef.worldX, lightDef.worldY, lightDef.plane);
					light.persistent = true;
					WORLD_LIGHTS.add(light);
				}
				lightDef.npcIds.forEach(id -> NPC_LIGHTS.put(id, lightDef));
				lightDef.objectIds.forEach(id -> OBJECT_LIGHTS.put(id, lightDef));
				lightDef.projectileIds.forEach(id -> PROJECTILE_LIGHTS.put(id, lightDef));
				lightDef.graphicsObjectIds.forEach(id -> GRAPHICS_OBJECT_LIGHTS.put(id, lightDef));
			}

			log.debug("Loaded {} lights", lights.length);

			// Reload lights once on plugin startup, and whenever lights.json should be hot-swapped.
			// If we don't reload on startup, NPCs won't have lights added until RuneLite fires events
			reloadLights = true;
		});
	}

	public void startUp() {
		entityHiderConfig = configManager.getConfig(EntityHiderConfig.class);
		LIGHTS_PATH.watch(path -> loadConfig(plugin.getGson(), path));
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
		clearEmissiveLightState();
	}

	public void update(@Nonnull SceneContext sceneContext, int[] cameraShift, float[][] cameraFrustum) {
		assert client.isClientThread();

		if (plugin.configDynamicLights == DynamicLights.NONE || client.getGameState() != GameState.LOGGED_IN) {
			sceneContext.numVisibleLights = 0;
			return;
		}

		if (reloadLights) {
			reloadLights = false;
			sceneContext.lights.clear();
			sceneContext.knownProjectiles.clear();
			// The light list was just cleared, so drop emissive bookkeeping that referenced it
			clearEmissiveLightState();
			loadSceneLights(sceneContext);
			swapSceneLights(sceneContext, null);

			client.getNpcs().forEach(npc -> {
				addNpcLights(npc);
				addSpotanimLights(npc);
			});
		}

		// These should never occur, but just in case...
		if (sceneContext.knownProjectiles.size() > 10000) {
			log.warn("Too many projectiles tracked: {}. Clearing...", sceneContext.knownProjectiles.size());
			sceneContext.knownProjectiles.clear();
		}
		if (sceneContext.lights.size() > 10000) {
			log.warn("Too many lights: {}. Clearing...", sceneContext.lights.size());
			sceneContext.lights.clear();
		}

		// Spawn/refresh/remove lights driven by emissive materials on players' worn equipment
		updateEmissiveEquipmentLights(sceneContext);

		int drawDistance = plugin.getDrawDistance() * LOCAL_TILE_SIZE;
		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		int[][][] tileHeights = sceneContext.scene.getTileHeights();
		var cachedNpcs = client.getTopLevelWorldView().npcs();
		var cachedPlayers = client.getTopLevelWorldView().players();
		int gameCycle = client.getGameCycle();
		final int plane = client.getPlane();
		boolean changedPlanes = false;

		if (plane != currentPlane) {
			currentPlane = plane;
			changedPlanes = true;
		}

		for (Light light : sceneContext.lights) {
			// Ways lights may get deleted:
			// - animation-specific:
			//   effectively spawn when the animation they're attached to starts playing, and despawns when it stops,
			//   but they are typically replayable, so they don't fully despawn until marked for removal by something else
			// - spotanim & projectile lights:
			//   automatically marked for removal upon completion
			// - actor lights:
			//   may be automatically marked for removal if the actor becomes invalid
			// - other lights:
			//   despawn when marked for removal by a RuneLite despawn event
			// - fixed lifetime && !replayable:
			//   All non-replayable lights with a fixed lifetime will be automatically marked for removal when done playing

			// Light fade-in and fade-out are based on whether the parent currently exists
			// Additionally, lights have an overruling fade-out when being deprioritized

			// Whatever the light is attached to is presumed to exist if it's not marked for removal yet
			boolean parentExists = !light.markedForRemoval;
			boolean hiddenTemporarily = light.hiddenTemporarily;

			if (light.tileObject != null) {
				if (!light.markedForRemoval && light.animationSpecific && light.tileObject instanceof GameObject) {
					int animationId = -1;
					var renderable = ((GameObject) light.tileObject).getRenderable();
					if (renderable instanceof DynamicObject) {
						var anim = ((DynamicObject) renderable).getAnimation();
						if (anim != null)
							animationId = anim.getId();
					}
					parentExists = light.def.animationIds.contains(animationId);
				}
			} else if (light.projectile != null) {
				light.origin[0] = (int) light.projectile.getX();
				light.origin[1] = (int) light.projectile.getZ() - light.def.height;
				light.origin[2] = (int) light.projectile.getY();
				hiddenTemporarily = !shouldShowProjectileLights();
				if (light.projectile.getRemainingCycles() <= 0) {
					light.markedForRemoval = true;
				} else {
					if (light.animationSpecific) {
						if (light.def.waitForAnimation && gameCycle < light.projectile.getStartCycle()) {
							parentExists = false;
						} else if (!light.def.animationIds.isEmpty()) {
							var animation = light.projectile.getAnimation();
							parentExists = animation != null && light.def.animationIds.contains(animation.getId());
						}
					}
					light.orientation = light.projectile.getOrientation();
				}
			} else if (light.graphicsObject != null) {
				light.origin[0] = light.graphicsObject.getLocation().getX();
				light.origin[1] = light.graphicsObject.getZ() - light.def.height;
				light.origin[2] = light.graphicsObject.getLocation().getY();
				if (light.graphicsObject.finished()) {
					light.markedForRemoval = true;
				} else if (light.animationSpecific) {
					if (light.def.waitForAnimation && gameCycle < light.graphicsObject.getStartCycle()) {
						parentExists = false;
					} else if (!light.def.animationIds.isEmpty()) {
						var animation = light.graphicsObject.getAnimation();
						parentExists = animation != null && light.def.animationIds.contains(animation.getId());
					}
				}
			} else if (light.actor != null && !light.markedForRemoval) {
				if (light.actor instanceof NPC && light.actor != cachedNpcs.byIndex(((NPC) light.actor).getIndex()) ||
					light.actor instanceof Player && light.actor != cachedPlayers.byIndex(((Player) light.actor).getId()) ||
					light.spotanimId != -1 && !light.actor.hasSpotAnim(light.spotanimId)
				) {
					parentExists = false;
					light.markedForRemoval = true;
				} else {
					var lp = light.actor.getLocalLocation();
					light.origin[0] = lp.getX();
					light.origin[2] = lp.getY();
					light.plane = plane;
					light.orientation = light.actor.getCurrentOrientation();

					if (light.animationSpecific) {
						if (light.spotanimId != -1) {
							if (light.def.waitForAnimation) {
								parentExists = false;
								for (var spotanim : light.actor.getSpotAnims()) {
									if (spotanim.getId() == light.spotanimId) {
										if (gameCycle >= spotanim.getStartCycle())
											parentExists = true;
										break;
									}
								}
							}
						} else {
							parentExists = light.def.animationIds.contains(light.actor.getAnimation());
						}
					}

					int tileExX = ((int) light.origin[0] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
					int tileExY = ((int) light.origin[2] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;

					// Some NPCs, such as Crystalline Hunllef in The Gauntlet, sometimes return scene X/Y values far outside the possible range.
					Tile tile;
					if (tileExX >= 0 && tileExY >= 0 &&
						tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE &&
						(tile = tiles[plane][tileExX][tileExY]) != null
					) {
						hiddenTemporarily = !isActorLightVisible(light.actor);

						if (!light.def.ignoreActorHiding &&
							!(light.actor instanceof NPC && ((NPC) light.actor).getComposition().getSize() > 1)
						) {
							// Check if the actor is hidden by another actor on the same tile
							for (var gameObject : tile.getGameObjects()) {
								if (gameObject == null || !(gameObject.getRenderable() instanceof Actor))
									continue;

								// Assume only the first actor at the same exact location will be rendered
								if (gameObject.getX() == round(light.origin[0]) &&
									gameObject.getY() == round(light.origin[2]) &&
									gameObject.getRenderable() != light.actor
								) {
									hiddenTemporarily = true;
									break;
								}
							}
						}

						// Interpolate between tile heights based on specific scene coordinates
						int tileZ = plane;
						if (tile.getBridge() != null)
							tileZ++;
						float lerpX = fract(light.origin[0] / (float) LOCAL_TILE_SIZE);
						float lerpY = fract(light.origin[2] / (float) LOCAL_TILE_SIZE);
						float heightNorth = mix(
							tileHeights[tileZ][tileExX][tileExY + 1],
							tileHeights[tileZ][tileExX + 1][tileExY + 1],
							lerpX
						);
						float heightSouth = mix(
							tileHeights[tileZ][tileExX][tileExY],
							tileHeights[tileZ][tileExX + 1][tileExY],
							lerpX
						);
						float tileHeight = mix(heightSouth, heightNorth, lerpY);
						light.origin[1] = (int) tileHeight - 1 - light.def.height;
					}
				}
			}

			light.pos[0] = light.origin[0];
			light.pos[1] = light.origin[1];
			light.pos[2] = light.origin[2];

			int orientation = 0;
			if (light.alignment.relative)
				orientation = mod(light.orientation + light.alignment.orientation, 2048);

			if (light.alignment == Alignment.CUSTOM) {
				// orientation 0 = south
				float sin = sin(orientation * JAU_TO_RAD);
				float cos = cos(orientation * JAU_TO_RAD);
				float x = light.offset[0];
				float z = light.offset[2];
				light.pos[0] += -cos * x - sin * z;
				light.pos[1] += light.offset[1];
				light.pos[2] += -cos * z + sin * x;
			} else {
				int localSizeX = light.sizeX * LOCAL_TILE_SIZE;
				int localSizeY = light.sizeY * LOCAL_TILE_SIZE;

				float radius = localSizeX / 2f;
				if (!light.alignment.radial)
					radius = sqrt(localSizeX * localSizeX + localSizeX * localSizeX) / 2;

				float sine = SINE[orientation] / 65536f;
				float cosine = COSINE[orientation] / 65536f;
				cosine /= (float) localSizeX / (float) localSizeY;

				int offsetX = (int) (radius * sine);
				int offsetY = (int) (radius * cosine);

				light.pos[0] += offsetX;
				light.pos[2] += offsetY;
			}

			// This is a little bit slow, so only update it when necessary
			if (light.prevPlane != light.plane) {
				light.prevPlane = light.plane;
				light.belowFloor = false;
				light.aboveFloor = false;
				int tileExX = ((int) light.pos[0] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
				int tileExY = ((int) light.pos[2] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
				if (light.plane >= 0 && tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE) {
					byte hasTile = sceneContext.filledTiles[tileExX][tileExY];
					if ((hasTile & (1 << light.plane + 1)) != 0)
						light.belowFloor = true;
					if ((hasTile & (1 << light.plane)) != 0)
						light.aboveFloor = true;
				}
			}

			if (!hiddenTemporarily && !light.def.visibleFromOtherPlanes) {
				// Hide certain lights on planes lower than the player to prevent light 'leaking' through the floor
				if (light.plane < plane && light.belowFloor)
					hiddenTemporarily = true;
				// Hide any light that is above the current plane and is above a solid floor
				if (light.plane > plane && light.aboveFloor)
					hiddenTemporarily = true;
			}

			if (parentExists != light.parentExists) {
				light.parentExists = parentExists;
				if (parentExists) {
					// Reset the light if it's replayable and the parent just spawned
					if (light.replayable) {
						light.elapsedTime = 0;
						light.changedVisibilityAt = -1;
						if (light.dynamicLifetime)
							light.lifetime = -1;
					}
				} else if (light.def.despawnWithParent) {
					light.lifetime = 0;
				} else if (light.lifetime == -1) {
					// Schedule despawning of the light if the parent just despawned, and the light isn't already scheduled to despawn
					float minLifetime = light.spawnDelay + light.fadeInDuration;
					light.lifetime = max(minLifetime, light.elapsedTime) + light.despawnDelay;
				}
			}

			if (hiddenTemporarily != light.hiddenTemporarily)
				light.toggleTemporaryVisibility(changedPlanes);

			light.elapsedTime += plugin.deltaClientTime;

			light.visible = light.spawnDelay <= light.elapsedTime && (light.lifetime == -1 || light.elapsedTime < light.lifetime);

			// If the light is temporarily hidden, keep it visible only while fading out
			if (light.visible && light.hiddenTemporarily)
				light.visible = light.changedVisibilityAt != -1 && light.elapsedTime - light.changedVisibilityAt < Light.VISIBILITY_FADE;

			if (light.visible) {
				// Prioritize lights closer to the focal point
				float distX = plugin.cameraFocalPoint[0] - light.pos[0];
				float distZ = plugin.cameraFocalPoint[1] - light.pos[2];
				light.distanceSquared = distX * distX + distZ * distZ;

				float maxRadius = light.def.radius;
				switch (light.def.type) {
					case FLICKER:
						maxRadius *= 1.5f;
						break;
					case PULSE:
						maxRadius *= 1 + light.def.range / 100f;
						break;
				}

				// Hide lights which cannot possibly affect the visible scene,
				// by either being behind the camera, or too far beyond the edge of the scene
				float near = -maxRadius * maxRadius;
				float far = drawDistance + LOCAL_HALF_TILE_SIZE + maxRadius;
				far *= far;
				light.visible = near < light.distanceSquared && light.distanceSquared < far;

				// Check that the light is within the camera's frustum specifically: left, right, bottom, top
				// The above check already covers the near plane
				if (plugin.configTiledLighting && light.visible) {
					light.visible = isSphereIntersectingFrustum(
						light.pos[0] + cameraShift[0],
						light.pos[1],
						light.pos[2] + cameraShift[1],
						maxRadius, // use max radius, since the radius hasn't been updated yet
						cameraFrustum,
						4
					);
				}
			}
		}

		// Order visible lights first, then by distance. Leave hidden lights unordered at the end.
		sceneContext.lights.sort((a, b) -> a.visible && b.visible ?
			Float.compare(a.distanceSquared, b.distanceSquared) :
			Boolean.compare(b.visible, a.visible));

		// Count number of visible lights
		sceneContext.numVisibleLights = 0;
		int maxLights = plugin.configTiledLighting ? UBOLights.MAX_LIGHTS : plugin.configDynamicLights.getMaxSceneLights();
		for (Light light : sceneContext.lights) {
			// Exit early once encountering the first invisible light, or the light limit is reached
			if (!light.visible || sceneContext.numVisibleLights >= maxLights)
				break;

			sceneContext.numVisibleLights++;

			// If the light was temporarily hidden, begin fading in
			if (!light.withinViewingDistance && light.hiddenTemporarily)
				light.toggleTemporaryVisibility(changedPlanes);
			light.withinViewingDistance = true;

			if (light.def.type == LightType.FLICKER) {
				float t = TWO_PI * (mod(plugin.elapsedTime, 60) / 60 + light.randomOffset);
				float flicker = (
					pow(cos(11 * t), 3) +
					pow(cos(17 * t), 6) +
					pow(cos(23 * t), 2) +
					pow(cos(31 * t), 6) +
					pow(cos(71 * t), 4) +
					pow(cos(151 * t), 6) / 2
				) / 4.335f;

				float maxFlicker = 1f + (light.def.range / 100f);
				float minFlicker = 1f - (light.def.range / 100f);

				flicker = minFlicker + (maxFlicker - minFlicker) * flicker;

				light.strength = light.def.strength * flicker;
				light.radius = (int) (light.def.radius * 1.5f);
			} else if (light.def.type == LightType.PULSE) {
				light.animation = fract(light.animation + plugin.deltaClientTime / light.duration);
				float output = 1 - 2 * abs(light.animation - .5f);
				float multiplier = 1 + (2 * output - 1) * light.def.range / 100;
				light.radius = light.def.radius * multiplier;
				light.strength = light.def.strength * multiplier;
			} else {
				light.strength = light.def.strength;
				light.radius = light.def.radius;
				light.color = light.def.color;
			}

			// Spawn & despawn fade-in and fade-out
			if (light.fadeInDuration > 0)
				light.strength *= saturate((light.elapsedTime - light.spawnDelay) / light.fadeInDuration);
			if (light.fadeOutDuration > 0 && light.lifetime != -1)
				light.strength *= saturate((light.lifetime - light.elapsedTime) / light.fadeOutDuration);

			light.applyTemporaryVisibilityFade();
		}

		for (int i = sceneContext.lights.size() - 1; i >= sceneContext.numVisibleLights; i--) {
			Light light = sceneContext.lights.get(i);
			light.withinViewingDistance = false;

			// Automatically despawn non-replayable fixed lifetime lights when they expire
			if (!light.replayable && light.lifetime != -1 && light.lifetime < light.elapsedTime)
				light.markedForRemoval = true;

			if (light.markedForRemoval) {
				sceneContext.lights.remove(i);
				if (light.projectile != null && --light.projectileRefCounter[0] == 0)
					sceneContext.knownProjectiles.remove(light.projectile);
			}
		}
	}

	private boolean isActorLightVisible(@Nonnull Actor actor) {
		try {
			// getModel may throw an exception from vanilla client code
			if (actor.getModel() == null)
				return false;
		} catch (Exception ex) {
			// Vanilla handles exceptions thrown in `DrawCallbacks#draw` gracefully, but here we have to handle them
			return false;
		}

		boolean entityHiderEnabled = pluginManager.isPluginEnabled(entityHiderPlugin);

		if (actor instanceof NPC) {
			if (!plugin.configNpcLights)
				return false;

			if (entityHiderEnabled) {
				var npc = (NPC) actor;
				boolean isPet = npc.getComposition().isFollower();

				if (client.getFollower() != null && client.getFollower().getIndex() == npc.getIndex())
					return true;

				if (entityHiderConfig.hideNPCs() && !isPet)
					return false;

				return !entityHiderConfig.hidePets() || !isPet;
			}
		} else if (actor instanceof Player) {
			if (entityHiderEnabled) {
				var player = (Player) actor;
				Player local = client.getLocalPlayer();
				if (local == null || player.getName() == null)
					return true;

				if (player == local)
					return !entityHiderConfig.hideLocalPlayer();

				if (entityHiderConfig.hideAttackers() && player.getInteracting() == local)
					return false;

				if (player.isFriend())
					return !entityHiderConfig.hideFriends();
				if (player.isFriendsChatMember())
					return !entityHiderConfig.hideFriendsChatMembers();
				if (player.isClanMember())
					return !entityHiderConfig.hideClanChatMembers();
				if (client.getIgnoreContainer().findByName(player.getName()) != null)
					return !entityHiderConfig.hideIgnores();

				return !entityHiderConfig.hideOthers();
			}
		}

		return true;
	}

	private boolean shouldShowProjectileLights() {
		return plugin.configProjectileLights && !(pluginManager.isPluginEnabled(entityHiderPlugin) && entityHiderConfig.hideProjectiles());
	}

	public void loadSceneLights(SceneContext sceneContext) {
		for (Light light : WORLD_LIGHTS) {
			assert light.worldPoint != null;
			if (sceneContext.sceneBounds.contains(light.worldPoint))
				addWorldLight(sceneContext, light);
		}

		for (Tile[][] plane : sceneContext.scene.getExtendedTiles()) {
			for (Tile[] column : plane) {
				for (Tile tile : column) {
					if (tile == null)
						continue;

					DecorativeObject decorativeObject = tile.getDecorativeObject();
					if (decorativeObject != null)
						handleObjectSpawn(sceneContext, decorativeObject);

					WallObject wallObject = tile.getWallObject();
					if (wallObject != null)
						handleObjectSpawn(sceneContext, wallObject);

					GroundObject groundObject = tile.getGroundObject();
					if (groundObject != null && groundObject.getRenderable() != null)
						handleObjectSpawn(sceneContext, groundObject);

					for (GameObject gameObject : tile.getGameObjects()) {
						// Skip nulls, players & NPCs
						if (gameObject == null || gameObject.getRenderable() instanceof Actor)
							continue;

						handleObjectSpawn(sceneContext, gameObject);
					}
				}
			}
		}
	}

	public void swapSceneLights(SceneContext sceneContext, @Nullable SceneContext oldSceneContext) {
		// Force lights to instantly appear when spawning them as part of a new scene
		for (int i = 0; i < sceneContext.lights.size(); i++)
			sceneContext.lights.get(i).fadeInDuration = 0;

		// Set the plane to an unreachable plane, forcing the first `toggleTemporaryVisibility` call to not fade
		currentPlane = -1;

		if (oldSceneContext == null)
			return;

		// Copy over NPC and projectile lights from the old scene, skipping any already scheduled
		// for removal so stale lights (e.g. despawned emissive equipment lights) don't carry over
		ArrayList<Light> lightsToKeep = new ArrayList<>();
		for (Light light : oldSceneContext.lights)
			if ((light.actor != null || light.projectile != null) && !light.markedForRemoval)
				lightsToKeep.add(light);

		sceneContext.lights.addAll(lightsToKeep);
		for (var light : lightsToKeep)
			if (light.projectile != null && oldSceneContext.knownProjectiles.contains(light.projectile))
				sceneContext.knownProjectiles.add(light.projectile);
	}

	private void removeLightIf(Predicate<Light> predicate) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;
		removeLightIf(sceneContext, predicate);
	}

	private void removeLightIf(@Nonnull SceneContext sceneContext, Predicate<Light> predicate) {
		for (var light : sceneContext.lights)
			if (predicate.test(light))
				light.markedForRemoval = true;
	}

	private void addSpotanimLights(Actor actor) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		int[] worldPos = sceneContext.localToWorld(actor.getLocalLocation());

		for (var spotAnim : actor.getSpotAnims()) {
			int spotAnimId = spotAnim.getId();
			for (var def : GRAPHICS_OBJECT_LIGHTS.get(spotAnim.getId())) {
				if (def.areas.length > 0) {
					boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
					if (!isInArea)
						continue;
				}
				if (def.excludeAreas.length > 0) {
					boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
					if (isInArea)
						continue;
				}

				boolean isDuplicate = sceneContext.lights.stream()
					.anyMatch(light ->
						light.spotanimId == spotAnimId &&
						light.actor == actor &&
						light.def == def);
				if (isDuplicate)
					continue;

				Light light = new Light(def);
				light.plane = -1;
				light.spotanimId = spotAnimId;
				light.actor = actor;
				sceneContext.lights.add(light);
			}
		}
	}

	private void addNpcLights(NPC npc)
	{
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		int uuid = ModelHash.packUuid(ModelHash.TYPE_NPC, npc.getId());
		int[] worldPos = sceneContext.localToWorld(npc.getLocalLocation());

		var modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		for (LightDefinition def : NPC_LIGHTS.get(npc.getId())) {
			if (def.areas.length > 0) {
				boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
				if (!isInArea)
					continue;
			}
			if (def.excludeAreas.length > 0) {
				boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
				if (isInArea)
					continue;
			}

			// Prevent duplicate lights from being spawned for the same NPC
			boolean isDuplicate = sceneContext.lights.stream()
				.anyMatch(light ->
					light.actor == npc &&
					light.def == def &&
					!light.markedForRemoval);
			if (isDuplicate)
				continue;

			Light light = new Light(def);
			light.plane = -1;
			light.actor = npc;
			sceneContext.lights.add(light);
		}
	}

	/**
	 * Tracks the active emissive-equipment light per player, for O(1) deduplication instead of
	 * scanning the whole light list each frame. Entries are reconciled every frame and dropped
	 * once a light is removed or its actor changes.
	 */
	private final Map<Player, Light> emissiveLightsByPlayer = new HashMap<>();

	/**
	 * Per-player cache of the last scanned model and its strongest emissive material, so the
	 * expensive face scan only re-runs when a player's model actually changes (e.g. on equipment
	 * change or a new animation frame). {@link #emissiveScanCache} stores the result for the
	 * {@link #emissiveScanModel} that was last scanned for each player.
	 */
	private final Map<Player, Model> emissiveScanModel = new HashMap<>();
	private final Map<Player, EmissiveScan> emissiveScanCache = new HashMap<>();
	/**
	 * The {@code MaterialManager.MATERIALS} array last seen by the scan. When materials hot-reload
	 * this reference changes, so we invalidate the cached scans (which hold stale Material objects).
	 */
	private Material[] lastSeenMaterials;

	/**
	 * Result of scanning a model for the strongest emissive material and the centroid of its
	 * emissive faces, in the model's pre-rotation local space (so it can be rotated by the actor's
	 * orientation and added as a light offset to track the emissive surface as the model animates).
	 */
	private static class EmissiveScan {
		@Nullable
		final Material material;
		final float centroidX;
		final float centroidY;
		final float centroidZ;

		EmissiveScan(@Nullable Material material, float centroidX, float centroidY, float centroidZ) {
			this.material = material;
			this.centroidX = centroidX;
			this.centroidY = centroidY;
			this.centroidZ = centroidZ;
		}
	}

	/** Drops all emissive-light bookkeeping. Call when the scene/light list resets or on shutdown. */
	public void clearEmissiveLightState() {
		emissiveLightsByPlayer.clear();
		emissiveScanModel.clear();
		emissiveScanCache.clear();
		lastSeenMaterials = null;
	}

	/**
	 * Spawns, refreshes and removes dynamic lights driven by emissive materials worn by
	 * players in view (e.g. the fire cape). Runs every frame on the client thread. Each player
	 * with an emissive material on its model gets at most one light, derived from the strongest
	 * emissive material present, positioned at the height of its emissive faces and following
	 * the player via the existing actor-light tracking in {@link #update}.
	 */
	private void updateEmissiveEquipmentLights(@Nonnull SceneContext sceneContext) {
		// If the feature was disabled, remove any lights it previously spawned and reset state
		if (!plugin.configEmissiveEquipmentLights) {
			if (!emissiveLightsByPlayer.isEmpty()) {
				removeLightIf(sceneContext, light -> light.emissiveMaterialName != null);
				clearEmissiveLightState();
			}
			return;
		}

		// MATERIALS may be null momentarily during a material reload
		if (MaterialManager.MATERIALS == null)
			return;

		// If materials were hot-reloaded, the cached scans hold stale Material objects; drop them
		// so they get rescanned (and existing lights refreshed) against the new materials
		if (lastSeenMaterials != MaterialManager.MATERIALS) {
			lastSeenMaterials = MaterialManager.MATERIALS;
			emissiveScanModel.clear();
			emissiveScanCache.clear();
		}

		// Collect the players currently in view, used both to iterate and to prune the caches
		var livePlayers = new HashSet<Player>();
		for (Player player : client.getTopLevelWorldView().players())
			if (player != null)
				livePlayers.add(player);

		// Drop tracked lights whose light is gone, or whose player left view / changed identity.
		// The main update() loop marks departed actors' lights for removal, so we just untrack here.
		emissiveLightsByPlayer.entrySet().removeIf(e -> {
			Light light = e.getValue();
			return light.markedForRemoval || light.actor != e.getKey() || !livePlayers.contains(e.getKey());
		});

		// Prune scan caches for players no longer in view, to keep the maps bounded
		emissiveScanModel.keySet().retainAll(livePlayers);
		emissiveScanCache.keySet().retainAll(livePlayers);

		for (Player player : livePlayers) {
			Light existing = emissiveLightsByPlayer.get(player);

			// Force a fresh scan (ignoring the per-model cache) for players that already have an
			// emissive light, so the centroid — and thus the light position — always tracks the
			// live, animated cape geometry. The cache only spares re-scanning non-emissive players.
			boolean forceRescan = existing != null;
			EmissiveScan scan = isActorLightVisible(player) ? scanPlayerEmissive(player, forceRescan) : null;
			Material emissive = scan == null ? null : scan.material;

			if (emissive == null) {
				// No emissive material this frame; remove any existing light. Keep the scan cache
				// (keyed by model identity) so this player isn't rescanned until their model changes.
				if (existing != null) {
					existing.markedForRemoval = true;
					emissiveLightsByPlayer.remove(player);
				}
				continue;
			}

			if (existing != null && !emissive.name.equals(existing.emissiveMaterialName)) {
				// The emissive material changed (e.g. swapped capes); replace the light
				existing.markedForRemoval = true;
				emissiveLightsByPlayer.remove(player);
				existing = null;
			}

			if (existing == null) {
				LightDefinition def = createEmissiveLightDef(emissive);
				existing = new Light(def);
				existing.plane = -1;
				existing.actor = player;
				existing.emissiveMaterialName = emissive.name;
				sceneContext.lights.add(existing);
				emissiveLightsByPlayer.put(player, existing);
			}

			// Refresh the light each frame so the offset tracks the (animated) emissive surface,
			// e.g. a swinging cape during an emote, and color/strength/radius track material reloads.
			refreshEmissiveLight(existing, emissive, scan);
		}
	}

	/**
	 * Returns the strongest emissive material on the player's current model and the centroid of
	 * its emissive faces, or null if none. The result is cached per player and only recomputed
	 * when the player's {@link Model} instance changes (e.g. each animation frame of an emote),
	 * since the face scan is the dominant cost of this feature.
	 */
	@Nullable
	private EmissiveScan scanPlayerEmissive(@Nonnull Player player, boolean forceRescan) {
		Model model;
		try {
			// getModel may throw from vanilla client code
			model = player.getModel();
		} catch (Exception ex) {
			model = null;
		}
		if (model == null) {
			emissiveScanModel.remove(player);
			emissiveScanCache.remove(player);
			return null;
		}

		// Reuse the cached scan only for players we don't already light. RuneLite reuses the same
		// Model instance for an animating actor and mutates its vertices in place, so model identity
		// does NOT change between animation frames; relying on it alone would freeze the centroid
		// (and the light) while a cape swings. forceRescan keeps known-emissive players' lights live.
		if (!forceRescan && emissiveScanModel.get(player) == model)
			return emissiveScanCache.get(player);

		EmissiveScan scan = findStrongestEmissiveMaterial(model);
		emissiveScanModel.put(player, model);
		emissiveScanCache.put(player, scan);
		return scan;
	}

	/**
	 * Resolves each textured face of the given model to its {@link Material} (mirroring the
	 * resolution used during model pushing) and returns the emissive material with the highest
	 * {@link Material#emissiveStrength}, along with the centroid of the emissive faces in the
	 * model's local (pre-rotation) space, or null if no emissive faces are present. The centroid
	 * lets the light track the emissive surface (e.g. a swinging cape) as the model animates.
	 */
	@Nullable
	private EmissiveScan findStrongestEmissiveMaterial(@Nonnull Model model) {
		final short[] faceTextures = model.getFaceTextures();
		if (faceTextures == null)
			return null; // Untextured models can't have emissive textures

		final int faceCount = min(model.getFaceCount(), faceTextures.length);
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();
		boolean hasGeometry = indices1 != null && indices2 != null && indices3 != null &&
			verticesX != null && verticesY != null && verticesZ != null;

		Material strongest = null;
		// Accumulate the centroid only over the strongest material's faces, so a small amount of a
		// stronger emissive material doesn't get its centroid diluted by a different material.
		float sumX = 0, sumY = 0, sumZ = 0;
		int centroidCount = 0;

		for (int face = 0; face < faceCount; face++) {
			short textureId = faceTextures[face];
			if (textureId == -1)
				continue;

			Material material = materialManager.fromVanillaTexture(textureId);
			if (material == null || !material.isEmissive())
				continue;

			if (strongest == null || material.emissiveStrength > strongest.emissiveStrength) {
				strongest = material;
				// Restart the centroid accumulation for the new strongest material
				sumX = sumY = sumZ = 0;
				centroidCount = 0;
			}

			// Only accumulate faces belonging to the current strongest material
			if (hasGeometry && material == strongest) {
				int a = indices1[face], b = indices2[face], c = indices3[face];
				sumX += (verticesX[a] + verticesX[b] + verticesX[c]) / 3f;
				sumY += (verticesY[a] + verticesY[b] + verticesY[c]) / 3f;
				sumZ += (verticesZ[a] + verticesZ[b] + verticesZ[c]) / 3f;
				centroidCount++;
			}
		}

		if (strongest == null)
			return null;
		if (centroidCount == 0)
			return new EmissiveScan(strongest, 0, 0, 0);
		return new EmissiveScan(strongest, sumX / centroidCount, sumY / centroidCount, sumZ / centroidCount);
	}

	/**
	 * Builds a {@link LightDefinition} for an emissive-material light. Uses {@link Alignment#CUSTOM}
	 * so the per-frame {@code light.offset} (the emissive-face centroid) is rotated by the actor's
	 * orientation and added to the actor position. Color/radius/strength are filled from the
	 * material; the position offset is set per-frame by {@link #refreshEmissiveLight}.
	 */
	private LightDefinition createEmissiveLightDef(@Nonnull Material material) {
		LightDefinition def = new LightDefinition();
		def.alignment = Alignment.CUSTOM;
		def.type = LightType.STATIC;
		def.height = 0; // Height comes from the per-frame offset, not a fixed def height
		def.offset = new float[3];
		def.color = new float[] { material.emissiveColor[0], material.emissiveColor[1], material.emissiveColor[2] };
		def.radius = round(material.emissiveRadius);
		def.strength = material.emissiveStrength;
		def.normalize();
		return def;
	}

	/**
	 * Refreshes an existing emissive light in place each frame so it tracks the (animated) emissive
	 * surface and any hot-reloaded material properties. The emissive-face centroid (in model-local,
	 * pre-rotation space) is written to {@code light.offset}, which the actor-light branch in
	 * {@link #update} rotates by the actor's orientation before adding it to the actor position.
	 */
	private void refreshEmissiveLight(@Nonnull Light light, @Nonnull Material material, @Nonnull EmissiveScan scan) {
		// Model-space X/Z map directly to the light's local offset; the CUSTOM-alignment block in
		// update() applies the actor's orientation. Model-space Y is negative-up, matching the
		// internal offset Y convention (negative raises the light), so it's used as-is.
		light.offset[0] = scan.centroidX;
		light.offset[1] = scan.centroidY;
		light.offset[2] = scan.centroidZ;

		// LightType.STATIC lights copy strength/radius/color from def each frame in update(), so
		// updating the def is sufficient; refresh them in case the material was hot-reloaded.
		light.def.radius = round(material.emissiveRadius);
		light.def.strength = material.emissiveStrength;
		light.def.color[0] = material.emissiveColor[0];
		light.def.color[1] = material.emissiveColor[1];
		light.def.color[2] = material.emissiveColor[2];
		light.color = light.def.color;
	}

	private void handleObjectSpawn(TileObject object) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext != null)
			handleObjectSpawn(sceneContext, object);
	}

	private int getImpostorId(TileObject tileObject) {
		ObjectComposition def = client.getObjectDefinition(tileObject.getId());
		var impostorIds = def.getImpostorIds();
		if (impostorIds != null) {
			try {
				int impostorVarbit = def.getVarbitId();
				int impostorVarp = def.getVarPlayerId();
				int impostorIndex = -1;
				if (impostorVarbit != -1) {
					impostorIndex = client.getVarbitValue(impostorVarbit);
				} else if (impostorVarp != -1) {
					impostorIndex = client.getVarpValue(impostorVarp);
				}
				if (impostorIndex >= 0)
					return impostorIds[min(impostorIndex, impostorIds.length - 1)];
			} catch (Exception ex) {
				log.debug("Error getting impostor:", ex);
			}
		}
		return tileObject.getId();
	}

	public void handleObjectSpawn(
		@Nonnull SceneContext sceneContext,
		@Nonnull TileObject tileObject
	) {
		// prevent objects at plane -1 and below from having lights
		if (tileObject.getPlane() < 0)
			return;

		// GameObjects with DynamicObject renderables may be impostors, so handle those in swapScene
		int tileObjectId = tileObject.getId();
		if (tileObject instanceof GameObject &&
			((GameObject) tileObject).getRenderable() instanceof DynamicObject
		) {
			if (client.isClientThread()) {
				tileObjectId = getImpostorId(tileObject);
			} else {
				sceneContext.lightSpawnsToHandleOnClientThread.add(tileObject);
				return;
			}
		}

		for (int i = 0; i < sceneContext.lights.size(); ++i) {
			var light = sceneContext.lights.get(i);
			if (light.tileObject == tileObject) {
				if (light.tileObjectId == tileObjectId)
					return; // Duplicate spawn, probably from spawn event right after scene load

				// Schedule despawning of the old light
				light.markedForRemoval = true;
			}
		}

		spawnLights(sceneContext, tileObject, tileObjectId);
	}

	private void handleObjectDespawn(TileObject tileObject) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		int impostorId = getImpostorId(tileObject);
		removeLightIf(sceneContext, l -> l.tileObject == tileObject && l.tileObjectId == impostorId);
	}

	private void spawnLights(@Nonnull SceneContext sceneContext, TileObject tileObject, int impostorId) {
		int sizeX = 1;
		int sizeY = 1;
		int[] orientations = { 0, 0 };
		int[] offset = { 0, 0 };

		if (tileObject instanceof GroundObject) {
			var object = (GroundObject) tileObject;
			imposterRenderables[0] = object.getRenderable();
			orientations[0] = HDUtils.getModelOrientation(object.getConfig());
		} else if (tileObject instanceof DecorativeObject) {
			var object = (DecorativeObject) tileObject;
			imposterRenderables[0] = object.getRenderable();
			imposterRenderables[1] = object.getRenderable2();
			int ori = orientations[0] = orientations[1] = HDUtils.getModelOrientation(object.getConfig());
			switch (ObjectType.fromConfig(object.getConfig())) {
				case WallDecorDiagonalNoOffset:
				case WallDecorDiagonalOffset:
				case WallDecorDiagonalBoth:
					ori = (ori + 512) % 2048;
					offset[0] = SINE[ori] * 64 >> 16;
					offset[1] = COSINE[ori] * 64 >> 16;
					break;
			}
			offset[0] += object.getXOffset();
			offset[1] += object.getYOffset();
		} else if (tileObject instanceof WallObject) {
			var object = (WallObject) tileObject;
			imposterRenderables[0] = object.getRenderable1();
			imposterRenderables[1] = object.getRenderable2();
			orientations[0] = HDUtils.convertWallObjectOrientation(object.getOrientationA());
			orientations[1] = HDUtils.convertWallObjectOrientation(object.getOrientationB());
		} else if (tileObject instanceof GameObject) {
			var object = (GameObject) tileObject;
			sizeX = object.sizeX();
			sizeY = object.sizeY();
			imposterRenderables[0] = object.getRenderable();
			int ori = orientations[0] = HDUtils.getModelOrientation(object.getConfig());
			int offsetDist = 64;
			switch (ObjectType.fromConfig(object.getConfig())) {
				case RoofEdgeDiagonalCorner:
				case RoofDiagonalWithRoofEdge:
					ori += 1024;
					offsetDist = round(offsetDist / sqrt(2));
				case WallDiagonal:
					ori = (ori + 2048 - 256) % 2048;
					offset[0] = SINE[ori] * offsetDist >> 16;
					offset[1] = COSINE[ori] * offsetDist >> 16;
					break;
			}
		} else {
			log.warn("Unhandled TileObject type: id: {}, hash: {}", tileObject.getId(), tileObject.getHash());
			return;
		}

		List<LightDefinition> lights = OBJECT_LIGHTS.get(impostorId == -1 ? tileObject.getId() : impostorId);
		HashSet<LightDefinition> onlySpawnOnce = new HashSet<>();

		LocalPoint lp = tileObject.getLocalLocation();
		int lightX = lp.getX() + offset[0];
		int lightZ = lp.getY() + offset[1];
		int plane = tileObject.getPlane();

		// Spawn animation-specific lights for each DynamicObject renderable, and non-animation-based lights
		for (int i = 0; i < 2; i++) {
			var renderable = imposterRenderables[i];
			if (renderable == null)
				continue;

			for (LightDefinition def : lights) {
				if (def.areas.length > 0) {
					int[] worldPos = sceneContext.localToWorld(lightX, lightZ, plane);
					boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
					if (!isInArea)
						continue;
				}
				if (def.excludeAreas.length > 0) {
					int[] worldPos = sceneContext.localToWorld(lightX, lightZ, plane);
					boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
					if (isInArea)
						continue;
				}

				// Rarely, it may be necessary to specify which of the two possible renderables the light should be attached to
				if (def.renderableIndex == -1) {
					// If unspecified, spawn it for the first non-null renderable
					if (onlySpawnOnce.contains(def))
						continue;
					onlySpawnOnce.add(def);
				} else if (def.renderableIndex != i) {
					continue;
				}

				int tileExX = clamp(lp.getSceneX() + sceneContext.sceneOffset, 0, EXTENDED_SCENE_SIZE - 2);
				int tileExY = clamp(lp.getSceneY() + sceneContext.sceneOffset, 0, EXTENDED_SCENE_SIZE - 2);
				float lerpX = fract(lightX / (float) LOCAL_TILE_SIZE);
				float lerpZ = fract(lightZ / (float) LOCAL_TILE_SIZE);
				int tileZ = clamp(plane, 0, MAX_Z - 1);

				Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
				Tile tile = tiles[tileZ][tileExX][tileExY];
				if (tile != null && tile.getBridge() != null && tileZ < MAX_Z - 1)
					tileZ++;

				int[][][] tileHeights = sceneContext.scene.getTileHeights();
				float heightNorth = mix(
					tileHeights[tileZ][tileExX][tileExY + 1],
					tileHeights[tileZ][tileExX + 1][tileExY + 1],
					lerpX
				);
				float heightSouth = mix(
					tileHeights[tileZ][tileExX][tileExY],
					tileHeights[tileZ][tileExX + 1][tileExY],
					lerpX
				);
				float tileHeight = mix(heightSouth, heightNorth, lerpZ);

				Light light = new Light(def);
				light.tileObject = tileObject;
				light.tileObjectId = impostorId;
				light.plane = plane;
				light.orientation = orientations[i];
				light.origin[0] = lightX;
				light.origin[1] = (int) tileHeight - light.def.height - 1;
				light.origin[2] = lightZ;
				light.sizeX = sizeX;
				light.sizeY = sizeY;
				sceneContext.lights.add(light);
			}
		}
	}

	private void addWorldLight(SceneContext sceneContext, Light light) {
		assert light.worldPoint != null;
		sceneContext.worldToLocals(light.worldPoint).forEach(local -> {
			int tileExX = local[0] / LOCAL_TILE_SIZE + sceneContext.sceneOffset;
			int tileExY = local[1] / LOCAL_TILE_SIZE + sceneContext.sceneOffset;
			if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
				return;

			var copy = new Light(light.def);
			copy.plane = local[2];
			copy.persistent = light.persistent;
			copy.origin[0] = local[0] + LOCAL_HALF_TILE_SIZE;
			copy.origin[1] = sceneContext.scene.getTileHeights()[local[2]][tileExX][tileExY] - copy.def.height - 1;
			copy.origin[2] = local[1] + LOCAL_HALF_TILE_SIZE;
			sceneContext.lights.add(copy);
		});
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved projectileMoved) {
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		// Since there's no spawn & despawn events for projectiles, add when they move for the first time
		Projectile projectile = projectileMoved.getProjectile();
		if (!sceneContext.knownProjectiles.add(projectile))
			return;

		int[] worldPos = sceneContext.localToWorld((int) projectile.getX(), (int) projectile.getY(), projectile.getFloor());

		int[] refCounter = { 0 };
		for (LightDefinition def : PROJECTILE_LIGHTS.get(projectile.getId())) {
			if (def.areas.length > 0) {
				boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
				if (!isInArea)
					continue;
			}
			if (def.excludeAreas.length > 0) {
				boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
				if (isInArea)
					continue;
			}

			Light light = new Light(def);
			light.projectile = projectile;
			light.projectileRefCounter = refCounter;
			refCounter[0]++;
			light.origin[0] = (int) projectile.getX();
			light.origin[1] = (int) projectile.getZ();
			light.origin[2] = (int) projectile.getY();
			light.plane = projectile.getFloor();

			sceneContext.lights.add(light);
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned spawn) {
		NPC npc = spawn.getNpc();
		addNpcLights(npc);
		addSpotanimLights(npc);
	}

	@Subscribe
	public void onNpcChanged(NpcChanged change) {
		// Respawn non-spotanim lights
		NPC npc = change.getNpc();
		removeLightIf(light -> light.actor == npc && light.spotanimId == -1);
		addNpcLights(change.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned despawn) {
		NPC npc = despawn.getNpc();
		removeLightIf(light -> light.actor == npc);
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned spawn) {
		addSpotanimLights(spawn.getPlayer());
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged change) {
		// Don't add spotanim lights on player change events, since it breaks death & respawn lights
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged change) {
		addSpotanimLights(change.getActor());
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned despawn) {
		Player player = despawn.getPlayer();
		removeLightIf(light -> light.actor == player);
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated graphicsObjectCreated) {
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		GraphicsObject graphicsObject = graphicsObjectCreated.getGraphicsObject();
		var lp = graphicsObject.getLocation();
		int[] worldPos = sceneContext.localToWorld(lp, graphicsObject.getLevel());

		for (LightDefinition def : GRAPHICS_OBJECT_LIGHTS.get(graphicsObject.getId())) {
			if (def.areas.length > 0) {
				boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
				if (!isInArea)
					continue;
			}
			if (def.excludeAreas.length > 0) {
				boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
				if (isInArea)
					continue;
			}

			Light light = new Light(def);
			light.graphicsObject = graphicsObject;
			light.origin[0] = lp.getX();
			light.origin[1] = graphicsObject.getZ();
			light.origin[2] = lp.getY();
			light.plane = worldPos[2];
			sceneContext.lights.add(light);
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned spawn) {
		handleObjectSpawn(spawn.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned despawn) {
		handleObjectDespawn(despawn.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned spawn) {
		handleObjectSpawn(spawn.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned despawn) {
		handleObjectDespawn(despawn.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned spawn) {
		handleObjectSpawn(spawn.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned despawn) {
		handleObjectDespawn(despawn.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned spawn) {
		handleObjectSpawn(spawn.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned despawn) {
		handleObjectDespawn(despawn.getGroundObject());
	}

	// TODO: Check whether this is still necessary. If so, we could track varbits/varps within each light
//	@Subscribe
//	public void onVarbitChanged(VarbitChanged event) {
//		var ctx = plugin.getSceneContext();
//		if (!(ctx instanceof LegacySceneContext))
//			return;
//		var sceneContext = (LegacySceneContext) ctx;
//
//		if (plugin.enableDetailedTimers)
//			frameTimer.begin(Timer.IMPOSTOR_TRACKING);
//		// Check if the event is specifically a varbit change first,
//		// since all varbit changes are necessarily also varp changes
//		if (event.getVarbitId() != -1) {
//			for (var tracker : sceneContext.trackedVarbits.get(event.getVarbitId()))
//				trackImpostorChanges(sceneContext, tracker);
//		} else if (event.getVarpId() != -1) {
//			for (var tracker : sceneContext.trackedVarps.get(event.getVarpId()))
//				trackImpostorChanges(sceneContext, tracker);
//		}
//		if (plugin.enableDetailedTimers)
//			frameTimer.end(Timer.IMPOSTOR_TRACKING);
//	}
}
