#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uTex;
uniform vec2 uTexel;      // 1/size
uniform vec2 uDirection;  // (1,0) or (0,1)

void main() {
    // 9-tap separable Gaussian (good quality / cheap).
    vec3 result = texture(uTex, vUv).rgb * 0.227027;

    vec2 o1 = uDirection * uTexel * 1.384615;
    vec2 o2 = uDirection * uTexel * 3.230769;

    result += texture(uTex, vUv + o1).rgb * 0.316216;
    result += texture(uTex, vUv - o1).rgb * 0.316216;
    result += texture(uTex, vUv + o2).rgb * 0.070270;
    result += texture(uTex, vUv - o2).rgb * 0.070270;

    FragColor = vec4(result, 1.0);
}
