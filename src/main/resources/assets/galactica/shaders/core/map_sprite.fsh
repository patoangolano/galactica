#version 330
#extension GL_ARB_separate_shader_objects : require

// 3D map sprite, additive: a sharp core with a soft halo for a star (pixel-sized), a broad Gaussian for a cloud
// (WORLD_SIZE). Black outside the unit disc, so overlapping sprites only ever add light.
layout(location = 0) in vec2 corner;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    float r2 = dot(corner, corner);
    if (r2 > 1.0) {
        discard;
    }
#ifdef WORLD_SIZE
    float a = exp(-r2 * 3.5) * (1.0 - r2);
#else
    float a = exp(-r2 * 10.0) + 0.22 * exp(-r2 * 2.6) * (1.0 - r2);
#endif
    fragColor = vec4(vertexColor.rgb * (a * vertexColor.a), 1.0);
}
