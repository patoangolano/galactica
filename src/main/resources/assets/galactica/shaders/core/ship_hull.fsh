#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

// Exposure for a sunlit hull: the camera meters on the lit side, so a face square to an Earth-distance sun shows
// the painted albedo times HULL_GAIN (the texture is a dark grey, ~0.3, which at unit gain reads near-black against
// space). The vertex colour carries the shading, 0..1 (ShipLighting.shade); HULL_GAIN is ShipLighting.HULL_GAIN,
// passed in as a shader define. A soft shoulder above SHOULDER compresses highlights by their
// brightest channel, so the orange trim stays orange instead of clipping to yellow.
#ifndef HULL_GAIN
#define HULL_GAIN 2.4
#endif
const float SHOULDER = 0.8;

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec4 overlayColor;
layout(location = 4) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

vec3 shoulder(vec3 c) {
    float m = max(c.r, max(c.g, c.b));
    if (m <= SHOULDER) return c;
    float k = 1.0 - SHOULDER;
    float mapped = SHOULDER + k * (1.0 - exp(-(m - SHOULDER) / k));
    return c * (mapped / m);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color.rgb *= vertexColor.rgb * HULL_GAIN;
    color.a *= vertexColor.a;
    color *= ColorModulator;
    color.rgb = shoulder(color.rgb);
    // Hurt flash (OverlayTexture), as vanilla entities do.
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
