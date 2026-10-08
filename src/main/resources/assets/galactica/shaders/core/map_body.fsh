#version 330
#extension GL_ARB_separate_shader_objects : require

// A sphere on a quad: Lambert light from the star with a dim ambient floor, a slight limb darkening, and an
// anti-aliased rim. Translucent blending, so a body covers the field behind it.
layout(location = 0) in vec2 corner;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec3 light;

layout(location = 0) out vec4 fragColor;

void main() {
    float r2 = dot(corner, corner);
    if (r2 > 1.0) {
        discard;
    }
    vec3 n = vec3(corner, sqrt(1.0 - r2));
    float lit;
    if (dot(light, light) < 0.01) {
        lit = 0.75 + 0.25 * n.z; // self-luminous: limb-darkened disc
    } else {
        lit = 0.07 + 0.93 * max(dot(n, normalize(light)), 0.0);
    }
    float rim = 1.0 - smoothstep(0.82, 1.0, sqrt(r2));
    fragColor = vec4(vertexColor.rgb * lit, vertexColor.a * rim);
}
