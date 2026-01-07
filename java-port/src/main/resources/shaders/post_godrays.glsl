#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uDepth;
uniform vec2 uSunUv;

uniform int uEnabled;
uniform int uSamples;      // 32..64
uniform float uDensity;    // ~0.8
uniform float uWeight;     // ~0.02
uniform float uDecay;      // ~0.96
uniform float uStrength;   // ~0.5

float skyMask(vec2 uv) {
    float d = texture(uDepth, uv).r;
    return step(0.9995, d); // 1 for sky/far, 0 for geometry
}

void main() {
    if (uEnabled == 0) {
        FragColor = vec4(0.0);
        return;
    }

    vec2 delta = (uSunUv - vUv);

    int samples = clamp(uSamples, 1, 64);
    vec2 stepUv = delta * (uDensity / float(samples));

    vec2 coord = vUv;
    float illum = 1.0;
    float sum = 0.0;

    for (int i = 0; i < 64; i++) {
        if (i >= samples) break;
        coord += stepUv;
        float m = skyMask(coord);
        sum += m * illum * uWeight;
        illum *= uDecay;
    }

    float v = sum * uStrength;
    FragColor = vec4(v, v, v, 1.0);
}
