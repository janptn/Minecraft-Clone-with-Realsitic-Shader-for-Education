package minecraftlike.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

public final class PostProcessor implements AutoCloseable {
    // Final pass: FXAA + vignette + saturation + contrast (existing shader).
    private final ShaderProgram finalShader;
    // HDR pipeline shaders
    private final ShaderProgram fogShader;
    private final ShaderProgram bloomExtractShader;
    private final ShaderProgram blurShader;
    private final ShaderProgram godraysShader;
    private final ShaderProgram combineShader;
    private final ShaderProgram tonemapShader;

    // Scene (HDR) FBO
    private int sceneFbo;
    private int sceneColor;
    private int sceneDepth;
    private int sceneW;
    private int sceneH;

    // Full-res HDR intermediates
    private int fogFbo;
    private int fogColor;

    private int combineFbo;
    private int combineColor;

    // LDR tonemap output
    private int ldrFbo;
    private int ldrColor;

    // Bloom (half res) ping-pong
    private int bloomW;
    private int bloomH;
    private int bloomFbo;
    private int bloomColor;
    private int pingFbo;
    private int pingColor;
    private int pongFbo;
    private int pongColor;

    // Godrays (quarter res)
    private int raysW;
    private int raysH;
    private int raysFbo;
    private int raysColor;

    private final int vao;
    private final int vbo;

    public PostProcessor() {
        this.finalShader = ShaderProgram.fromResources("shaders/post.vert", "shaders/post.frag");
        this.fogShader = ShaderProgram.fromResources("shaders/post.vert", "shaders/post_fog.glsl");
        this.bloomExtractShader = ShaderProgram.fromResources("shaders/post.vert", "shaders/post_bloom_extract.glsl");
        this.blurShader = ShaderProgram.fromResources("shaders/post.vert", "shaders/post_blur.glsl");
        this.godraysShader = ShaderProgram.fromResources("shaders/post.vert", "shaders/post_godrays.glsl");
        this.combineShader = ShaderProgram.fromResources("shaders/post.vert", "shaders/post_combine.glsl");
        this.tonemapShader = ShaderProgram.fromResources("shaders/post.vert", "shaders/post_tonemap.glsl");

        this.vao = glGenVertexArrays();
        this.vbo = glGenBuffers();

        // Fullscreen quad: pos2, uv2
        float[] verts = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
             1f,  1f, 1f, 1f,

            -1f, -1f, 0f, 0f,
             1f,  1f, 1f, 1f,
            -1f,  1f, 0f, 1f,
        };
        FloatBuffer fb = MemoryUtil.memAllocFloat(verts.length);
        fb.put(verts).flip();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);

        MemoryUtil.memFree(fb);

        this.sceneFbo = 0;
        this.sceneColor = 0;
        this.sceneDepth = 0;
        this.sceneW = 0;
        this.sceneH = 0;

        this.fogFbo = 0;
        this.fogColor = 0;

        this.combineFbo = 0;
        this.combineColor = 0;

        this.ldrFbo = 0;
        this.ldrColor = 0;

        this.bloomW = 0;
        this.bloomH = 0;
        this.bloomFbo = 0;
        this.bloomColor = 0;
        this.pingFbo = 0;
        this.pingColor = 0;
        this.pongFbo = 0;
        this.pongColor = 0;

        this.raysW = 0;
        this.raysH = 0;
        this.raysFbo = 0;
        this.raysColor = 0;
    }

    // PASS A: Scene pass (HDR)
    public void beginScene(int w, int h) {
        ensureSceneSize(w, h);
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glViewport(0, 0, sceneW, sceneH);
    }

    // PASS B/C: Post stack + final output
    public void endAndDraw(
        int screenW,
        int screenH,
        boolean postEnabled,
        boolean fxaa,
        float fxaaSpan,
        float vignette,
        float saturation,
        float contrast,
        boolean fogEnabled,
        float fogNear,
        float fogFar,
        boolean bloomEnabled,
        float bloomThreshold,
        float bloomStrength,
        boolean godraysEnabled,
        int raysSamples,
        float raysStrength,
        float raysDensity,
        float raysWeight,
        float raysDecay,
        float raysEdgeFade,
        float exposure,
        float gamma,
        Matrix4f proj,
        Matrix4f view,
        Vector3f cameraPos,
        Vector3f lightDir
    ) {
        ensureSceneSize(screenW, screenH);
        ensureIntermediates(screenW, screenH);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        // Blur passes are fixed for now (quality/perf) but bloom/rays are tunable via the menu.
        final int blurPasses = 6; // ping-pong (H/V)*3

        // Compute sun position in screen UV + a smooth fade factor.
        // The previous hard "clip.w > 0" validity caused godrays to pop off when turning.
        Vector3f sunDir = new Vector3f(lightDir).negate().normalize(); // towards the sun
        Vector3f sunWorld = new Vector3f(cameraPos).fma(1000.0f, sunDir);

        // PV transform (world -> clip). Use Matrix4f.transform to avoid mul-order confusion.
        Matrix4f pv = new Matrix4f(proj).mul(view);
        Vector4f clip = new Vector4f(sunWorld, 1.0f);
        pv.transform(clip);

        float sunUvX = 0.5f;
        float sunUvY = 0.5f;
        float sunFade = 0.0f;
        if (clip.w != 0.0f) {
            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            sunUvX = ndcX * 0.5f + 0.5f;
            sunUvY = ndcY * 0.5f + 0.5f;

            // Fade out when the sun goes behind the camera.
            // For a standard perspective matrix, clip.w is proportional to -viewPos.z:
            //   clip.w > 0  => in front of camera
            //   clip.w < 0  => behind camera
            // Use a small range to smoothly fade near 90° instead of popping.
            float fadeFront = smoothstep(0.0f, 25.0f, clip.w);

            // Fade out smoothly as it leaves the screen (avoid hard on/off at edges).
            float offX = Math.max(-sunUvX, sunUvX - 1.0f);
            float offY = Math.max(-sunUvY, sunUvY - 1.0f);
            float off = Math.max(offX, offY); // 0 inside, >0 outside
            // Wider range so it doesn't drop to zero abruptly when the sun slides out.
            float edgeFade = Math.max(0.01f, raysEdgeFade);
            float fadeEdge = 1.0f - smoothstep(0.0f, edgeFade, off);

            sunFade = clamp01(fadeFront * fadeEdge);
        }

        // Clamp UV only for sampling (keep fade computed from unclamped UV).
        float sunUvSampleX = clampRange(sunUvX, -0.25f, 1.25f);
        float sunUvSampleY = clampRange(sunUvY, -0.25f, 1.25f);

        // --- Fog pass (HDR, full res) ---
        int fogInputTex = sceneColor;
        if (fogEnabled) {
            glBindFramebuffer(GL_FRAMEBUFFER, fogFbo);
            glViewport(0, 0, sceneW, sceneH);
            glClear(GL_COLOR_BUFFER_BIT);

            fogShader.bind();
            fogShader.setUniform1i("uFogEnabled", 1);
            fogShader.setUniform1f("uFogNear", fogNear);
            fogShader.setUniform1f("uFogFar", fogFar);
            fogShader.setUniform3f("uFogColor", 0.65f, 0.80f, 0.99f);
            fogShader.setUniform1f("uCamY", cameraPos.y);

            Matrix4f invProj = new Matrix4f(proj).invert();
            Matrix4f invView = new Matrix4f(view).invert();
            fogShader.setUniformMat4("uInvProj", invProj);
            fogShader.setUniformMat4("uInvView", invView);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, sceneColor);
            fogShader.setUniform1i("uScene", 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, sceneDepth);
            fogShader.setUniform1i("uDepth", 1);

            drawFullscreen();
            fogInputTex = fogColor;
        }

        // --- Godrays (quarter res) ---
        if (postEnabled && godraysEnabled) {
            glBindFramebuffer(GL_FRAMEBUFFER, raysFbo);
            glViewport(0, 0, raysW, raysH);
            glClear(GL_COLOR_BUFFER_BIT);

            godraysShader.bind();
            godraysShader.setUniform1i("uEnabled", 1);
            godraysShader.setUniform2f("uSunUv", sunUvSampleX, sunUvSampleY);
            godraysShader.setUniform1i("uSamples", raysSamples);
            godraysShader.setUniform1f("uDensity", raysDensity);
            godraysShader.setUniform1f("uWeight", raysWeight);
            godraysShader.setUniform1f("uDecay", raysDecay);
            godraysShader.setUniform1f("uStrength", raysStrength * sunFade);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, sceneDepth);
            godraysShader.setUniform1i("uDepth", 0);

            drawFullscreen();
        } else {
            // Clear rays when disabled.
            glBindFramebuffer(GL_FRAMEBUFFER, raysFbo);
            glViewport(0, 0, raysW, raysH);
            glClearColor(0f, 0f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
        }

        // --- Bloom extract + blur (half res) ---
        if (postEnabled && bloomEnabled) {
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo);
            glViewport(0, 0, bloomW, bloomH);
            glClear(GL_COLOR_BUFFER_BIT);

            bloomExtractShader.bind();
            bloomExtractShader.setUniform1f("uThreshold", bloomThreshold);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, fogInputTex);
            bloomExtractShader.setUniform1i("uScene", 0);
            drawFullscreen();

            // Blur ping-pong
            boolean horizontal = true;
            int input = bloomColor;
            for (int i = 0; i < blurPasses; i++) {
                glBindFramebuffer(GL_FRAMEBUFFER, horizontal ? pingFbo : pongFbo);
                glViewport(0, 0, bloomW, bloomH);
                glClear(GL_COLOR_BUFFER_BIT);

                blurShader.bind();
                blurShader.setUniform2f("uTexel", 1.0f / (float) bloomW, 1.0f / (float) bloomH);
                blurShader.setUniform2f("uDirection", horizontal ? 1f : 0f, horizontal ? 0f : 1f);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, input);
                blurShader.setUniform1i("uTex", 0);
                drawFullscreen();

                input = horizontal ? pingColor : pongColor;
                horizontal = !horizontal;
            }

            // Copy last blur result into bloomColor (so combine always reads bloomColor)
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo);
            glViewport(0, 0, bloomW, bloomH);
            glClear(GL_COLOR_BUFFER_BIT);
            // Reuse blur shader as a simple copy (direction 0 = sample center).
            blurShader.bind();
            blurShader.setUniform2f("uTexel", 1.0f / (float) bloomW, 1.0f / (float) bloomH);
            blurShader.setUniform2f("uDirection", 0f, 0f);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, input);
            blurShader.setUniform1i("uTex", 0);
            drawFullscreen();
        } else {
            // Clear bloom when disabled.
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo);
            glViewport(0, 0, bloomW, bloomH);
            glClearColor(0f, 0f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
        }

        // --- Combine (HDR, full res) ---
        glBindFramebuffer(GL_FRAMEBUFFER, combineFbo);
        glViewport(0, 0, sceneW, sceneH);
        glClear(GL_COLOR_BUFFER_BIT);

        combineShader.bind();
        combineShader.setUniform1i("uBloomEnabled", (postEnabled && bloomEnabled) ? 1 : 0);
        combineShader.setUniform1f("uBloomStrength", bloomStrength);
        combineShader.setUniform1i("uGodraysEnabled", (postEnabled && godraysEnabled) ? 1 : 0);
        combineShader.setUniform1f("uGodraysStrength", 0.75f * sunFade);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fogInputTex);
        combineShader.setUniform1i("uScene", 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, bloomColor);
        combineShader.setUniform1i("uBloom", 1);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, raysColor);
        combineShader.setUniform1i("uGodrays", 2);

        drawFullscreen();

        // --- Tonemap to LDR (full res) ---
        glBindFramebuffer(GL_FRAMEBUFFER, ldrFbo);
        glViewport(0, 0, sceneW, sceneH);
        glClear(GL_COLOR_BUFFER_BIT);

        tonemapShader.bind();
        tonemapShader.setUniform1f("uExposure", exposure);
        tonemapShader.setUniform1f("uGamma", gamma);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, combineColor);
        tonemapShader.setUniform1i("uHdr", 0);
        drawFullscreen();

        // --- Final: FXAA + grading to default framebuffer ---
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, screenW, screenH);

        finalShader.bind();
        int pid = finalShader.id();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, ldrColor);
        int locTex = glGetUniformLocation(pid, "uTex");
        if (locTex >= 0) glUniform1i(locTex, 0);

        int locRes = glGetUniformLocation(pid, "uResolution");
        if (locRes >= 0) glUniform2f(locRes, (float) sceneW, (float) sceneH);

        int locFxaa = glGetUniformLocation(pid, "uFxaaEnabled");
        if (locFxaa >= 0) glUniform1i(locFxaa, (postEnabled && fxaa) ? 1 : 0);

        int locSpan = glGetUniformLocation(pid, "uFxaaSpan");
        if (locSpan >= 0) glUniform1f(locSpan, fxaaSpan);

        int locV = glGetUniformLocation(pid, "uVignette");
        if (locV >= 0) glUniform1f(locV, postEnabled ? vignette : 0.0f);

        int locSat = glGetUniformLocation(pid, "uSaturation");
        if (locSat >= 0) glUniform1f(locSat, postEnabled ? saturation : 1.0f);

        int locCon = glGetUniformLocation(pid, "uContrast");
        if (locCon >= 0) glUniform1f(locCon, postEnabled ? contrast : 1.0f);

        drawFullscreen();

        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private static float clampRange(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge0 == edge1) return x < edge0 ? 0.0f : 1.0f;
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    private void drawFullscreen() {
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
    }

    private static int createColorTex(int w, int h, int internalFormat, int format, int type, boolean linear) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    private static int createDepthTex(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    private static void attachColor(int fbo, int tex) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
    }

    private static void attachDepth(int fbo, int depthTex) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex, 0);
    }

    private static void assertComplete(String name) {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(name + " framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    private void ensureSceneSize(int w, int h) {
        if (w <= 0) w = 1;
        if (h <= 0) h = 1;
        if (sceneFbo != 0 && w == sceneW && h == sceneH) return;

        sceneW = w;
        sceneH = h;

        // Delete old
        if (sceneDepth != 0) glDeleteTextures(sceneDepth);
        if (sceneColor != 0) glDeleteTextures(sceneColor);
        if (sceneFbo == 0) sceneFbo = glGenFramebuffers();

        sceneColor = createColorTex(sceneW, sceneH, GL_RGBA16F, GL_RGBA, GL_FLOAT, true);
        sceneDepth = createDepthTex(sceneW, sceneH);

        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        attachColor(sceneFbo, sceneColor);
        attachDepth(sceneFbo, sceneDepth);
        assertComplete("scene");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void ensureIntermediates(int w, int h) {
        // Full-res HDR intermediates
        if (fogFbo == 0) fogFbo = glGenFramebuffers();
        if (combineFbo == 0) combineFbo = glGenFramebuffers();
        if (ldrFbo == 0) ldrFbo = glGenFramebuffers();

        if (fogColor != 0) glDeleteTextures(fogColor);
        if (combineColor != 0) glDeleteTextures(combineColor);
        if (ldrColor != 0) glDeleteTextures(ldrColor);

        fogColor = createColorTex(sceneW, sceneH, GL_RGBA16F, GL_RGBA, GL_FLOAT, true);
        combineColor = createColorTex(sceneW, sceneH, GL_RGBA16F, GL_RGBA, GL_FLOAT, true);
        ldrColor = createColorTex(sceneW, sceneH, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, true);

        glBindFramebuffer(GL_FRAMEBUFFER, fogFbo);
        attachColor(fogFbo, fogColor);
        assertComplete("fog");

        glBindFramebuffer(GL_FRAMEBUFFER, combineFbo);
        attachColor(combineFbo, combineColor);
        assertComplete("combine");

        glBindFramebuffer(GL_FRAMEBUFFER, ldrFbo);
        attachColor(ldrFbo, ldrColor);
        assertComplete("ldr");

        // Bloom half-res
        int bw = Math.max(1, sceneW / 2);
        int bh = Math.max(1, sceneH / 2);
        if (bw != bloomW || bh != bloomH || bloomFbo == 0) {
            bloomW = bw;
            bloomH = bh;

            if (bloomFbo == 0) bloomFbo = glGenFramebuffers();
            if (pingFbo == 0) pingFbo = glGenFramebuffers();
            if (pongFbo == 0) pongFbo = glGenFramebuffers();

            if (bloomColor != 0) glDeleteTextures(bloomColor);
            if (pingColor != 0) glDeleteTextures(pingColor);
            if (pongColor != 0) glDeleteTextures(pongColor);

            bloomColor = createColorTex(bloomW, bloomH, GL_RGBA16F, GL_RGBA, GL_FLOAT, true);
            pingColor = createColorTex(bloomW, bloomH, GL_RGBA16F, GL_RGBA, GL_FLOAT, true);
            pongColor = createColorTex(bloomW, bloomH, GL_RGBA16F, GL_RGBA, GL_FLOAT, true);

            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo);
            attachColor(bloomFbo, bloomColor);
            assertComplete("bloom");

            glBindFramebuffer(GL_FRAMEBUFFER, pingFbo);
            attachColor(pingFbo, pingColor);
            assertComplete("ping");

            glBindFramebuffer(GL_FRAMEBUFFER, pongFbo);
            attachColor(pongFbo, pongColor);
            assertComplete("pong");
        }

        // Godrays quarter-res
        int rw = Math.max(1, sceneW / 4);
        int rh = Math.max(1, sceneH / 4);
        if (rw != raysW || rh != raysH || raysFbo == 0) {
            raysW = rw;
            raysH = rh;

            if (raysFbo == 0) raysFbo = glGenFramebuffers();
            if (raysColor != 0) glDeleteTextures(raysColor);
            raysColor = createColorTex(raysW, raysH, GL_RGBA16F, GL_RGBA, GL_FLOAT, true);

            glBindFramebuffer(GL_FRAMEBUFFER, raysFbo);
            attachColor(raysFbo, raysColor);
            assertComplete("rays");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    @Override
    public void close() {
        finalShader.close();
        fogShader.close();
        bloomExtractShader.close();
        blurShader.close();
        godraysShader.close();
        combineShader.close();
        tonemapShader.close();

        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);

        if (sceneDepth != 0) glDeleteTextures(sceneDepth);
        if (sceneColor != 0) glDeleteTextures(sceneColor);
        if (sceneFbo != 0) glDeleteFramebuffers(sceneFbo);

        if (fogColor != 0) glDeleteTextures(fogColor);
        if (fogFbo != 0) glDeleteFramebuffers(fogFbo);

        if (combineColor != 0) glDeleteTextures(combineColor);
        if (combineFbo != 0) glDeleteFramebuffers(combineFbo);

        if (ldrColor != 0) glDeleteTextures(ldrColor);
        if (ldrFbo != 0) glDeleteFramebuffers(ldrFbo);

        if (bloomColor != 0) glDeleteTextures(bloomColor);
        if (pingColor != 0) glDeleteTextures(pingColor);
        if (pongColor != 0) glDeleteTextures(pongColor);
        if (bloomFbo != 0) glDeleteFramebuffers(bloomFbo);
        if (pingFbo != 0) glDeleteFramebuffers(pingFbo);
        if (pongFbo != 0) glDeleteFramebuffers(pongFbo);

        if (raysColor != 0) glDeleteTextures(raysColor);
        if (raysFbo != 0) glDeleteFramebuffers(raysFbo);
    }
}
