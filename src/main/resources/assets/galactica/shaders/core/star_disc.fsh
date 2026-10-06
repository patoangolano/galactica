#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <galactica:galactica/star_params.glsl>
#include <galactica:galactica/noise.glsl>

// Resolved star: photosphere (limb darkening, granulation, supergranular network, spots with
// umbra/penumbra and the Wilson effect, faculae, flares), chromosphere and spicules, limb
// prominences, K-corona with streamers, and a bloom halo. Output is premultiplied alpha:
// the disc occludes what is behind it (alpha = coverage), everything outside adds light (alpha 0).
// Physics and citations live in dev.galactica.client.vfx.star.StarSurfaceModel.

layout(location = 0) in vec2 quadUv;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

const float PI = 3.14159265;
const float TAU = 6.28318531;
// Limb darkening is stronger at shorter wavelengths (Neckel & Labs 1994). Matches StarSurfaceModel.LIMB_CHROMA.
const vec3 LIMB_CHROMA = vec3(0.80, 1.00, 1.30);
// Share of granulation contrast kept after tone mapping. Matches StarSurfaceModel.DETAIL_KEEP.
const float DETAIL_KEEP = 0.85;
// H-alpha 656.3 nm in linear sRGB, the colour of chromosphere and prominences.
const vec3 H_ALPHA = vec3(1.0, 0.16, 0.20);

vec3 tonemap(vec3 l) {
    return 1.0 - exp(-max(l, vec3(0.0)) * StarLimb.w);
}

vec3 encodeSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(12.92 * c, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

// Local tone mapping: large-scale profile through the filmic curve, small-scale detail reapplied
// multiplicatively so the 15-20 % solar granulation stays visible. Mirrors StarSurfaceModel.displayDisc.
vec3 displayDisc(vec3 base, float detail) {
    return clamp(encodeSrgb(tonemap(base)) * (1.0 + DETAIL_KEEP * (detail - 1.0)), 0.0, 1.0);
}

// Differential rotation (Snodgrass & Ulrich 1990): Omega = A (1 - 0.162 sin^2 lat - 0.121 sin^4 lat).
float rotationAngle(float sinLat, float t) {
    float s2 = sinLat * sinLat;
    return StarGeom.w * t * (1.0 - 0.162 * s2 - 0.121 * s2 * s2);
}

// View-frame normal (z toward the viewer, y = stellar north) to the co-rotating surface frame.
vec3 toSurface(vec3 n, float t) {
    float a = rotationAngle(n.y, t);
    float c = cos(a);
    float s = sin(a);
    return vec3(c * n.x - s * n.z, n.y, s * n.x + c * n.z);
}

vec3 onSphere(float lat, float lon) {
    float cl = cos(lat);
    return vec3(cl * sin(lon), sin(lat), cl * cos(lon));
}

// Flare state for M dwarfs: xyz = site on the visible hemisphere, w = light curve (fast rise,
// exponential decay, as in white-light flares on UV Ceti stars; Hawley et al. 2014).
vec4 flareState(float t, float seed) {
    if (StarActive.z <= 0.0) return vec4(0.0, 0.0, 1.0, 0.0);
    float window = 20.0;
    float k = floor(t / window);
    if (gx_hash13(vec3(k, seed, 3.1)) > StarActive.z * window / 60.0) return vec4(0.0, 0.0, 1.0, 0.0);
    float t0 = (t - k * window) - gx_hash13(vec3(k, seed, 7.7)) * window * 0.3;
    if (t0 <= 0.0) return vec4(0.0, 0.0, 1.0, 0.0);
    vec3 h = gx_hash33(vec3(k, seed, 1.3)) * 2.0 - 1.0;
    float profile = (1.0 - exp(-t0 / 0.8)) * exp(-t0 / 4.0);
    return vec4(onSphere(asin(h.y * 0.8), h.x * 1.2), profile);
}

void main() {
    float t = StarGeom.z;
    float seed = StarActive.w;
    float angR = max(StarGeom.x, 1e-7);
    float len = length(quadUv);
    // Gnomonic quad: a point at quadUv lies atan(|uv| tan(half)) from the star's centre.
    float r = atan(len * StarGeom.y) / angR;              // in stellar radii
    float rMax = atan(StarGeom.y) / angR;                  // quad edge, stellar radii
    float pr = max(fwidth(r), 1e-5);                       // stellar radii per pixel
    vec2 dir2 = len > 1e-6 ? quadUv / len : vec2(1.0, 0.0);
    bool smoothSurface = StarLimb.z > 0.5;
    vec4 flare = flareState(t, seed);

    float cover = 1.0 - smoothstep(1.0 - pr, 1.0 + pr, r);
    vec3 disc = vec3(0.0);
    if (cover > 0.0) {
        float rr = min(r, 0.9995);
        vec3 n = vec3(dir2 * rr, sqrt(1.0 - rr * rr));
        float mu = n.z;
        float x = 1.0 - mu;
        vec3 a = StarLimb.x * LIMB_CHROMA;
        vec3 b = StarLimb.y * LIMB_CHROMA;
        vec3 limb = max(vec3(0.03), 1.0 - a * x - b * x * x);
        float detail = 1.0;
        float bright = 1.0;
        vec3 tint = vec3(1.0);

        if (!smoothSurface) {
            vec3 q = toSurface(n, t);
            vec3 qs = q + vec3(seed * 1.7, seed * 0.3, seed * 2.9);

            // Granulation: Worley cells of the size set by the pressure scale height. Cells look
            // smaller toward the limb (foreshortening by mu), so they fade out there first.
            float cells = StarGran.x;
            float lod = 1.0 - smoothstep(0.18, 0.45, cells * pr / max(mu, 0.15));
            if (lod > 0.0 && StarGran.z > 0.0) {
                float phase = TAU * t / StarGran.y;
                vec3 gp = qs * cells + 0.12 * vec3(gx_simplex3(qs * cells * 0.5 + vec3(0.0, 0.0, t * 0.01)));
                vec3 w = gx_worley3(gp, phase);
                float lane = smoothstep(0.0, 0.22, w.y - w.x);       // dark intergranular lanes
                float life = 0.65 + 0.35 * sin(phase + TAU * w.z);   // each granule brightens then fades
                float g = lane * (1.0 - 0.35 * w.x) * life;          // hot upflow brightest at the centre
                float contrast = StarGran.z * (0.35 + 0.65 * mu);    // contrast drops toward the limb
                detail = mix(1.0, max(0.2, 1.0 + contrast * (g - 0.45) / 0.22), lod);
            }

            // Supergranulation: the magnetic network outlines ~30-granule cells and brightens toward the limb.
            float superLod = 1.0 - smoothstep(0.25, 0.6, StarGran.w * pr / max(mu, 0.15));
            if (superLod > 0.0) {
                vec3 sw = gx_worley3(qs * StarGran.w + 31.0, TAU * t / (StarGran.y * 24.0));
                float network = 1.0 - smoothstep(0.0, 0.10, sw.y - sw.x);
                bright *= 1.0 + superLod * (0.03 + 0.04 * min(StarActive.y, 2.0)) * network * (0.4 + 0.6 * x);
            }

            // Starspots: umbra (T about 0.7 Teff, I about 0.2) inside a filamentary penumbra (I about 0.75).
            float facula = 0.0;
            for (int i = 0; i < 8; i++) {
                vec4 sp = StarSpots[i];
                if (sp.z <= 0.0) continue;
                float sl = sin(sp.x);
                vec3 c = onSphere(sp.x, sp.y + rotationAngle(sl, t));
                float d = acos(clamp(dot(n, c), -1.0, 1.0));
                facula += exp(-pow(d / (sp.z * 3.0), 2.0));
                if (d > sp.z * 1.3) continue;
                // Wilson effect: the umbra sits ~0.5 Mm deep, so near the limb it shows displaced
                // toward disc centre and the centre-side penumbra looks narrower.
                vec3 toCentre = vec3(0.0, 0.0, 1.0) - c * c.z;
                vec3 cu = normalize(c + toCentre * 0.35 * sp.z * sp.w * 2.0);
                float du = acos(clamp(dot(n, cu), -1.0, 1.0));
                vec3 e1 = normalize(cross(vec3(0.0, 1.0, 0.0), c) + vec3(1e-5, 0.0, 0.0));
                vec3 e2 = cross(c, e1);
                float az = atan(dot(n - c, e2), dot(n - c, e1));
                float fil = 0.85 + 0.15 * gx_simplex3(vec3(cos(az) * 7.0, sin(az) * 7.0, d / sp.z * 1.5 + float(i) * 7.0 + t * 0.02));
                float pen = 1.0 - smoothstep(sp.z * 0.9, sp.z * 1.05, d);
                float umb = 1.0 - smoothstep(sp.z * sp.w * 0.85, sp.z * sp.w * 1.1, du);
                float spotI = mix(mix(1.0, 0.72 * fil, pen), 0.18, umb);
                bright *= spotI;
                tint *= mix(mix(vec3(1.0), vec3(1.0, 0.93, 0.86), pen * 0.6), vec3(1.0, 0.78, 0.6), umb);
                detail = mix(detail, 1.0, pen * 0.85);
            }

            // Faculae: bright plage around active regions and in the activity belts, almost invisible
            // at disc centre and up to ~20 % brighter close to the limb.
            float belt = exp(-pow((abs(q.y) - 0.35) / 0.15, 2.0)) * smoothstep(0.55, 0.85, 0.5 + 0.5 * gx_fbm3(q * 6.0 + seed));
            float plage = min(1.0, facula + 0.3 * min(StarActive.y, 2.0) * belt) * (0.5 + 0.5 * gx_fbm3(q * cells * 0.25 + 5.0));
            bright *= 1.0 + 0.3 * StarActive.x * plage * pow(x, 1.2);

            // Flare kernel.
            if (flare.w > 0.0) {
                float df = acos(clamp(dot(n, flare.xyz), -1.0, 1.0));
                bright += 4.0 * flare.w * exp(-df * df / 0.006);
            }
        }
        vec3 base = StarColor.rgb * limb * bright * tint;
        disc = displayDisc(base, detail);
    }

    // ---- everything above the limb adds light
    vec3 emit = vec3(0.0);
    float h = r - 1.0;
    float theta = atan(dir2.y, dir2.x);
    if (!smoothSurface && h > -pr) {
        float hp = max(h, 0.0);
        // Chromosphere (~2 Mm, drawn ~4x thicker) and spicules (jets to ~10 Mm, de Pontieu et al. 2007);
        // only once the disc is big enough on screen to show sub-percent heights.
        float fine = 1.0 - smoothstep(0.004, 0.02, pr);
        if (fine > 0.0) {
            float chromo = StarChromo.x * exp(-hp / 0.006);
            float sp = gx_simplex3(vec3(cos(theta) * 90.0, sin(theta) * 90.0, t * 0.25 + seed));
            float spH = 0.010 + 0.010 * (0.5 + 0.5 * sp);
            float spic = StarChromo.y * 0.6 * (1.0 - smoothstep(spH * 0.6, spH, hp));
            emit += H_ALPHA * 0.12 * (chromo + spic) * fine;
        }
        // Prominences: active-region loops and quiescent hedgerows standing on the limb, slowly churning.
        float promLod = 1.0 - smoothstep(0.01, 0.04, pr);
        if (promLod > 0.0) {
            float prom = 0.0;
            for (int k = 0; k < 4; k++) {
                if (float(k) >= StarChromo.w) break;
                vec3 hk = gx_hash33(vec3(float(k) * 13.1, seed, 5.0));
                float halfW = 0.05 + 0.08 * hk.y;
                float height = 0.04 + 0.10 * hk.z;
                float dth = mod(theta - hk.x * TAU + PI, TAU) - PI;
                float u = dth / halfW;
                if (abs(u) > 1.4) continue;
                float v = hp / height;
                float turb = 0.5 + 0.5 * gx_fbm3(vec3(u * 3.0, v * 5.0 - t * 0.04, float(k) * 7.0 + seed));
                float shape;
                if (hk.y < 0.5) {
                    float arch = sqrt(max(0.0, 1.0 - u * u));
                    shape = exp(-pow((v - arch) / 0.16, 2.0)) * (1.0 - smoothstep(0.95, 1.1, abs(u)));
                } else {
                    shape = (1.0 - smoothstep(0.2, 1.0, v)) * (1.0 - smoothstep(0.6, 1.1, abs(u)));
                }
                prom += shape * turb * turb;
            }
            emit += H_ALPHA * 0.25 * StarChromo.z * prom * promLod * step(0.0, h);
        }
        // K-corona: photospheric light Thomson-scattered by free electrons, so it keeps the star's
        // colour. Brightness falls as r^-n (Baumbach 1937); helmet streamers hug the equator at
        // activity minimum and appear at all position angles at maximum.
        float rc = max(r, 1.0);
        float rays = 0.5 + 0.5 * gx_simplex3(vec3(cos(theta) * StarCorona.z * 0.8, sin(theta) * StarCorona.z * 0.8, rc * 0.35 - t * 0.004 + seed));
        float equatorial = mix(1.0 - smoothstep(0.3, 0.9, abs(dir2.y)), 1.0, clamp(StarActive.y, 0.0, 1.0));
        emit += StarColor.rgb * StarCorona.x * 0.10 * pow(rc, -StarCorona.y) * (0.35 + 0.9 * rays * rays * equatorial);
    }
    // Bloom halo, scaled to the quad so even a few-pixel disc glows like a camera PSF.
    float xh = clamp((r - 1.0) / max(rMax - 1.0, 1e-3), 0.0, 1.0);
    vec3 flareGlow = vec3(0.55, 0.65, 1.0) * 0.4 * flare.w;
    emit += (StarColor.rgb + flareGlow) * StarCorona.w * (0.22 * exp(-xh * 9.0) + 0.06 * exp(-xh * 3.0));

    float edgeFade = 1.0 - smoothstep(0.75, 1.0, len);
    emit *= edgeFade * (1.0 - 0.9 * cover);
    vec3 emitDisplay = encodeSrgb(tonemap(emit));

    float intensity = StarColor.a;
    vec3 tintIn = vertexColor.rgb * ColorModulator.rgb;
    fragColor = vec4((disc * cover + emitDisplay) * intensity * tintIn, cover * intensity);
}
