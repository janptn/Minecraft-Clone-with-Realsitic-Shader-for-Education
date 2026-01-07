#version 330 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in float aSky;
layout(location = 4) in float aEmissive;

uniform mat4 uViewProj;
uniform mat4 uModel;
uniform float uTime;

out vec2 vUV;
out vec3 vNormal;
out float vSky;
out vec3 vWorldPos;

void main() {
    // Simple wave: displace Y by normalmap + sin(time + pos.xz)
    vec3 worldPos = (uModel * vec4(aPos, 1.0)).xyz;
    float wave = sin(worldPos.x * 2.0 + uTime * 1.2) * 0.07 + cos(worldPos.z * 2.0 + uTime * 1.5) * 0.07;
    worldPos.y += wave;
    gl_Position = uViewProj * vec4(worldPos, 1.0);
    vUV = aUV;
    vNormal = aNormal;
    vSky = aSky;
    vWorldPos = worldPos;
}
