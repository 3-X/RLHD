#version 330

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/color_blindness.glsl>
#include <utils/misc.glsl>
#include <utils/starfield.glsl>
#include <utils/aurora.glsl>
#include <utils/sky.glsl>

in vec2 fScreenPos;

out vec4 FragColor;

// Moon surface noise functions
float moonHash(in vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

float moonNoise(in vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);
    float a = moonHash(i);
    float b = moonHash(i + vec2(1.0, 0.0));
    float c = moonHash(i + vec2(0.0, 1.0));
    float d = moonHash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) +
        (c - a) * u.y * (1.0 - u.x) +
        (d - b) * u.x * u.y;
}

float moonFbm(in vec2 st) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 6; i++) {
        value += amplitude * moonNoise(st);
        st *= 2;
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    // Unproject a near/far ray to get the view direction.
    vec4 nearClip = vec4(fScreenPos, -1.0, 1.0);
    vec4 farClip = vec4(fScreenPos, 1.0, 1.0);

    vec4 nearWorld = invProjectionMatrix * nearClip;
    vec4 farWorld = invProjectionMatrix * farClip;

    nearWorld /= nearWorld.w;
    farWorld /= farWorld.w;

    vec3 viewDir = normalize(farWorld.xyz - nearWorld.xyz);

    SkyGradient sky = computeSkyGradient(viewDir);
    vec3 skyColor = sky.color;

    // Shift the shared night-sky horizon line.
    float horizonShift = nightHorizonOffset();

    // Stars appear first opposite the sun, then spread through twilight.
    float baseProgress = 1.0 - sky.nightFade;
    float sunProximity = sky.sunSideBlend * (1.0 - sky.zenithBlend);
    // Aurora visibility is independent of the star field's environment override.
    float nightFactor = pow(baseProgress, mix(0.4, 0.9, sunProximity));
    float starBlend = nightFactor * starVisibility;
    // Rotate the night sky about the local celestial pole using simulated time.
    float celestialAngle = skyCelestialRotation;
    vec3 celestialAxis = skyCelestialPole;
    float celestialCos = cos(celestialAngle);
    float celestialSin = sin(celestialAngle);
    if (nightFactor > 0.001) {
        vec3 starDir = viewDir;
        starDir = starDir * celestialCos + cross(celestialAxis, starDir) * celestialSin +
            celestialAxis * dot(celestialAxis, starDir) * (1.0 - celestialCos);

        // Individual stars are drawn separately as point sprites.
        vec3 nightSkyColor = proceduralStarfieldBackground(starDir);

        // Converge to the fog-matched gradient at the horizon.
        float horizonStarFade = smoothstep(-0.1 + horizonShift, 0.07 + horizonShift, sky.upAmount);
        skyColor = mix(skyColor, nightSkyColor, nightFactor * horizonStarFade);

        // Shooting stars are atmospheric, so they do not follow celestial rotation.
        if (-viewDir.y > 0.05 + horizonShift) {
            skyColor += shootingStars(viewDir, elapsedTime) * starBlend;
        }
    }

    // === MOON DISK ===
    if (moonVisibility > 0.001) {
        // Apply the sun's perceived-horizon offset.
        vec3 moonDir = normalize(vec3(skyMoonDir.x, -skyMoonDir.y + HORIZON_OFFSET, skyMoonDir.z));
        vec3 moonSunDir = normalize(vec3(skyMoonPhaseLightDirection.x, -skyMoonPhaseLightDirection.y + HORIZON_OFFSET, skyMoonPhaseLightDirection.z));

        float moonDot = dot(viewDir, moonDir);

        // The moon becomes opaque only after the sun is well below the horizon.
        float moonDayAlpha = 1.0 - smoothstep(-0.17, 0.5, skySunDir.y);

        // Fade the moon near the sun.
        float sunMoonDot = dot(moonDir, sky.sunDir);
        float sunProximityFade = smoothstep(0.9, 0.7, sunMoonDot);
        moonDayAlpha *= sunProximityFade;

        if (moonDot > 0.0 && moonDayAlpha > 0.001) {
            // Deliberately enlarged ~1.9° moon radius, scaled per environment.
            float moonBaseRadius = acos(0.99945);
            float moonAngularRadius = cos(moonBaseRadius * moonSizeMult);
            float edgeWidth = moonDot > 0.01 ? fwidth(moonDot) * 1.5 : 0;

            float moonDisk = smoothstep(moonAngularRadius - edgeWidth, moonAngularRadius, moonDot);

            if (moonDisk > 0.0) {
                // Moon-local coordinates for the phase shape.
                float angDist = acos(clamp(moonDot, 0.0, 1.0));
                float moonRadius = acos(moonAngularRadius); // angular radius in radians

                // Use a fallback reference axis near vertical to avoid a zero cross product.
                vec3 moonUp = abs(moonDir.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
                vec3 moonRight = normalize(cross(moonUp, moonDir));
                moonUp = normalize(cross(moonDir, moonRight));

                vec3 toView = normalize(viewDir - moonDir * moonDot);
                float localX = dot(toView, moonRight) * angDist / moonRadius;
                float localY = dot(toView, moonUp) * angDist / moonRadius;

                // Orient the terminator toward the sun; the mirrored moon can still use its own phase.
                vec2 moonToSun = vec2(dot(moonSunDir, moonRight), dot(moonSunDir, moonUp));
                float moonToSunLength = length(moonToSun);
                moonToSun = moonToSunLength > 1e-4 ? moonToSun / moonToSunLength : vec2(1.0, 0.0);
                moonToSun *= skyMoonPhaseReversed > 0.5 ? -1.0 : 1.0;

                vec2 moonLocal = vec2(localX, localY);
                float moonLocalZ = sqrt(max(0.0, 1.0 - dot(moonLocal, moonLocal)));
                vec3 moonSurfaceNormal = vec3(moonLocal, moonLocalZ);
                float phaseCos = 2.0 * skyMoonIllumination - 1.0;
                float phaseSin = sqrt(max(0.0, 1.0 - phaseCos * phaseCos));
                vec3 moonLightDir = vec3(moonToSun * phaseSin, phaseCos);

                // Libration moves surface detail without rotating the sun-facing terminator.
                vec2 moonSurface = moonLocal + skyMoonLibration * (2.0 / PI);
                float librationRoll = (skyMoonLibration.x + skyMoonLibration.y) * 0.25;
                mat2 librationRotation = mat2(cos(librationRoll), -sin(librationRoll), sin(librationRoll), cos(librationRoll));
                vec2 moonDetail = librationRotation * moonSurface;
                vec2 moonUV = moonDetail * 4.0 + vec2(50.0, 50.0);

                // Large-scale terrain - broad tonal variation
                float largeTerrain = moonFbm(moonUV * 0.4);

                // Medium-scale detail
                float medTerrain = moonFbm(moonUV * 1.5);

                // Fine surface texture
                float fineTerrain = moonFbm(moonUV * 5.0);

                // Base brightness from blended terrain layers
                float surfaceBrightness = largeTerrain * 0.4 + medTerrain * 0.4 + fineTerrain * 0.2;
                surfaceBrightness = mix(0.6, 1, surfaceBrightness);

                // Dark maria (seas) - a few subtle darker patches
                float seaNoise = moonFbm(moonUV * 0.8 + vec2(30.0, 70.0));
                float seaMask = smoothstep(0.50, 0.40, seaNoise);
                surfaceBrightness *= mix(1.0, 0.88, seaMask);

                vec2 impactPositions[3] = vec2[3](
                    vec2(0.6, -0.25),
                    vec2(-0.25, -0.1),
                    vec2(0.55, 0.55)
                );
                float impactMaxDistance[3] = float[3](1.8, 2.0, 2.8);
                float impactRadius[3] = float[3](0.10, 0.1, 0.11);
                float impactLightening = 0.0;
                for (int impact = 0; impact < 3; impact++) {
                    vec2 impactDetail = impactPositions[impact];
                    vec2 impactLocal = transpose(librationRotation) * impactDetail - skyMoonLibration * (2.0 / PI);
                    float impactLocalZ = sqrt(max(0.0, 1.0 - dot(impactLocal, impactLocal)));
                    vec3 impactNormal = vec3(impactLocal, impactLocalZ);
                    float distanceFromImpact = acos(clamp(dot(moonSurfaceNormal, impactNormal), -1.0, 1.0)) * 4.0;
                    float ejectaStartFade = smoothstep(
                        impactRadius[impact] * 0.2,
                        impactRadius[impact] * 0.8,
                        distanceFromImpact
                    );
                    if (distanceFromImpact >= impactMaxDistance[impact])
                        continue;

                    float distanceFade = 1.0 - smoothstep(impactRadius[impact], impactMaxDistance[impact], distanceFromImpact);
                    float impactEdgeWidth = fwidth(distanceFromImpact) * 1.5;
                    float ejectaFade = smoothstep(
                        impactRadius[impact] * 0.8 - impactEdgeWidth,
                        impactRadius[impact] * 0.8 + impactEdgeWidth,
                        distanceFromImpact
                    );
                    float halo = distanceFade * distanceFade * 0.08;
                    float nearImpact = smoothstep(impactRadius[impact] * 1.5, impactRadius[impact] * 10.0, distanceFromImpact);
                    float webNoise = moonFbm(moonUV * 6.0 + vec2(float(impact) * 17.0));
                    float webNoise2 = moonFbm(moonUV * 10.0 + vec2(float(impact) * 31.0));
                    float webbing = (smoothstep(0.42, 0.62, webNoise) + smoothstep(0.45, 0.65, webNoise2) * 0.6) *
                        (1.0 - nearImpact) * distanceFade * 0.12;
                    float impactRays = 0.0;
                    vec3 impactEast = vec3(impactNormal.z, 0.0, -impactNormal.x);
                    float impactEastLength = length(impactEast);
                    impactEast = impactEastLength > 1e-4 ? impactEast / impactEastLength : vec3(1.0, 0.0, 0.0);
                    vec3 impactNorth = normalize(cross(impactNormal, impactEast));
                    for (int ray = 0; ray < 14; ray++) {
                        float rayAngle = moonHash(vec2(
                            float(impact) * 7.0 + float(ray) * 13.0,
                            float(ray) * 3.0 + float(impact) * 11.0
                        )) * 6.2832;
                        vec3 rayDirection = impactEast * cos(rayAngle) + impactNorth * sin(rayAngle);
                        float alongRay = atan(
                            dot(moonSurfaceNormal, rayDirection),
                            dot(moonSurfaceNormal, impactNormal)
                        ) * 4.0;
                        if (alongRay <= 0.0)
                            continue;

                        float wobble = (moonNoise(vec2(
                            alongRay * 3.0 + float(impact) * 20.0,
                            float(ray) * 5.0
                        )) - 0.5) * 0.06;
                        vec3 rayPlaneNormal = cross(impactNormal, rayDirection);
                        float perpendicularDistance = abs(
                            asin(clamp(dot(moonSurfaceNormal, rayPlaneNormal), -1.0, 1.0)) * 4.0 + wobble
                        );
                        float rayWidth = 0.025 + moonNoise(vec2(float(ray) * 9.0, float(impact) * 4.0)) * 0.015;
                        float rayLine = smoothstep(rayWidth, rayWidth * 0.2, perpendicularDistance);
                        float rayIntensity = 0.5 + moonHash(vec2(float(ray) * 11.0, float(impact) * 6.0)) * 0.5;
                        impactRays = max(impactRays, rayLine * rayIntensity);
                    }
                    float impactBrightness = (impactRays * distanceFade * 0.09 + halo + webbing) *
                        ejectaStartFade * ejectaFade;
                    float impactDarkness = 1.0 - smoothstep(0.7, 1, surfaceBrightness);
                    impactLightening = max(impactLightening, impactBrightness * impactDarkness * 8.0);
                }

                float lambert = dot(moonSurfaceNormal, moonLightDir);

                float terminatorJitter =
                    (surfaceBrightness - 0.85) * 0.01 +
                    (fineTerrain - 0.5) * 0.03;
                // Surface relief only perturbs incidence near the terminator.
                float terminatorRoughness =
                    (1.0 - smoothstep(0.05, 0.35, abs(lambert))) *
                    smoothstep(0.05, 0.25, moonLocalZ);
                float roughLambert = lambert + terminatorJitter * terminatorRoughness;
                float lightCos = max(roughLambert, 0.0);
                float viewCos = moonLocalZ;
                // Lunar regolith scatters closer to Lommel-Seeliger than ideal Lambertian diffuse.
                float lommelSeeliger = 2.0 * lightCos / max(lightCos + viewCos, 1e-4);
                float lunarLambertWeight = mix(0.75, 0.25, max(phaseCos, 0.0));
                float lunarDiffuse = mix(lommelSeeliger, lightCos, lunarLambertWeight);
                float terminatorFade = smoothstep(-0.14, 0.08, roughLambert);
                float isLit = skyMoonIllumination > 0.001
                    ? clamp(lunarDiffuse, 0.0, 1.0) * terminatorFade
                    : 0.0;
                float terminatorProximity = 1.0 - smoothstep(0.02, 0.2, abs(roughLambert));
                float crescentEdgeFade = smoothstep(0.0, 0.25, moonLocalZ);
                isLit *= mix(1.0, crescentEdgeFade, terminatorProximity);

                // Warm gray color that shifts subtly with brightness
                // Darker areas slightly warmer, brighter areas slightly cooler
                float colorBlend = smoothstep(0.7, 0.95, surfaceBrightness);
                vec3 darkTone = vec3(0.8);
                vec3 brightTone = vec3(1.0);
                vec3 surfaceColor = mix(darkTone, brightTone, colorBlend);
                surfaceColor = mix(surfaceColor, brightTone, min(impactLightening, 1.0));

                float moonBrightness = max(max(skyMoonColor.r, skyMoonColor.g), skyMoonColor.b);
                vec3 moonLightColor = mix(skyMoonColor, moonBrightness * vec3(1.0, 0.94, 0.84), 0.6);

                vec3 litColor = moonLightColor * surfaceBrightness * surfaceColor;
                litColor *= vec3(0.7529423, 0.79910284, 1.0); // blue ish tint
                litColor *= 1.350980; // intensity
                // The unlit disk blocks stars and nebulae, but is no brighter than empty night sky.
                vec3 darkSideMoon = STARFIELD_BACKGROUND_COLOR;
                vec3 moonFinalColor = mix(darkSideMoon, litColor, isLit);
                // Fade moon near the horizon to match the star/nebula horizon fade
                float moonHorizonFade = smoothstep(-0.1 + horizonShift, 0.07 + horizonShift, sky.upAmount);
                float moonAlpha = moonDisk * moonDayAlpha * moonVisibility * moonHorizonFade;

                skyColor = mix(skyColor, moonFinalColor, moonAlpha);
            }

            // Subtle atmospheric glow around the moon (also faded by daytime transparency).
            // Divide the falloff exponent by moonSizeMult so the glow widens with a
            // larger moon (and tightens with a smaller one), matching the disk.
            float glowHorizonFade = smoothstep(-0.1 + horizonShift, 0.07 + horizonShift, sky.upAmount);
            float moonGlow = pow(moonDot, 256.0 / max(moonSizeMult, 0.001)) * 0.05 * skyMoonIllumination * moonDayAlpha * moonVisibility * glowHorizonFade;
            skyColor += skyMoonColor * moonGlow;
        }
    }

    // Aurora borealis - animated curtains near the northern horizon. Shown on the
    // randomly-selected aurora nights and faded with the night, but decoupled from
    // starVisibility so it can be scaled independently per environment via
    // auroraVisibility (0 on non-aurora nights or aurora-hidden areas). Uses
    // nightFactor (the star-independent night fade) in place of the previous
    // nightSkyBlend so it no longer disappears when starVisibility is 0.
    // Drawn AFTER the moon disk so the aurora composites visually in front of the moon.
    if (auroraVisibility > 0.001 && nightFactor > 0.001) {
        skyColor += proceduralAurora(viewDir, elapsedTime) * nightFactor * auroraVisibility;
    }

    skyColor = applySkyHaze(skyColor, sky.upAmount, sky.sunSideBlend, sky.zenithBlend);

    // Apply gamma correction
    skyColor = pow(skyColor, vec3(gammaCorrection));

    // Apply color blindness compensation
    skyColor = colorBlindnessCompensation(skyColor);

    // Dithering to eliminate color banding in dark sky gradients
    // Add ±0.5/255 noise per pixel to break up 8-bit quantization bands
    float dither = moonHash(gl_FragCoord.xy) - 0.5;
    skyColor += dither / 255.0;

    FragColor = vec4(skyColor, 1.0);
}
