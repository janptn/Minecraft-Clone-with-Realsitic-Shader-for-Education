#version 330 core

layout (location = 0) in vec3 aPos;

uniform mat4 uLightVP;
uniform mat4 uModel;

out vec3 vWorldPos;

void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vWorldPos = wp.xyz;
    gl_Position = uLightVP * wp;
}
