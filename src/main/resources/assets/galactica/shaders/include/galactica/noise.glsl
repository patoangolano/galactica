#ifndef GALACTICA_NOISE_GLSL
#define GALACTICA_NOISE_GLSL

// Galactica procedural noise library. Float-only (no integer bit tricks) so it survives the
// shaderc -> SPIR-V -> SPIRV-Cross GLSL 330 path of the 26.3 GL backend and runs on Vulkan as is.
//
//  gx_hash13 / gx_hash33  hash without sine (D. Hoskins, MIT), stable across GPUs
//  gx_value3              trilinear value noise in [0, 1]
//  gx_simplex3            3D simplex noise in [-1, 1] (Perlin 2001; Gustavson 2005 formulation,
//                         permutation polynomial of McEwan et al. 2012, J. Graphics Tools 16, 85)
//  gx_fbm3                4-octave fractal Brownian motion of simplex noise, about [-1, 1]
//  gx_worley3             animated cellular noise (Worley 1996): x = F1, y = F2, z = cell id in [0, 1)
//  gx_warp3               domain warping (Quilez 2002): p displaced by a vector fbm field

float gx_hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 gx_hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

float gx_value3(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(gx_hash13(i), gx_hash13(i + vec3(1.0, 0.0, 0.0)), f.x),
                   mix(gx_hash13(i + vec3(0.0, 1.0, 0.0)), gx_hash13(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(gx_hash13(i + vec3(0.0, 0.0, 1.0)), gx_hash13(i + vec3(1.0, 0.0, 1.0)), f.x),
                   mix(gx_hash13(i + vec3(0.0, 1.0, 1.0)), gx_hash13(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}

vec3 gx_mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 gx_mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 gx_permute(vec4 x) { return gx_mod289(((x * 34.0) + 1.0) * x); }
vec4 gx_taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float gx_simplex3(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    // Skew to the simplex grid and find the containing simplex.
    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;
    i = gx_mod289(i);
    vec4 p = gx_permute(gx_permute(gx_permute(
                 i.z + vec4(0.0, i1.z, i2.z, 1.0))
               + i.y + vec4(0.0, i1.y, i2.y, 1.0))
               + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    // Gradients on a 7x7 grid mapped onto an octahedron.
    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = gx_taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x;
    p1 *= norm.y;
    p2 *= norm.z;
    p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

float gx_fbm3(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * gx_simplex3(p);
        p = p * 2.02 + vec3(1.7, 9.2, 3.1);
        amp *= 0.5;
    }
    return sum / 0.9375;
}

// Cellular noise with feature points that orbit their cell centre with the given phase (radians),
// so cells grow, shrink and merge over time instead of sliding. One full phase turn = one cell lifetime.
vec3 gx_worley3(vec3 p, float phase) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    float f1 = 8.0;
    float f2 = 8.0;
    float id = 0.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 o = vec3(float(x), float(y), float(z));
                vec3 h = gx_hash33(cell + o);
                vec3 pt = o + 0.5 + 0.38 * sin(phase + 6.2831853 * h);
                vec3 d = pt - f;
                float dd = dot(d, d);
                if (dd < f1) {
                    f2 = f1;
                    f1 = dd;
                    id = gx_hash13(cell + o + 17.0);
                } else if (dd < f2) {
                    f2 = dd;
                }
            }
        }
    }
    return vec3(sqrt(f1), sqrt(f2), id);
}

vec3 gx_warp3(vec3 p, float amount) {
    vec3 q = vec3(gx_fbm3(p), gx_fbm3(p + vec3(5.2, 1.3, 2.8)), gx_fbm3(p + vec3(9.1, 4.7, 7.3)));
    return p + amount * q;
}

#endif
