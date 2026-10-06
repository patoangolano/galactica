#version 330
#extension GL_ARB_separate_shader_objects : require

// Entry plasma sheath (bow-shock shell) and wake (Galactica M7).
// Vertex colour carries parameters, not a colour:
//   r = plasma intensity 0..1 (Sutton-Graves heat flux, logarithmic)
//   g = normalised wall temperature 0..1 (orange-red -> yellow-white -> white-violet)
//   b = part: 0 = shock shell, 1 = wake
//   a = edge fade of the mesh (0 at the open rim)
// UV: u = azimuth around the flow axis (0..1), v = 0 at the stagnation point, 1 at the far end of the part.
// GameTime * 1200 is seconds (wraps every 20 min; the noise has no visible seam at the wrap speed used).

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 w = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), w.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), w.x), w.y);
}

// Noise that wraps in x with the given period, so the azimuth seam at u = 0/1 is invisible.
float pnoise(vec2 p, float period) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 w = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float x0 = mod(i.x, period);
    float x1 = mod(i.x + 1.0, period);
    return mix(mix(hash12(vec2(x0, i.y)), hash12(vec2(x1, i.y)), w.x),
               mix(hash12(vec2(x0, i.y + 1.0)), hash12(vec2(x1, i.y + 1.0)), w.x), w.y);
}

float fbmWrap(vec2 p, float period) {
    float s = 0.0;
    float a = 0.5;
    for (int k = 0; k < 4; k++) {
        s += a * pnoise(p, period);
        p *= 2.0;
        period *= 2.0;
        a *= 0.5;
    }
    return s;
}

vec3 plasmaColour(float tn) {
    vec3 ember = vec3(1.0, 0.22, 0.04);
    vec3 orange = vec3(1.0, 0.48, 0.12);
    vec3 yellowWhite = vec3(1.0, 0.86, 0.62);
    vec3 white = vec3(1.0, 0.97, 0.95);
    vec3 violet = vec3(0.86, 0.78, 1.0);
    vec3 c = mix(ember, orange, smoothstep(0.0, 0.25, tn));
    c = mix(c, yellowWhite, smoothstep(0.25, 0.55, tn));
    c = mix(c, white, smoothstep(0.55, 0.8, tn));
    return mix(c, violet, smoothstep(0.8, 1.0, tn));
}

void main() {
    float intensity = vertexColor.r;
    float tn = vertexColor.g;
    float wake = step(0.5, vertexColor.b);
    float rim = vertexColor.a;
    float t = GameTime * 1200.0;
    float u = texCoord0.x;
    float v = texCoord0.y;

    // Turbulent flow lines: streaks elongated along the flow, advected downstream (v grows with time).
    float speed = mix(2.2, 1.4, wake);
    float streak = fbmWrap(vec2(u * 28.0, v * 3.0 - t * speed), 28.0);
    float fine = fbmWrap(vec2(u * 64.0 + 7.0, v * 9.0 - t * speed * 2.3), 64.0);
    float lines = pow(clamp(streak * 0.75 + fine * 0.5, 0.0, 1.0), 2.2) * 2.4;

    float shell = exp(-v * 2.6) * 1.15 + 0.25 * (1.0 - v);   // hottest at the stagnation point
    float tail = exp(-v * 3.2) * (1.0 - v);                    // wake glow fades downstream
    float profile = mix(shell, tail, wake);

    float brightness = profile * (0.35 + lines) * rim;
    vec3 colour = plasmaColour(clamp(tn - v * 0.35 * (1.0 + wake), 0.0, 1.0));
    // Hot core near the nose goes white regardless of temperature (optically thick shock layer).
    colour = mix(colour, vec3(1.0, 0.96, 0.92), clamp((1.0 - v * 4.0) * tn, 0.0, 1.0) * (1.0 - wake));

    vec4 color = vec4(colour * (1.0 + intensity), clamp(brightness * intensity, 0.0, 1.0)) * ColorModulator;
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
