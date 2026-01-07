#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uScene;  // HDR scene
uniform sampler2D uDepth;  // depth texture from scene

uniform mat4 uInvProj;
uniform mat4 uInvView;

uniform int uFogEnabled;
uniform float uFogNear;
uniform float uFogFar;
uniform vec3 uFogColor;

uniform float uCamY;

vec3 reconstructViewPos(vec2 uv, float depth01) {
    float z = depth01 * 2.0 - 1.0;
    vec4 clip = vec4(uv * 2.0 - 1.0, z, 1.0);
    vec4 view = uInvProj * clip;
    return view.xyz / max(view.w, 1e-6);
}

void main() {
    vec3 scene = texture(uScene, vUv).rgb;

    if (uFogEnabled == 0) {
        FragColor = vec4(scene, 1.0);
        return;
    }

    float d01 = texture(uDepth, vUv).r;

    // Sky pixels (no geometry) should stay unfogged.
    if (d01 >= 0.999999) {
        FragColor = vec4(scene, 1.0);
        return;
    }

    vec3 viewPos = reconstructViewPos(vUv, d01);
    vec4 world4 = uInvView * vec4(viewPos, 1.0);
    float worldY = world4.y;

    float dist = length(viewPos);

    float fogNear = max(uFogNear, 0.0);
    float fogFar = max(uFogFar, fogNear + 0.001);

    // Distance fog: keep menu semantics (near/far).
    float fogLin = clamp((dist - fogNear) / (fogFar - fogNear), 0.0, 1.0);

    // Subtle height fog: thicker below the camera.
    float h = max(0.0, uCamY - worldY);
    float heightFog = 1.0 - exp(-h * 0.06);

    float fog = clamp(fogLin + fogLin * 0.35 * heightFog, 0.0, 1.0);

    vec3 col = mix(scene, uFogColor, fog);
    FragColor = vec4(col, 1.0);
}
