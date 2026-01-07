package minecraftlike.voxel;

import java.util.Random;

public final class WorldGen {
    private final long seed;

    // Canopy radius in blocks (XZ). Used for cross-chunk placement.
    private static final int CANOPY_R = 2;

    public WorldGen(long seed) {
        this.seed = seed;
    }

    public void generateChunk(Chunk chunk) {
        int baseX = chunk.cx * Chunk.SIZE;
        int baseZ = chunk.cz * Chunk.SIZE;

        int[] heightMap = new int[Chunk.SIZE * Chunk.SIZE];

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                int h = terrainHeight(wx, wz);
                heightMap[lx + lz * Chunk.SIZE] = h;

                // Simple slope estimate (world-space height differences) for rocky mountains.
                int hX1 = terrainHeight(wx + 1, wz);
                int hZ1 = terrainHeight(wx, wz + 1);
                int slope = Math.max(Math.abs(h - hX1), Math.abs(h - hZ1));

                boolean rocky = (h >= 58 && slope >= 4) || (h >= 70);

                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    BlockType t;
                    if (y > h) {
                        t = BlockType.AIR;
                    } else if (y == h) {
                        t = rocky ? BlockType.COBBLESTONE : BlockType.GRASS;
                    } else if (y >= h - 4) {
                        t = rocky ? BlockType.STONE : BlockType.DIRT;
                    } else {
                        t = BlockType.STONE;
                    }
                    if (lx >= 0 && lx < Chunk.SIZE && lz >= 0 && lz < Chunk.SIZE && y >= 0 && y < Chunk.HEIGHT) {
                        chunk.set(lx, y, lz, t);
                    }
                }
                // Trees (rare)
                // Trees are generated in a second pass (world-space, cross-chunk safe).
            }
        }
        // Place tall grass after terrain generation
        scatterTallGrass(chunk, heightMap, baseX, baseZ);
    }

    // Now at class level, using heightMap
    private void scatterTallGrass(Chunk chunk, int[] heightMap, int baseX, int baseZ) {
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int h = heightMap[lx + lz * Chunk.SIZE];
                if (h < 1 || h >= Chunk.HEIGHT) continue;
                if (chunk.get(lx, h, lz) == BlockType.GRASS && chunk.get(lx, h + 1, lz) == BlockType.AIR && Math.random() < 0.15) {
                    chunk.set(lx, h + 1, lz, BlockType.TALL_GRASS);
                }
            }
        }
    }

    private void carveCaves(Chunk chunk, int baseX, int baseZ, int[] heightMap) {
        // Tunnel caves: carve where two 3D noise fields are both close to their midline.
        // This produces narrow connected passages (not huge blobs).
        final double freqXZ = 0.050;
        final double freqY = 0.060;

        // Offsets to decorrelate the fields.
        final double o1 = ((seed >>> 0) & 0xffff) * 0.001;
        final double o2 = ((seed >>> 16) & 0xffff) * 0.001;
        final double o3 = ((seed >>> 32) & 0xffff) * 0.001;
        final double o4 = ((seed >>> 48) & 0xffff) * 0.001;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int surfaceY = heightMap[lx + lz * Chunk.SIZE];

                // Cave regions: only carve in some world-space areas.
                double region = fbm2(wx * 0.010, wz * 0.010);
                boolean inRegion = region > 0.45;
                // Entrances: can happen when region is strong (and a bit more in mountains).
                double entranceMask = fbm2(wx * 0.030, wz * 0.030);
                boolean entrance = (region > 0.62) || (entranceMask > 0.70) || (surfaceY >= 55 && region > 0.55);

                // Deep caves should exist almost everywhere so digging down finds them.
                boolean deepCavesEverywhere = true;

                if (!deepCavesEverywhere && !inRegion && !entrance) continue;

                // Allow entrances to reach and even break the surface (user requested it's OK).
                int maxCarveY = Math.max(0, surfaceY - 6);
                if (entrance) maxCarveY = Math.max(0, surfaceY + 1);

                for (int y = 6; y <= maxCarveY; y++) {
                    BlockType t = chunk.get(lx, y, lz);
                    if (t == BlockType.AIR) continue;
                    // Don't carve trees/leaves/placed blocks; keep it to terrain materials.
                    if (t != BlockType.STONE && t != BlockType.DIRT && t != BlockType.GRASS && t != BlockType.COBBLESTONE) continue;

                    // If we're not in a cave region, only allow carving deep underground (so you can still find caves by digging).
                    if (!inRegion && !entrance && y > 42) continue;

                    // Unless this column is an entrance candidate, keep a solid cap.
                    if (!entrance && y > surfaceY - 10) continue;

                        // More tunnels at mid-depth.
                        double y01 = y / (double) Chunk.HEIGHT;
                        double depthBoost = 1.0 - Math.abs(y01 - 0.35) * 2.2;
                        depthBoost = clamp01(depthBoost);

                        // Base tunnel radius (in noise space). Clamp tightly to avoid rooms.
                        double r = 0.045 + 0.012 * depthBoost;
                        // Small per-column variation (kept subtle).
                        double rv = fbm2(wx * 0.020, wz * 0.020);
                        r *= (0.92 + 0.10 * rv);
                        // Hard clamp: prevents big cavities.
                        r = Math.max(0.038, Math.min(0.060, r));

                        // Entrances: allow breaking the surface, but keep the opening narrow.
                        if (entrance && y >= surfaceY - 3) {
                            r = Math.min(r, 0.040);
                        }

                        double nx = wx * freqXZ;
                        double ny = y * freqY;
                        double nz = wz * freqXZ;

                        double a = fbm3(nx + o1, ny + o2, nz + o3);
                        double b = fbm3(nx - o3 + o4, ny + o1 - o2, nz + o2 + o4);

                        double da = Math.abs(a - 0.5);
                        double db = Math.abs(b - 0.5);

                        boolean tunnel = (da < r) && (db < r);
                        if (tunnel) {
                        chunk.set(lx, y, lz, BlockType.AIR);
                    }
                }
            }
        }
    }

    private double fbm3(double x, double y, double z) {
        double sum = 0.0;
        double amp = 0.55;
        double f = 1.0;
        for (int i = 0; i < 4; i++) {
            sum += amp * valueNoise3(x * f, y * f, z * f);
            f *= 2.02;
            amp *= 0.52;
        }
        return sum;
    }

    private double valueNoise3(double x, double y, double z) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        int z0 = fastFloor(z);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;

        double tx = x - x0;
        double ty = y - y0;
        double tz = z - z0;

        double u = fade(tx);
        double v = fade(ty);
        double w = fade(tz);

        double c000 = hash01(x0, y0, z0);
        double c100 = hash01(x1, y0, z0);
        double c010 = hash01(x0, y1, z0);
        double c110 = hash01(x1, y1, z0);
        double c001 = hash01(x0, y0, z1);
        double c101 = hash01(x1, y0, z1);
        double c011 = hash01(x0, y1, z1);
        double c111 = hash01(x1, y1, z1);

        double x00 = lerp(c000, c100, u);
        double x10 = lerp(c010, c110, u);
        double x01 = lerp(c001, c101, u);
        double x11 = lerp(c011, c111, u);
        double y0v = lerp(x00, x10, v);
        double y1v = lerp(x01, x11, v);
        return lerp(y0v, y1v, w);
    }

    private double hash01(int x, int y, int z) {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) z * 0x165667B19E3779F9L;
        h = mix64(h);
        // Convert top 53 bits to [0,1)
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static double fade(double t) {
        // 6t^5 - 15t^4 + 10t^3
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private void generateTreesForChunk(Chunk chunk, int baseX, int baseZ) {
        int minX = baseX - CANOPY_R;
        int maxX = baseX + Chunk.SIZE - 1 + CANOPY_R;
        int minZ = baseZ - CANOPY_R;
        int maxZ = baseZ + Chunk.SIZE - 1 + CANOPY_R;

        for (int wz = minZ; wz <= maxZ; wz++) {
            for (int wx = minX; wx <= maxX; wx++) {
                if (!isTreeCenter(wx, wz)) continue;

                int h = terrainHeight(wx, wz);
                if (h <= 10 || h >= Chunk.HEIGHT - 10) continue;

                Random r = rnd(wx, wz);
                int trunkH = 4 + r.nextInt(2);
                placeTreePart(chunk, baseX, baseZ, wx, h + 1, wz, trunkH);
            }
        }
    }

    private boolean isTreeCenter(int wx, int wz) {
        // Deterministic sparse placement. Roughly 1 tree per ~500 positions.
        long h = seed;
        h ^= (long) wx * 0x9E3779B97F4A7C15L;
        h ^= (long) wz * 0xC2B2AE3D27D4EB4FL;
        h = mix64(h);
        // Keep it stable and simple.
        return (int) (Math.floorMod(h, 520L)) == 0;
    }

    private static void placeTreePart(Chunk chunk, int baseX, int baseZ, int wx, int y, int wz, int trunkH) {
        // Trunk
        for (int i = 0; i < trunkH; i++) {
            int ty = y + i;
            setIfInChunk(chunk, baseX, baseZ, wx, ty, wz, BlockType.WOOD, true);
        }

        int top = y + trunkH - 1;
        // Canopy (simple, Ursina-like)
        placeLeavesDiamondPart(chunk, baseX, baseZ, wx, top + 1, wz, 2);
        placeLeavesDiamondPart(chunk, baseX, baseZ, wx, top + 2, wz, 1);
        setIfInChunk(chunk, baseX, baseZ, wx, top + 3, wz, BlockType.LEAVES, false);
    }

    private static void placeLeavesDiamondPart(Chunk chunk, int baseX, int baseZ, int cx, int y, int cz, int r) {
        if (y < 0 || y >= Chunk.HEIGHT) return;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int md = Math.abs(dx) + Math.abs(dz);
                if (md > r) continue;
                setIfInChunk(chunk, baseX, baseZ, cx + dx, y, cz + dz, BlockType.LEAVES, false);
            }
        }
    }

    private static void setIfInChunk(Chunk chunk, int baseX, int baseZ, int wx, int wy, int wz, BlockType t, boolean overwrite) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return;
        int lx = wx - baseX;
        int lz = wz - baseZ;
        if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE) return;

        if (!overwrite) {
            if (chunk.get(lx, wy, lz) != BlockType.AIR) return;
        }
        chunk.set(lx, wy, lz, t);
    }

    private int terrainHeight(int x, int z) {
        // Less perfect terrain: low-frequency biome blending + fractal noise.
        // Biome blend: plains -> hills -> mountains.
        double biome = fbm2(x * 0.0026, z * 0.0026);

        double plains = fbm2(x * 0.020, z * 0.020) * 10.0 + fbm2(x * 0.060, z * 0.060) * 4.0;
        double hills = fbm2(x * 0.012, z * 0.012) * 26.0 + fbm2(x * 0.030, z * 0.030) * 10.0;
        double mountains = fbm2(x * 0.008, z * 0.008) * 70.0 + fbm2(x * 0.020, z * 0.020) * 18.0;

        // Ridged for sharper peaks.
        double ridged = 1.0 - Math.abs(fbm2(x * 0.0055, z * 0.0055) * 2.0 - 1.0);
        ridged = ridged * ridged;

        // Map biome into a 2-stage blend: plains->hills, then hills->mountains.
        double t1 = clamp01((biome - 0.25) * 1.6);
        double t2 = clamp01((biome - 0.42) * 2.4);

        double baseShape = lerp(plains, hills, t1);
        baseShape = lerp(baseShape, mountains, t2);

        double heightNoise = baseShape + ridged * (6.0 + t2 * 30.0);
        int base = 60;
        int h = (int) Math.round(base + heightNoise);
        h = Math.max(4, Math.min(Chunk.HEIGHT - 2, h));
        return h;
    }

    private double fbm2(double x, double z) {
        // Use 3D value noise with a constant Y slice to get stable 2D noise.
        double sum = 0.0;
        double amp = 0.55;
        double f = 1.0;
        double ySlice = (double) ((seed ^ 0x9E3779B97F4A7C15L) & 0xffff) * 0.001;
        for (int i = 0; i < 4; i++) {
            sum += amp * valueNoise3(x * f, ySlice, z * f);
            f *= 2.02;
            amp *= 0.52;
        }
        return sum;
    }

    private Random rnd(int x, int z) {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) z * 0xC2B2AE3D27D4EB4FL;
        h = mix64(h);
        return new Random(h);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
