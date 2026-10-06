#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Geometry from ExhaustRenderer (POSITION_TEX_COLOR, camera-relative):
//   UV0.x  4*engine + local, local = along (0 at the nozzle exit .. 1 at the tail) for planes, -1..1 for discs
//   UV0.y  across, -1..1 from one plume edge to the other; for discs: -1..1
//   Color  r = throttle, g = shock cells / 16, b = kind / 4, a = weight (crossed-plane share x brightness)
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 plumeUv;
layout(location = 1) out vec4 plumeParams;
layout(location = 2) out float sphericalVertexDistance;
layout(location = 3) out float cylindricalVertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    plumeUv = UV0;
    plumeParams = Color;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
