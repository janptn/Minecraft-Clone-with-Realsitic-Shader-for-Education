package minecraftlike.voxel;

public final class Chunk {
    public static final int SIZE = 16;
    public static final int HEIGHT = 128;

    public final int cx;
    public final int cz;

    // Simple storage for now (MVP). Later: palettes/bitpacking.
    private final BlockType[] blocks = new BlockType[SIZE * HEIGHT * SIZE];

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        for (int i = 0; i < blocks.length; i++) blocks[i] = BlockType.AIR;
    }

    public BlockType get(int x, int y, int z) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE || y < 0 || y >= HEIGHT) return BlockType.AIR;
        return blocks[(y * SIZE + z) * SIZE + x];
    }

    public void set(int x, int y, int z, BlockType t) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE || y < 0 || y >= HEIGHT) return;
        blocks[(y * SIZE + z) * SIZE + x] = t;
    }
}
