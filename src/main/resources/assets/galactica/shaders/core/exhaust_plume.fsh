#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <galactica:galactica/exhaust_noise.glsl>

// Volumetric-looking rocket exhaust. Every fragment of a plume plane ray-marches the chord that its line of sight
// cuts through the round jet and integrates an emissive density field along it: a hot core near the nozzle that
// cools downstream, turbulence advected at a speed that grows with throttle, and, in an atmosphere, the shock
// diamonds of an imperfectly expanded jet (cell count from ExhaustPhysics). Additive blend, depth-tested, no
// depth write. Kinds: 0 plume plane, 1 end-on disc, 2 nozzle glow, 3 RCS plane, 4 RCS disc.

layout(location = 0) in vec2 plumeUv;
layout(location = 1) in vec4 plumeParams;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

const int MARCH_STEPS = 8;

// Seconds since the day started; GameTime is (gameTime % 24000 + partial) / 24000.
float exhaustSeconds() {
    return GameTime * 1200.0;
}

// Emissive density at normalised plume coordinates: along 0..1, radius r 0..1 of the local plume radius.
float plumeDensity(float along, float r, vec3 noisePos, float throttle, float cells) {
    bool vacuum = cells < 0.5;
    float t = exhaustSeconds();
    // Advection: the turbulent eddies move downstream at a fraction of the jet speed, faster at full throttle.
    float flow = t * (1.5 + 5.0 * throttle);
    float turb = exhaust_fbm(noisePos + vec3(-flow, 0.0, 0.0));
    float radial = vacuum ? exp(-r * r * 1.6) : exp(-r * r * 3.2);
    float d = radial * (vacuum ? (0.75 + 0.5 * turb) : (0.45 + 1.1 * turb));
    if (!vacuum) {
        // Shock-cell train: oblique shocks cross the axis once per cell, drawing a diamond in (along, r); a Mach
        // disc brightens the axis where they meet. Cells fade as mixing eats the supersonic core.
        float phase = along * cells;
        float f = fract(phase);
        float edge = abs(1.0 - 2.0 * f) * 0.85;
        float diamond = exp(-pow((r - edge) / 0.13, 2.0));
        float disc = exp(-pow((f - 0.5) / 0.07, 2.0)) * exp(-r * r * 10.0);
        float survive = 1.0 - smoothstep(0.35, 0.85, along);
        d += (0.9 * diamond + 1.6 * disc) * survive * step(phase, cells);
    }
    return d;
}

vec3 plumePlane(float along, float across, float seed, float throttle, float cells) {
    // Line of sight through a round jet at lateral offset s: the chord runs over q in [-h, h], h = sqrt(1 - s^2).
    float h = sqrt(max(0.0, 1.0 - across * across));
    float sum = 0.0;
    float heatSum = 0.0;
    for (int i = 0; i < MARCH_STEPS; i++) {
        float q = ((float(i) + 0.5) / float(MARCH_STEPS) * 2.0 - 1.0) * h;
        float r = sqrt(across * across + q * q);
        vec3 p = vec3(along * 7.0, across * 2.2 + seed * 17.0, q * 2.2);
        float d = plumeDensity(along, r, p, throttle, cells);
        sum += d;
        heatSum += d * (1.0 - r);
    }
    float chord = 2.0 * h / float(MARCH_STEPS);
    float density = sum * chord;
    float coreBias = heatSum / max(sum, 1e-4);
    bool vacuum = cells < 0.5;
    // Temperature falls downstream (expansion and mixing) and outward from the core.
    float heat = exp(-along * (vacuum ? 1.6 : 2.6)) * (0.55 + 0.45 * coreBias) * (0.7 + 0.3 * throttle);
    float tail = 1.0 - smoothstep(vacuum ? 0.45 : 0.6, 1.0, along);
    float nozzleBloom = 1.0 + 1.5 * exp(-along * 18.0);
    vec3 col = exhaust_ramp(heat * 1.15);
    if (vacuum) col = mix(col, vec3(0.6, 0.7, 1.0), 0.35);
    return col * density * tail * nozzleBloom;
}

vec3 plumeDisc(vec2 uv, float seed, float throttle) {
    // Looking down the jet: the whole length integrates onto a bright core with turbulent lobes.
    float rho = length(uv);
    float ang = atan(uv.y, uv.x);
    float t = exhaustSeconds();
    float lobes = exhaust_fbm(vec3(cos(ang) * 2.0 + seed * 7.0, sin(ang) * 2.0, rho * 3.0 - t * (2.0 + 6.0 * throttle)));
    float core = exp(-rho * rho * 9.0);
    float halo = exp(-rho * rho * 2.5) * (0.4 + 0.8 * lobes);
    vec3 col = exhaust_ramp(0.95) * core * 1.6 + exhaust_ramp(0.45 + 0.3 * lobes) * halo * 0.6;
    return col * (1.0 - smoothstep(0.8, 1.0, rho));
}

vec3 nozzleGlow(vec2 uv, float throttle) {
    // Light scattered off the nozzle bell and the hull around it: a tight hot spot inside a broad warm halo.
    float rho2 = dot(uv, uv);
    float hot = exp(-rho2 * 22.0);
    float halo = exp(-rho2 * 4.5);
    vec3 col = exhaust_ramp(0.9) * hot * (0.6 + 0.8 * throttle) + vec3(1.0, 0.5, 0.18) * halo * 0.35;
    return col * (1.0 - smoothstep(0.85, 1.0, sqrt(rho2)));
}

vec3 rcsPlane(float along, float across, float seed) {
    // Cold-gas RCS: no combustion, the puff is condensing propellant lit by the scene, white and short-lived.
    float h = sqrt(max(0.0, 1.0 - across * across));
    float t = exhaustSeconds();
    float turb = exhaust_fbm(vec3(along * 5.0 - t * 9.0, across * 3.0 + seed * 13.0, t * 2.0));
    float d = h * exp(-across * across * 2.0) * (0.5 + turb) * (1.0 - smoothstep(0.2, 1.0, along));
    return vec3(0.82, 0.88, 0.95) * d * 0.55;
}

vec3 rcsDisc(vec2 uv, float seed) {
    float rho2 = dot(uv, uv);
    float t = exhaustSeconds();
    float flick = 0.7 + 0.3 * exhaust_noise(vec3(seed * 5.0, t * 12.0, 0.0));
    return vec3(0.85, 0.9, 1.0) * exp(-rho2 * 6.0) * 0.5 * flick * (1.0 - smoothstep(0.8, 1.0, sqrt(rho2)));
}

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    return color;
}

void main() {
    float throttle = plumeParams.r;
    float cells = plumeParams.g * 16.0;
    int kind = int(floor(plumeParams.b * 4.0 + 0.5));
    float weight = plumeParams.a;
    float seed = floor((plumeUv.x + 1.5) / 4.0);
    float local = plumeUv.x - seed * 4.0;

    vec3 emission;
    if (kind == 0) {
        emission = plumePlane(clamp(local, 0.0, 1.0), plumeUv.y, seed, throttle, cells);
    } else if (kind == 1) {
        emission = plumeDisc(vec2(local, plumeUv.y), seed, throttle);
    } else if (kind == 2) {
        emission = nozzleGlow(vec2(local, plumeUv.y), throttle);
    } else if (kind == 3) {
        emission = rcsPlane(clamp(local, 0.0, 1.0), plumeUv.y, seed);
    } else {
        emission = rcsDisc(vec2(local, plumeUv.y), seed);
    }
    emission *= weight * ColorModulator.rgb;

    // Additive light fades to nothing (not to the fog colour) inside fog.
    float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
                                FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    emission *= 1.0 - fog;
    // A camera parked inside the jet (chase view of a firing engine) would be blinded by every plane at once:
    // fade geometry within a few blocks of the eye.
    emission *= smoothstep(0.75, 3.5, sphericalVertexDistance);
    // Soft shoulder instead of a hard clip, so overlapping planes and the core saturate gracefully.
    emission = vec3(1.0) - exp(-emission * 1.1);

    // Blend is (SRC_ALPHA, ONE): write the emission as colour x coverage so rgb * a == emission up to 1.
    float a = clamp(max(emission.r, max(emission.g, emission.b)), 0.0, 1.0);
    vec4 color = vec4(emission / max(a, 1e-4), a);
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    if (a < 0.002) {
        discard;
    }
    fragColor = calculateFinalColor(color);
    #endif
}
