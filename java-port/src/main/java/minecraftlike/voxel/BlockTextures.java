package minecraftlike.voxel;

public final class BlockTextures {
    private final TextureAtlas atlas;

    private final UvRect dirt;
    private final UvRect grassTop;
    private final UvRect grassSide;
        // Removed unnecessary fields
    // Removed waterWaves reference

    public BlockTextures(TextureAtlas atlas) {
        this.atlas = atlas;
        dirt = atlas.uvForTile(atlas.tileIndex("dirt"));
        grassTop = atlas.uvForTile(atlas.tileIndex("grass_top"));
        grassSide = atlas.uvForTile(atlas.tileIndex("grass_side"));
            // Removed unnecessary initializations
        // Removed initialization of waterWaves
    }

    public TextureAtlas atlas() {
        return atlas;
    }

    // Face convention: 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
    public UvRect uv(BlockType t, int face) {
        switch (t) {
            case DIRT:
                return dirt;
            case GRASS:
                if (face == 2) return grassTop;
                if (face == 0 || face == 1 || face == 4 || face == 5) return grassSide;
                return dirt;
            // ...existing code...
            default:
                return dirt;
        }
    }

    public boolean isCutout(BlockType t) {
        return t == BlockType.LEAVES || t == BlockType.TALL_GRASS || t == BlockType.TORCH;
    }

    // ...existing code...
}
