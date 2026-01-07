package minecraftlike.voxel;

import java.util.HashMap;
import java.util.Map;

public final class World {
    private final Map<Long, Chunk> chunks = new HashMap<>();

    private final WorldGen gen;

    public World(long seed) {
        this.gen = new WorldGen(seed);
    }

    public Chunk getOrCreateChunk(int cx, int cz) {
        long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
        return chunks.computeIfAbsent(key, k -> {
            Chunk c = new Chunk(cx, cz);
            gen.generateChunk(c);
            return c;
        });
    }

    public Chunk getChunkIfLoaded(int cx, int cz) {
        long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
        return chunks.get(key);
    }

    public BlockType getBlock(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return BlockType.AIR;
        int cx = floorDiv(wx, Chunk.SIZE);
        int cz = floorDiv(wz, Chunk.SIZE);
        int lx = floorMod(wx, Chunk.SIZE);
        int lz = floorMod(wz, Chunk.SIZE);
        Chunk c = getOrCreateChunk(cx, cz);
        return c.get(lx, wy, lz);
    }

    public void setBlock(int wx, int wy, int wz, BlockType t) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return;
        int cx = floorDiv(wx, Chunk.SIZE);
        int cz = floorDiv(wz, Chunk.SIZE);
        int lx = floorMod(wx, Chunk.SIZE);
        int lz = floorMod(wz, Chunk.SIZE);
        Chunk c = getOrCreateChunk(cx, cz);
        c.set(lx, wy, lz, t);
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        int m = a % b;
        if ((m != 0) && ((m < 0) != (b < 0))) r--;
        return r;
    }

    private static int floorMod(int a, int b) {
        int m = a % b;
        if ((m != 0) && ((m < 0) != (b < 0))) m += b;
        return m;
    }
}
