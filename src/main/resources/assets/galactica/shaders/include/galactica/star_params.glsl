#ifndef GALACTICA_STAR_PARAMS_GLSL
#define GALACTICA_STAR_PARAMS_GLSL

// Per-star uniform block. Layout must match dev.galactica.client.vfx.star.StarUniforms
// (only vec4 members, so std140 offsets are 16 * slot; 15 slots = 240 bytes).
layout(std140) uniform StarParams {
    vec4 StarColor;     // rgb = blackbody colour (linear sRGB, max 1), a = overall intensity
    vec4 StarLimb;      // x = quadratic limb a, y = b (V band, Claret 2000), z = 1 for smooth remnants, w = exposure
    vec4 StarGran;      // x = granulation cells per radius, y = granule lifetime s, z = rms contrast, w = supergranules per radius
    vec4 StarActive;    // x = faculae, y = activity (Sun = 1), z = flares per minute, w = seed in [0, 1000)
    vec4 StarChromo;    // x = chromosphere, y = spicules, z = prominence brightness, w = prominence count
    vec4 StarCorona;    // x = K-corona at the limb, y = power index n (I ~ r^-n), z = streamer count, w = bloom
    vec4 StarGeom;      // x = disc angular radius rad, y = tan(quad half-angle), z = time s, w = equatorial rotation rad/s
    vec4 StarSpots[8];  // x = latitude rad, y = longitude at t = 0, z = penumbra radius (stellar radii, 0 = unused), w = umbra fraction
};

#endif
