#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// 3D map planet, moon or star disc (MapRenderer): a pixel-sized quad (UV0 = corner sign times half-size in pixels)
// shaded as a sphere. Normal is the unit direction to the light in view space; zero for a self-luminous disc.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in vec3 Normal;

layout(location = 0) out vec2 corner;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 light;

void main() {
    vec4 params = TextureMat[0];
    vec2 c = sign(UV0);
    gl_Position = ProjMat * (ModelViewMat * vec4(Position, 1.0));
    gl_Position.xy += c * abs(UV0.x) * 2.0 / params.xy * gl_Position.w;
    corner = c;
    vertexColor = Color * ColorModulator;
    light = Normal;
}
