#version 330 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uTex;
uniform vec2 uResolution;

uniform int uFxaaEnabled;
uniform float uFxaaSpan;
uniform float uVignette;
uniform float uSaturation;
uniform float uContrast;

vec3 applyVignette(vec3 c, vec2 uv, float strength) {
    vec2 p = uv * 2.0 - 1.0;
    float d = dot(p, p);
    float v = smoothstep(0.25, 1.25, d);
    return mix(c, c * (1.0 - strength), v);
}

vec3 applySaturation(vec3 c, float sat) {
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    return mix(vec3(l), c, sat);
}

vec3 applyContrast(vec3 c, float contrast) {
    return (c - 0.5) * contrast + 0.5;
}

// Very small FXAA-ish pass (good enough for voxel edges).
vec3 fxaa(sampler2D tex, vec2 uv, vec2 res) {
    vec2 px = 1.0 / res;

    vec3 rgbNW = texture(tex, uv + vec2(-1.0, -1.0) * px).rgb;
    vec3 rgbNE = texture(tex, uv + vec2( 1.0, -1.0) * px).rgb;
    vec3 rgbSW = texture(tex, uv + vec2(-1.0,  1.0) * px).rgb;
    vec3 rgbSE = texture(tex, uv + vec2( 1.0,  1.0) * px).rgb;
    vec3 rgbM  = texture(tex, uv).rgb;

    float lumaNW = dot(rgbNW, vec3(0.299, 0.587, 0.114));
    float lumaNE = dot(rgbNE, vec3(0.299, 0.587, 0.114));
    float lumaSW = dot(rgbSW, vec3(0.299, 0.587, 0.114));
    float lumaSE = dot(rgbSE, vec3(0.299, 0.587, 0.114));
    float lumaM  = dot(rgbM,  vec3(0.299, 0.587, 0.114));

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * 0.5), 1.0 / 128.0);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    float span = max(uFxaaSpan, 2.0);
    dir = clamp(dir * rcpDirMin, vec2(-span), vec2(span)) * px;

    vec3 rgbA = 0.5 * (
        texture(tex, uv + dir * (1.0 / 3.0 - 0.5)).rgb +
        texture(tex, uv + dir * (2.0 / 3.0 - 0.5)).rgb
    );
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
        texture(tex, uv + dir * -0.5).rgb +
        texture(tex, uv + dir * 0.5).rgb
    );

    float lumaB = dot(rgbB, vec3(0.299, 0.587, 0.114));
    if (lumaB < lumaMin || lumaB > lumaMax) return rgbA;
    return rgbB;
}

void main() {
    vec3 col = texture(uTex, vUv).rgb;

    if (uFxaaEnabled != 0) {
        col = fxaa(uTex, vUv, uResolution);
    }

    col = applyContrast(col, uContrast);
    col = applySaturation(col, uSaturation);
    col = applyVignette(col, vUv, uVignette);

    FragColor = vec4(col, 1.0);
}
