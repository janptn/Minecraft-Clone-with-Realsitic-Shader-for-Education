#version 330 core

in vec3 vWorldPos;

uniform vec3 uLightPos;
uniform float uFar;

void main() {
    float dist = length(vWorldPos - uLightPos);
    float d = dist / max(uFar, 0.0001);
    // Write linear depth into the depth cubemap.
    gl_FragDepth = clamp(d, 0.0, 1.0);
}
