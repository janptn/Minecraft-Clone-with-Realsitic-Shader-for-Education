public class Main {
    public static void main(String[] args) {
        // Minimal Main class after full revert
    }
}
            float sy = invY + cy * (slot + gap);

            boolean hover = hit(mx, my, sx, sy, slot, slot);
            ui.drawRect(sx, sy, slot, slot, screenW, screenH, 1f, 1f, 1f, hover ? 0.18f : 0.12f);
            ui.drawRect(sx + 1f, sy + 1f, slot - 2f, slot - 2f, screenW, screenH, 0f, 0f, 0f, 0.35f);

            UvRect uv = blockTextures.uv(t, 2);
            float iconPad = 6f;
            ui.drawTexturedRect(
                sx + iconPad,
                sy + iconPad,
                slot - iconPad * 2f,
                slot - iconPad * 2f,
                screenW,
                screenH,
                uv.u0(), uv.v0(), uv.u1(), uv.v1(),
                1.0f
            );

            if (count > 0) {
                text.draw(ui, sx + 6f, sy + slot - 18f, screenW, screenH, "x" + count, 1.5f, 1f, 1f, 1f, 0.80f);
            }

            idx++;
        }

        // Hotbar row
        text.draw(ui, x0 + 12f, hotY - 18f, screenW, screenH, "HOTBAR (DROP HERE)", 2.0f, 1f, 1f, 1f, 0.75f);
        for (int i = 0; i < hotSlots; i++) {
            float sx = hotX + i * (slot + gap);
            float sy = hotY;
            boolean sel = (i == selectedSlot);
            boolean hover = hit(mx, my, sx, sy, slot, slot);

            float a = sel ? 0.30f : (hover ? 0.20f : 0.14f);
            ui.drawRect(sx, sy, slot, slot, screenW, screenH, 1f, 1f, 1f, a);
            ui.drawRect(sx + 1f, sy + 1f, slot - 2f, slot - 2f, screenW, screenH, 0f, 0f, 0f, 0.35f);

            BlockType t = hotbar[i];
            if (i == 0) {
                text.draw(ui, sx + 6f, sy + 18f, screenW, screenH, "TOOL", 1.5f, 1f, 1f, 1f, 0.92f);
            } else if (t != null && t != BlockType.AIR) {
                UvRect uv = blockTextures.uv(t, 2);
                float iconPad = 6f;
                ui.drawTexturedRect(
                    sx + iconPad,
                    sy + iconPad,
                    slot - iconPad * 2f,
                    slot - iconPad * 2f,
                    screenW,
                    screenH,
                    uv.u0(), uv.v0(), uv.u1(), uv.v1(),
                    1.0f
                );
            }
        }

        // Drag icon follows cursor while holding.
        if (mouseDown && dragged != BlockType.AIR) {
            UvRect uv = blockTextures.uv(dragged, 2);
            float d = slot - 12f;
            ui.drawTexturedRect(
                mx - d * 0.5f,
                my - d * 0.5f,
                d,
                d,
                screenW,
                screenH,
                uv.u0(), uv.v0(), uv.u1(), uv.v1(),
                0.95f
            );
        }
    }

    private record MenuLayout(
        float x,
        float y,
        float w,
        float h,
        float headerH,
        float rowH,
        float labelW
    ) {}

    private static MenuLayout menuLayout(int screenW, int screenH) {
        float x = 18f;
        float w = 420f;

        float headerH = 12f + 18f + 22f;
        float rowH = 22f;
        float labelW = 150f;

        // Rows currently in the menu:
        // 0 preset button
        // 1 reset button
        // 2 plain/off button
        // 3 fog toggle
        // 4 fogNear
        // 5 fogFar
        // 6 bright
        // 7 gamma
        // 8 shadows toggle
        // 9 shadow strength
        // 10 shadow softness
        // 11 shadow distance
        // 12 shadow resolution
        // +6px separator
        // 11 post toggle
        // 12 fxaa toggle
        // Sliders used (indices):
        // 0 fog near
        // 1 fog far
        // 2 bright
        // 3 gamma
        // 4 fov
        // 5 sun pos
        // 6 sh str
        // 7 sh soft
        // 8 sh dist
        // 9 sh res (discrete)
        // post toggles (no slider index)
        // 10 aa level
        // 11 vignette
        // 12 saturation
        // 13 contrast
        // bloom toggle (no slider index)
        // 14 bloom threshold
        // 15 bloom strength
        // godrays toggle (no slider index)
        // 16 gr strength
        // 17 gr samples (discrete)
        // 18 gr density
        // 19 gr weight
        // 20 gr decay
        // 21 gr edge
        // +6px separator
        // filter toggle (no slider index)
        // pixel snap toggle (no slider index)
        // 22 aniso
        // 23 mip bias
        // 24 wind
        // Total rendered rows (including intentional blank spacing rows via ty += rowH).
        int rows = 40;
        float extra = 6f + 6f; // section separators (ty += rowH + 6f)
        float paddingBottom = 12f;

        // Fit the menu on-screen: if necessary, shrink row height.
        float availH = screenH - 36f;
        float wantedH = headerH + rows * rowH + extra + paddingBottom;
        if (wantedH > availH) {
            float minRowH = 10f;
            rowH = (availH - headerH - extra - paddingBottom) / (float) rows;
            if (rowH < minRowH) rowH = minRowH;
        }
        float h = headerH + rows * rowH + extra + paddingBottom;

        float y = 18f;
        float maxY = screenH - 18f - h;
        if (y > maxY) y = Math.max(18f, maxY);

        return new MenuLayout(x, y, w, h, headerH, rowH, labelW);
    }

    // Godray sample steps (discrete).
    private static final int[] GODRAY_SAMPLE_STEPS = new int[] { 8, 12, 16, 24, 32, 48, 64, 96, 128 };

    private static int godraysSamplesToIndex(int samples) {
        int bestIdx = 0;
        int bestDist = Math.abs(GODRAY_SAMPLE_STEPS[0] - samples);
        for (int i = 1; i < GODRAY_SAMPLE_STEPS.length; i++) {
            int dist = Math.abs(GODRAY_SAMPLE_STEPS[i] - samples);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static MenuResult handleGraphicsMenuInput(
            float mx, float my,
            boolean pressed,
            boolean down,
            int activeSlider,
            boolean fogEnabled,
            float fogNear,
            float fogFar,
            float brightness,
            float gamma,
            float fovDeg,
            float gloss,
            boolean postEnabled,
            boolean fxaaEnabled,
            float vignette,
            float saturation,
            float contrast,
            boolean bloomEnabled,
            float bloomThreshold,
            float bloomStrength,
            boolean godraysEnabled,
            int godraysSamples,
            float godraysStrength,
            float godraysDensity,
            float godraysWeight,
            float godraysDecay,
            float godraysEdgeFade,
                float fxaaSpan,
                boolean texSmooth,
                float texAniso,
                boolean pixelSnap,
                float windStrength,
                boolean shadowsEnabled,
                float sunElevationDeg,
                float shadowStrength,
                float shadowSoftness,
                float shadowDistance,
                int shadowRes,
                int[] shadowResSteps,
                float texAnisoMax,
            int screenW,
            int screenH
    ) {
        // Must match drawGraphicsMenu geometry.
        MenuLayout l = menuLayout(screenW, screenH);
        float x = l.x;
        float y = l.y;
        float w = l.w;
        float headerH = l.headerH;
        float rowH = l.rowH;
        float labelW = l.labelW;
        float tx = x + 12f;
        float ty0 = y + headerH;
        float controlX = tx + labelW;
        float controlW = w - 24f - labelW;

        // Helper hit-tests
        java.util.function.BiFunction<Float, Float, Boolean> inPanel = (px, py) -> (px >= x && px <= x + w && py >= y && py <= y + l.h);
        if (!inPanel.apply(mx, my) && pressed) {
            activeSlider = -1;
            return new MenuResult(activeSlider, fogEnabled, fogNear, fogFar, brightness, gamma, fovDeg, gloss,
                postEnabled, fxaaEnabled, fxaaSpan, vignette, saturation, contrast,
                bloomEnabled, bloomThreshold, bloomStrength,
                godraysEnabled, godraysSamples, godraysStrength, godraysDensity, godraysWeight, godraysDecay, godraysEdgeFade,
                texSmooth, texAniso, pixelSnap, windStrength,
                shadowsEnabled, sunElevationDeg, shadowStrength, shadowSoftness, shadowDistance, shadowRes);
        }

        // Row 0: Preset button (control area)
        float rowY = ty0;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            // Apply user preset (immediate)
            fogEnabled = true;
            fogNear = 35.72f;
            fogFar = 400.00f;
            brightness = 1.02f;
            gamma = 1.04f;
            fovDeg = 104.68f;

            shadowsEnabled = true;
            sunElevationDeg = 25.16f;
            shadowStrength = 0.80f;
            shadowSoftness = 2.04f;
            shadowDistance = 40.00f;
            shadowRes = 4096;

            postEnabled = true;
            fxaaEnabled = true;
            fxaaSpan = 64.00f;
            vignette = 0.23f;
            saturation = 1.05f;
            contrast = 1.05f;

            bloomEnabled = true;
            bloomThreshold = 0.29f;
            bloomStrength = 0.40f;

            godraysEnabled = true;
            godraysStrength = 0.79f;
            godraysSamples = 128;
            godraysDensity = 0.21f;
            godraysWeight = 0.02f;
            godraysDecay = 0.98f;
            godraysEdgeFade = 1.15f;

            texSmooth = false; // PIXEL
            pixelSnap = true;
            texAniso = Math.min(16.0f, Math.max(1.0f, texAnisoMax));
            gloss = 0.75f;
            windStrength = 1.00f;

            activeSlider = -1;
        }

        // Row 1: Reset-to-defaults button (control area)
        rowY += rowH;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            // Restore built-in defaults (the values you start with on boot)
            fogEnabled = true;
            fogNear = 30.0f;
            fogFar = 90.0f;
            brightness = 1.00f;
            gamma = 1.00f;
            fovDeg = 75.0f;
            gloss = -0.35f;

            shadowsEnabled = true;
            sunElevationDeg = 60.0f;
            shadowStrength = 0.80f;
            shadowSoftness = 2.04f;
            shadowDistance = 40.0f;
            shadowRes = 16384;

            postEnabled = true;
            fxaaEnabled = true;
            fxaaSpan = 64.0f;
            vignette = 0.18f;
            saturation = 1.05f;
            contrast = 1.05f;

            bloomEnabled = true;
            bloomThreshold = 1.00f;
            bloomStrength = 0.35f;

            godraysEnabled = true;
            godraysSamples = 48;
            godraysStrength = 0.45f;
            godraysDensity = 0.85f;
            godraysWeight = 0.020f;
            godraysDecay = 0.965f;
            godraysEdgeFade = 0.85f;

            texSmooth = false;
            texAniso = Math.min(16.0f, Math.max(1.0f, texAnisoMax));
            pixelSnap = true;
            windStrength = 1.00f;

            activeSlider = -1;
        }

        // Row 2: Plain/off button (control area)
        rowY += rowH;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            // Disable "everything" for a plain look.
            fogEnabled = false;
            shadowsEnabled = false;
            postEnabled = false;
            fxaaEnabled = false;
            bloomEnabled = false;
            godraysEnabled = false;

            // Neutral post grading controls (in case post is turned back on).
            vignette = 0.00f;
            saturation = 1.00f;
            contrast = 1.00f;

            activeSlider = -1;
        }

        // Row 3: Fog toggle button (control area)
        rowY += rowH;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            fogEnabled = !fogEnabled;
        }

        // Sliders (bars are inside control area)
        // fogNear slider index 0
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 0, controlX, rowY, controlW, fogNear, 0f, 200f);
        if (activeSlider == 0 && down) fogNear = sliderValue(mx, controlX, controlW, 0f, 200f);

        // fogFar slider index 1
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 1, controlX, rowY, controlW, fogFar, 10f, 400f);
        if (activeSlider == 1 && down) fogFar = sliderValue(mx, controlX, controlW, 10f, 400f);

        // brightness slider index 2
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 2, controlX, rowY, controlW, brightness, 0.50f, 2.00f);
        if (activeSlider == 2 && down) brightness = sliderValue(mx, controlX, controlW, 0.50f, 2.00f);

        // gamma slider index 3
        rowY += rowH;
        // Must match drawGraphicsMenu() range.
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 3, controlX, rowY, controlW, gamma, 0.80f, 2.20f);
        if (activeSlider == 3 && down) gamma = sliderValue(mx, controlX, controlW, 0.80f, 2.20f);

        // FOV slider index 4
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 4, controlX, rowY, controlW, fovDeg, 50.0f, 110.0f);
        if (activeSlider == 4 && down) fovDeg = sliderValue(mx, controlX, controlW, 50.0f, 110.0f);

        // Shadows toggle
        rowY += rowH;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            shadowsEnabled = !shadowsEnabled;
        }

        // Sun position slider index 5
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 5, controlX, rowY, controlW, sunElevationDeg, 5.0f, 85.0f);
        if (activeSlider == 5 && down) sunElevationDeg = sliderValue(mx, controlX, controlW, 5.0f, 85.0f);

        // Shadow strength slider index 6
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 6, controlX, rowY, controlW, shadowStrength, 0.00f, 1.00f);
        if (activeSlider == 6 && down) shadowStrength = sliderValue(mx, controlX, controlW, 0.00f, 1.00f);

        // Shadow softness slider index 7
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 7, controlX, rowY, controlW, shadowSoftness, 0.50f, 4.00f);
        if (activeSlider == 7 && down) shadowSoftness = sliderValue(mx, controlX, controlW, 0.50f, 4.00f);

        // Shadow distance slider index 8
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 8, controlX, rowY, controlW, shadowDistance, 0.0f, 250.0f);
        if (activeSlider == 8 && down) shadowDistance = sliderValue(mx, controlX, controlW, 0.0f, 250.0f);

        // Shadow resolution slider index 9 (discrete steps)
        rowY += rowH;
        int curShadowResIdx = shadowResToIndex(shadowRes, shadowResSteps);
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 9, controlX, rowY, controlW, (float) curShadowResIdx, 0f, (float) (shadowResSteps.length - 1));
        if (activeSlider == 9 && down) {
            float idxF = sliderValue(mx, controlX, controlW, 0f, (float) (shadowResSteps.length - 1));
            int idx = Math.round(idxF);
            idx = Math.max(0, Math.min(shadowResSteps.length - 1, idx));
            shadowRes = shadowResSteps[idx];
        }

        // Post toggle
        rowY += rowH + 6f;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            postEnabled = !postEnabled;
        }

        // FXAA toggle
        rowY += rowH;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            fxaaEnabled = !fxaaEnabled;
        }

        // AA LEVEL slider index 10
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 10, controlX, rowY, controlW, fxaaSpan, 2.0f, 64.0f);
        if (activeSlider == 10 && down) fxaaSpan = sliderValue(mx, controlX, controlW, 2.0f, 64.0f);

        // Vignette slider index 11
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 11, controlX, rowY, controlW, vignette, 0.00f, 0.45f);
        if (activeSlider == 11 && down) vignette = sliderValue(mx, controlX, controlW, 0.00f, 0.45f);

        // Saturation slider index 12
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 12, controlX, rowY, controlW, saturation, 0.50f, 1.50f);
        if (activeSlider == 12 && down) saturation = sliderValue(mx, controlX, controlW, 0.50f, 1.50f);

        // Contrast slider index 13
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 13, controlX, rowY, controlW, contrast, 0.80f, 1.40f);
        if (activeSlider == 13 && down) contrast = sliderValue(mx, controlX, controlW, 0.80f, 1.40f);

        // Bloom toggle
        rowY += rowH;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            bloomEnabled = !bloomEnabled;
        }

        // Bloom threshold slider index 14
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 14, controlX, rowY, controlW, bloomThreshold, 0.00f, 2.50f);
        if (activeSlider == 14 && down) bloomThreshold = sliderValue(mx, controlX, controlW, 0.00f, 2.50f);

        // Bloom strength slider index 15
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 15, controlX, rowY, controlW, bloomStrength, 0.00f, 1.25f);
        if (activeSlider == 15 && down) bloomStrength = sliderValue(mx, controlX, controlW, 0.00f, 1.25f);

        // Godrays toggle
        rowY += rowH;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            godraysEnabled = !godraysEnabled;
        }

        // Godrays strength slider index 16
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 16, controlX, rowY, controlW, godraysStrength, 0.00f, 1.50f);
        if (activeSlider == 16 && down) godraysStrength = sliderValue(mx, controlX, controlW, 0.00f, 1.50f);

        // Godrays samples slider index 17 (discrete)
        rowY += rowH;
        int curGrIdx = godraysSamplesToIndex(godraysSamples);
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 17, controlX, rowY, controlW, (float) curGrIdx, 0f, (float) (GODRAY_SAMPLE_STEPS.length - 1));
        if (activeSlider == 17 && down) {
            float idxF = sliderValue(mx, controlX, controlW, 0f, (float) (GODRAY_SAMPLE_STEPS.length - 1));
            int idx = Math.round(idxF);
            idx = Math.max(0, Math.min(GODRAY_SAMPLE_STEPS.length - 1, idx));
            godraysSamples = GODRAY_SAMPLE_STEPS[idx];
        }

        // Godrays density slider index 18
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 18, controlX, rowY, controlW, godraysDensity, 0.20f, 1.50f);
        if (activeSlider == 18 && down) godraysDensity = sliderValue(mx, controlX, controlW, 0.20f, 1.50f);

        // Godrays weight slider index 19
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 19, controlX, rowY, controlW, godraysWeight, 0.000f, 0.050f);
        if (activeSlider == 19 && down) godraysWeight = sliderValue(mx, controlX, controlW, 0.000f, 0.050f);

        // Godrays decay slider index 20
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 20, controlX, rowY, controlW, godraysDecay, 0.850f, 0.999f);
        if (activeSlider == 20 && down) godraysDecay = sliderValue(mx, controlX, controlW, 0.850f, 0.999f);

        // Godrays edge fade slider index 21
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 21, controlX, rowY, controlW, godraysEdgeFade, 0.10f, 1.50f);
        if (activeSlider == 21 && down) godraysEdgeFade = sliderValue(mx, controlX, controlW, 0.10f, 1.50f);

        // Texture filtering
        rowY += rowH + 6f;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            texSmooth = !texSmooth;
        }

        // Pixel snap toggle
        rowY += rowH;
        if (pressed && hit(mx, my, controlX, rowY, controlW, rowH - 2f)) {
            pixelSnap = !pixelSnap;
        }

        // Aniso slider index 22
        rowY += rowH;
        float anisoMax = Math.max(1.0f, texAnisoMax);
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 22, controlX, rowY, controlW, texAniso, 1.0f, anisoMax);
        if (activeSlider == 22 && down) texAniso = sliderValue(mx, controlX, controlW, 1.0f, anisoMax);

        // Detail slider index 23
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 23, controlX, rowY, controlW, gloss, -1.50f, 3.00f);
        if (activeSlider == 23 && down) gloss = sliderValue(mx, controlX, controlW, -1.50f, 3.00f);

        // Wind strength slider index 24
        rowY += rowH;
        activeSlider = sliderInput(mx, my, pressed, down, activeSlider, 24, controlX, rowY, controlW, windStrength, 0.00f, 2.00f);
        if (activeSlider == 24 && down) windStrength = sliderValue(mx, controlX, controlW, 0.00f, 2.00f);

        // Keep fogFar > fogNear
        if (fogFar < fogNear + 5f) fogFar = fogNear + 5f;

        // Release
        if (!down) activeSlider = -1;

        return new MenuResult(activeSlider, fogEnabled, fogNear, fogFar, brightness, gamma, fovDeg, gloss,
            postEnabled, fxaaEnabled, fxaaSpan, vignette, saturation, contrast,
            bloomEnabled, bloomThreshold, bloomStrength,
            godraysEnabled, godraysSamples, godraysStrength, godraysDensity, godraysWeight, godraysDecay, godraysEdgeFade,
            texSmooth, texAniso, pixelSnap, windStrength,
            shadowsEnabled, sunElevationDeg, shadowStrength, shadowSoftness, shadowDistance, shadowRes);
    }

    private static Vector3f computeSunLightDir(float sunElevationDeg) {
        // Keep azimuth fixed (original look), but allow elevation to be adjusted from the graphics menu.
        // This direction points from the sun toward the world (downwards).
        float elev = clamp(sunElevationDeg, 5.0f, 85.0f);
        float elevRad = (float) Math.toRadians(elev);
        float sin = (float) Math.sin(elevRad);
        float cos = (float) Math.cos(elevRad);

        // Original horizontal direction (-0.45, -0.35) normalized.
        float hx = -0.78935224f;
        float hz = -0.6139406f;

        return new Vector3f(hx * cos, -sin, hz * cos);
    }

    private static int shadowResToIndex(int shadowRes, int[] shadowResSteps) {
        if (shadowResSteps == null || shadowResSteps.length == 0) return 0;
        int bestIdx = 0;
        int bestDist = Math.abs(shadowResSteps[0] - shadowRes);
        for (int i = 1; i < shadowResSteps.length; i++) {
            int dist = Math.abs(shadowResSteps[i] - shadowRes);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static float queryMaxAnisotropy() {
        if (!org.lwjgl.opengl.GL.getCapabilities().GL_EXT_texture_filter_anisotropic) return 1.0f;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var fb = stack.mallocFloat(1);
            glGetFloatv(org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, fb);
            float max = fb.get(0);
            if (!(max > 0.0f)) return 1.0f;
            return max;
        }
    }

    private static boolean hit(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int sliderInput(float mx, float my, boolean pressed, boolean down, int activeSlider, int idx,
                                   float barX, float rowY, float barW, float current, float min, float max) {
        float barY = rowY + 6f;
        float barH = 10f;
        if (pressed && hit(mx, my, barX, barY - 3f, barW, barH + 6f)) {
            return idx;
        }
        if (activeSlider == idx && down) return idx;
        return activeSlider;
    }

    private static float sliderValue(float mx, float barX, float barW, float min, float max) {
        float t = (mx - barX) / barW;
        t = clamp(t, 0f, 1f);
        return min + t * (max - min);
    }

    private static String fmt2(float v) {
        int iv = (int) Math.round(v * 100.0);
        int a = iv / 100;
        int b = Math.abs(iv % 100);
        if (b < 10) return a + ".0" + b;
        return a + "." + b;
    }

    private static void findSafeSpawn(World world, Vector3f outFeetPos, float radius, float height) {
        // Search around origin for the first spawn position that is not colliding.
        // This avoids spawning inside stone and avoids "fixing" collisions by moving far upward.
        final float eps = 0.001f;

        for (int r = 0; r <= 24; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // perimeter only
                    int x = dx;
                    int z = dz;

                    int surfaceY = findSurfaceY(world, x, z);
                    if (surfaceY < 1) continue;
                    if (!isSolid(world.getBlock(x, surfaceY, z))) continue;

                    outFeetPos.set(x + 0.5f, surfaceY + 1.001f, z + 0.5f);
                    if (!collides(world, outFeetPos, radius, height, eps)) return;
                }
            }
        }

        // Fallback: origin
        int surfaceY = findSurfaceY(world, 0, 0);
        outFeetPos.set(0.5f, surfaceY + 1.001f, 0.5f);
    }

    private static Vector3f cameraForward(Camera c) {
        float yaw = (float) Math.toRadians(c.yaw);
        float pitch = (float) Math.toRadians(c.pitch);

        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float cy = (float) Math.cos(yaw);
        float sy = (float) Math.sin(yaw);

        Vector3f f = new Vector3f(sy * cp, -sp, -cy * cp);
        if (f.lengthSquared() > 0.000001f) f.normalize();
        return f;
    }

    private static int findSurfaceY(World world, int x, int z) {
        for (int y = Chunk.HEIGHT - 1; y >= 0; y--) {
            if (world.getBlock(x, y, z) != BlockType.AIR) return y;
        }
        return 40;
    }

    private static void markDirtyAround(ChunkRenderer r, int wx, int wz) {
        int cx = (int) Math.floor(wx / (float) Chunk.SIZE);
        int cz = (int) Math.floor(wz / (float) Chunk.SIZE);
        r.markDirtyChunk(cx, cz);
        // neighbor chunks can share faces across borders
        r.markDirtyChunk(cx + 1, cz);
        r.markDirtyChunk(cx - 1, cz);
        r.markDirtyChunk(cx, cz + 1);
        r.markDirtyChunk(cx, cz - 1);
    }

    private static boolean aabbIntersects(
        float aMinX, float aMinY, float aMinZ,
        float aMaxX, float aMaxY, float aMaxZ,
        float bMinX, float bMinY, float bMinZ,
        float bMaxX, float bMaxY, float bMaxZ
    ) {
        return (aMinX < bMaxX && aMaxX > bMinX) &&
               (aMinY < bMaxY && aMaxY > bMinY) &&
               (aMinZ < bMaxZ && aMaxZ > bMinZ);
    }

    private static boolean tryStepUp(World world, Vector3f pos, float radius, float height, float eps, float stepHeight, float baseY) {
        // If even the max step doesn't clear collision, we can't step.
        pos.y = baseY + stepHeight;
        if (collides(world, pos, radius, height, eps)) {
            pos.y = baseY;
            return false;
        }

        // Find the minimal lift (reduces the "teleport" feeling when walking up stairs).
        float low = 0.0f;
        float high = stepHeight;
        for (int i = 0; i < 10; i++) {
            float mid = (low + high) * 0.5f;
            pos.y = baseY + mid;
            if (collides(world, pos, radius, height, eps)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        pos.y = baseY + high;
        return true;
    }

    private static boolean integrateAndCollide(World world, Vector3f pos, Vector3f vel, float dt, float radius, float height) {
        final float eps = 0.001f;
        final float stepHeight = 0.51f;
        boolean grounded = false;

        // X
        float dx = vel.x * dt;
        if (dx != 0f) {
            boolean canStep = isOnGround(world, pos, radius, eps);
            float oldY = pos.y;

            pos.x += dx;
            float corr = collisionCorrectionX(world, pos, radius, height, eps, dx);
            if (corr != 0f) {
                boolean stepped = false;
                if (canStep) {
                    stepped = tryStepUp(world, pos, radius, height, eps, stepHeight, oldY);
                }
                if (!stepped) {
                    pos.x += corr;
                    vel.x = 0f;
                }
            }
        }

        // Z
        float dz = vel.z * dt;
        if (dz != 0f) {
            boolean canStep = isOnGround(world, pos, radius, eps);
            float oldY = pos.y;

            pos.z += dz;
            float corr = collisionCorrectionZ(world, pos, radius, height, eps, dz);
            if (corr != 0f) {
                boolean stepped = false;
                if (canStep) {
                    stepped = tryStepUp(world, pos, radius, height, eps, stepHeight, oldY);
                }
                if (!stepped) {
                    pos.z += corr;
                    vel.z = 0f;
                }
            }
        }

        // Y
        float dy = vel.y * dt;
        if (dy != 0f) {
            pos.y += dy;
            float corr = collisionCorrectionY(world, pos, radius, height, eps, dy);
            if (corr != 0f) {
                pos.y += corr;
                if (dy < 0f) grounded = true;
                vel.y = 0f;
            }
        }

        // Ground check even if we didn't overlap the ground block.
        if (!grounded) grounded = isOnGround(world, pos, radius, eps);

        return grounded;
    }

    private static boolean isOnGround(World world, Vector3f pos, float radius, float eps) {
        float feetY = pos.y;
        int yBelow = (int) Math.floor(feetY - eps);

        float pMinX = pos.x - radius;
        float pMaxX = pos.x + radius;
        float pMinZ = pos.z - radius;
        float pMaxZ = pos.z + radius;

        int minX = (int) Math.floor(pMinX + eps);
        int maxX = (int) Math.floor(pMaxX - eps);
        int minZ = (int) Math.floor(pMinZ + eps);
        int maxZ = (int) Math.floor(pMaxZ - eps);

        // Tiny AABB at feet to test overlap.
        float aMinX = pMinX;
        float aMaxX = pMaxX;
        float aMinY = feetY - 0.01f;
        float aMaxY = feetY + 0.01f;
        float aMinZ = pMinZ;
        float aMaxZ = pMaxZ;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // ...existing code...
                if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                // ...existing code...
                    // Lower (full depth)
                    float top = yBelow + 0.5f;
                    if (feetY - top <= 0.06f) {
                        if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ,
                            x, yBelow, z,
                            x + 1f, top, z + 1f)) return true;
                    }
                    // Upper (back half)
                    top = yBelow + 1.0f;
                    if (feetY - top <= 0.06f) {
                        if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ,
                            x, yBelow + 0.5f, z + 0.5f,
                            x + 1f, top, z + 1f)) return true;
                    }
                } else {
                    float top = yBelow + 1.0f;
                    if (feetY - top > 0.06f) continue;
                    if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ,
                        x, yBelow, z,
                        x + 1f, top, z + 1f)) return true;
                }
            }
        }
        return false;
    }

    private static float collisionCorrectionX(World world, Vector3f pos, float radius, float height, float eps, float dx) {
        if (!collides(world, pos, radius, height, eps)) return 0f;

        float aMinX = pos.x - radius;
        float aMaxX = pos.x + radius;
        float aMinY = pos.y;
        float aMaxY = pos.y + height;
        float aMinZ = pos.z - radius;
        float aMaxZ = pos.z + radius;

        int minX = (int) Math.floor(aMinX);
        int maxX = (int) Math.floor(aMaxX - eps);
        int minY = (int) Math.floor(aMinY);
        int maxY = (int) Math.floor(aMaxY - eps);
        int minZ = (int) Math.floor(aMinZ);
        int maxZ = (int) Math.floor(aMaxZ - eps);

        if (dx > 0f) {
            float best = Float.NEGATIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        // ...existing code...
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        // ...existing code...
                            // Lower
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 0.5f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMinX - aMaxX) - eps;
                                if (corr > best) best = corr;
                            }
                            // Upper
                            bMinY = y + 0.5f; bMaxY = y + 1f;
                            bMinZ = z + 0.5f; bMaxZ = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMinX - aMaxX) - eps;
                                if (corr > best) best = corr;
                            }
                        } else {
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 1f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (!aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) continue;
                            float corr = (bMinX - aMaxX) - eps;
                            if (corr > best) best = corr;
                        }
                    }
                }
            }
            return (best == Float.NEGATIVE_INFINITY) ? 0f : best;
        } else {
            float best = Float.POSITIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        // ...existing code...
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        // ...existing code...
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 0.5f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMaxX - aMinX) + eps;
                                if (corr < best) best = corr;
                            }
                            bMinY = y + 0.5f; bMaxY = y + 1f;
                            bMinZ = z + 0.5f; bMaxZ = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMaxX - aMinX) + eps;
                                if (corr < best) best = corr;
                            }
                        } else {
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 1f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (!aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) continue;
                            float corr = (bMaxX - aMinX) + eps;
                            if (corr < best) best = corr;
                        }
                    }
                }
            }
            return (best == Float.POSITIVE_INFINITY) ? 0f : best;
        }
    }

    private static float collisionCorrectionZ(World world, Vector3f pos, float radius, float height, float eps, float dz) {
        if (!collides(world, pos, radius, height, eps)) return 0f;

        float aMinX = pos.x - radius;
        float aMaxX = pos.x + radius;
        float aMinY = pos.y;
        float aMaxY = pos.y + height;
        float aMinZ = pos.z - radius;
        float aMaxZ = pos.z + radius;

        int minX = (int) Math.floor(aMinX);
        int maxX = (int) Math.floor(aMaxX - eps);
        int minY = (int) Math.floor(aMinY);
        int maxY = (int) Math.floor(aMaxY - eps);
        int minZ = (int) Math.floor(aMinZ);
        int maxZ = (int) Math.floor(aMaxZ - eps);

        if (dz > 0f) {
            float best = Float.NEGATIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        // ...existing code...
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        // ...existing code...
                            // Lower
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 0.5f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMinZ - aMaxZ) - eps;
                                if (corr > best) best = corr;
                            }
                            // Upper
                            bMinY = y + 0.5f; bMaxY = y + 1f;
                            bMinZ = z + 0.5f; bMaxZ = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMinZ - aMaxZ) - eps;
                                if (corr > best) best = corr;
                            }
                        } else {
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 1f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (!aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) continue;
                            float corr = (bMinZ - aMaxZ) - eps;
                            if (corr > best) best = corr;
                        }
                    }
                }
            }
            return (best == Float.NEGATIVE_INFINITY) ? 0f : best;
        } else {
            float best = Float.POSITIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        // ...existing code...
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        // ...existing code...
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 0.5f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMaxZ - aMinZ) + eps;
                                if (corr < best) best = corr;
                            }
                            bMinY = y + 0.5f; bMaxY = y + 1f;
                            bMinZ = z + 0.5f; bMaxZ = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMaxZ - aMinZ) + eps;
                                if (corr < best) best = corr;
                            }
                        } else {
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 1f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (!aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) continue;
                            float corr = (bMaxZ - aMinZ) + eps;
                            if (corr < best) best = corr;
                        }
                    }
                }
            }
            return (best == Float.POSITIVE_INFINITY) ? 0f : best;
        }
    }

    private static float collisionCorrectionY(World world, Vector3f pos, float radius, float height, float eps, float dy) {
        if (!collides(world, pos, radius, height, eps)) return 0f;

        float aMinX = pos.x - radius;
        float aMaxX = pos.x + radius;
        float aMinY = pos.y;
        float aMaxY = pos.y + height;
        float aMinZ = pos.z - radius;
        float aMaxZ = pos.z + radius;

        int minX = (int) Math.floor(aMinX);
        int maxX = (int) Math.floor(aMaxX - eps);
        int minY = (int) Math.floor(aMinY);
        int maxY = (int) Math.floor(aMaxY - eps);
        int minZ = (int) Math.floor(aMinZ);
        int maxZ = (int) Math.floor(aMaxZ - eps);

        if (dy > 0f) {
            float best = Float.NEGATIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockType t = world.getBlock(x, y, z);
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        if (t == BlockType.OAK_STAIRS) {
                            // Lower
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            float bMinY = y, bMaxY = y + 0.5f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMinY - aMaxY) - eps;
                                if (corr > best) best = corr;
                            }
                            // Upper
                            bMinY = y + 0.5f; bMaxY = y + 1f;
                            float bMinZ2 = z + 0.5f, bMaxZ2 = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ2, bMaxX, bMaxY, bMaxZ2)) {
                                float corr = (bMinY - aMaxY) - eps;
                                if (corr > best) best = corr;
                            }
                        } else {
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 1f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (!aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) continue;
                            float corr = (bMinY - aMaxY) - eps;
                            if (corr > best) best = corr;
                        }
                    }
                }
            }
            return (best == Float.NEGATIVE_INFINITY) ? 0f : best;
        } else {
            float best = Float.POSITIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockType t = world.getBlock(x, y, z);
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        if (t == BlockType.OAK_STAIRS) {
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            // Lower
                            float bMinY = y, bMaxY = y + 0.5f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) {
                                float corr = (bMaxY - aMinY) + eps;
                                if (corr < best) best = corr;
                            }
                            // Upper
                            bMinY = y + 0.5f; bMaxY = y + 1f;
                            float bMinZ2 = z + 0.5f, bMaxZ2 = z + 1f;
                            if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ2, bMaxX, bMaxY, bMaxZ2)) {
                                float corr = (bMaxY - aMinY) + eps;
                                if (corr < best) best = corr;
                            }
                        } else {
                            float bMinX = x, bMaxX = x + 1f;
                            float bMinY = y, bMaxY = y + 1f;
                            float bMinZ = z, bMaxZ = z + 1f;
                            if (!aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ)) continue;
                            float corr = (bMaxY - aMinY) + eps;
                            if (corr < best) best = corr;
                        }
                    }
                }
            }
            return (best == Float.POSITIVE_INFINITY) ? 0f : best;
        }
    }

    private static boolean collides(World world, Vector3f pos, float radius, float height, float eps) {
        float aMinX = pos.x - radius;
        float aMaxX = pos.x + radius;
        float aMinY = pos.y;
        float aMaxY = pos.y + height;
        float aMinZ = pos.z - radius;
        float aMaxZ = pos.z + radius;

        int minX = (int) Math.floor(aMinX);
        int maxX = (int) Math.floor(aMaxX - eps);
        int minY = (int) Math.floor(aMinY);
        int maxY = (int) Math.floor(aMaxY - eps);
        int minZ = (int) Math.floor(aMinZ);
        int maxZ = (int) Math.floor(aMaxZ - eps);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // ...existing code...
                    if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                    // ...existing code...
                        if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ,
                            x, y, z,
                            x + 1f, y + 0.5f, z + 1f)) return true;
                        if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ,
                            x, y + 0.5f, z + 0.5f,
                            x + 1f, y + 1f, z + 1f)) return true;
                    } else {
                        if (aabbIntersects(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ,
                            x, y, z,
                            x + 1f, y + 1f, z + 1f)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isSolid(BlockType t) {
        // Minimal collision rules for now.
        return t != BlockType.AIR && t != BlockType.TALL_GRASS && t != BlockType.TORCH;
    }
}
