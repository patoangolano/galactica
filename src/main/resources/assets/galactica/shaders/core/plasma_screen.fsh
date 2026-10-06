#version 330
#extension GL_ARB_separate_shader_objects : require

// Screen-space half of the seamless transfer (Galactica M7), drawn after the level with vanilla screenquad.vsh.
//   InSampler       = copy of the last frame before the dimension switch
//   ColorModulator  = (vignette 0..1, normalised temperature 0..1, cross-fade alpha 0..1, mode: 0 plasma / 1 haze)
//   TextureMat[0]   = (seconds, flow centre x, flow centre y, plasma streak speed) ; flow centre in 0..1 screen space
// Output is premultiplied: rgb is added over the scene and alpha hides it, so the glow and the opacity of the
// mask come from one blend (TRANSLUCENT_PREMULTIPLIED_ALPHA). Every term is smooth: no stepping, no texels.

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

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

float fbm(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int k = 0; k < 5; k++) {
        s += a * vnoise(p);
        p = p * 2.02 + vec2(17.3, 9.1);
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
    float vignette = ColorModulator.x;
    float tn = ColorModulator.y;
    float fade = ColorModulator.z;
    float haze = ColorModulator.w;
    vec4 p0 = TextureMat[0];
    float t = p0.x;
    vec2 centre = p0.yz;
    float streakSpeed = p0.w;

    vec2 aspect = vec2(ScreenSize.x / max(ScreenSize.y, 1.0), 1.0);
    vec2 c = (texCoord - centre) * aspect;
    float r = length(c);
    float ang = atan(c.y, c.x);

    // Flow lines radiating from the flow centre, streaming outwards (the gas passes the camera).
    float a1 = ang * 3.5;
    float streaks = fbm(vec2(cos(ang) * 6.0 + sin(ang) * 2.0, log(r + 0.05) * 2.4 - t * streakSpeed))
                  * 0.65 + fbm(vec2(a1, r * 5.0 - t * streakSpeed * 1.7)) * 0.45;
    float edge = smoothstep(0.35, 1.25, r + 0.28 * (streaks - 0.5));
    float glow = edge * vignette;

    vec3 plasma = plasmaColour(clamp(tn - (1.0 - edge) * 0.25, 0.0, 1.0));
    vec3 hazeColour = vec3(0.86, 0.9, 0.96);
    vec3 maskColour = mix(plasma, hazeColour, haze);

    // Near the switch the whole frame veils over, centre included, so the cut is never visible.
    float veil = smoothstep(0.55, 0.95, vignette) * (0.55 + 0.45 * edge) * mix(0.75 + 0.5 * streaks, 1.0, haze);
    float alpha = clamp(max(glow * mix(0.55, 0.35, haze), veil * 0.92), 0.0, 0.97);
    vec3 add = maskColour * (glow * mix(1.6, 0.9, haze) + veil);

    // Previous frame under the mask (cross-fade), premultiplied.
    vec3 previous = texture(InSampler, texCoord).rgb * fade;
    vec3 rgb = add + previous * (1.0 - alpha);
    float outAlpha = alpha + fade * (1.0 - alpha);
    fragColor = vec4(rgb, clamp(outAlpha, 0.0, 1.0));
}
