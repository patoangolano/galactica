#version 330
#extension GL_ARB_separate_shader_objects : require

// Plasma embers and sparks (Galactica M7): a 64x64 soft sprite sampled LINEAR, additive.
// Inputs match vanilla particle.vsh (locations 0-3). The sprite's alpha is squared-out so the falloff
// reads linear-light smooth instead of a hard disc.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    float core = tex.a * tex.a;
    vec4 color = vec4(mix(vertexColor.rgb, vec3(1.0, 0.97, 0.9), core * 0.6), tex.a * vertexColor.a) * ColorModulator;
    color.a *= 1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    if (color.a < 0.004) {
        discard;
    }
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
  #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
  #endif
    fragColor = color;
#endif
}
