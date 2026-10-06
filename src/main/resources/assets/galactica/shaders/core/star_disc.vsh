#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Camera-facing star sprite on the sky shell. The placement (shell radius, tangent basis, quad
// half-size) is baked into ModelViewMat by StarDiscRenderer; the vertex buffer is one unit quad.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;     // quad corner in [-1, 1]: x = right, y = up on the sky
layout(location = 2) in vec4 Color;   // white unless a caller tints the quad

layout(location = 0) out vec2 quadUv;
layout(location = 1) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    quadUv = UV0;
    vertexColor = Color;
}
