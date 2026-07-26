#pragma once

layout(std140) uniform UBOSkybox {
    int skyGradientEnabled;
    vec3 skyZenithColor;
    vec3 skyHorizonColor;
    vec3 skySunColor;
    vec3 skySunDir;

    // Moon uniforms for Day & night Cycle
    vec3 skyMoonDir;
    vec3 skyMoonColor;
    float skyMoonIllumination;

    // Star visibility (from environment override)
    float starVisibility;

    // Nebula visibility (toggled via config)
    float nebulaVisibility;

    // Moon visibility (from environment override)
    float moonVisibility;

    // Aurora visibility (1 on randomly-selected aurora nights, else 0)
    float auroraVisibility;

    // Moon size multiplier (from environment override). Scales the moon disk, its
    // glow, and the star-occlusion mask around it. 1 = default size.
    float moonSizeMult;
};