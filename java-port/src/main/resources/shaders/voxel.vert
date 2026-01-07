#version 330 core

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aUv;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in float aSky;
layout (location = 4) in float aEmissive;

uniform mat4 uProj;
uniform mat4 uView;
uniform mat4 uModel;
uniform mat4 uLightVP;
uniform float uTime;
uniform float uWindStrength;

out vec2 vUv;
out vec3 vWorldPos;
out vec3 vNormal;
out float vViewZ;
out vec4 vShadowPos;
out float vSky;
out float vEmissive;

void main() {
    vec3 pos = aPos;

    // Wind animation for cutout vegetation (tall grass): we tag these vertices with aEmissive < 0.
    if (aEmissive < 0.0) {
        float y01 = fract(pos.y);
        // Stronger sway near the top.
        float bend = y01 * y01;

        // Stable per-block phase from world position.
        float phase = (pos.x * 12.37 + pos.z * 9.73);
        float t = uTime;
        float gust = sin(t * 1.6 + phase) * 0.6 + sin(t * 2.7 + phase * 1.3) * 0.4;

        vec2 dir = normalize(vec2(
            sin(phase * 0.7 + t * 0.15),
            cos(phase * 0.6 + t * 0.12)
        ));

        float amp = 0.10 * max(uWindStrength, 0.0); // block units; keep subtle
        pos.xz += dir * (gust * amp * bend);
    }

    vec4 viewPos = uView * uModel * vec4(pos, 1.0);
    vViewZ = -viewPos.z;
    gl_Position = uProj * viewPos;

    vUv = aUv;
    vec4 worldPos4 = uModel * vec4(pos, 1.0);
    vWorldPos = worldPos4.xyz;
    // Model is identity for chunks, but keep correct if changed.
    vNormal = mat3(uModel) * aNormal;

    vShadowPos = uLightVP * worldPos4;
    vSky = aSky;
    vEmissive = aEmissive;
}
