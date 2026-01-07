#version 330 core

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aUv;

uniform mat4 uLightVP;
uniform mat4 uModel;

out vec2 vUv;

void main() {
    vUv = aUv;
    gl_Position = uLightVP * (uModel * vec4(aPos, 1.0));
}
