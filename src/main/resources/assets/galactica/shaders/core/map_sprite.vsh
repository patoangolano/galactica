#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// 3D map sprite (MapRenderer): a star, a glow or a deep-sky cloud. Position is the centre in model units, UV0 the
// corner (sign) times the half-size: pixels by default, model units with WORLD_SIZE. TextureMat[0] carries
// (viewport width, viewport height, near-cull distance, unused) in pixels and camera units.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 corner;
layout(location = 1) out vec4 vertexColor;

void main() {
    vec4 params = TextureMat[0];
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    vec2 c = sign(UV0);
    float size = abs(UV0.x);
#ifdef WORLD_SIZE
    view.xy += c * size * length(ModelViewMat[0].xyz);
    gl_Position = ProjMat * view;
#else
    gl_Position = ProjMat * view;
    gl_Position.xy += c * size * 2.0 / params.xy * gl_Position.w;
#endif
    // The focused system's own star is drawn by the system layer, at full precision: hide its copy in the field.
    if (length(view.xyz) < params.z) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    }
    corner = c;
    vertexColor = Color * ColorModulator;
}
