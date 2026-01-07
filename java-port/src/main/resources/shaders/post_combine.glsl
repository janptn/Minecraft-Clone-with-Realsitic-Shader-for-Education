#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uScene;   // HDR
uniform sampler2D uBloom;   // HDR half-res
uniform sampler2D uGodrays; // HDR quarter-res (stored as grayscale in rgb)

uniform int uBloomEnabled;
uniform float uBloomStrength;

uniform int uGodraysEnabled;
uniform float uGodraysStrength;

void main() {
    vec3 scene = texture(uScene, vUv).rgb;

    vec3 bloom = (uBloomEnabled != 0) ? texture(uBloom, vUv).rgb : vec3(0.0);
    vec3 rays  = (uGodraysEnabled != 0) ? texture(uGodrays, vUv).rgb : vec3(0.0);

    vec3 hdr = scene + bloom * uBloomStrength + rays * uGodraysStrength;
    FragColor = vec4(hdr, 1.0);
}
