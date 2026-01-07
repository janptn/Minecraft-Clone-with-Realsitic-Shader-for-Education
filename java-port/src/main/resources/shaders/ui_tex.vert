#version 330 core

layout (location = 0) in vec2 aPos;
layout (location = 1) in vec2 aUv;

uniform vec2 uScreen; // width,height

out vec2 vUv;

void main() {
    // aPos is in pixel space.
    vec2 ndc = vec2(
        (aPos.x / uScreen.x) * 2.0 - 1.0,
        1.0 - (aPos.y / uScreen.y) * 2.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = aUv;
}
