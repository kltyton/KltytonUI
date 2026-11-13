#version 330

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

out vec4 fragColor;

void main() {
    // The mask geometry is rasterised in the parent target's screen space, so
    // the clip layer is sampled at the fragment's own pixel. NEAREST filtering
    // keeps the color and depth lookup aligned with the stored texel instead
    // of blending depth values across the mask edge.
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));
    fragColor = texture(Sampler0, uv);
    gl_FragDepth = texture(Sampler1, uv).r;
}
