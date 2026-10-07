#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// Opaque with cut-outs: the canvas leaves the attitude ball's window transparent, and the ball (drawn just behind
// it with its own texture) shows through. No fog, no lightmap: the screen is its own light source.
uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.5) {
        discard;
    }
    fragColor = vec4(color.rgb * ColorModulator.rgb, 1.0);
}
