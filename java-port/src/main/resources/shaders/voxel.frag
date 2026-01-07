#version 330 core

in vec2 vUv;
in vec3 vWorldPos;
in vec3 vNormal;
in float vViewZ;
in vec4 vShadowPos;
in float vSky;
in float vEmissive;

out vec4 FragColor;

uniform sampler2D uTex;
uniform int uPixelSnap; // 0/1

// Simple directional sunlight
uniform vec3 uLightDir;   // direction the light rays travel (sun -> world), normalized
uniform float uAmbient;   // 0..1
uniform float uSun;       // 0..2

// Shadows
uniform sampler2DShadow uShadowMap;
uniform int uShadowsEnabled;
uniform float uShadowStrength; // 0..1
uniform float uShadowSoftness; // 0.5..4

// Extra multiplier for skylight. Chunks use 1.0; entities can reduce this.
uniform float uSkyMul; // 0..1

// Torch/point lights (simple dynamic lights)
uniform int uPointLightCount;
uniform vec3 uPointLightPos[32];
uniform float uPointLightStrength[32];
uniform float uPointLightRadius[32];

// Point-light shadows (cubemap). We apply this to a single torch light for performance.
uniform int uPointShadowsEnabled;
uniform samplerCube uPointShadowMap;
uniform int uPointShadowIndex;
uniform vec3 uPointShadowPos;
uniform float uPointShadowFar;
uniform float uPointShadowStrength; // 0..1

float pointShadowPCF(vec3 worldPos, vec3 n) {
    vec3 toFrag = worldPos - uPointShadowPos;
    float dist = length(toFrag);
    float farP = max(uPointShadowFar, 0.0001);
    float cur = dist / farP;
    if (cur >= 1.0) return 1.0;

    vec3 ldir = (dist > 0.0001) ? ((uPointShadowPos - worldPos) / dist) : vec3(0.0, 1.0, 0.0);
    float ndl = max(dot(n, ldir), 0.0);

    // Bias in *normalized* depth units.
    float bias = (0.05 + 0.18 * (1.0 - ndl)) / farP;

    // Small PCF kernel around the direction.
    vec3 dir = toFrag;
    float disk = 0.020 + 0.030 * cur; // more blur further away

    float sum = 0.0;
    int count = 0;

    vec3 offs[8] = vec3[8](
        vec3( 1,  1,  1), vec3(-1,  1,  1), vec3( 1, -1,  1), vec3(-1, -1,  1),
        vec3( 1,  1, -1), vec3(-1,  1, -1), vec3( 1, -1, -1), vec3(-1, -1, -1)
    );

    for (int i = 0; i < 8; i++) {
        float closest = texture(uPointShadowMap, dir + offs[i] * disk).r;
        sum += ((cur - bias) > closest) ? 0.0 : 1.0;
        count++;
    }

    return sum / float(count);
}

float shadowPCF(vec4 shadowPos, vec3 n) {
    // Project to [0,1]
    vec3 proj = shadowPos.xyz / max(shadowPos.w, 0.00001);
    proj = proj * 0.5 + 0.5;

    // Outside shadow map => lit.
    if (proj.z > 1.0) return 1.0;
    if (proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0) return 1.0;

    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    float radius = max(uShadowSoftness, 0.5);

    // Use a bigger kernel for softer shadows (helps jaggy edges).
    int taps = (radius > 2.0) ? 2 : 1; // 1 => 3x3, 2 => 5x5

    // Bias: scale with texel size; strong enough to avoid acne/striping on large flat surfaces.
    vec3 l = normalize(-uLightDir);
    float ndl = max(dot(normalize(n), l), 0.0);
    float texelSz = 1.0 / float(textureSize(uShadowMap, 0).x);
    float bias = max(texelSz * (3.0 * (1.0 - ndl)), texelSz * 1.25);

    float sum = 0.0;
    int count = 0;
    for (int y = -taps; y <= taps; y++) {
        for (int x = -taps; x <= taps; x++) {
            vec2 o = vec2(x, y) * texel * radius;
            sum += texture(uShadowMap, vec3(proj.xy + o, proj.z - bias));
            count++;
        }
    }
    return sum / float(count);
}

// Simple fog (Minecraft-ish feel). Tweak later.
uniform vec3 uFogColor;
uniform float uFogNear;
uniform float uFogFar;

// Simple post controls
uniform float uBrightness; // 1.0 = default
uniform float uGamma;      // 2.2 = default-ish
uniform int uFogEnabled;   // 0/1

void main() {
    vec2 uv = vUv;
    if (uPixelSnap != 0) {
        vec2 ts = vec2(textureSize(uTex, 0));
        uv = (floor(uv * ts) + 0.5) / ts;
    }
    vec4 tex = texture(uTex, uv);
    // Cutout for textures like tall_grass etc.
    if (tex.a < 0.5) discard;

    vec3 n = normalize(vNormal);
    vec3 l = normalize(-uLightDir);
    float ndl = max(dot(n, l), 0.0);

    float sky = clamp(vSky * uSkyMul, 0.0, 1.0);

    float shadow = 1.0;
    if (uShadowsEnabled != 0 && sky > 0.5) {
        shadow = shadowPCF(vShadowPos, n);
    }
    float shadowMix = mix(1.0 - clamp(uShadowStrength, 0.0, 1.0), 1.0, shadow);

    float sunTerm = ndl * uSun * shadowMix * sky;
    float light = clamp(uAmbient + sunTerm, 0.0, 4.0);

    // Simple point lights (e.g. torches). No shadowing, but good enough for caves.
    float point = 0.0;
    int nLights = clamp(uPointLightCount, 0, 32);
    for (int i = 0; i < nLights; i++) {
        vec3 toL = uPointLightPos[i] - vWorldPos;
        float dist = length(toL);
        float r = max(uPointLightRadius[i], 0.001);
        float t = clamp(1.0 - dist / r, 0.0, 1.0);
        // Add some directional shading so the world doesn't look "flat".
        vec3 lp = (dist > 0.0001) ? (toL / dist) : vec3(0.0, 1.0, 0.0);
        float ndlP = max(dot(n, lp), 0.0);

        // Softer falloff + a bit of ambient from the torch.
        float diff = (0.25 + 0.75 * ndlP);

        float sh = 1.0;
        if (uPointShadowsEnabled != 0 && uPointShadowIndex == i) {
            sh = mix(1.0 - clamp(uPointShadowStrength, 0.0, 1.0), 1.0, pointShadowPCF(vWorldPos, n));
        }

        point += uPointLightStrength[i] * (t * t) * diff * sh;
    }
    // Don't double-light emissive surfaces (torch mesh is emissive and sits inside its own light).
    point *= (1.0 - clamp(vEmissive, 0.0, 1.0));
    point = clamp(point, 0.0, 2.0);

    // Emissive: adds unshadowed light to the surface itself (e.g. torches).
    float emissive = max(vEmissive, 0.0);
    vec3 base = tex.rgb * (light + point + emissive);
    base *= uBrightness;

    // Gamma correction (approx). Use max to avoid NaNs.
    base = pow(max(base, vec3(0.0)), vec3(1.0 / max(uGamma, 0.001)));

    float fog = 0.0;
    if (uFogEnabled != 0) {
        fog = clamp((vViewZ - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
    }
    vec3 rgb = mix(base, uFogColor, fog);

    FragColor = vec4(rgb, 1.0);
}
