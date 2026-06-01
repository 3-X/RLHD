/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
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
#include <uniforms/global.glsl>
#include <uniforms/water_types.glsl>

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>
#include <utils/noise.glsl>
#include <utils/misc.glsl>
#include <utils/water_reflection.glsl>
#include <utils/shadows.glsl>
#include <utils/fresnel.glsl>
#include <utils/legacy_water.glsl>

#if !LEGACY_WATER

// Global multiplier for storm strength. Constant for now, but kept as a single
// hook so a weather/time-of-day uniform can drive it later without touching the
// effect math. 0 = calm, 1 = full storm.
#define STORM_INTENSITY 1.0

// Per-water-type wave parameters used by the surface normal. Returning these as a
// struct lets us evaluate them per vertex type and blend the results across tile
// boundaries instead of hard-switching on a single index.
struct WaveParams {
    float height;
    float speed;
    float stormWeight; // 0..1 contribution of the storm effect for this type
};

WaveParams getWaveParams(int waterTypeIndex) {
    WaveParams w;
    w.height = 1;
    w.speed = .0072;
    w.stormWeight = 0;

    switch (waterTypeIndex) {
        case WATER_TYPE_BLACK_TAR_FLAT:
            w.height = .1;
            w.speed *= .42;
            break;
        case WATER_TYPE_MUDDY_WATER:
            w.height = .1;
            break;
        case WATER_TYPE_BLOOD:
            w.height = .75;
            break;
        case WATER_TYPE_ICE:
        case WATER_TYPE_ICE_FLAT:
            w.height = .3;
            w.speed = 0;
            break;
        case WATER_TYPE_ABYSS_BILE:
            w.height = .7;
            break;
    }

    // Storm waters: taller, faster, choppier. effectType == 1 selects the storm
    // branch; STORM_INTENSITY scales the whole thing so it can later react to weather.
    if (getWaterType(waterTypeIndex).effectType == 1) {
        w.stormWeight = STORM_INTENSITY;
        w.height = mix(w.height, 2.4, w.stormWeight);
        w.speed = mix(w.speed, .0072 * 3.0, w.stormWeight);
    }

    return w;
}

// Evaluate the surface normal for a single set of wave parameters.
vec3 evalWaterSurfaceNormal(WaveParams w, vec3 position) {
    vec2 worldUv = -position.xz / 128;

    vec2 uv1 = worldUv / 26 + w.speed * elapsedTime * vec2( 1, -4);
    vec2 uv2 = worldUv /  6 + w.speed * elapsedTime * vec2(-2,  1);

    vec3 n1 = texture(waterNormalMaps, vec3(uv1, 0)).xyz;
    vec3 n2 = texture(waterNormalMaps, vec3(uv2, 1)).xyz;

    // Scale wave strength
    n1.z /= w.height * .225;
    n2.z /= w.height * .8;
    // Normalize
    n1.xy = n1.xy * 2 - 1;
    n2.xy = n2.xy * 2 - 1;
    // Tangent space to world, assuming flat surface
    n1.z *= -1;
    n2.z *= -1;
    n1 = normalize(n1.xzy);
    n2 = normalize(n2.xzy);

    vec3 n = n1 + n2;

    // Storm chop: a third, smaller and faster normal layer adds disordered detail
    // on top of the base swell. Faded in by the storm weight so it disappears as
    // storm water blends into calm water.
    if (w.stormWeight > 0) {
        vec2 uv3 = worldUv / 2.5 + w.speed * elapsedTime * vec2(3, -2);
        vec3 n3 = texture(waterNormalMaps, vec3(uv3, 0)).xyz;
        n3.z /= w.height * .35;
        n3.xy = n3.xy * 2 - 1;
        n3.z *= -1;
        n3 = normalize(n3.xzy);
        n += n3 * w.stormWeight;
    }

    return normalize(n);
}

// Blend the surface normal across up to three water types by their texBlend weights.
// The common case (all three the same type) collapses to a single evaluation.
vec3 sampleWaterSurfaceNormal(ivec3 waterTypeIndices, vec3 weights, vec3 position) {
    WaveParams w0 = getWaveParams(waterTypeIndices[0]);
    if (waterTypeIndices[0] == waterTypeIndices[1] && waterTypeIndices[0] == waterTypeIndices[2])
        return evalWaterSurfaceNormal(w0, position);

    vec3 n0 = evalWaterSurfaceNormal(w0, position);
    vec3 n1 = evalWaterSurfaceNormal(getWaveParams(waterTypeIndices[1]), position);
    vec3 n2 = evalWaterSurfaceNormal(getWaveParams(waterTypeIndices[2]), position);
    return normalize(n0 * weights.x + n1 * weights.y + n2 * weights.z);
}

// Backwards-compatible single-type overload (used by sampleUnderwater).
vec3 sampleWaterSurfaceNormal(int waterTypeIndex, vec3 position) {
    return evalWaterSurfaceNormal(getWaveParams(waterTypeIndex), position);
}

void sampleUnderwater(inout vec3 outputColor, int waterTypeIndex, float depth) {
    WaterType waterType = getWaterType(waterTypeIndex);

    // Ignore refraction for the underwater position, since it would require computing a quartic equation
    vec3 fragPos = IN.position;
    float fragDist = length(fragPos - cameraPos);
    vec3 underwaterNormal = normalize(IN.normal);
    vec3 surfaceNormal = vec3(0, -1, 0); // Assume a flat surface

    vec3 sunDir = -lightDir; // The light's direction from the sun towards any fragment
    vec3 refractedSunDir = refract(sunDir, surfaceNormal, IOR_AIR_TO_WATER);
    float sunToFragDist = depth / refractedSunDir.y;

    vec3 camToFrag = normalize(fragPos - cameraPos);
    float fragToSurfaceDist = abs(depth / camToFrag.y);

    // Incoming and outgoing light directions pointing away from the fragment
    vec3 omega_i = -refractedSunDir;
    vec3 omega_o = -camToFrag;

    vec3 surfacePos = fragPos - camToFrag * fragToSurfaceDist;
    surfaceNormal = sampleWaterSurfaceNormal(waterTypeIndex, surfacePos);
    omega_o = -refract(camToFrag, surfaceNormal, IOR_AIR_TO_WATER);

    // Initialize light intensities with linear RGB colors
    vec3 directionalLight = lightColor * lightStrength;
    vec3 ambientLight = ambientColor * ambientStrength;
    vec3 seabedAlbedo = outputColor;

    // Naming convention taken from http://graphics.cs.cmu.edu/courses/15-468/2021_spring/lectures/lecture17.pdf

    // Absorption, scattering and diffuse attenuation coefficients of pure sea water
    // From page 31 of https://misclab.umeoce.maine.edu/boss/classes/RT_Weizmann/Chapter3.pdf
    vec3 sigma_a_pureWater = vec3(
        .244,  // ~red   600 nm
        .0638, // ~green 550 nm
        .0145  // ~blue  450 nm
    );
    vec3 sigma_s_pureWater = vec3(
        .0014, // ~red   600 nm
        .0019, // ~green 550 nm
        .0045  // ~blue  450 nm
    );

    // Initialize absorption and scattering coefficients based on water type
    vec3 sigma_a_particles = vec3(0);
    vec3 sigma_s_particles = vec3(0);

    // Scattering anisotropy factor (average cosine), used in the Henyey-Greenstein phase function
    // Taken from https://www.researchgate.net/figure/a-Correlation-of-Mie-scattering-coefficient-and-wavelength-and-b-anisotropy-factor_fig5_337670010
    float g = .924;

    float noise = gradientNoise(gl_FragCoord.xy);

    switch (waterTypeIndex) {
        default:
        case WATER_TYPE_WATER:
        case WATER_TYPE_PLAIN_WATER:
            // Coefficients for Jerlov water types, taken from https://doi.org/10.1364/AO.54.005392
            // https://www.researchgate.net/figure/Left-Jerlov-water-types-based-on-the-attenuation-coefficients-bl-Types-I-III-are_fig1_338015606

            // Jerlov I
            sigma_a_particles = vec3(.228, .062, .018);
            sigma_s_particles = vec3(1.22E-03, 1.70E-03, 3.81E-03);
            g = .88;
            break;
        case WATER_TYPE_BLOOD:
            sigma_a_particles = (1 - vec3(.9, .1, .2)) * 7;
            sigma_s_particles = vec3(1, .1, .1) * .05;
            g = .2;
            break;
        case WATER_TYPE_SCAR_SLUDGE:
            sigma_a_particles = vec3(.309, .3, .1548) * .35;
            sigma_a_particles += vec3(0.005, 0.0175, 0.0275) * 20;
            break;
        case WATER_TYPE_CYAN_WATER:
            sigma_a_particles = sigma_a_pureWater * 4;
            sigma_s_particles = vec3(.325, .659, .675);
            g = .01;
            break;
        case WATER_TYPE_GREEN_CAVE_WATER:
            sigma_a_particles = (1.1 - vec3(0, .973, .718)) * .2;
            sigma_s_particles = vec3(.01, .973, .418) * .01;
            break;
        case WATER_TYPE_SWAMP_WATER:
        case WATER_TYPE_POISON_WASTE:
        case WATER_TYPE_ABYSS_BILE:
        case WATER_TYPE_DARK_BLUE_WATER:
            // Jerlov 1C
            sigma_a_particles = vec3(.236, .076, .105);
            sigma_s_particles = vec3(.314, .365, .514);
            g = .89;
            break;
    }

    // Kind of hacky way to fix the edges for some water types
    switch (waterTypeIndex) {
        case WATER_TYPE_BLOOD:
        case WATER_TYPE_SWAMP_WATER:
        case WATER_TYPE_POISON_WASTE:
        case WATER_TYPE_MUDDY_WATER:
        case WATER_TYPE_SCAR_SLUDGE:
        case WATER_TYPE_CYAN_WATER:
        case WATER_TYPE_ARAXXOR_WASTE:
            depth += 48;
            sunToFragDist = depth / refractedSunDir.y;
            fragToSurfaceDist = abs(depth / camToFrag.y);
            break;
    }

    // Convert coefficients from per meter to in-game units
    sigma_a_particles /= 128;
    sigma_s_particles /= 128;
    sigma_a_pureWater /= 128;
    sigma_s_pureWater /= 128;

    vec3 sigma_a = sigma_a_pureWater + sigma_a_particles;
    vec3 sigma_s = sigma_s_pureWater + sigma_s_particles;
    // Extinction coefficient = absorption + scattering
    vec3 sigma_t = sigma_a + sigma_s;

    // Compute single-scattering of directional light
    float cosTheta = dot(-omega_i, omega_o);

    // P = normalized phase function
    // B = backscatter fraction (portion scattered backwards)

    // Scattering phase function of pure water
    // https://www.oceanopticsbook.info/view/inherent-and-apparent-optical-properties/visualizing-vsfs
    float P_pureWater = 0.06225 + 0.05197875 * cosTheta*cosTheta;
    float B_pureWater = 0.5; // symmetrical

    // Henyey-Greenstein phase function
    // https://www.oceanopticsbook.info/view/scattering/level-2/the-henyey-greenstein-phase-function
    float P_hg = (1 - g*g) / (4 * PI * pow(1 + g*g - 2*g*cosTheta, 3.f / 2.f));
    float B_hg = (1 - g) / (2 * g) * ((1 + g) / sqrt(1 + g*g) - 1);

    // We start off by calculating the amount of light reaching the seabed fragment
    vec3 L_directional = directionalLight;

    // Account for loss to Fresnel reflection upon entering the water body
    L_directional *= 1 - calculateFresnel(max(0, dot(-sunDir, surfaceNormal)), IOR_WATER);

    // Add underwater caustics as additional directional light
    if (SHORELINE_CAUSTICS == 1) {
        vec2 causticsUv = worldUvs(3.333);
        const vec2 direction = vec2(1, -2);
        vec2 flow1 = causticsUv + animationFrame(13) * direction;
        vec2 flow2 = causticsUv * 1.5 + animationFrame(17) * -direction;
        vec3 caustics = sampleCaustics(flow1, flow2, depth * .00003);

        // Apply caustics color based on the environment
        // Usually this falls back to directional lighting
        caustics *= underwaterCausticsColor;

        float average = texture(textureArray, vec3(0, 0, MAT_CAUSTICS_MAP.colorMap), 100).r;
        caustics -= average;
        caustics *= 2;

        // Fade caustics out too close to the shoreline
        caustics *= min(1, smoothstep(0, 1, depth / 32));

        // Fade caustics out with depth, since they should decay sharply due to focus
        caustics *= max(0, 1 - smoothstep(0, 1, depth / 768));

        // Add caustics as additional directional light
        L_directional *= 1 + caustics;
    }

    // Account for shadowing of the directional light
    if (WATER_TRANSPARENCY == 1 && !waterType.isFlat /* Disable shadows for flat water, as it needs more work */) {
        // For shadows, we can take refraction into account, since sunlight is parallel
        vec3 surfaceSunPos = fragPos - refractedSunDir * sunToFragDist;
        surfaceSunPos += refractedSunDir * 32; // Push the position a short distance below the surface
        vec2 distortion = vec2(0);
        {
            vec2 flowMapUv = worldUvs(26) + animationFrame(26 * waterType.duration);
            float flowMapStrength = 0.025;
            vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;
            distortion = uvFlow * .001 * (1 - exp(-.01 * depth));
        }

        const float SHADOW_FALLBACK_DIST = 8192.0;
        const float SHADOW_FALLBACK_BLEND = 512.0;
        float fallbackWeight = saturate((fragDist - SHADOW_FALLBACK_DIST) / SHADOW_FALLBACK_BLEND);
        float shadow = 0.0;
        if(fallbackWeight < 1.0) {
            // Calculate optical depth using relative luminance to avoid over bluring
            float opticalDepth = dot(sigma_t, vec3(0.2126, 0.7152, 0.0722)) * sunToFragDist;

            // Blur radius in shadow map UV space
            const float SHADOW_MAX_BLUR = 0.009;
            const float SHADOW_BLUR_OPTICAL_DEPTH_INV = 1.0 / 6.0;
            float blurRadius = SHADOW_MAX_BLUR * saturate(opticalDepth * SHADOW_BLUR_OPTICAL_DEPTH_INV);

            // Rotate the Poisson disk per fragment to break up repeated patterns.
            float angle = noise * 2.0 * PI;
            float s = sin(angle);
            float c = cos(angle);
            mat2 rot = mat2(c, -s, s, c);

            int numSamples = 1 + int(floor(float(POISSON_DISK_LENGTH - 1) * saturate(opticalDepth * SHADOW_BLUR_OPTICAL_DEPTH_INV)));
            for (int i = 0; i < numSamples; i++) {
                vec2 offset = rot * getPoissonDisk(i) * blurRadius;
                shadow += sampleShadowMap(surfaceSunPos, distortion + offset, dot(-sunDir, underwaterNormal));
            }
            shadow /= float(numSamples);
        } else {
            shadow = sampleShadowMap(surfaceSunPos, distortion, dot(-sunDir, underwaterNormal));
        }

        // Apply shadow to directional light
        L_directional *= 1.0 - shadow * 0.9; // Clamp Shadow to avoid being fully black
    }

    // Wrap lighting around to add a fraction of ambient lighting to side which are perpendicular
    const float wrap = 0.55;

    // Attenuate the directional light as it travels down to the seabed
    L_directional *= exp(-sigma_t * sunToFragDist);

    // Calculate Lambertian reflection from the seabed
    L_directional *= max(0, (dot(omega_i, underwaterNormal) + wrap) / (1.0 + wrap));

    // Also calculate the amount of ambient lighting reaching the fragment
    vec3 L_ambient = ambientLight;

    // Crudely account for average loss to Fresnel reflection upon entering the water body
    L_ambient *= .975;

    // Rough approximation of diffuse attenuation coefficient
    vec3 K_d = sigma_a + sigma_s_pureWater * B_pureWater + sigma_s_particles * B_hg;
    L_ambient *= exp(-K_d * depth);

    // Rough approximation of Lambertian reflection from the seabed
    L_ambient *= max(0, (dot(vec3(0, -1, 0), underwaterNormal) + wrap) / (1.0 + wrap));

    // Now we're ready to start assembling the outgoing light L

    // Calculate Lambertian reflection from the seabed
    vec3 L = seabedAlbedo * (L_directional + L_ambient);

    // Attenuate the reflected light as it travels back up towards the surface
    L *= exp(-sigma_t * fragToSurfaceDist);

    // QSSA for upwelling radiance at the surface for a given depth
    // https://www.oceanopticsbook.info/view/radiative-transfer-theory/level-2/the-quasi-single-scattering-approximation
    float mu_sw = refractedSunDir.y; // Cosine of the angle between downward direction in water and the sunlight's direction
    float mu = omega_o.y;
    vec3 E_d0 = (directionalLight * .5 + ambientLight) * mu_sw; // Downwelling plane irradiance at the surface
    vec3 b_pureWater = sigma_s_pureWater * B_pureWater; // Backscatter coefficient
    vec3 zeta_star_pureWater = (sigma_a + b_pureWater) * depth; // Optical depth
    vec3 b_particles = sigma_s_particles * B_hg; // Backscatter coefficient
    vec3 zeta_star_particles = (sigma_a + b_particles) * depth; // Optical depth

    // Add scattering contribution from pure water and particles
    vec3 QSSA = E_d0 / (mu_sw - mu) * (
        b_pureWater / (sigma_a + b_pureWater)
            * P_pureWater / B_pureWater
            * (1 - exp(zeta_star_pureWater * (1 / mu - 1 / mu_sw)))
        + b_particles / (sigma_a + b_particles)
            * P_hg / B_hg
            * (1 - exp(zeta_star_particles * (1 / mu - 1 / mu_sw)))
    );
    L += QSSA;

    // Fresnel reflection upon leaving the water body is already accounted for by the water surface fragment
    outputColor = L;

    // Break up color banding with some noise
    outputColor.rgb += (gradientNoise(gl_FragCoord.xy) - .5) / 0xFF;
}

// Blend the UBO-backed properties of up to three water types by their texBlend
// weights into a single WaterType. The common case (a single type across the
// triangle) returns that type directly to avoid the extra fetches and math.
WaterType blendWaterTypes(ivec3 indices, vec3 weights) {
    WaterType a = getWaterType(indices[0]);
    if (indices[0] == indices[1] && indices[0] == indices[2])
        return a;

    WaterType b = getWaterType(indices[1]);
    WaterType c = getWaterType(indices[2]);

    WaterType r;
    r.specularStrength = a.specularStrength * weights.x + b.specularStrength * weights.y + c.specularStrength * weights.z;
    r.specularGloss    = a.specularGloss    * weights.x + b.specularGloss    * weights.y + c.specularGloss    * weights.z;
    r.normalStrength   = a.normalStrength   * weights.x + b.normalStrength   * weights.y + c.normalStrength   * weights.z;
    r.baseOpacity      = a.baseOpacity      * weights.x + b.baseOpacity      * weights.y + c.baseOpacity      * weights.z;
    r.duration         = a.duration         * weights.x + b.duration         * weights.y + c.duration         * weights.z;
    r.fresnelAmount    = a.fresnelAmount    * weights.x + b.fresnelAmount    * weights.y + c.fresnelAmount    * weights.z;
    r.surfaceColor     = a.surfaceColor     * weights.x + b.surfaceColor     * weights.y + c.surfaceColor     * weights.z;
    r.foamColor        = a.foamColor        * weights.x + b.foamColor        * weights.y + c.foamColor        * weights.z;
    r.depthColor       = a.depthColor       * weights.x + b.depthColor       * weights.y + c.depthColor       * weights.z;
    // Discrete flags: treat as "on if the dominant-weighted type has it on".
    // A hard switch here would pop at the boundary, but flatness/foam can't be
    // partially blended, so we bias toward the most-present type. isFlat is a bool
    // in the struct, so accumulate its weight separately and threshold.
    float flatWeight = (a.isFlat ? weights.x : 0.0) + (b.isFlat ? weights.y : 0.0) + (c.isFlat ? weights.z : 0.0);
    r.isFlat   = flatWeight >= 0.5;
    r.hasFoam  = (a.hasFoam  * weights.x + b.hasFoam  * weights.y + c.hasFoam  * weights.z) >= 0.5 ? 1 : 0;
    r.normalMap = a.normalMap; // Normal map layer choice can't be interpolated; use the provoking type's.
    r.effectType = a.effectType;
    return r;
}

// Weighted storm contribution across the three vertex types (0 = calm, 1 = full storm).
float blendStormWeight(ivec3 indices, vec3 weights) {
    float s0 = getWaterType(indices[0]).effectType == 1 ? STORM_INTENSITY : 0.0;
    if (indices[0] == indices[1] && indices[0] == indices[2])
        return s0;
    float s1 = getWaterType(indices[1]).effectType == 1 ? STORM_INTENSITY : 0.0;
    float s2 = getWaterType(indices[2]).effectType == 1 ? STORM_INTENSITY : 0.0;
    return s0 * weights.x + s1 * weights.y + s2 * weights.z;
}

vec4 sampleWater(ivec3 waterTypeIndices, vec3 weights, vec3 viewDir) {
    int waterTypeIndex = waterTypeIndices[0];
    WaterType waterType = blendWaterTypes(waterTypeIndices, weights);
    float stormWeight = blendStormWeight(waterTypeIndices, weights);

    #if ZONE_RENDERER
        // Compute the face normal from screen-space derivatives of the world position.
        // This gives the true geometric normal of the triangle, which is needed to
        // distinguish flat water (slope ~1) from waterfalls (slope ~0).
        // Stored normals can't be used because water surfaces use UP_NORMAL.
        vec3 waterFlatNormal = normalize(cross(dFdx(IN.position), dFdy(IN.position)));
    #else
        vec3 waterFlatNormal = IN.flatNormal;
    #endif

    float slope = abs(waterFlatNormal.y);
    if (slope < .8) {
        float waterfallMask = smoothstep(.8, .6, slope);

        vec3 bgColor = srgbToLinear(vec3(.063, .119, .194));
        vec3 bgColor2 = srgbToLinear(vec3(.063, .2, .3));
        vec3 fgColor = srgbToLinear(vec3(.9));

        vec3 N = waterFlatNormal;
        const float discretize = 5;
        N = floor(N * discretize) / discretize;
        vec3 T = normalize(vec3(-N.z, 0, N.x)); // Up cross normal
        vec3 B = cross(N, T);
        mat3 TBN = mat3(T, B, N);
        mat3 invTBN = transpose(TBN);

        const float uvScale = .15;
        vec3 uvw = (invTBN * IN.position) / -128;
        vec2 uv = uvw.xy;
        uv *= uvScale;

//        uv.y -= elapsedTime * uvw.z * .0001;

        vec2 flowMapUv = vec2(uv.x, IN.position.y / 128 * uvScale) * .3 - animationFrame(vec2(200, 4));
        vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;
        uv += uvFlow * .2;

        uv.y -= elapsedTime * .5;

        vec3 n = texture(waterNormalMaps, vec3(uv, 0)).xyz;
        n.xy = n.xy * 2 - 1;
        n.z *= .3;
        n = TBN * n;
        n = normalize(n);

        float cosAngle = max(0, dot(n, viewDir));
        float fresnel = calculateFresnel(cosAngle, IOR_WATER);

        vec4 dst = vec4(0);
        vec4 src = vec4(0);

        vec3 light = lightColor * lightStrength + ambientColor * ambientStrength;

        src.rgb = mix(bgColor, bgColor2, cosAngle) * light;
//        src += srgbToLinear(fogColor) * fresnel;
        vec3 omega_h = normalize(viewDir + lightDir); // half-way vector
        vec3 sunSpecular = pow(max(0, dot(n, omega_h)), 500) * lightColor * lightStrength;
        src.rgb += sunSpecular;
        src.a = waterfallMask * .15;
        dst = dst * (1 - src.a) + src;

        return dst;
    }

    float specularGloss = 500; // Ignore values set per water type, as they don't make a lot of sense
    float specularStrength = 1;

    vec3 ambientLight = ambientColor * ambientStrength;
    vec3 directionalLight = lightColor * lightStrength;

    vec3 N = sampleWaterSurfaceNormal(waterTypeIndices, weights, IN.position);

    vec3 fragToCam = viewDir;
    vec3 I = -viewDir; // Incident

    // Assume the water is level
    vec3 flatR = reflect(I, vec3(0, -1, 0));
    vec3 R = reflect(I, N);
    float distortionFactor = 50;
    float reflectionBias = 0;

    // Per-type reflection distortion, blended by weight so it transitions smoothly.
    {
        float d = 0;
        for (int i = 0; i < 3; i++) {
            float di = 1.0;
            switch (waterTypeIndices[i]) {
                case WATER_TYPE_ICE:        di = 12.0; break;
                case WATER_TYPE_ABYSS_BILE: di = 4.0;  break;
            }
            d += di * weights[i];
        }
        distortionFactor *= d;
    }

    vec4 reflection = vec4(
        sampleWaterReflection(flatR, R, distortionFactor),
        calculateFresnel(dot(fragToCam, N), IOR_WATER)
    );

    {
        // Blood lifts the red floor of the reflection; fade it by blood's weight.
        float bloodWeight = 0;
        for (int i = 0; i < 3; i++)
            if (waterTypeIndices[i] == WATER_TYPE_BLOOD)
                bloodWeight += weights[i];
        reflection.r = mix(reflection.r, max(reflection.r, .4f), bloodWeight);
    }

    // Break up color banding with some noise
    reflection.a += (gradientNoise(gl_FragCoord.xy) - .5) / 0xFF;

    vec3 additionalLight = vec3(0);

    vec3 omega_i = lightDir; // Incoming = frag to sun
    vec3 omega_o = viewDir; // Outgoing = frag to camera
    vec3 omega_h = normalize(omega_o + omega_i); // Half-way vector
    vec3 omega_n = N; // Surface normal

    vec3 sunSpecular = pow(max(0, dot(N, omega_h)), 2e3) * directionalLight;
    additionalLight += sunSpecular;

    // Storm darkening: storm water reads as a darker, moodier surface. The sky
    // reflection (driven by fresnel) otherwise washes the dark surfaceColor out, so
    // we explicitly pull the reflection toward the (already-blended) storm surface
    // color and knock down its brightness, scaled by the blended storm weight so it
    // fades to nothing where storm water meets calm ocean.
    if (stormWeight > 0) {
        vec3 stormSurface = srgbToLinear(waterType.surfaceColor);
        vec3 light = ambientColor * ambientStrength + lightColor * lightStrength;
        // Tint toward the dark storm color and dim the reflection.
        reflection.rgb = mix(reflection.rgb, stormSurface * light, 0.55 * stormWeight);
        reflection.rgb *= mix(1.0, 0.7, stormWeight);
    }

    // Begin constructing final output color
    vec4 dst = reflection;

    // In theory, we could just add the light and be done with it, but since the color
    // will be multiplied by alpha during alpha blending, we need to divide by alpha to
    // end up with our target amount of additional light after alpha blending
    dst.rgb += additionalLight / dst.a;

    // The issue now is that or color may exceed 100% brightness, and get clipped.
    // To work around this, we can adjust the alpha component to let more of the light through,
    // and adjust our color accordingly. This necessarily causes the surface to become more opaque,
    // but since we're adding lots of light, this should have minimal impact on the final picture.
    float maxIntensity = max(max(dst.r, dst.g), dst.b);
    // Check if the color would get clipped
    if (maxIntensity > 1) {
        // Bring the brightest color back down to 1
        dst.rgb /= maxIntensity;
        // And bump up the alpha to increase brightness instead
        dst.a *= maxIntensity;
        // While not strictly necessary, we might as well clamp the alpha component in case it exceeds 1
        dst.a = min(1, dst.a);
    }

    // TODO: specify transparent, faked depth or lambertian per water type
    // A highly scattering medium roughly approaches a Lambertian reflector.
    // Compute the weighted presence of lambertian types and the weighted lambertian
    // surface color, then blend the lambertian result in by that weight so a
    // muddy/araxxor/tar tile transitions smoothly into clear water.
    float lambertianWeight = 0;
    vec3 lambertianColor = vec3(0);
    for (int i = 0; i < 3; i++) {
        vec3 ci = vec3(0);
        bool isLambertian = true;
        switch (waterTypeIndices[i]) {
            case WATER_TYPE_MUDDY_WATER:   ci = vec3(.238, .161, .007) * .065; break;
            case WATER_TYPE_ARAXXOR_WASTE: ci = vec3(22, 255, 13) / 0xFF * .5; break;
            case WATER_TYPE_BLACK_TAR_FLAT: ci = srgbToLinear(getWaterType(waterTypeIndices[i]).surfaceColor); break;
            default: isLambertian = false; break;
        }
        if (isLambertian) {
            lambertianWeight += weights[i];
            lambertianColor += ci * weights[i];
        }
    }

    if (lambertianWeight > 0) {
        // Normalize the accumulated color by the lambertian weight to get its average color.
        vec3 surfaceColor = lambertianColor / max(lambertianWeight, 1e-4);
        vec4 src = dst;
        vec4 lamb;
        lamb.rgb = surfaceColor * (ambientLight + directionalLight * max(0, dot(N, omega_o)));
        lamb.rgb = mix(lamb.rgb, src.rgb, src.a);
        lamb.a = 1;
        dst = mix(dst, lamb, lambertianWeight);
    }

    if (waterType.isFlat || WATER_TRANSPARENCY == 0) { // If the water is opaque, blend in a fake underwater surface
        // Computed from packedHslToSrgb(6676)
        const vec3 underwaterColor = vec3(0.04856183, 0.025971446, 0.005794384);
        int depth = 768; // Works for boat cutscenes such as when going diving with Murphy
//        int depth = 512; // Works alright for the obelisk fix in Catherby

        // TODO: add a way for tile overrides to specify water depth
        if (waterTypeIndex == WATER_TYPE_ABYSS_BILE)
            depth = 96;

        vec4 src = dst;
        dst.rgb = underwaterColor;
        sampleUnderwater(dst.rgb, waterTypeIndex, depth);

        dst.rgb = mix(dst.rgb, src.rgb, src.a);
        dst.a = 1;
    }

    #if WATER_FOAM
        if (waterType.hasFoam == 1) {
            vec2 flowMapUv = worldUvs(5) + animationFrame(30 * waterType.duration);
            float flowMapStrength = .25;
            vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;
            vec2 uv = IN.uv + uvFlow * flowMapStrength;
            float foamMask = texture(textureArray, vec3(uv, MAT_WATER_FOAM.colorMap)).r;
            float shoreLineMask = 1 - dot(IN.texBlend, fAlphaBiasHsl / 127.f);
            shoreLineMask *= shoreLineMask;
            shoreLineMask *= shoreLineMask;
            shoreLineMask *= shoreLineMask;

            vec3 light = ambientColor * ambientStrength + lightColor * lightStrength;
            vec4 foam = vec4(light, shoreLineMask * foamMask * .04);
            foam.rgb *= waterType.foamColor;

            // Blend in foam at the very end as an overlay
            dst.rgb = foam.rgb * foam.a + dst.rgb * dst.a * (1 - foam.a);
            dst.a = foam.a + dst.a * (1 - foam.a);
            dst.rgb /= dst.a;
        }
    #endif

    // Storm whitecaps: foamy crests across the open surface (not just the shoreline).
    // Driven by the blended storm weight so they fade out as storm water meets calm
    // water. Crests are detected where the surface normal tilts away from straight up,
    // broken up with animated noise so they read as churning foam rather than a flat wash.
    if (stormWeight > 0) {
        // N.y is near -1 on flat water (UP_NORMAL points down in this space); crests
        // are where it deviates. Map that deviation into a 0..1 crest mask.
        float crest = saturate((1.0 - abs(N.y)) * 6.0);

        vec2 capUv = worldUvs(2.5) + animationFrame(8.0) * vec2(1.0, -0.6);
        float capNoise = noise(capUv * 8.0);
        capNoise = capNoise * 0.6 + noise(capUv * 19.0 + 3.7) * 0.4;

        float capMask = saturate(crest * capNoise * 2.0 - 0.35);
        capMask *= stormWeight;

        vec3 light = ambientColor * ambientStrength + lightColor * lightStrength;
        vec3 capColor = srgbToLinear(vec3(0.92, 0.95, 0.97)) * light;

        float capAlpha = capMask * 0.5;
        dst.rgb = capColor * capAlpha + dst.rgb * dst.a * (1 - capAlpha);
        dst.a = capAlpha + dst.a * (1 - capAlpha);
        dst.rgb /= max(dst.a, 1e-4);
    }

    return dst;
}
#endif