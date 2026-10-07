#version 330
#extension GL_ARB_separate_shader_objects : require

// Galaxy disk layer, additive: the face-on bake times the layer weight, faded to black toward the square's edge so
// the layers read as one soft disk from any angle.
uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 d = texCoord0 * 2.0 - 1.0;
    float edge = 1.0 - smoothstep(0.8, 1.0, length(d));
    vec3 c = texture(Sampler0, texCoord0).rgb;
    fragColor = vec4(c * vertexColor.rgb * (vertexColor.a * edge), 1.0);
}
