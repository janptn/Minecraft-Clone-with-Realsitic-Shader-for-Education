#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uScene;      // HDR scene
uniform float uThreshold;      // ~1.0

void main() {
    vec3 c = texture(uScene, vUv).rgb;

    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float m = max(lum - uThreshold, 0.0);

    // Keep bright colors, suppress the rest.
    vec3 outC = (lum > 1e-4) ? (c * (m / lum)) : vec3(0.0);
    FragColor = vec4(outC, 1.0);
}
