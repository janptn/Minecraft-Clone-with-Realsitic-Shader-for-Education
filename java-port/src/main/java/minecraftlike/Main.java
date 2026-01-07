package minecraftlike;

import minecraftlike.engine.*;
import minecraftlike.voxel.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Random;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL33.*;

public final class Main {
    private static final int[] SHADOW_RES_STEPS_ALL = { 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384 };

    private static final int MAX_POINT_LIGHTS = 32;

    // Point-light (torch) shadow map. Render only the nearest torch for performance.
    private static final int POINT_SHADOW_RES = 512;
    private static final float POINT_SHADOW_NEAR = 0.15f;
    private static final float POINT_SHADOW_FAR = 16.0f;

    private static final class FloatBuilder {
        private float[] data;
        private int size;

        FloatBuilder(int initialCapacity) {
            this.data = new float[Math.max(16, initialCapacity)];
        }

        void add(float v) {
            if (size >= data.length) {
                float[] n = new float[data.length * 2];
                System.arraycopy(data, 0, n, 0, data.length);
                data = n;
            }
            data[size++] = v;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    private static final class DroppedItem {
        final BlockType type;
        final Vector3f pos = new Vector3f();
        final Vector3f vel = new Vector3f();
        float age = 0f;
        float rotY = 0f;
        float skyMul = 1.0f;

        DroppedItem(BlockType type, float x, float y, float z) {
            this.type = type;
            this.pos.set(x, y, z);
        }
    }

    private enum BuildPrefab {
        HOUSE,
        TREE,
        SMALL_BUILDING,
        TOWER,
        WELL,
        CAMPFIRE,
        LAMP_POST
    }

    private static int[] buildShadowResSteps(int maxTextureSize) {
        if (maxTextureSize <= 0) maxTextureSize = 1;

        int count = 0;
        for (int s : SHADOW_RES_STEPS_ALL) {
            if (s > 0 && s <= maxTextureSize) count++;
        }
        if (count == 0) return new int[] { maxTextureSize };

        int[] out = new int[count];
        int i = 0;
        for (int s : SHADOW_RES_STEPS_ALL) {
            if (s > 0 && s <= maxTextureSize) out[i++] = s;
        }
        return out;
    }

    public static void main(String[] args) {
        try (Window window = Window.create("Minecraft-like (Java/LWJGL)", 1920, 1080)) {
            window.setVsync(false);
            window.setRelativeMouseMode(true);

            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);

            ShaderProgram shader = ShaderProgram.fromResources("shaders/voxel.vert", "shaders/voxel.frag");
            ShaderProgram shadowShader = ShaderProgram.fromResources("shaders/shadow.vert", "shaders/shadow.frag");
            ShaderProgram pointShadowShader = ShaderProgram.fromResources("shaders/point_shadow.vert", "shaders/point_shadow.frag");
            ShaderProgram waterShader = ShaderProgram.fromResources("shaders/water.vert", "shaders/water.frag");

            // Wasser-Normalmap als separate Textur laden
            Texture waterNormalMap = Texture.fromFileSmooth(Assets.findTexture("water_waves.png"));
            UiRenderer ui = new UiRenderer();
            UiText uiText = new UiText();
            PostProcessor post = new PostProcessor();
            ShadowMap shadowMap = new ShadowMap(2048);
            ShadowCubeMap pointShadowMap = new ShadowCubeMap(POINT_SHADOW_RES);
            SkyboxRenderer skybox = new SkyboxRenderer();

            Texture skyTex = Texture.fromFileSmooth(Assets.findTexture("skybox.png"));

            // Build atlas from existing workspace textures/.
            TextureAtlas atlas = TextureAtlas.buildDefault();
            BlockTextures blockTextures = new BlockTextures(atlas);

            final long worldSeed = 42L;
            World world = new World(worldSeed);
            ChunkRenderer chunkRenderer = new ChunkRenderer(world, blockTextures);
            chunkRenderer.setViewDistanceChunks(6);

            // Survival-ish pickup counts: break -> drop -> pick up -> place consumes.
            EnumMap<BlockType, Integer> inventory = new EnumMap<>(BlockType.class);

            // Creative-style default: make all existing placeable blocks available.
            // This also ensures they show up in the inventory UI immediately.
            for (BlockType t : BlockType.values()) {
                if (t == BlockType.AIR) continue;
                if (!isPlaceable(t)) continue;
                inventory.put(t, 999);
            }
            ArrayList<DroppedItem> drops = new ArrayList<>();
            EnumMap<BlockType, ChunkMesh> dropMeshes = new EnumMap<>(BlockType.class);
            Random rng = new Random(worldSeed ^ 0x9E3779B97F4A7C15L);

            Camera camera = new Camera();
            camera.position.set(0.0f, 65.0f, 0.0f);
            camera.pitch = -10.0f;

            // Player physics (simple walking controller).
            final float playerRadius = 0.30f;
            final float playerHeight = 1.80f;
            final float eyeHeight = 1.62f;
            final float walkSpeed = 5.0f;
            final float sprintMultiplier = 1.8f;
            final float gravity = -22.0f;
            final float jumpSpeed = 7.5f;
            Vector3f playerPos = new Vector3f(0.0f, 70.0f, 0.0f); // feet position
            Vector3f velocity = new Vector3f();
            boolean grounded = false;
            float jumpCooldown = 0.0f;

            float mouseSensitivity = 0.10f;

            Time time = new Time();

            // FPS counter
            int fpsFrames = 0;
            float fpsAcc = 0f;
            float fpsValue = 0f;

            // Graphics settings (tweakable in-game)
            boolean gfxMenu = false;
            boolean inventoryMenu = false;
            boolean fogEnabled = true;
            float fogNear = 30.0f;
            float fogFar = 90.0f;
            float brightness = 1.00f;
            float gamma = 1.00f;
            float fovDeg = 75.0f;
            // Texture LOD bias. Negative values sharpen (more Minecraft-ish), positive values blur.
            float gloss = -0.35f;

            // Sun + shadows
            boolean shadowsEnabled = true;
            // Sun position control (elevation angle above horizon).
            // 0 = near horizon, 90 = straight overhead.
            float sunElevationDeg = 60.0f;
            float shadowStrength = 0.80f;
            float shadowSoftness = 2.04f;
            float shadowDistance = 40.0f;
            int shadowRes = 16384;

            // Post processing controls
            boolean postEnabled = true;
            boolean fxaaEnabled = true;
            float fxaaSpan = 64.0f;
            float vignette = 0.18f;
            float saturation = 1.05f;
            float contrast = 1.05f;

            // Shaderpack-style post controls
            boolean bloomEnabled = true;
            float bloomThreshold = 1.00f;
            float bloomStrength = 0.35f;

            boolean godraysEnabled = true;
            int godraysSamples = 48;
            float godraysStrength = 0.45f;
            float godraysDensity = 0.85f;
            float godraysWeight = 0.020f;
            float godraysDecay = 0.965f;
            float godraysEdgeFade = 0.85f;

            // Texture filtering (distance shimmer reduction)
            boolean texSmooth = false;
            float texAniso = 16.0f;
            boolean pixelSnap = true;
            float windStrength = 1.00f;
            boolean lastTexSmooth = texSmooth;
            float lastTexAniso = texAniso;
            float lastGloss = gloss;

            int activeSlider = -1; // -1 = none

            final int maxTextureSize = glGetInteger(GL_MAX_TEXTURE_SIZE);
            final int[] shadowResSteps = buildShadowResSteps(maxTextureSize);

            final float texAnisoMax = queryMaxAnisotropy();
            texAniso = Math.min(texAniso, texAnisoMax);
            lastTexAniso = texAniso;

            // Apply initial filtering settings once (atlas defaults are conservative).
            atlas.texture().setFiltering(texSmooth, texAniso, gloss);

                // Hotbar: slot 0 is a placer tool, slots 1..8 are placeable blocks.
                final int placerSlot = 0;
                BlockType[] hotbar = new BlockType[] {
                    BlockType.AIR, // placer tool (not a block)
                    BlockType.GRASS,
                    BlockType.DIRT,
                    BlockType.STONE,
                    BlockType.COBBLESTONE,
                    BlockType.WOOD,
                    BlockType.PLANKS,
                    BlockType.LEAVES,
                    BlockType.TORCH,
                };
                int selectedSlot = 1;

                boolean buildMenu = false;
                BuildPrefab selectedPrefab = BuildPrefab.HOUSE;

                // Inventory drag state (drag from inventory slot -> drop on hotbar slot)
                BlockType dragged = BlockType.AIR;
                boolean invLeftWasDown = false;

                // Simple dynamic point lights (torches). Reused each frame.
                float[] plx = new float[MAX_POINT_LIGHTS];
                float[] ply = new float[MAX_POINT_LIGHTS];
                float[] plz = new float[MAX_POINT_LIGHTS];
                float[] pls = new float[MAX_POINT_LIGHTS];
                float[] plr = new float[MAX_POINT_LIGHTS];

                // Spawn above terrain near (0,0), but ensure we don't start inside blocks.
                findSafeSpawn(world, playerPos, playerRadius, playerHeight);
                velocity.zero();
                grounded = false;
                camera.position.set(playerPos.x, playerPos.y + eyeHeight, playerPos.z);

            while (!window.shouldClose()) {
                time.update();
                window.pollEvents();

                // FPS update (half-second window)
                fpsFrames++;
                fpsAcc += time.deltaSeconds();
                if (fpsAcc >= 0.5f) {
                    fpsValue = fpsFrames / fpsAcc;
                    fpsFrames = 0;
                    fpsAcc = 0f;
                }

                if (window.wasKeyPressed(Keys.ESCAPE)) {
                    window.setRelativeMouseMode(!window.isRelativeMouseMode());
                }

                if (window.wasKeyPressed(Keys.F1)) {
                    gfxMenu = !gfxMenu;
                    // Show cursor while editing settings.
                    if (gfxMenu) {
                        inventoryMenu = false;
                        buildMenu = false;
                        dragged = BlockType.AIR;
                    }
                    window.setRelativeMouseMode(!(gfxMenu || buildMenu || inventoryMenu));
                    activeSlider = -1;
                }

                if (window.wasKeyPressed(Keys.H)) {
                    buildMenu = !buildMenu;
                    if (buildMenu) {
                        inventoryMenu = false;
                        gfxMenu = false;
                        dragged = BlockType.AIR;
                        activeSlider = -1;
                    }
                    window.setRelativeMouseMode(!(gfxMenu || buildMenu || inventoryMenu));
                }

                if (window.wasKeyPressed(Keys.E)) {
                    inventoryMenu = !inventoryMenu;
                    if (inventoryMenu) {
                        gfxMenu = false;
                        buildMenu = false;
                        activeSlider = -1;
                    } else {
                        dragged = BlockType.AIR;
                        invLeftWasDown = false;
                    }
                    window.setRelativeMouseMode(!(gfxMenu || buildMenu || inventoryMenu));
                }

                // Reset world (regenerate chunks + clear edits)
                if (window.wasKeyPressed(Keys.R)) {
                    chunkRenderer.close();
                    world = new World(worldSeed);
                    chunkRenderer = new ChunkRenderer(world, blockTextures);
                    chunkRenderer.setViewDistanceChunks(12);

                    findSafeSpawn(world, playerPos, playerRadius, playerHeight);
                    velocity.zero();
                    grounded = false;
                    camera.position.set(playerPos.x, playerPos.y + eyeHeight, playerPos.z);
                }

                float scroll = window.scrollDeltaY();
                if (scroll != 0f) {
                    selectedSlot -= (int) Math.signum(scroll);
                    if (selectedSlot < 0) selectedSlot = 0;
                    if (selectedSlot > 8) selectedSlot = 8;
                }

                // Mouse look
                if (window.isRelativeMouseMode()) {
                    camera.yaw += window.mouseDeltaX() * mouseSensitivity;
                    camera.pitch += window.mouseDeltaY() * mouseSensitivity;
                    camera.pitch = Math.max(-89.0f, Math.min(89.0f, camera.pitch));
                }

                // Clickable graphics menu (mouse)
                if (gfxMenu) {
                    float mx = window.mouseX();
                    float my = window.mouseY();
                    boolean pressed = window.wasMousePressed(Keys.MOUSE_LEFT);
                    boolean down = window.isMouseDown(Keys.MOUSE_LEFT);

                    MenuResult r = handleGraphicsMenuInput(mx, my, pressed, down, activeSlider,
                        fogEnabled, fogNear, fogFar, brightness, gamma, fovDeg, gloss,
                        postEnabled, fxaaEnabled, vignette, saturation, contrast,
                        bloomEnabled, bloomThreshold, bloomStrength,
                        godraysEnabled, godraysSamples, godraysStrength, godraysDensity, godraysWeight, godraysDecay, godraysEdgeFade,
                        fxaaSpan, texSmooth, texAniso, pixelSnap, windStrength,
                        shadowsEnabled, sunElevationDeg, shadowStrength, shadowSoftness, shadowDistance, shadowRes,
                        shadowResSteps,
                        texAnisoMax,
                        window.width(), window.height()
                    );

                    activeSlider = r.activeSlider;
                    fogEnabled = r.fogEnabled;
                    fogNear = r.fogNear;
                    fogFar = r.fogFar;
                    brightness = r.brightness;
                    gamma = r.gamma;
                    fovDeg = r.fovDeg;
                    gloss = r.gloss;
                    postEnabled = r.postEnabled;
                    fxaaEnabled = r.fxaaEnabled;
                    fxaaSpan = r.fxaaSpan;
                    vignette = r.vignette;
                    saturation = r.saturation;
                    contrast = r.contrast;
                    bloomEnabled = r.bloomEnabled;
                    bloomThreshold = r.bloomThreshold;
                    bloomStrength = r.bloomStrength;
                    godraysEnabled = r.godraysEnabled;
                    godraysSamples = r.godraysSamples;
                    godraysStrength = r.godraysStrength;
                    godraysDensity = r.godraysDensity;
                    godraysWeight = r.godraysWeight;
                    godraysDecay = r.godraysDecay;
                    godraysEdgeFade = r.godraysEdgeFade;
                    texSmooth = r.texSmooth;
                    texAniso = r.texAniso;
                    pixelSnap = r.pixelSnap;
                    windStrength = r.windStrength;
                    shadowsEnabled = r.shadowsEnabled;
                    sunElevationDeg = r.sunElevationDeg;
                    shadowStrength = r.shadowStrength;
                    shadowSoftness = r.shadowSoftness;
                    shadowDistance = r.shadowDistance;
                    shadowRes = r.shadowRes;
                }

                // Build menu (mouse)
                if (buildMenu) {
                    float mx = window.mouseX();
                    float my = window.mouseY();
                    boolean pressed = window.wasMousePressed(Keys.MOUSE_LEFT);
                    selectedPrefab = handleBuildMenuInput(mx, my, pressed, selectedPrefab, window.width(), window.height());
                }

                // Inventory (mouse)
                if (inventoryMenu) {
                    float mx = window.mouseX();
                    float my = window.mouseY();
                    boolean pressed = window.wasMousePressed(Keys.MOUSE_LEFT);
                    boolean down = window.isMouseDown(Keys.MOUSE_LEFT);
                    boolean released = invLeftWasDown && !down;
                    invLeftWasDown = down;

                    InventoryResult r = handleInventoryInput(mx, my, pressed, down, released, inventory, dragged, hotbar, selectedSlot, window.width(), window.height());
                    dragged = r.dragged;
                    selectedSlot = r.selectedSlot;
                }

                // Apply texture filtering if changed.
                if (texSmooth != lastTexSmooth || Math.abs(texAniso - lastTexAniso) > 0.01f || Math.abs(gloss - lastGloss) > 0.001f) {
                    atlas.texture().setFiltering(texSmooth, texAniso, gloss);
                    lastTexSmooth = texSmooth;
                    lastTexAniso = texAniso;
                    lastGloss = gloss;
                }

                // Walking movement on XZ plane (relative to yaw).
                // Disable movement while any menu is open.
                Vector3f input = new Vector3f();
                boolean sprint = false;
                if (!(gfxMenu || buildMenu || inventoryMenu)) {
                    if (window.isKeyDown(Keys.W)) input.z += 1.0f;
                    if (window.isKeyDown(Keys.S)) input.z -= 1.0f;
                    if (window.isKeyDown(Keys.A)) input.x -= 1.0f;
                    if (window.isKeyDown(Keys.D)) input.x += 1.0f;
                    sprint = window.isKeyDown(Keys.LEFT_CONTROL);
                }

                float yawRad = (float) Math.toRadians(camera.yaw);
                Vector3f forward = new Vector3f((float) Math.sin(yawRad), 0.0f, (float) -Math.cos(yawRad));
                Vector3f right = new Vector3f(-forward.z, 0.0f, forward.x);

                Vector3f wishDir = new Vector3f();
                wishDir.fma(input.z, forward);
                wishDir.fma(input.x, right);
                if (wishDir.lengthSquared() > 0.0001f) wishDir.normalize();

                float moveSpeed = walkSpeed * (sprint ? sprintMultiplier : 1.0f);
                velocity.x = wishDir.x * moveSpeed;
                velocity.z = wishDir.z * moveSpeed;

                // Gravity + jump
                float dt = time.deltaSeconds();
                velocity.y += gravity * dt;
                if (jumpCooldown > 0.0f) jumpCooldown -= dt;

                boolean wantJump = !(gfxMenu || buildMenu || inventoryMenu) && window.isKeyDown(Keys.SPACE);
                if (grounded) {
                    // Prevent accumulating small negative velocities when standing.
                    if (velocity.y < 0f) velocity.y = 0f;
                    // Auto-jump: hold SPACE to keep jumping.
                    if (wantJump && jumpCooldown <= 0.0f) {
                        velocity.y = jumpSpeed;
                        grounded = false;
                        jumpCooldown = 0.10f;
                    }
                }

                // Integrate + collide
                grounded = integrateAndCollide(world, playerPos, velocity, dt, playerRadius, playerHeight);

                // Update camera to eye position
                camera.position.set(playerPos.x, playerPos.y + eyeHeight, playerPos.z);

                // Ensure chunks exist + meshes built around player.
                chunkRenderer.update(playerPos.x, playerPos.z);

                // Raycast from camera for break/place.
                if (!(gfxMenu || buildMenu || inventoryMenu) && (window.wasMousePressed(Keys.MOUSE_LEFT) || window.wasMousePressed(Keys.MOUSE_RIGHT))) {
                    // Placer tool: place prefab even if we're not looking at a block.
                    if (window.wasMousePressed(Keys.MOUSE_RIGHT) && selectedSlot == placerSlot) {
                        placePrefabInFront(world, chunkRenderer, playerPos, camera, playerRadius, playerHeight, selectedPrefab, rng);
                    } else {
                        Vector3f dir = cameraForward(camera);
                        RaycastHit hit = Raycast.raycast(world, camera.position, dir, 6.0f);
                        if (hit != null) {
                            if (window.wasMousePressed(Keys.MOUSE_LEFT)) {
                                BlockType broken = world.getBlock(hit.x(), hit.y(), hit.z());
                                if (broken != BlockType.AIR) {
                                    world.setBlock(hit.x(), hit.y(), hit.z(), BlockType.AIR);
                                    markDirtyAround(chunkRenderer, hit.x(), hit.z());

                                    // Spawn a dropped item for solid blocks (skip cutouts like tall grass).
                                    if (broken != BlockType.TALL_GRASS) {
                                        spawnDrop(drops, broken,
                                            hit.x() + 0.5f, hit.y() + 0.55f, hit.z() + 0.5f,
                                            rng
                                        );
                                    }
                                }
                            } else if (window.wasMousePressed(Keys.MOUSE_RIGHT)) {
                                int px = hit.x() + hit.nx();
                                int py = hit.y() + hit.ny();
                                int pz = hit.z() + hit.nz();
                                if (world.getBlock(px, py, pz) == BlockType.AIR) {
                                    BlockType place = hotbar[selectedSlot];
                                    if (place != BlockType.AIR && !playerIntersectsBlock(playerPos, playerRadius, playerHeight, px, py, pz)) {
                                        world.setBlock(px, py, pz, place);
                                        markDirtyAround(chunkRenderer, px, pz);
                                    }
                                }
                            }
                        }
                    }
                }

                // Update dropped items (physics + pickup)
                updateDrops(world, drops, inventory, playerPos, playerRadius, playerHeight, dt);

                int w = window.width();
                int h = window.height();

                Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fovDeg), (float) w / (float) h, 0.05f, 250.0f);
                Matrix4f view = camera.viewMatrix();

                // Build light matrices for sun + shadows.
                // Light rays travel along uLightDir (sun -> world).
                Vector3f lightDir = computeSunLightDir(sunElevationDeg);
                float shadowDistanceClamped = Math.max(0.5f, shadowDistance);
                Matrix4f lightVP = buildStableLightVP(camera, lightDir, fovDeg, (float) w / (float) h, shadowDistanceClamped, shadowRes);

                boolean shadowsActive = shadowsEnabled && shadowDistance > 0.01f;

                // Gather nearby torch lights for simple cave/room lighting.
                int pointLightCount = gatherTorchLights(world, camera.position, 18.0f, plx, ply, plz, pls, plr);

                // Point-light shadow pass (nearest torch only).
                int shadowTorchIndex = -1;
                if (pointLightCount > 0) {
                    float bestD2 = Float.POSITIVE_INFINITY;
                    for (int i = 0; i < pointLightCount; i++) {
                        float dx = plx[i] - camera.position.x;
                        float dy = ply[i] - camera.position.y;
                        float dz = plz[i] - camera.position.z;
                        float d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 < bestD2) {
                            bestD2 = d2;
                            shadowTorchIndex = i;
                        }
                    }
                }

                if (shadowTorchIndex >= 0) {
                    Vector3f lightPos = new Vector3f(plx[shadowTorchIndex], ply[shadowTorchIndex], plz[shadowTorchIndex]);

                    Matrix4f shadowProj = new Matrix4f().perspective((float) Math.toRadians(90.0f), 1.0f, POINT_SHADOW_NEAR, POINT_SHADOW_FAR);
                    Matrix4f[] views = new Matrix4f[] {
                        new Matrix4f().lookAt(lightPos, new Vector3f(lightPos).add( 1,  0,  0), new Vector3f(0, -1,  0)),
                        new Matrix4f().lookAt(lightPos, new Vector3f(lightPos).add(-1,  0,  0), new Vector3f(0, -1,  0)),
                        new Matrix4f().lookAt(lightPos, new Vector3f(lightPos).add( 0,  1,  0), new Vector3f(0,  0,  1)),
                        new Matrix4f().lookAt(lightPos, new Vector3f(lightPos).add( 0, -1,  0), new Vector3f(0,  0, -1)),
                        new Matrix4f().lookAt(lightPos, new Vector3f(lightPos).add( 0,  0,  1), new Vector3f(0, -1,  0)),
                        new Matrix4f().lookAt(lightPos, new Vector3f(lightPos).add( 0,  0, -1), new Vector3f(0, -1,  0)),
                    };

                    pointShadowMap.begin(POINT_SHADOW_RES);
                    glEnable(GL_DEPTH_TEST);
                    glEnable(GL_CULL_FACE);
                    glCullFace(GL_BACK);
                    glEnable(GL_POLYGON_OFFSET_FILL);
                    glPolygonOffset(2.2f, 4.0f);

                    pointShadowShader.bind();
                    pointShadowShader.setUniformMat4("uModel", new Matrix4f());
                    pointShadowShader.setUniform3f("uLightPos", lightPos.x, lightPos.y, lightPos.z);
                    pointShadowShader.setUniform1f("uFar", POINT_SHADOW_FAR);

                    for (int face = 0; face < 6; face++) {
                        pointShadowMap.beginFace(face);
                        Matrix4f lightVPFace = new Matrix4f(shadowProj).mul(views[face]);
                        pointShadowShader.setUniformMat4("uLightVP", lightVPFace);
                        chunkRenderer.drawNear(pointShadowShader, lightPos.x, lightPos.z, POINT_SHADOW_FAR);
                    }

                    glDisable(GL_POLYGON_OFFSET_FILL);
                    pointShadowMap.end();
                }

                // Shadow pass
                if (shadowsActive) {
                    shadowMap.begin(shadowRes);
                    glEnable(GL_DEPTH_TEST);
                    glEnable(GL_CULL_FACE);
                    glCullFace(GL_BACK);
                    glEnable(GL_POLYGON_OFFSET_FILL);
                    glPolygonOffset(2.2f, 4.0f);
                    glClear(GL_DEPTH_BUFFER_BIT);

                    shadowShader.bind();
                    shadowShader.setUniformMat4("uLightVP", lightVP);
                    shadowShader.setUniformMat4("uModel", new Matrix4f());

                    // Atlas needed for cutout alpha (tall grass) in shadow.frag
                    glActiveTexture(GL_TEXTURE0);
                    atlas.texture().bind();
                    shadowShader.setUniform1i("uTex", 0);

                    chunkRenderer.drawAll(shadowShader);

                    // Dropped items should cast shadows too.
                    for (DroppedItem d : drops) {
                        ChunkMesh m = getDropMesh(dropMeshes, blockTextures, d.type);
                        if (m == null || m.vertexCount() <= 0) continue;

                        Matrix4f itemModel = new Matrix4f()
                            .translate(d.pos)
                            .rotateY(d.rotY)
                            .scale(0.28f);
                        shadowShader.setUniformMat4("uModel", itemModel);
                        m.draw();
                    }

                    glDisable(GL_POLYGON_OFFSET_FILL);

                    shadowMap.end();
                }

                // PASS A: render the scene into the HDR scene framebuffer (always).
                post.beginScene(w, h);
                glClearColor(0f, 0f, 0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

                // Skybox (draw first)
                Matrix4f invProj = new Matrix4f(proj).invert();
                Matrix4f viewRot = new Matrix4f(view);
                viewRot.m30(0f);
                viewRot.m31(0f);
                viewRot.m32(0f);
                Matrix4f invViewRot = new Matrix4f(viewRot).invert();
                skybox.draw(skyTex, invProj, invViewRot);

                Matrix4f model = new Matrix4f();

                shader.bind();
                shader.setUniformMat4("uProj", proj);
                shader.setUniformMat4("uView", view);
                shader.setUniformMat4("uModel", new Matrix4f().translate(0f, -60f, 0f));
                shader.setUniform1f("uTime", time.totalSeconds());
                shader.setUniform1f("uWindStrength", windStrength);
                // Output linear HDR from the world shader. Post handles exposure/gamma/fog.
                shader.setUniform1f("uBrightness", 1.0f);
                shader.setUniform1f("uGamma", 1.0f);
                shader.setUniform1i("uFogEnabled", 0);
                shader.setUniform3f("uFogColor", 0.65f, 0.80f, 0.99f);
                shader.setUniform1f("uFogNear", fogNear);
                shader.setUniform1f("uFogFar", fogFar);

                shader.setUniform3f("uLightDir", lightDir.x, lightDir.y, lightDir.z);
                shader.setUniform1i("uPixelSnap", pixelSnap ? 1 : 0);
                shader.setUniform1f("uAmbient", 0.35f);
                shader.setUniform1f("uSun", 0.85f);

                shader.setUniformMat4("uLightVP", lightVP);
                shader.setUniform1i("uShadowsEnabled", shadowsActive ? 1 : 0);
                shader.setUniform1f("uShadowStrength", shadowStrength);
                shader.setUniform1f("uShadowSoftness", shadowSoftness);
                shader.setUniform1f("uSkyMul", 1.0f);

                // Point lights (torches)
                shader.setUniform1i("uPointLightCount", pointLightCount);
                for (int i = 0; i < pointLightCount; i++) {
                    shader.setUniform3f("uPointLightPos[" + i + "]", plx[i], ply[i], plz[i]);
                    shader.setUniform1f("uPointLightStrength[" + i + "]", pls[i]);
                    shader.setUniform1f("uPointLightRadius[" + i + "]", plr[i]);
                }

                glActiveTexture(GL_TEXTURE0);
                atlas.texture().bind();
                shader.setUniform1i("uTex", 0);

                // Shadow map on unit 1
                shadowMap.bindDepthTexture(1);
                shader.setUniform1i("uShadowMap", 1);

                // Point shadow cubemap on unit 2 (nearest torch only)
                if (shadowTorchIndex >= 0) {
                    pointShadowMap.bindDepthCubeTexture(2);
                    shader.setUniform1i("uPointShadowsEnabled", 1);
                    shader.setUniform1i("uPointShadowMap", 2);
                    shader.setUniform1i("uPointShadowIndex", shadowTorchIndex);
                    shader.setUniform3f("uPointShadowPos", plx[shadowTorchIndex], ply[shadowTorchIndex], plz[shadowTorchIndex]);
                    shader.setUniform1f("uPointShadowFar", POINT_SHADOW_FAR);
                    shader.setUniform1f("uPointShadowStrength", 0.95f);
                } else {
                    shader.setUniform1i("uPointShadowsEnabled", 0);
                    shader.setUniform1i("uPointShadowIndex", -1);
                    shader.setUniform1f("uPointShadowStrength", 0.0f);
                }

                chunkRenderer.drawAll(shader);
                // Draw water with waterShader
                // Enable blending and set depth mask for transparent water
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glDepthMask(true);
                waterShader.bind();
                Matrix4f viewProj = new Matrix4f(proj).mul(view);
                waterShader.setUniformMat4("uViewProj", viewProj);
                waterShader.setUniformMat4("uModel", model);
                waterShader.setUniform1f("uTime", time.totalSeconds());
                // Bind Wasser-Normalmap auf Texture Unit 0
                glActiveTexture(GL_TEXTURE0);
                waterNormalMap.bind();
                waterShader.setUniform1i("uNormalMap", 0);
                waterShader.setUniform1f("uWindStrength", windStrength);
                chunkRenderer.drawAllWater(waterShader);
                // Restore depth mask and disable blending
                glDepthMask(true);
                glDisable(GL_BLEND);

                // Draw dropped items as small cubes.
                for (DroppedItem d : drops) {
                    ChunkMesh m = getDropMesh(dropMeshes, blockTextures, d.type);
                    if (m == null || m.vertexCount() <= 0) continue;

                    // Upright cube; only spin around Y.
                    Matrix4f itemModel = new Matrix4f()
                        .translate(d.pos)
                        .rotateY(d.rotY)
                        .scale(0.28f);

                    shader.setUniformMat4("uModel", itemModel);
                    shader.setUniform1f("uSkyMul", clamp(d.skyMul, 0f, 1f));
                    m.draw();
                }

                // Restore for any later draws.
                shader.setUniformMat4("uModel", model);
                shader.setUniform1f("uSkyMul", 1.0f);

                // PASS B/C: post stack and final output to default framebuffer.
                post.endAndDraw(
                    w, h,
                    postEnabled,
                    fxaaEnabled,
                    fxaaSpan,
                    vignette,
                    saturation,
                    contrast,
                    fogEnabled,
                    fogNear,
                    fogFar,
                    bloomEnabled,
                    bloomThreshold,
                    bloomStrength,
                    godraysEnabled,
                    godraysSamples,
                    godraysStrength,
                    godraysDensity,
                    godraysWeight,
                    godraysDecay,
                    godraysEdgeFade,
                    brightness,
                    gamma,
                    proj,
                    view,
                    camera.position,
                    lightDir
                );

                // Draw held item AFTER post (HUD-like), so it isn't affected by fog/bloom.
                {
                    BlockType held = hotbar[selectedSlot];
                    if (selectedSlot != placerSlot && held != BlockType.AIR) {
                        ChunkMesh m = getDropMesh(dropMeshes, blockTextures, held);
                        if (m != null && m.vertexCount() > 0) {
                            shader.bind();

                            // Ensure atlas is bound.
                            glActiveTexture(GL_TEXTURE0);
                            atlas.texture().bind();
                            shader.setUniform1i("uTex", 0);

                            Matrix4f heldView = new Matrix4f();
                            Matrix4f heldProj = new Matrix4f().perspective((float) Math.toRadians(50.0f), (float) w / (float) h, 0.01f, 10.0f);
                            Matrix4f heldModel = new Matrix4f()
                                .translate(0.55f, -0.40f, -1.45f)
                                .rotateY(0.70f)
                                .scale(0.36f);

                            shader.setUniformMat4("uProj", heldProj);
                            shader.setUniformMat4("uView", heldView);
                            shader.setUniformMat4("uModel", heldModel);
                            shader.setUniform1f("uTime", time.totalSeconds());
                            shader.setUniform1f("uWindStrength", windStrength);
                            shader.setUniform1f("uBrightness", brightness);
                            shader.setUniform1f("uGamma", gamma);
                            shader.setUniform1i("uFogEnabled", 0);
                            shader.setUniform1i("uShadowsEnabled", 0);
                            shader.setUniform1i("uPixelSnap", pixelSnap ? 1 : 0);
                            shader.setUniform1f("uAmbient", 1.0f);
                            shader.setUniform1f("uSun", 0.0f);
                            shader.setUniform1f("uSkyMul", 1.0f);
                            shader.setUniform1i("uPointLightCount", 0);

                            glDisable(GL_DEPTH_TEST);
                            glEnable(GL_CULL_FACE);
                            glCullFace(GL_BACK);
                            m.draw();
                            glEnable(GL_DEPTH_TEST);

                            // Restore world uniforms for any later draws.
                            shader.setUniformMat4("uProj", proj);
                            shader.setUniformMat4("uView", view);
                            shader.setUniformMat4("uModel", model);
                            shader.setUniform1f("uTime", time.totalSeconds());
                            shader.setUniform1f("uWindStrength", windStrength);
                            shader.setUniform1f("uBrightness", 1.0f);
                            shader.setUniform1f("uGamma", 1.0f);
                            shader.setUniform1i("uFogEnabled", 0);
                            shader.setUniform1i("uShadowsEnabled", shadowsActive ? 1 : 0);
                            shader.setUniform1i("uPixelSnap", pixelSnap ? 1 : 0);
                            shader.setUniform1f("uAmbient", 0.35f);
                            shader.setUniform1f("uSun", 0.85f);
                            shader.setUniform1i("uPointLightCount", pointLightCount);
                        }
                    }
                }

                // UI overlay (hotbar)
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_CULL_FACE);
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                // Ensure atlas is bound for UI icons.
                glActiveTexture(GL_TEXTURE0);
                atlas.texture().bind();

                drawHotbar(ui, uiText, blockTextures, hotbar, w, h, selectedSlot, placerSlot);
                drawCrosshair(ui, w, h);

                // FPS counter (top-left)
                String selName;
                int selCount;
                if (selectedSlot == placerSlot) {
                    selName = "PLACER";
                    selCount = 0;
                } else {
                    BlockType sel = hotbar[selectedSlot];
                    selCount = getCount(inventory, sel);
                    selName = sel.name().replace('_', ' ');
                }
                uiText.draw(ui, 10f, 10f, w, h,
                    "FPS " + (int) Math.round(fpsValue) + "   " + selName + " x" + selCount,
                    2.0f, 1f, 1f, 1f, 0.90f
                );

                if (gfxMenu) {
                    drawGraphicsMenu(ui, uiText, w, h, activeSlider,
                        fogEnabled, fogNear, fogFar, brightness, gamma, fovDeg, gloss,
                        postEnabled, fxaaEnabled, fxaaSpan, vignette, saturation, contrast,
                        bloomEnabled, bloomThreshold, bloomStrength,
                        godraysEnabled, godraysSamples, godraysStrength, godraysDensity, godraysWeight, godraysDecay, godraysEdgeFade,
                        texSmooth, texAniso, pixelSnap, windStrength,
                        shadowsEnabled, sunElevationDeg, shadowStrength, shadowSoftness, shadowDistance, shadowRes,
                        shadowResSteps,
                        texAnisoMax
                    );
                }

                if (buildMenu) {
                    drawBuildMenu(ui, uiText, w, h, selectedPrefab);
                }

                if (inventoryMenu) {
                    drawInventory(ui, uiText, blockTextures, w, h, inventory, hotbar, selectedSlot, dragged, window.mouseX(), window.mouseY(), window.isMouseDown(Keys.MOUSE_LEFT));
                }

                glDisable(GL_BLEND);
                glEnable(GL_CULL_FACE);
                glEnable(GL_DEPTH_TEST);

                window.swapBuffers();
            }

            chunkRenderer.close();
            for (ChunkMesh m : dropMeshes.values()) {
                try { m.close(); } catch (Exception ignored) {}
            }
            dropMeshes.clear();
            atlas.texture().close();
            skyTex.close();
            shadowMap.close();
            pointShadowMap.close();
            post.close();
            ui.close();
            skybox.close();
            shader.close();
            shadowShader.close();
            pointShadowShader.close();
        }
    }

    private static Matrix4f buildStableLightVP(Camera camera, Vector3f lightDir, float fovDeg, float aspect, float shadowDistance, int shadowRes) {
        Vector3f camPos = new Vector3f(camera.position);
        Vector3f forward = cameraForward(camera);
        Vector3f worldUp = new Vector3f(0f, 1f, 0f);

        Vector3f right = new Vector3f(forward).cross(worldUp);
        if (right.lengthSquared() < 1e-6f) right.set(1f, 0f, 0f);
        right.normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();

        float fovY = (float) Math.toRadians(fovDeg);
        float near = 0.05f;
        float far = shadowDistance;
        float tan = (float) Math.tan(fovY * 0.5f);
        float nh = near * tan;
        float nw = nh * aspect;
        float fh = far * tan;
        float fw = fh * aspect;

        Vector3f cn = new Vector3f(camPos).fma(near, forward);
        Vector3f cf = new Vector3f(camPos).fma(far, forward);

        Vector3f[] corners = new Vector3f[8];
        corners[0] = new Vector3f(cn).fma(-nw, right).fma(-nh, up);
        corners[1] = new Vector3f(cn).fma( nw, right).fma(-nh, up);
        corners[2] = new Vector3f(cn).fma( nw, right).fma( nh, up);
        corners[3] = new Vector3f(cn).fma(-nw, right).fma( nh, up);
        corners[4] = new Vector3f(cf).fma(-fw, right).fma(-fh, up);
        corners[5] = new Vector3f(cf).fma( fw, right).fma(-fh, up);
        corners[6] = new Vector3f(cf).fma( fw, right).fma( fh, up);
        corners[7] = new Vector3f(cf).fma(-fw, right).fma( fh, up);

        Vector3f center = new Vector3f();
        for (Vector3f c : corners) center.add(c);
        center.mul(1f / 8f);

        Vector3f lightPos = new Vector3f(center).sub(new Vector3f(lightDir).mul(220.0f));
        Vector3f lf = new Vector3f(center).sub(lightPos);
        if (lf.lengthSquared() < 1e-6f) lf.set(lightDir);
        lf.normalize();
        Vector3f lRight = new Vector3f(lf).cross(worldUp);
        if (lRight.lengthSquared() < 1e-6f) lRight.set(1f, 0f, 0f);
        lRight.normalize();
        Vector3f lUp = new Vector3f(lRight).cross(lf).normalize();

        Matrix4f lightView = new Matrix4f().lookAt(lightPos, center, lUp);

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (Vector3f c : corners) {
            Vector4f ls = new Vector4f(c, 1f).mul(lightView);
            minX = Math.min(minX, ls.x);
            minY = Math.min(minY, ls.y);
            minZ = Math.min(minZ, ls.z);
            maxX = Math.max(maxX, ls.x);
            maxY = Math.max(maxY, ls.y);
            maxZ = Math.max(maxZ, ls.z);
        }

        float marginXY = 12.0f;
        float marginZ = 80.0f;
        float left = minX - marginXY;
        float rightB = maxX + marginXY;
        float bottom = minY - marginXY;
        float top = maxY + marginXY;

        // Snap bounds to texels for stability.
        float width = rightB - left;
        float height = top - bottom;
        float texelX = width / (float) shadowRes;
        float texelY = height / (float) shadowRes;
        float cX = (left + rightB) * 0.5f;
        float cY = (bottom + top) * 0.5f;
        float snapCX = Math.round(cX / texelX) * texelX;
        float snapCY = Math.round(cY / texelY) * texelY;
        float dx = snapCX - cX;
        float dy = snapCY - cY;
        left += dx;
        rightB += dx;
        bottom += dy;
        top += dy;

        // JOML ortho zNear/zFar are distances along -Z in view space.
        float zNear = -maxZ - marginZ;
        float zFar = -minZ + marginZ;

        Matrix4f lightProj = new Matrix4f().ortho(left, rightB, bottom, top, zNear, zFar);
        return new Matrix4f(lightProj).mul(lightView);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int gatherTorchLights(World world, Vector3f center, float searchRadius,
                                         float[] outPosX, float[] outPosY, float[] outPosZ,
                                         float[] outStrength, float[] outRadius) {
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);

        int r = (int) Math.ceil(searchRadius);
        int minX = cx - r;
        int maxX = cx + r;
        int minY = Math.max(0, cy - r);
        int maxY = Math.min(Chunk.HEIGHT - 1, cy + r);
        int minZ = cz - r;
        int maxZ = cz + r;

        float r2 = searchRadius * searchRadius;
        int count = 0;

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (world.getBlock(x, y, z) != BlockType.TORCH) continue;

                    float lx = x + 0.5f;
                    float ly = y + 0.70f; // near flame height
                    float lz = z + 0.5f;

                    float dx = lx - center.x;
                    float dy = ly - center.y;
                    float dz = lz - center.z;
                    float d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > r2) continue;

                    if (count >= MAX_POINT_LIGHTS) return count;
                    outPosX[count] = lx;
                    outPosY[count] = ly;
                    outPosZ[count] = lz;
                    outStrength[count] = 0.85f;
                    outRadius[count] = 11.5f;
                    count++;
                }
            }
        }

        return count;
    }

    private static void spawnDrop(ArrayList<DroppedItem> drops, BlockType type, float x, float y, float z, Random rng) {
        DroppedItem d = new DroppedItem(type,
            x + (rng.nextFloat() - 0.5f) * 0.15f,
            y,
            z + (rng.nextFloat() - 0.5f) * 0.15f
        );
        d.vel.set(
            (rng.nextFloat() - 0.5f) * 2.0f,
            4.5f + rng.nextFloat() * 2.0f,
            (rng.nextFloat() - 0.5f) * 2.0f
        );
        d.rotY = rng.nextFloat() * (float) (Math.PI * 2.0);
        drops.add(d);
    }

    private static void updateDrops(World world, ArrayList<DroppedItem> drops, EnumMap<BlockType, Integer> inventory,
                                    Vector3f playerFeetPos, float playerRadius, float playerHeight, float dt) {
        if (drops.isEmpty()) return;

        Vector3f playerCenter = new Vector3f(playerFeetPos.x, playerFeetPos.y + playerHeight * 0.5f, playerFeetPos.z);

        final float gravity = -22.0f;
        final float half = 0.14f;
        final float eps = 0.001f;

        for (Iterator<DroppedItem> it = drops.iterator(); it.hasNext();) {
            DroppedItem d = it.next();
            d.age += dt;

            // Despawn after a while.
            if (d.age > 90.0f) {
                it.remove();
                continue;
            }

            // Spin slowly.
            d.rotY += dt * 1.8f;

            // Gravity
            d.vel.y += gravity * dt;

            // Integrate + collide (AABB)
            boolean hitY = integrateAndCollideAabb(world, d.pos, d.vel, dt, half, eps);
            if (hitY && d.vel.y == 0f) {
                // Apply a bit of friction when on ground.
                d.vel.x *= 0.75f;
                d.vel.z *= 0.75f;
            }

            // Simple skylight factor for entity lighting: 1 if open to sky, else 0.
            d.skyMul = hasSkyAbove(world, d.pos.x, d.pos.y + half, d.pos.z) ? 1.0f : 0.0f;

            // Pickup
            if (d.age > 0.15f) {
                float dx = d.pos.x - playerCenter.x;
                float dy = d.pos.y - playerCenter.y;
                float dz = d.pos.z - playerCenter.z;
                float dist2 = dx * dx + dy * dy + dz * dz;
                if (dist2 < (1.15f * 1.15f)) {
                    addCount(inventory, d.type, 1);
                    it.remove();
                }
            }
        }

        // Keep drops from sitting inside each other (simple repulsion).
        resolveDropOverlaps(drops, dt);
    }

    private static void resolveDropOverlaps(ArrayList<DroppedItem> drops, float dt) {
        int n = drops.size();
        if (n < 2) return;

        // Visual/physics size for the cube we render: scale(0.28) => ~0.28m wide.
        // Use a slightly larger minimum distance so they appear separated.
        final float minDist = 0.32f;
        final float minDist2 = minDist * minDist;
        final float velPush = 2.0f;
        final float maxPush = 0.03f;
        final float maxVel = 6.0f;

        for (int i = 0; i < n; i++) {
            DroppedItem a = drops.get(i);
            for (int j = i + 1; j < n; j++) {
                DroppedItem b = drops.get(j);

                float dx = b.pos.x - a.pos.x;
                float dz = b.pos.z - a.pos.z;
                float d2 = dx * dx + dz * dz;

                if (d2 >= minDist2) continue;

                // If perfectly overlapping in XZ, pick a stable pseudo-direction.
                if (d2 < 1e-8f) {
                    float t = (float) ((i * 92837111L + j * 689287499L) & 1023L) / 1023.0f;
                    float ang = t * (float) (Math.PI * 2.0);
                    dx = (float) Math.cos(ang);
                    dz = (float) Math.sin(ang);
                    d2 = dx * dx + dz * dz;
                }

                float dist = (float) Math.sqrt(d2);
                float nx = dx / dist;
                float nz = dz / dist;

                float push = (minDist - dist) * 0.5f;
                push = clamp(push, 0.0f, maxPush);

                // Separate positions in XZ (avoid fighting gravity/ground).
                a.pos.x -= nx * push;
                a.pos.z -= nz * push;
                b.pos.x += nx * push;
                b.pos.z += nz * push;

                // Add a small velocity impulse so they continue to drift apart.
                float impulse = velPush * push;
                a.vel.x -= nx * impulse;
                a.vel.z -= nz * impulse;
                b.vel.x += nx * impulse;
                b.vel.z += nz * impulse;

                // Clamp horizontal speed to avoid "yeeting".
                float aV2 = a.vel.x * a.vel.x + a.vel.z * a.vel.z;
                if (aV2 > maxVel * maxVel) {
                    float s = maxVel / (float) Math.sqrt(aV2);
                    a.vel.x *= s;
                    a.vel.z *= s;
                }
                float bV2 = b.vel.x * b.vel.x + b.vel.z * b.vel.z;
                if (bV2 > maxVel * maxVel) {
                    float s = maxVel / (float) Math.sqrt(bV2);
                    b.vel.x *= s;
                    b.vel.z *= s;
                }
            }
        }
    }

    private static boolean integrateAndCollideAabb(World world, Vector3f center, Vector3f vel, float dt, float half, float eps) {
        boolean hitY = false;

        float dx = vel.x * dt;
        if (dx != 0f) {
            center.x += dx;
            float corr = aabbCorrectionX(world, center, half, eps, dx);
            if (corr != 0f) {
                center.x += corr;
                vel.x = 0f;
            }
        }

        float dz = vel.z * dt;
        if (dz != 0f) {
            center.z += dz;
            float corr = aabbCorrectionZ(world, center, half, eps, dz);
            if (corr != 0f) {
                center.z += corr;
                vel.z = 0f;
            }
        }

        float dy = vel.y * dt;
        if (dy != 0f) {
            center.y += dy;
            float corr = aabbCorrectionY(world, center, half, eps, dy);
            if (corr != 0f) {
                center.y += corr;
                if (dy < 0f) {
                    // Bounce a bit, then settle.
                    vel.y = -vel.y * 0.25f;
                    if (Math.abs(vel.y) < 1.2f) vel.y = 0f;
                    hitY = true;
                } else {
                    vel.y = 0f;
                }
            }
        }

        return hitY;
    }

    private static boolean aabbCollides(World world, Vector3f center, float half, float eps) {
        float aMinX = center.x - half;
        float aMaxX = center.x + half;
        float aMinY = center.y - half;
        float aMaxY = center.y + half;
        float aMinZ = center.z - half;
        float aMaxZ = center.z + half;

        int minX = (int) Math.floor(aMinX);
        int maxX = (int) Math.floor(aMaxX - eps);
        int minY = (int) Math.floor(aMinY);
        int maxY = (int) Math.floor(aMaxY - eps);
        int minZ = (int) Math.floor(aMinZ);
        int maxZ = (int) Math.floor(aMaxZ - eps);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isSolid(world.getBlock(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    private static float aabbCorrectionX(World world, Vector3f center, float half, float eps, float dx) {
        if (!aabbCollides(world, center, half, eps)) return 0f;

        float aMinX = center.x - half;
        float aMaxX = center.x + half;
        float aMinY = center.y - half;
        float aMaxY = center.y + half;
        float aMinZ = center.z - half;
        float aMaxZ = center.z + half;

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
                        if (!isSolid(world.getBlock(x, y, z))) continue;
                        float blockMinX = x;
                        float corr = (blockMinX - aMaxX) - eps;
                        if (corr > best) best = corr;
                    }
                }
            }
            return (best == Float.NEGATIVE_INFINITY) ? 0f : best;
        } else {
            float best = Float.POSITIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!isSolid(world.getBlock(x, y, z))) continue;
                        float blockMaxX = x + 1f;
                        float corr = (blockMaxX - aMinX) + eps;
                        if (corr < best) best = corr;
                    }
                }
            }
            return (best == Float.POSITIVE_INFINITY) ? 0f : best;
        }
    }

    private static float aabbCorrectionZ(World world, Vector3f center, float half, float eps, float dz) {
        if (!aabbCollides(world, center, half, eps)) return 0f;

        float aMinX = center.x - half;
        float aMaxX = center.x + half;
        float aMinY = center.y - half;
        float aMaxY = center.y + half;
        float aMinZ = center.z - half;
        float aMaxZ = center.z + half;

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
                        if (!isSolid(world.getBlock(x, y, z))) continue;
                        float blockMinZ = z;
                        float corr = (blockMinZ - aMaxZ) - eps;
                        if (corr > best) best = corr;
                    }
                }
            }
            return (best == Float.NEGATIVE_INFINITY) ? 0f : best;
        } else {
            float best = Float.POSITIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!isSolid(world.getBlock(x, y, z))) continue;
                        float blockMaxZ = z + 1f;
                        float corr = (blockMaxZ - aMinZ) + eps;
                        if (corr < best) best = corr;
                    }
                }
            }
            return (best == Float.POSITIVE_INFINITY) ? 0f : best;
        }
    }

    private static float aabbCorrectionY(World world, Vector3f center, float half, float eps, float dy) {
        if (!aabbCollides(world, center, half, eps)) return 0f;

        float aMinX = center.x - half;
        float aMaxX = center.x + half;
        float aMinY = center.y - half;
        float aMaxY = center.y + half;
        float aMinZ = center.z - half;
        float aMaxZ = center.z + half;

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
                        if (!isSolid(world.getBlock(x, y, z))) continue;
                        float blockMinY = y;
                        float corr = (blockMinY - aMaxY) - eps;
                        if (corr > best) best = corr;
                    }
                }
            }
            return (best == Float.NEGATIVE_INFINITY) ? 0f : best;
        } else {
            float best = Float.POSITIVE_INFINITY;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!isSolid(world.getBlock(x, y, z))) continue;
                        float blockMaxY = y + 1f;
                        float corr = (blockMaxY - aMinY) + eps;
                        if (corr < best) best = corr;
                    }
                }
            }
            return (best == Float.POSITIVE_INFINITY) ? 0f : best;
        }
    }

    private static boolean playerIntersectsBlock(Vector3f playerFeetPos, float radius, float height, int bx, int by, int bz) {
        float pMinX = playerFeetPos.x - radius;
        float pMaxX = playerFeetPos.x + radius;
        float pMinY = playerFeetPos.y;
        float pMaxY = playerFeetPos.y + height;
        float pMinZ = playerFeetPos.z - radius;
        float pMaxZ = playerFeetPos.z + radius;

        float bMinX = bx;
        float bMaxX = bx + 1f;
        float bMinY = by;
        float bMaxY = by + 1f;
        float bMinZ = bz;
        float bMaxZ = bz + 1f;

        return (pMinX < bMaxX && pMaxX > bMinX) &&
               (pMinY < bMaxY && pMaxY > bMinY) &&
               (pMinZ < bMaxZ && pMaxZ > bMinZ);
    }

    private static boolean hasSkyAbove(World world, float x, float y, float z) {
        int wx = (int) Math.floor(x);
        int wz = (int) Math.floor(z);
        int startY = (int) Math.floor(y);
        for (int yy = Chunk.HEIGHT - 1; yy > startY; yy--) {
            if (world.getBlock(wx, yy, wz) != BlockType.AIR) return false;
        }
        return true;
    }

    private static int getCount(EnumMap<BlockType, Integer> inv, BlockType t) {
        Integer v = inv.get(t);
        return v == null ? 0 : v;
    }

    private static void addCount(EnumMap<BlockType, Integer> inv, BlockType t, int delta) {
        int cur = getCount(inv, t);
        int next = cur + delta;
        if (next <= 0) inv.remove(t);
        else inv.put(t, next);
    }

    private static ChunkMesh getDropMesh(EnumMap<BlockType, ChunkMesh> cache, BlockTextures textures, BlockType type) {
        ChunkMesh m = cache.get(type);
        if (m != null) return m;
        ChunkMesh built = buildDropCubeMesh(textures, type);
        cache.put(type, built);
        return built;
    }

    private static ChunkMesh buildDropCubeMesh(BlockTextures textures, BlockType type) {
        // Local-space cube centered at origin, compatible with voxel shader format:
        // pos(3), uv(2), normal(3), sky(1), emissive(1)
        FloatBuilder verts = new FloatBuilder(6 * 6 * 10);

        float x0 = -0.5f, x1 = 0.5f;
        float y0 = -0.5f, y1 = 0.5f;
        float z0 = -0.5f, z1 = 0.5f;

        // Face order must match BlockTextures uv() convention in ChunkMesher.
        addDropFace(verts, textures.uv(type, 0), x1, y0, z1,  x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  1f, 0f, 0f);
        addDropFace(verts, textures.uv(type, 1), x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0, -1f, 0f, 0f);
        addDropFace(verts, textures.uv(type, 2), x0, y1, z1,  x1, y1, z1,  x1, y1, z0,  x0, y1, z0,  0f, 1f, 0f);
        addDropFace(verts, textures.uv(type, 3), x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,  0f,-1f, 0f);
        addDropFace(verts, textures.uv(type, 4), x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,  0f, 0f, 1f);
        addDropFace(verts, textures.uv(type, 5), x1, y0, z0,  x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  0f, 0f,-1f);

        float[] arr = verts.toArray();
        int vertexCount = arr.length / 10;
        return new ChunkMesh(arr, vertexCount);
    }

    private static void addDropFace(FloatBuilder out, UvRect uv,
                                    float bx, float by, float bz,
                                    float rx, float ry, float rz,
                                    float tx, float ty, float tz,
                                    float lx, float ly, float lz,
                                    float nx, float ny, float nz) {
        float u0 = uv.u0();
        float v0 = uv.v0();
        float u1 = uv.u1();
        float v1 = uv.v1();

        // Two triangles: (bl, br, tr) (bl, tr, tl)
        vDrop(out, bx, by, bz, u0, v0, nx, ny, nz);
        vDrop(out, rx, ry, rz, u1, v0, nx, ny, nz);
        vDrop(out, tx, ty, tz, u1, v1, nx, ny, nz);

        vDrop(out, bx, by, bz, u0, v0, nx, ny, nz);
        vDrop(out, tx, ty, tz, u1, v1, nx, ny, nz);
        vDrop(out, lx, ly, lz, u0, v1, nx, ny, nz);
    }

    private static void vDrop(FloatBuilder out, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        out.add(x);
        out.add(y);
        out.add(z);
        out.add(u);
        out.add(v);
        out.add(nx);
        out.add(ny);
        out.add(nz);
        out.add(1.0f); // sky vertex attr; multiplied by uSkyMul
        out.add(0.0f); // emissive
    }

    private static void drawHotbar(UiRenderer ui, UiText text, BlockTextures blockTextures, BlockType[] hotbar, int screenW, int screenH, int selected, int placerSlot) {
        float slot = 44f;
        float gap = 6f;
        float totalW = 9f * slot + 8f * gap;
        float x0 = (screenW - totalW) * 0.5f;
        float y0 = screenH - 18f - slot;

        // background bar
        ui.drawRect(x0 - 10f, y0 - 10f, totalW + 20f, slot + 20f, screenW, screenH, 0f, 0f, 0f, 0.35f);

        for (int i = 0; i < 9; i++) {
            float x = x0 + i * (slot + gap);
            float r = 0.18f, g = 0.18f, b = 0.18f, a = 0.75f;
            if (i == selected) {
                r = 0.95f; g = 0.95f; b = 0.95f; a = 0.85f;
                ui.drawRect(x - 2f, y0 - 2f, slot + 4f, slot + 4f, screenW, screenH, 0f, 0f, 0f, 0.70f);
            }
            ui.drawRect(x, y0, slot, slot, screenW, screenH, r, g, b, a);

            float pad = 6f;
            float iw = slot - pad * 2f;
            float ih = slot - pad * 2f;

            if (i == placerSlot) {
                ui.drawRect(x + pad, y0 + pad, iw, ih, screenW, screenH, 0f, 0f, 0f, 0.35f);
                text.draw(ui, x + pad + 4f, y0 + pad + 10f, screenW, screenH, "BUILD", 2.0f, 1f, 1f, 1f, 0.90f);
            } else {
                // Item icon (use top texture as a simple icon)
                BlockType t = hotbar[i];
                UvRect uv = blockTextures.uv(t, 2);
                ui.drawTexturedRect(x + pad, y0 + pad, iw, ih, screenW, screenH, uv.u0(), uv.v0(), uv.u1(), uv.v1(), 1.0f);
            }
        }
    }

    private static void drawCrosshair(UiRenderer ui, int screenW, int screenH) {
        float cx = screenW * 0.5f;
        float cy = screenH * 0.5f;

        float len = 10f;
        float thick = 2f;
        float gap = 3f;

        // Horizontal
        ui.drawRect(cx - (gap + len), cy - thick * 0.5f, len, thick, screenW, screenH, 1f, 1f, 1f, 0.90f);
        ui.drawRect(cx + gap, cy - thick * 0.5f, len, thick, screenW, screenH, 1f, 1f, 1f, 0.90f);
        // Vertical
        ui.drawRect(cx - thick * 0.5f, cy - (gap + len), thick, len, screenW, screenH, 1f, 1f, 1f, 0.90f);
        ui.drawRect(cx - thick * 0.5f, cy + gap, thick, len, screenW, screenH, 1f, 1f, 1f, 0.90f);
    }

    private record BuildMenuLayout(
        float x,
        float y,
        float w,
        float h,
        float headerH,
        float rowH
    ) {}

    private record BuildMenuEntry(BuildPrefab prefab, String label) {}

    private static final BuildMenuEntry[] BUILD_MENU_ENTRIES = new BuildMenuEntry[] {
        new BuildMenuEntry(BuildPrefab.HOUSE, "HOUSE"),
        new BuildMenuEntry(BuildPrefab.TREE, "TREE"),
        new BuildMenuEntry(BuildPrefab.SMALL_BUILDING, "SMALL BUILDING"),
        new BuildMenuEntry(BuildPrefab.TOWER, "TOWER"),
        new BuildMenuEntry(BuildPrefab.WELL, "WELL"),
        new BuildMenuEntry(BuildPrefab.CAMPFIRE, "CAMPFIRE"),
        new BuildMenuEntry(BuildPrefab.LAMP_POST, "LAMP POST")
    };

    private static BuildMenuLayout buildMenuLayout(int screenW, int screenH) {
        float w = 260f;
        float headerH = 12f + 18f + 22f;
        float rowH = 26f;
        float rows = (float) BUILD_MENU_ENTRIES.length;
        float paddingBottom = 12f;
        float h = headerH + rows * rowH + paddingBottom;

        float x = screenW - 18f - w;
        float y = 18f;
        float maxY = screenH - 18f - h;
        if (y > maxY) y = Math.max(18f, maxY);
        if (x < 18f) x = 18f;

        return new BuildMenuLayout(x, y, w, h, headerH, rowH);
    }

    private static void drawBuildMenu(UiRenderer ui, UiText text, int screenW, int screenH, BuildPrefab selected) {
        BuildMenuLayout l = buildMenuLayout(screenW, screenH);
        ui.drawRect(l.x, l.y, l.w, l.h, screenW, screenH, 0f, 0f, 0f, 0.50f);

        float tx = l.x + 12f;
        float ty = l.y + 12f;
        float scale = 2.0f;

        text.draw(ui, tx, ty, screenW, screenH, "BUILD (H)", scale, 1f, 1f, 1f, 0.95f);
        ty += 18f;
        text.draw(ui, tx, ty, screenW, screenH, "CLICK TO SELECT", scale, 1f, 1f, 1f, 0.70f);

        float rowY = l.y + l.headerH;
        float btnX = l.x + 12f;
        float btnW = l.w - 24f;

        for (BuildMenuEntry e : BUILD_MENU_ENTRIES) {
            drawToggle(ui, text, screenW, screenH, btnX, rowY, btnW, l.rowH, e.label, selected == e.prefab);
            rowY += l.rowH;
        }
    }

    private static BuildPrefab handleBuildMenuInput(float mx, float my, boolean pressed, BuildPrefab selected, int screenW, int screenH) {
        if (!pressed) return selected;

        BuildMenuLayout l = buildMenuLayout(screenW, screenH);
        float x = l.x;
        float y = l.y;
        float w = l.w;
        float h = l.h;

        if (!hit(mx, my, x, y, w, h)) return selected;

        float rowY = y + l.headerH;
        float btnX = x + 12f;
        float btnW = w - 24f;

        for (BuildMenuEntry e : BUILD_MENU_ENTRIES) {
            if (hit(mx, my, btnX, rowY, btnW, l.rowH - 2f)) return e.prefab;
            rowY += l.rowH;
        }

        return selected;
    }

    private static final class PrefabBlock {
        final int x;
        final int y;
        final int z;
        final BlockType t;

        PrefabBlock(int x, int y, int z, BlockType t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }
    }

    private static boolean isReplaceable(BlockType t) {
        return t == BlockType.AIR || t == BlockType.TALL_GRASS;
    }

    private static void placePrefabInFront(
        World world,
        ChunkRenderer chunkRenderer,
        Vector3f playerFeetPos,
        Camera camera,
        float playerRadius,
        float playerHeight,
        BuildPrefab prefab,
        Random rng
    ) {
        Vector3f f = cameraForward(camera);
        f.y = 0f;
        if (f.lengthSquared() < 1e-6f) {
            float yawRad = (float) Math.toRadians(camera.yaw);
            f.set((float) Math.sin(yawRad), 0f, (float) -Math.cos(yawRad));
        }
        f.normalize();

        float dist = 6.0f;
        float tx = playerFeetPos.x + f.x * dist;
        float tz = playerFeetPos.z + f.z * dist;

        int centerX = (int) Math.floor(tx);
        int centerZ = (int) Math.floor(tz);
        int groundY = findSurfaceY(world, centerX, centerZ);
        int baseY = Math.min(Chunk.HEIGHT - 1, groundY + 1);

        ArrayList<PrefabBlock> blocks = new ArrayList<>();

        switch (prefab) {
            case HOUSE -> {
                int w = 7;
                int d = 7;
                int ox = centerX - w / 2;
                int oz = centerZ - d / 2;
                addHousePrefab(blocks, ox, baseY, oz);
            }
            case TREE -> {
                addTreePrefab(blocks, centerX, baseY, centerZ, rng);
            }
            case SMALL_BUILDING -> {
                int w = 5;
                int d = 5;
                int ox = centerX - w / 2;
                int oz = centerZ - d / 2;
                addSmallBuildingPrefab(blocks, ox, baseY, oz);
            }
            case TOWER -> {
                int w = 5;
                int d = 5;
                int ox = centerX - w / 2;
                int oz = centerZ - d / 2;
                addTowerPrefab(blocks, ox, baseY, oz);
            }
            case WELL -> {
                int w = 5;
                int d = 5;
                int ox = centerX - w / 2;
                int oz = centerZ - d / 2;
                addWellPrefab(blocks, ox, baseY, oz);
            }
            case CAMPFIRE -> {
                addCampfirePrefab(blocks, centerX, baseY, centerZ);
            }
            case LAMP_POST -> {
                addLampPostPrefab(blocks, centerX, baseY, centerZ);
            }
        }

        // Validate: don't overwrite solid structures and don't place inside the player.
        for (PrefabBlock b : blocks) {
            if (b.t == BlockType.AIR) continue;
            if (!isReplaceable(world.getBlock(b.x, b.y, b.z))) return;
            if (playerIntersectsBlock(playerFeetPos, playerRadius, playerHeight, b.x, b.y, b.z)) return;
        }

        // Place.
        for (PrefabBlock b : blocks) {
            if (b.t == BlockType.AIR) continue;
            world.setBlock(b.x, b.y, b.z, b.t);
            markDirtyAround(chunkRenderer, b.x, b.z);
        }
    }

    private static void addHousePrefab(ArrayList<PrefabBlock> out, int ox, int oy, int oz) {
        int w = 7;
        int d = 7;
        int wallH = 4;

        // Floor
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                out.add(new PrefabBlock(ox + x, oy, oz + z, BlockType.COBBLESTONE));
            }
        }

        // Walls + corner posts
        for (int y = 1; y <= wallH; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = (x == 0 || x == w - 1 || z == 0 || z == d - 1);
                    if (!edge) continue;
                    boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    BlockType t = corner ? BlockType.WOOD : BlockType.PLANKS;
                    out.add(new PrefabBlock(ox + x, oy + y, oz + z, t));
                }
            }
        }

        // Door opening (front side z=0)
        int doorX = ox + (w / 2);
        out.add(new PrefabBlock(doorX, oy + 1, oz + 0, BlockType.AIR));
        out.add(new PrefabBlock(doorX, oy + 2, oz + 0, BlockType.AIR));

        // Roof (flat)
        int ry = oy + wallH + 1;
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                out.add(new PrefabBlock(ox + x, ry, oz + z, BlockType.PLANKS));
            }
        }
    }

    private static void addSmallBuildingPrefab(ArrayList<PrefabBlock> out, int ox, int oy, int oz) {
        int w = 5;
        int d = 5;
        int wallH = 3;

        // Floor
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                out.add(new PrefabBlock(ox + x, oy, oz + z, BlockType.COBBLESTONE));
            }
        }

        // Walls
        for (int y = 1; y <= wallH; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = (x == 0 || x == w - 1 || z == 0 || z == d - 1);
                    if (!edge) continue;
                    out.add(new PrefabBlock(ox + x, oy + y, oz + z, BlockType.WOOD));
                }
            }
        }

        // Door
        int doorX = ox + (w / 2);
        out.add(new PrefabBlock(doorX, oy + 1, oz + 0, BlockType.AIR));
        out.add(new PrefabBlock(doorX, oy + 2, oz + 0, BlockType.AIR));

        // Roof
        int ry = oy + wallH + 1;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                out.add(new PrefabBlock(ox + x, ry, oz + z, BlockType.PLANKS));
            }
        }
    }

    private static void addTowerPrefab(ArrayList<PrefabBlock> out, int ox, int oy, int oz) {
        int w = 5;
        int d = 5;
        int h = 8;

        // Base platform
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                out.add(new PrefabBlock(ox + x, oy, oz + z, BlockType.COBBLESTONE));
            }
        }

        // Hollow shaft (cobble shell)
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = (x == 0 || x == w - 1 || z == 0 || z == d - 1);
                    if (!edge) continue;
                    out.add(new PrefabBlock(ox + x, oy + y, oz + z, BlockType.COBBLESTONE));
                }
            }
        }

        // Door opening
        int doorX = ox + (w / 2);
        out.add(new PrefabBlock(doorX, oy + 1, oz + 0, BlockType.AIR));
        out.add(new PrefabBlock(doorX, oy + 2, oz + 0, BlockType.AIR));

        // Top cap + torches
        int topY = oy + h + 1;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                out.add(new PrefabBlock(ox + x, topY, oz + z, BlockType.PLANKS));
            }
        }
        out.add(new PrefabBlock(ox + 0, topY + 1, oz + 0, BlockType.TORCH));
        out.add(new PrefabBlock(ox + (w - 1), topY + 1, oz + 0, BlockType.TORCH));
        out.add(new PrefabBlock(ox + 0, topY + 1, oz + (d - 1), BlockType.TORCH));
        out.add(new PrefabBlock(ox + (w - 1), topY + 1, oz + (d - 1), BlockType.TORCH));
    }

    private static void addWellPrefab(ArrayList<PrefabBlock> out, int ox, int oy, int oz) {
        int w = 5;
        int d = 5;

        // Cobble pad
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                out.add(new PrefabBlock(ox + x, oy, oz + z, BlockType.COBBLESTONE));
            }
        }

        // Ring (1-block high)
        int y = oy + 1;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = (x == 0 || x == w - 1 || z == 0 || z == d - 1);
                if (!edge) continue;
                out.add(new PrefabBlock(ox + x, y, oz + z, BlockType.COBBLESTONE));
            }
        }

        // Posts
        out.add(new PrefabBlock(ox + 0, oy + 2, oz + 0, BlockType.WOOD));
        out.add(new PrefabBlock(ox + 0, oy + 3, oz + 0, BlockType.WOOD));
        out.add(new PrefabBlock(ox + (w - 1), oy + 2, oz + 0, BlockType.WOOD));
        out.add(new PrefabBlock(ox + (w - 1), oy + 3, oz + 0, BlockType.WOOD));

        // Roof
        int ry = oy + 4;
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= 1; z++) {
                out.add(new PrefabBlock(ox + x, ry, oz + z, BlockType.PLANKS));
            }
        }

        // Lantern-ish torches
        out.add(new PrefabBlock(ox + 0, ry + 1, oz + 0, BlockType.TORCH));
        out.add(new PrefabBlock(ox + (w - 1), ry + 1, oz + 0, BlockType.TORCH));
    }

    private static void addCampfirePrefab(ArrayList<PrefabBlock> out, int x0, int y0, int z0) {
        // Simple cozy campfire: ring + crossed logs + torch.
        out.add(new PrefabBlock(x0, y0, z0, BlockType.COBBLESTONE));
        out.add(new PrefabBlock(x0 + 1, y0, z0, BlockType.COBBLESTONE));
        out.add(new PrefabBlock(x0 - 1, y0, z0, BlockType.COBBLESTONE));
        out.add(new PrefabBlock(x0, y0, z0 + 1, BlockType.COBBLESTONE));
        out.add(new PrefabBlock(x0, y0, z0 - 1, BlockType.COBBLESTONE));

        out.add(new PrefabBlock(x0, y0 + 1, z0, BlockType.TORCH));
        out.add(new PrefabBlock(x0 + 1, y0 + 1, z0 + 1, BlockType.WOOD));
        out.add(new PrefabBlock(x0 - 1, y0 + 1, z0 - 1, BlockType.WOOD));
        out.add(new PrefabBlock(x0 + 1, y0 + 1, z0 - 1, BlockType.WOOD));
        out.add(new PrefabBlock(x0 - 1, y0 + 1, z0 + 1, BlockType.WOOD));
    }

    private static void addLampPostPrefab(ArrayList<PrefabBlock> out, int x0, int y0, int z0) {
        int h = 5;
        // Base
        out.add(new PrefabBlock(x0, y0, z0, BlockType.COBBLESTONE));
        // Post
        for (int y = 1; y <= h; y++) {
            out.add(new PrefabBlock(x0, y0 + y, z0, BlockType.WOOD));
        }
        // Cap
        out.add(new PrefabBlock(x0, y0 + h + 1, z0, BlockType.PLANKS));
        // Torches around
        out.add(new PrefabBlock(x0 + 1, y0 + h, z0, BlockType.TORCH));
        out.add(new PrefabBlock(x0 - 1, y0 + h, z0, BlockType.TORCH));
        out.add(new PrefabBlock(x0, y0 + h, z0 + 1, BlockType.TORCH));
        out.add(new PrefabBlock(x0, y0 + h, z0 - 1, BlockType.TORCH));
    }

    private static void addTreePrefab(ArrayList<PrefabBlock> out, int x0, int y0, int z0, Random rng) {
        int trunkH = 5 + rng.nextInt(3); // 5..7
        for (int y = 0; y < trunkH; y++) {
            out.add(new PrefabBlock(x0, y0 + y, z0, BlockType.WOOD));
        }

        int topY = y0 + trunkH - 1;
        int r = 2;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    int d2 = x * x + y * y + z * z;
                    if (d2 > r * r + 1) continue;
                    // Light randomness so it doesn't look like a perfect cube.
                    if (d2 == r * r + 1 && rng.nextFloat() < 0.45f) continue;
                    int wx = x0 + x;
                    int wy = topY + y + 1;
                    int wz = z0 + z;
                    out.add(new PrefabBlock(wx, wy, wz, BlockType.LEAVES));
                }
            }
        }
    }

    private static void drawGraphicsMenu(
            UiRenderer ui,
            UiText text,
            int screenW,
            int screenH,
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
                float fxaaSpan,
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
                    float texAnisoMax
    ) {
        MenuLayout l = menuLayout(screenW, screenH);
        ui.drawRect(l.x, l.y, l.w, l.h, screenW, screenH, 0f, 0f, 0f, 0.50f);

        float tx = l.x + 12f;
        float ty = l.y + 12f;
        float scale = 2.0f;

        text.draw(ui, tx, ty, screenW, screenH, "GRAPHICS (F1)", scale, 1f, 1f, 1f, 0.95f);
        ty += 18f;
        text.draw(ui, tx, ty, screenW, screenH, "CLICK BUTTONS / DRAG SLIDERS", scale, 1f, 1f, 1f, 0.70f);
        ty += 22f;

        // Layout
        float rowH = l.rowH;
        float labelW = l.labelW;
        float controlX = tx + labelW;
        float controlW = l.w - 24f - labelW;

        // Preset
        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "PRESET", true);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, "APPLY", true);
        ty += rowH;

        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "RESET", true);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, "DEFAULTS", true);
        ty += rowH;

        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "PLAIN", true);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, "OFF", true);
        ty += rowH;

        // Buttons
        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "FOG", fogEnabled);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, fogEnabled ? "ON" : "OFF", fogEnabled);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "FOG NEAR", fogNear, 0f, 200f, activeSlider == 0);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "FOG FAR", fogFar, 10f, 400f, activeSlider == 1);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "BRIGHT", brightness, 0.50f, 2.00f, activeSlider == 2);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "GAMMA", gamma, 0.80f, 2.20f, activeSlider == 3);

        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "FOV", fovDeg, 50.0f, 110.0f, activeSlider == 4);

        ty += rowH;

        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "SUN SHADOWS", shadowsEnabled);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, shadowsEnabled ? "ON" : "OFF", shadowsEnabled);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "SUN POS", sunElevationDeg, 5.0f, 85.0f, activeSlider == 5);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "SH STR", shadowStrength, 0.00f, 1.00f, activeSlider == 6);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "SH SOFT", shadowSoftness, 0.50f, 4.00f, activeSlider == 7);

        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "SH DIST", shadowDistance, 0.0f, 250.0f, activeSlider == 8);
        ty += rowH;

        int shadowResIdx = shadowResToIndex(shadowRes, shadowResSteps);
        drawSliderDiscrete(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "SH RES", shadowResIdx, shadowResSteps, activeSlider == 9);
        ty += rowH + 6f;

        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "POST", postEnabled);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, postEnabled ? "ON" : "OFF", postEnabled);
        ty += rowH;

        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "FXAA", fxaaEnabled);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, fxaaEnabled ? "ON" : "OFF", fxaaEnabled);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "AA LEVEL", fxaaSpan, 2.0f, 64.0f, activeSlider == 10);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "VIGNETTE", vignette, 0.00f, 0.45f, activeSlider == 11);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "SATURATION", saturation, 0.50f, 1.50f, activeSlider == 12);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "CONTRAST", contrast, 0.80f, 1.40f, activeSlider == 13);

        ty += rowH;

        // Bloom
        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "BLOOM", bloomEnabled);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, bloomEnabled ? "ON" : "OFF", bloomEnabled);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "BL THR", bloomThreshold, 0.00f, 2.50f, activeSlider == 14);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "BL STR", bloomStrength, 0.00f, 1.25f, activeSlider == 15);

        ty += rowH;

        // Godrays
        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "GODRAYS", godraysEnabled);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, godraysEnabled ? "ON" : "OFF", godraysEnabled);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "GR STR", godraysStrength, 0.00f, 1.50f, activeSlider == 16);
        ty += rowH;

        int grSampIdx = godraysSamplesToIndex(godraysSamples);
        drawSliderDiscrete(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "GR SMP", grSampIdx, GODRAY_SAMPLE_STEPS, activeSlider == 17);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "GR DEN", godraysDensity, 0.20f, 1.50f, activeSlider == 18);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "GR WGT", godraysWeight, 0.000f, 0.050f, activeSlider == 19);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "GR DCY", godraysDecay, 0.850f, 0.999f, activeSlider == 20);
        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "GR EDGE", godraysEdgeFade, 0.10f, 1.50f, activeSlider == 21);

        ty += rowH + 6f;

        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "FILTER", texSmooth);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, texSmooth ? "SMOOTH" : "PIXEL", texSmooth);
        ty += rowH;

        drawToggle(ui, text, screenW, screenH, tx, ty, labelW, rowH, "PIXEL SNAP", pixelSnap);
        drawToggle(ui, text, screenW, screenH, controlX, ty, controlW, rowH, pixelSnap ? "ON" : "OFF", pixelSnap);
        ty += rowH;

        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "ANISO", texAniso, 1.0f, Math.max(1.0f, texAnisoMax), activeSlider == 22);

        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "MIP BIAS", gloss, -1.50f, 3.00f, activeSlider == 23);

        ty += rowH;
        drawSlider(ui, text, screenW, screenH, tx, ty, labelW, controlX, controlW, rowH,
            "WIND", windStrength, 0.00f, 2.00f, activeSlider == 24);
    }

    private static void drawSliderDiscrete(UiRenderer ui, UiText text, int sw, int sh,
                                           float labelX, float y, float labelW,
                                           float barX, float barW, float rowH,
                                           String label, int index, int[] steps, boolean active) {
        if (steps == null || steps.length == 0) return;
        int maxIdx = steps.length - 1;
        int idx = Math.max(0, Math.min(maxIdx, index));
        text.draw(ui, labelX, y + 5f, sw, sh, label, 2.0f, 1f, 1f, 1f, 0.90f);

        float t = maxIdx == 0 ? 0f : (float) idx / (float) maxIdx;

        float barY = y + 6f;
        float barH = 10f;
        ui.drawRect(barX, barY, barW, barH, sw, sh, 1f, 1f, 1f, active ? 0.22f : 0.14f);
        ui.drawRect(barX + 1f, barY + 1f, barW - 2f, barH - 2f, sw, sh, 0f, 0f, 0f, 0.35f);

        float knobX = barX + t * barW;
        ui.drawRect(knobX - 3f, barY - 2f, 6f, barH + 4f, sw, sh, 1f, 1f, 1f, 0.85f);

        text.draw(ui, barX + barW - 80f, y + 5f, sw, sh, Integer.toString(steps[idx]), 2.0f, 1f, 1f, 1f, 0.85f);
    }

    private static void drawToggle(UiRenderer ui, UiText text, int sw, int sh, float x, float y, float w, float h, String label, boolean on) {
        float a = on ? 0.70f : 0.35f;
        ui.drawRect(x, y, w, h - 2f, sw, sh, 1f, 1f, 1f, 0.08f);
        ui.drawRect(x, y, w, h - 2f, sw, sh, 0f, 0f, 0f, 0.20f);
        ui.drawRect(x + 1f, y + 1f, w - 2f, h - 4f, sw, sh, on ? 0.2f : 0.1f, on ? 0.8f : 0.2f, on ? 0.3f : 0.2f, a);
        text.draw(ui, x + 6f, y + 5f, sw, sh, label, 2.0f, 1f, 1f, 1f, 0.90f);
    }

    private static void drawSlider(UiRenderer ui, UiText text, int sw, int sh,
                                   float labelX, float y, float labelW,
                                   float barX, float barW, float rowH,
                                   String label, float value, float min, float max, boolean active) {
        text.draw(ui, labelX, y + 5f, sw, sh, label, 2.0f, 1f, 1f, 1f, 0.90f);

        float t = (value - min) / (max - min);
        t = clamp(t, 0f, 1f);

        float barY = y + 6f;
        float barH = 10f;
        ui.drawRect(barX, barY, barW, barH, sw, sh, 1f, 1f, 1f, active ? 0.22f : 0.14f);
        ui.drawRect(barX + 1f, barY + 1f, barW - 2f, barH - 2f, sw, sh, 0f, 0f, 0f, 0.35f);

        float knobX = barX + t * barW;
        ui.drawRect(knobX - 3f, barY - 2f, 6f, barH + 4f, sw, sh, 1f, 1f, 1f, 0.85f);

        text.draw(ui, barX + barW - 80f, y + 5f, sw, sh, fmt2(value), 2.0f, 1f, 1f, 1f, 0.85f);
    }

    private record MenuResult(
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
        float fxaaSpan,
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
        boolean texSmooth,
        float texAniso,
        boolean pixelSnap,
        float windStrength,
        boolean shadowsEnabled,
        float sunElevationDeg,
        float shadowStrength,
        float shadowSoftness,
        float shadowDistance,
        int shadowRes
    ) {}

    private record InventoryResult(
        BlockType dragged,
        int selectedSlot
    ) {}

    private static boolean isPlaceable(BlockType t) {
        // Keep it simple: only blocks that exist in the voxel world.
         return t == BlockType.GRASS ||
             t == BlockType.DIRT ||
             t == BlockType.STONE ||
             t == BlockType.WOOD ||
             t == BlockType.PLANKS ||
             t == BlockType.OAK_STAIRS ||
             t == BlockType.CRAFTING_TABLE ||
             t == BlockType.LEAVES ||
             t == BlockType.COBBLESTONE ||
             t == BlockType.TORCH ||
             t == BlockType.TALL_GRASS ||
             t == BlockType.FURNACE ||
             t == BlockType.IRON_ORE ||
             t == BlockType.COAL_ORE ||
             t == BlockType.WATER;
    }

    private static String shortName(BlockType t) {
        String s = t.name().replace('_', ' ');
        // Keep labels compact for the UI.
        if (s.length() > 10) s = s.substring(0, 10);
        return s;
    }

    private static InventoryResult handleInventoryInput(
        float mx,
        float my,
        boolean pressed,
        boolean down,
        boolean released,
        EnumMap<BlockType, Integer> inventory,
        BlockType dragged,
        BlockType[] hotbar,
        int selectedSlot,
        int screenW,
        int screenH
    ) {
        // Start drag on press; drop on release.

        // Layout
        int cols = 6;
        int invCount = 0;
        for (BlockType t : BlockType.values()) {
            if (t == BlockType.AIR) continue;
            if (!isPlaceable(t)) continue;
            invCount++;
        }
        int rows = Math.max(1, (int) Math.ceil(invCount / (double) cols));

        float slot = 56f;
        float gap = 6f;
        float pad = 16f;

        float invW = cols * slot + (cols - 1) * gap;
        float invH = rows * slot + Math.max(0, rows - 1) * gap;

        int hotSlots = hotbar.length;
        float hotW = hotSlots * slot + (hotSlots - 1) * gap;
        float hotH = slot;

        float panelW = Math.max(invW, hotW) + pad * 2f;
        float panelH = 48f + invH + 18f + hotH + pad;
        float x0 = (screenW - panelW) * 0.5f;
        float y0 = (screenH - panelH) * 0.5f;

        float invX = x0 + pad;
        float invY = y0 + 48f;
        float hotX = x0 + pad;
        float hotY = invY + invH + 18f;

        // Drop on release
        if (released && dragged != BlockType.AIR) {
            for (int i = 0; i < hotSlots; i++) {
                float sx = hotX + i * (slot + gap);
                float sy = hotY;
                if (hit(mx, my, sx, sy, slot, slot)) {
                    hotbar[i] = dragged;
                    return new InventoryResult(BlockType.AIR, i);
                }
            }
            // Released not on hotbar => cancel.
            return new InventoryResult(BlockType.AIR, selectedSlot);
        }

        // If we are currently dragging, ignore clicks until released.
        if (down && dragged != BlockType.AIR) {
            return new InventoryResult(dragged, selectedSlot);
        }

        if (!pressed) return new InventoryResult(dragged, selectedSlot);

        // Click outside => cancel drag/selection
        if (!(mx >= x0 && mx <= x0 + panelW && my >= y0 && my <= y0 + panelH)) {
            return new InventoryResult(BlockType.AIR, selectedSlot);
        }

        // Hotbar hit (select only; dropping happens on release)
        for (int i = 0; i < hotSlots; i++) {
            float sx = hotX + i * (slot + gap);
            float sy = hotY;
            if (hit(mx, my, sx, sy, slot, slot)) {
                return new InventoryResult(dragged, i);
            }
        }

        // Inventory hit
        int idx = 0;
        for (BlockType t : BlockType.values()) {
            if (t == BlockType.AIR) continue;
            if (!isPlaceable(t)) continue;

            int cx = idx % cols;
            int cy = idx / cols;
            float sx = invX + cx * (slot + gap);
            float sy = invY + cy * (slot + gap);
            if (hit(mx, my, sx, sy, slot, slot)) {
                return new InventoryResult(t, selectedSlot);
            }
            idx++;
        }

        return new InventoryResult(dragged, selectedSlot);
    }

    private static void drawInventory(
        UiRenderer ui,
        UiText text,
        BlockTextures blockTextures,
        int screenW,
        int screenH,
        EnumMap<BlockType, Integer> inventory,
        BlockType[] hotbar,
        int selectedSlot,
        BlockType dragged,
        float mx,
        float my,
        boolean mouseDown
    ) {
        int cols = 6;
        int invCount = 0;
        for (BlockType t : BlockType.values()) {
            if (t == BlockType.AIR) continue;
            if (!isPlaceable(t)) continue;
            invCount++;
        }
        int rows = Math.max(1, (int) Math.ceil(invCount / (double) cols));

        float slot = 56f;
        float gap = 6f;
        float pad = 16f;
        float invW = cols * slot + (cols - 1) * gap;
        float invH = rows * slot + Math.max(0, rows - 1) * gap;
        int hotSlots = hotbar.length;
        float hotW = hotSlots * slot + (hotSlots - 1) * gap;
        float hotH = slot;

        float panelW = Math.max(invW, hotW) + pad * 2f;
        float panelH = 48f + invH + 18f + hotH + pad;
        float x0 = (screenW - panelW) * 0.5f;
        float y0 = (screenH - panelH) * 0.5f;

        ui.drawRect(x0, y0, panelW, panelH, screenW, screenH, 0f, 0f, 0f, 0.55f);
        text.draw(ui, x0 + 12f, y0 + 12f, screenW, screenH, "INVENTORY (E)", 2.0f, 1f, 1f, 1f, 0.92f);
        if (dragged != BlockType.AIR) {
            text.draw(ui, x0 + panelW - 180f, y0 + 12f, screenW, screenH,
                "DRAG: " + shortName(dragged), 2.0f, 1f, 1f, 1f, 0.80f);
        }

        float invX = x0 + pad;
        float invY = y0 + 48f;
        float hotX = x0 + pad;
        float hotY = invY + invH + 18f;

        // Inventory grid
        int idx = 0;
        for (BlockType t : BlockType.values()) {
            if (t == BlockType.AIR) continue;
            if (!isPlaceable(t)) continue;
            int count = getCount(inventory, t);

            int cx = idx % cols;
            int cy = idx / cols;
            float sx = invX + cx * (slot + gap);
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
                BlockType t = world.getBlock(x, yBelow, z);
                if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                if (t == BlockType.OAK_STAIRS) {
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
                        BlockType t = world.getBlock(x, y, z);
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        if (t == BlockType.OAK_STAIRS) {
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
                        BlockType t = world.getBlock(x, y, z);
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        if (t == BlockType.OAK_STAIRS) {
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
                        BlockType t = world.getBlock(x, y, z);
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        if (t == BlockType.OAK_STAIRS) {
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
                        BlockType t = world.getBlock(x, y, z);
                        if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                        if (t == BlockType.OAK_STAIRS) {
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
                    BlockType t = world.getBlock(x, y, z);
                    if (t == BlockType.AIR || t == BlockType.TALL_GRASS || t == BlockType.TORCH) continue;

                    if (t == BlockType.OAK_STAIRS) {
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
