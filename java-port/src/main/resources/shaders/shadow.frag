#version 330 core

in vec2 vUv;

uniform sampler2D uTex;

void main() {
    // Depth-only pass with cutout alpha.
    // This ensures vegetation like tall grass doesn't cast full-quad shadows.
    float a = texture(uTex, vUv).a;
    if (a < 0.5) discard;
}
