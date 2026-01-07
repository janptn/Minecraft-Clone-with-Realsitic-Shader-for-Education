package minecraftlike.voxel;

public final class BlockTextures {
    private final TextureAtlas atlas;

    private final UvRect dirt;
    private final UvRect grassTop;
    private final UvRect grassSide;
    private final UvRect stone;
    private final UvRect planks;
    private final UvRect logTop;
    private final UvRect logSide;
    private final UvRect cobble;
    private final UvRect leaves;
    private final UvRect craftTop;
    private final UvRect craftSide;
    private final UvRect ironOre;
    private final UvRect coalOre;
    private final UvRect furnace;
    private final UvRect torchSide;
    private final UvRect torchTop;
    private final UvRect torchBottom;
    private final UvRect torchFallback;
    private final UvRect tallGrass;
    private final UvRect waterWaves;

    public BlockTextures(TextureAtlas atlas) {
        this.atlas = atlas;
        dirt = atlas.uvForTile(atlas.tileIndex("dirt"));
        grassTop = atlas.uvForTile(atlas.tileIndex("grass_top"));
        grassSide = atlas.uvForTile(atlas.tileIndex("grass_side"));
        stone = atlas.uvForTile(atlas.tileIndex("stone"));
        planks = atlas.uvForTile(atlas.tileIndex("planks"));
        logTop = atlas.uvForTile(atlas.tileIndex("log_top"));
        logSide = atlas.uvForTile(atlas.tileIndex("log_side"));
        cobble = atlas.uvForTile(atlas.tileIndex("cobblestone"));
        leaves = atlas.uvForTile(atlas.tileIndex("leaves"));
        craftTop = atlas.uvForTile(atlas.tileIndex("craft_top"));
        craftSide = atlas.uvForTile(atlas.tileIndex("craft_side"));
        ironOre = atlas.uvForTile(atlas.tileIndex("iron_ore"));
        coalOre = atlas.uvForTile(atlas.tileIndex("coal_ore"));
        furnace = atlas.uvForTile(atlas.tileIndex("furnace"));
        torchSide = atlas.uvForTile(atlas.tileIndex("torch_side"));
        torchTop = atlas.uvForTile(atlas.tileIndex("torch_top"));
        torchBottom = atlas.uvForTile(atlas.tileIndex("torch_bottom"));
        torchFallback = atlas.uvForTile(atlas.tileIndex("torch"));
        tallGrass = atlas.uvForTile(atlas.tileIndex("tall_grass"));
        waterWaves = atlas.uvForTile(atlas.tileIndex("water_waves"));
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
            case STONE:
                return stone;
            case COBBLESTONE:
                return cobble;
            case WOOD:
                if (face == 2 || face == 3) return logTop;
                return logSide;
            case PLANKS:
                return planks;
            case LEAVES:
                return leaves;
            case CRAFTING_TABLE:
                if (face == 2) return craftTop;
                if (face == 0 || face == 1 || face == 4 || face == 5) return craftSide;
                return craftTop;
            case IRON_ORE:
                return ironOre;
            case COAL_ORE:
                return coalOre;
            case FURNACE:
                return furnace;
            case TORCH:
                if (face == 2) return torchTop;
                if (face == 3) return torchBottom;
                if (face == 0 || face == 1 || face == 4 || face == 5) return torchSide;
                return torchFallback;
            case TALL_GRASS:
                return tallGrass;
            case WATER:
                return waterWaves;
            default:
                return dirt;
        }
    }

    public boolean isCutout(BlockType t) {
        return t == BlockType.LEAVES || t == BlockType.TALL_GRASS || t == BlockType.TORCH;
    }

    public UvRect tallGrassUv() {
        return tallGrass;
    }
}
