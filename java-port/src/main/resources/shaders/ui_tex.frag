#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uTex;
uniform float uAlpha;

void main() {
    vec4 c = texture(uTex, vUv);
    FragColor = vec4(c.rgb, c.a * uAlpha);
}
