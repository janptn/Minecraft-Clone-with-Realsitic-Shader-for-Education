package minecraftlike.voxel;

import minecraftlike.engine.ShaderProgram;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ChunkRenderer implements AutoCloseable {
    private final World world;
    private final BlockTextures textures;

    private final Map<Long, ChunkMesh> meshes = new HashMap<>();

    public ChunkRenderer(World world, BlockTextures textures) {
        this.world = world;
        this.textures = textures;
    }

    public void buildChunk(int cx, int cz) {
        long k = key(cx, cz);
        ChunkMesh old = meshes.remove(k);
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }
        ChunkMesh mesh = ChunkMesher.buildMesh(world, cx, cz, textures);
        meshes.put(k, mesh);
    }

    public void drawAll(ShaderProgram shader) {
        // Meshes are already in world space; uModel should be identity.
        for (ChunkMesh m : meshes.values()) {
            if (m != null) m.draw();
        }
    }


    public void drawNear(ShaderProgram shader, float centerX, float centerZ, float radius) {
        float r = Math.max(0.0f, radius);
        float r2 = r * r;
        float pad = Chunk.SIZE * 0.75f;
        float rPad2 = (r + pad) * (r + pad);

        for (Map.Entry<Long, ChunkMesh> e : meshes.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 32);
            int cz = (int) k;

            float chunkCenterX = cx * (float) Chunk.SIZE + (float) Chunk.SIZE * 0.5f;
            float chunkCenterZ = cz * (float) Chunk.SIZE + (float) Chunk.SIZE * 0.5f;
            float dx = chunkCenterX - centerX;
            float dz = chunkCenterZ - centerZ;

            if (dx * dx + dz * dz > rPad2) continue;
            e.getValue().draw();
        }
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    @Override
    public void close() {
        for (ChunkMesh m : meshes.values()) {
            try { m.close(); } catch (Exception ignored) {}
        }
        meshes.clear();
        // ...existing code...
    }
}
