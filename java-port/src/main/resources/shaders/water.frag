#version 330 core

in vec2 vUV;
in vec3 vNormal;
in float vSky;
in vec3 vWorldPos;

out vec4 FragColor;

uniform sampler2D uNormalMap;
uniform float uTime;

void main() {
    // Sample normalmap
    float TILING = 6.0;
    vec3 normal;
    if (abs(vNormal.y - 1.0) < 0.01) {
        // Oberseite: Normalmap verwenden
        vec2 blockUv = vWorldPos.xz - floor(vWorldPos.xz);
        vec2 nmUv = blockUv * TILING + vec2(uTime * 0.03, uTime * 0.02);
        vec3 normalSample = texture(uNormalMap, nmUv).rgb;
        normal = normalize(normalSample * 2.0 - 1.0);
    } else {
        // Seiten: Standard-Normal
        normal = normalize(vNormal);
    }

        // Licht: Sonne von schräg oben
        vec3 sunDir = normalize(vec3(0.3, 0.8, 0.4));
        float sunDiffuse = max(dot(normal, sunDir), 0.0);
        float sunSpec = pow(max(dot(reflect(-sunDir, normal), vec3(0,1,0)), 0.0), 32.0);

        // Fresnel-Effekt für Reflexion
        float viewDot = abs(dot(normalize(vNormal), vec3(0,1,0)));
        float fresnel = pow(1.0 - viewDot, 3.0);

        // Wasserfarbe und Reflexion
        vec3 baseColor = mix(vec3(0.10, 0.28, 0.65), vec3(0.22, 0.55, 0.95), fresnel);
        vec3 sunColor = vec3(1.0, 0.98, 0.85);
        vec3 reflectedSky = mix(vec3(0.55, 0.75, 1.0), sunColor, sunSpec * fresnel);
        vec3 color = baseColor * (0.7 + 0.5 * sunDiffuse) + reflectedSky * fresnel * 0.7;

        // Transparenz und Unterwasser-Fog
        float alpha = 0.65 + 0.25 * fresnel;
        float fog = smoothstep(0.0, 1.0, fresnel);
        vec3 fogColor = vec3(0.13, 0.22, 0.38);
        color = mix(color, fogColor, fog * 0.18);

        FragColor = vec4(color, alpha);
        if (FragColor.a < 0.1) discard;
    // Discard if alpha is very low (for edge blending)
    if (FragColor.a < 0.1) discard;
}
