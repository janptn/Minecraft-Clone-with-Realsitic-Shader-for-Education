#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uHdr;
uniform float uExposure; // maps from BRIGHT slider
uniform float uGamma;    // maps from GAMMA slider

vec3 acesFilm(vec3 x) {
    // ACES fitted curve
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec3 hdr = texture(uHdr, vUv).rgb;

    float exp = max(uExposure, 0.0);
    hdr *= exp;

    vec3 col = acesFilm(hdr);
    col = pow(max(col, vec3(0.0)), vec3(1.0 / max(uGamma, 0.001)));

    FragColor = vec4(col, 1.0);
}
