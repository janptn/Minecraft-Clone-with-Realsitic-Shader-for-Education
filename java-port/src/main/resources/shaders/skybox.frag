#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uSky;
uniform mat4 uInvProj;
uniform mat4 uInvViewRot;

const float PI = 3.14159265359;

vec3 sampleEquirect(vec3 dir) {
    dir = normalize(dir);
    float u = atan(dir.z, dir.x) / (2.0 * PI) + 0.5;
    float v = asin(clamp(dir.y, -1.0, 1.0)) / PI + 0.5;
    return texture(uSky, vec2(u, v)).rgb;
}

void main() {
    // Reconstruct a view ray from screen UV.
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 clip = vec4(ndc, 1.0, 1.0);
    vec4 view = uInvProj * clip;
    vec3 dirView = normalize(view.xyz / max(view.w, 0.00001));

    vec3 dirWorld = normalize((uInvViewRot * vec4(dirView, 0.0)).xyz);
    vec3 col = sampleEquirect(dirWorld);

    FragColor = vec4(col, 1.0);
}
