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
    private final Map<Long, ChunkMesh> waterMeshes = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();

    private int viewDistanceChunks = 6;

    public ChunkRenderer(World world, BlockTextures textures) {
        this.world = world;
        this.textures = textures;
    }

    public void setViewDistanceChunks(int v) {
        this.viewDistanceChunks = Math.max(1, v);
    }

    public void markDirtyChunk(int cx, int cz) {
        dirty.add(key(cx, cz));
    }

    public void update(float camX, float camZ) {
        int ccx = (int) Math.floor(camX / Chunk.SIZE);
        int ccz = (int) Math.floor(camZ / Chunk.SIZE);

        // Ensure chunks exist + meshes built around player.
        for (int dz = -viewDistanceChunks; dz <= viewDistanceChunks; dz++) {
            for (int dx = -viewDistanceChunks; dx <= viewDistanceChunks; dx++) {
                int cx = ccx + dx;
                int cz = ccz + dz;
                long k = key(cx, cz);

                world.getOrCreateChunk(cx, cz);

                if (!meshes.containsKey(k) || dirty.remove(k)) {
                    rebuild(cx, cz);
                }
            }
        }

        // Optional: could unload far meshes later.
    }

    private void rebuild(int cx, int cz) {
        long k = key(cx, cz);
        ChunkMesh old = meshes.remove(k);
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }
        ChunkMesh oldWater = waterMeshes.remove(k);
        if (oldWater != null) {
            try { oldWater.close(); } catch (Exception ignored) {}
        }
        ChunkMesh[] splitMeshes = ChunkMesher.buildMeshSplitWater(world, cx, cz, textures);
        meshes.put(k, splitMeshes[0]);
        waterMeshes.put(k, splitMeshes[1]);
    }

    public void drawAll(ShaderProgram shader) {
        // Meshes are already in world space; uModel should be identity.
        for (ChunkMesh m : meshes.values()) {
            if (m != null) m.draw();
        }
    }

    public void drawAllWater(ShaderProgram shader) {
        for (ChunkMesh m : waterMeshes.values()) {
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
        dirty.clear();
    }
}
