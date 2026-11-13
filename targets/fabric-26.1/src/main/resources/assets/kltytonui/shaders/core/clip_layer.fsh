#version 330

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

out vec4 fragColor;

void main() {
    // Restore the clipped layer at the destination fragment's screen position.
    // Both attachments share the same dimensions, so texel centres line up.
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));
    fragColor = texture(Sampler0, uv);
    gl_FragDepth = texture(Sampler1, uv).r;
}
