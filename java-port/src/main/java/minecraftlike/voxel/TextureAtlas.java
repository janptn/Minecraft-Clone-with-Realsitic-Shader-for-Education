package minecraftlike.voxel;

import minecraftlike.engine.Assets;
import minecraftlike.engine.Texture;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.*;

public final class TextureAtlas {
    public final int tileSize;
    public final int pad;
    public final int cellSize;
    public final int grid;
    public final int width;
    public final int height;

    private final Map<String, Integer> tileIndexByName;
    private final Texture texture;

    private TextureAtlas(int tileSize, int pad, int grid, Map<String, Integer> tileIndexByName, Texture texture) {
        this.tileSize = tileSize;
        this.pad = pad;
        this.cellSize = tileSize + pad * 2;
        this.grid = grid;
        this.width = cellSize * grid;
        this.height = cellSize * grid;
        this.tileIndexByName = tileIndexByName;
        this.texture = texture;
    }

    public static TextureAtlas buildDefault() {
        // 5x5 atlas = 25 tiles.
        // TEST: use full 1024x1024 tiles (your source textures are 1024x1024).
        // WARNING: this makes the atlas very large (and uses a lot of VRAM).
        int tileSize = 1024;
        int grid = 5;
        int pad = 16; // padding around each tile to prevent mipmap bleeding (needs to be bigger for large tiles)

        // Keep stable ordering (important because UVs depend on index).
        LinkedHashMap<String, String> tiles = new LinkedHashMap<>();
        tiles.put("dirt", "dirt.png");
        tiles.put("grass_top", "grass_block_top.png");
        tiles.put("grass_side", "grass_block_side.png");
        tiles.put("stone", "stone.jpg");
        tiles.put("planks", "oak_planks.png");
        tiles.put("log_top", "oak_log_top.jpg");
        tiles.put("log_side", "oak_log_side.jpg");
        tiles.put("cobblestone", "cobblestone.png");
        tiles.put("leaves", "oak_leaves.png");
        tiles.put("craft_top", "crafting_table_top.jpg");
        tiles.put("craft_side", "crafting_table_side.jpg");
        tiles.put("iron_ore", "iron_ore.png");
        tiles.put("coal_ore", "coal_ore.png");
        tiles.put("furnace", "furnace.png");
        // Torch: use side/top/bottom like Minecraft block textures.
        tiles.put("torch_side", "torch_side.png");
        tiles.put("torch_top", "torch_top.png");
        tiles.put("torch_bottom", "torch_bottom.png");
        // Optional legacy single-tile torch (kept for compatibility/debug).
        tiles.put("torch", "torch.png");
        tiles.put("tall_grass", "tall_grass.png");
        tiles.put("water_waves", "water_waves.png");

        if (tiles.size() > grid * grid) {
            throw new IllegalStateException("Too many tiles for atlas grid");
        }

        int cellSize = tileSize + pad * 2;
        int atlasW = cellSize * grid;
        int atlasH = cellSize * grid;
        ByteBuffer atlas = memAlloc(atlasW * atlasH * 4);
        // Fill with magenta to highlight missing tiles.
        for (int i = 0; i < atlasW * atlasH; i++) {
            atlas.put((byte) 255).put((byte) 0).put((byte) 255).put((byte) 255);
        }
        atlas.flip();

        Map<String, Integer> indexByName = new LinkedHashMap<>();

        int idx = 0;
        for (var e : tiles.entrySet()) {
            String name = e.getKey();
            String file = e.getValue();
            Path path = Assets.findTexture(file);

            ByteBuffer tile = loadImageRgba(path);
            int srcW = lastW;
            int srcH = lastH;

            ByteBuffer scaled = nearestScale(tile, srcW, srcH, tileSize, tileSize);
            STBImage.stbi_image_free(tile);

            int tx = idx % grid;
            int ty = idx / grid;
            blitTileWithPadding(atlas, atlasW, atlasH, scaled, tileSize, tileSize, tx * cellSize, ty * cellSize, pad);
            memFree(scaled);

            indexByName.put(name, idx);
            idx++;
        }

        Texture glTex = Texture.fromRgba(atlasW, atlasH, atlas);
        memFree(atlas);

        return new TextureAtlas(tileSize, pad, grid, indexByName, glTex);
    }

    // Scratch fields populated by loadImageRgba
    private static int lastW;
    private static int lastH;

    private static ByteBuffer loadImageRgba(Path path) {
        STBImage.stbi_set_flip_vertically_on_load(true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);
            ByteBuffer data = STBImage.stbi_load(path.toString(), w, h, c, 4);
            if (data == null) {
                throw new IllegalArgumentException("Failed to load image: " + path + " : " + STBImage.stbi_failure_reason());
            }
            lastW = w.get(0);
            lastH = h.get(0);
            return data;
        }
    }

    private static ByteBuffer nearestScale(ByteBuffer src, int srcW, int srcH, int dstW, int dstH) {
        ByteBuffer out = memAlloc(dstW * dstH * 4);
        for (int y = 0; y < dstH; y++) {
            int sy = (int) ((y + 0.5) * srcH / (double) dstH);
            if (sy < 0) sy = 0;
            if (sy >= srcH) sy = srcH - 1;
            for (int x = 0; x < dstW; x++) {
                int sx = (int) ((x + 0.5) * srcW / (double) dstW);
                if (sx < 0) sx = 0;
                if (sx >= srcW) sx = srcW - 1;

                int srcIndex = (sy * srcW + sx) * 4;
                out.put(src.get(srcIndex));
                out.put(src.get(srcIndex + 1));
                out.put(src.get(srcIndex + 2));
                out.put(src.get(srcIndex + 3));
            }
        }
        out.flip();
        return out;
    }

    private static void blitTileWithPadding(ByteBuffer atlas, int atlasW, int atlasH,
                                           ByteBuffer tile, int tileW, int tileH,
                                           int dstX, int dstY, int pad) {
        // Copy inner tile.
        for (int y = 0; y < tileH; y++) {
            int ay = dstY + pad + y;
            for (int x = 0; x < tileW; x++) {
                int ax = dstX + pad + x;
                copyPixel(tile, tileW, x, y, atlas, atlasW, ax, ay);
            }
        }

        // Extrude left/right edges into padding.
        for (int y = 0; y < tileH; y++) {
            int ay = dstY + pad + y;
            for (int p = 1; p <= pad; p++) {
                copyPixel(atlas, atlasW, dstX + pad, ay, atlas, atlasW, dstX + pad - p, ay);
                copyPixel(atlas, atlasW, dstX + pad + tileW - 1, ay, atlas, atlasW, dstX + pad + tileW - 1 + p, ay);
            }
        }

        // Extrude top/bottom edges into padding (including already extruded left/right columns).
        int rowW = tileW + pad * 2;
        for (int x = 0; x < rowW; x++) {
            int ax = dstX + x;
            for (int p = 1; p <= pad; p++) {
                copyPixel(atlas, atlasW, ax, dstY + pad, atlas, atlasW, ax, dstY + pad - p);
                copyPixel(atlas, atlasW, ax, dstY + pad + tileH - 1, atlas, atlasW, ax, dstY + pad + tileH - 1 + p);
            }
        }
    }

    private static void copyPixel(ByteBuffer src, int srcW, int sx, int sy,
                                  ByteBuffer dst, int dstW, int dx, int dy) {
        int srcIndex = (sy * srcW + sx) * 4;
        int dstIndex = (dy * dstW + dx) * 4;
        dst.put(dstIndex, src.get(srcIndex));
        dst.put(dstIndex + 1, src.get(srcIndex + 1));
        dst.put(dstIndex + 2, src.get(srcIndex + 2));
        dst.put(dstIndex + 3, src.get(srcIndex + 3));
    }

    public Texture texture() {
        return texture;
    }

    public int tileIndex(String name) {
        Integer idx = tileIndexByName.get(name);
        if (idx == null) throw new IllegalArgumentException("Unknown tile: " + name);
        return idx;
    }

    public UvRect uvForTile(int tileIndex) {
        int tx = tileIndex % grid;
        int ty = tileIndex / grid;
        float u0 = (tx * cellSize + pad) / (float) width;
        float v0 = (ty * cellSize + pad) / (float) height;
        float u1 = (tx * cellSize + pad + tileSize) / (float) width;
        float v1 = (ty * cellSize + pad + tileSize) / (float) height;

        // Half-texel inset to avoid edge bleeding between atlas tiles.
        float du = 0.5f / (float) width;
        float dv = 0.5f / (float) height;
        u0 += du;
        v0 += dv;
        u1 -= du;
        v1 -= dv;
        return new UvRect(u0, v0, u1, v1);
    }
}
