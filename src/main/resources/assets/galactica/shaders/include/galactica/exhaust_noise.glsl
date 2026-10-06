#ifndef GALACTICA_EXHAUST_NOISE_GLSL
#define GALACTICA_EXHAUST_NOISE_GLSL

// Hash-based 3D value noise and fBm for the exhaust field. Float-only (no integer bit tricks), so it survives
// shaderc -> SPIR-V -> SPIRV-Cross GLSL 330 unchanged on every backend.

float exhaust_hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.11, 0.17, 0.13));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float exhaust_noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(exhaust_hash(i + vec3(0.0, 0.0, 0.0)), exhaust_hash(i + vec3(1.0, 0.0, 0.0)), f.x),
                   mix(exhaust_hash(i + vec3(0.0, 1.0, 0.0)), exhaust_hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(exhaust_hash(i + vec3(0.0, 0.0, 1.0)), exhaust_hash(i + vec3(1.0, 0.0, 1.0)), f.x),
                   mix(exhaust_hash(i + vec3(0.0, 1.0, 1.0)), exhaust_hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}

// Four octaves: turbulence of a free jet has energy over a decade of scales (Kolmogorov cascade); more octaves
// would be sub-pixel at plume sizes of a few blocks.
float exhaust_fbm(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * exhaust_noise(p);
        p = p * 2.07 + vec3(1.7, 9.2, 3.1);
        amp *= 0.5;
    }
    return sum;
}

// Emission colour ramp of a hydrogen-oxygen flame, 0 = cold edge .. 1 = hottest core. Near the nozzle the
// exhaust glows by line emission (OH* 306 nm, H2O bands) and the shock-heated core reads white-blue; downstream
// the cooling gas is seen by its thermal continuum, orange then dull red, then nothing.
vec3 exhaust_ramp(float heat) {
    vec3 ember = vec3(0.55, 0.12, 0.02);
    vec3 orange = vec3(1.0, 0.45, 0.10);
    vec3 white = vec3(0.92, 0.95, 1.0);
    vec3 blue = vec3(0.55, 0.75, 1.0);
    float h = clamp(heat, 0.0, 1.0);
    vec3 c = mix(ember, orange, smoothstep(0.0, 0.35, h));
    c = mix(c, white, smoothstep(0.35, 0.75, h));
    return mix(c, blue, smoothstep(0.75, 1.0, h) * 0.6);
}

#endif
