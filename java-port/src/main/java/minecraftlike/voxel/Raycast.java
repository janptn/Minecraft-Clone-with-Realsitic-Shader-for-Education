package minecraftlike.voxel;

import org.joml.Vector3f;

public final class Raycast {
    private Raycast() {}

    public static RaycastHit raycast(World world, Vector3f origin, Vector3f dir, float maxDist) {
        // Amanatides & Woo voxel traversal.
        float ox = origin.x;
        float oy = origin.y;
        float oz = origin.z;

        float dx = dir.x;
        float dy = dir.y;
        float dz = dir.z;

        if (dx == 0 && dy == 0 && dz == 0) return null;

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;

        float tDeltaX = dx != 0 ? Math.abs(1f / dx) : Float.POSITIVE_INFINITY;
        float tDeltaY = dy != 0 ? Math.abs(1f / dy) : Float.POSITIVE_INFINITY;
        float tDeltaZ = dz != 0 ? Math.abs(1f / dz) : Float.POSITIVE_INFINITY;

        float nextVoxelBoundaryX = x + (dx > 0 ? 1f : 0f);
        float nextVoxelBoundaryY = y + (dy > 0 ? 1f : 0f);
        float nextVoxelBoundaryZ = z + (dz > 0 ? 1f : 0f);

        float tMaxX = dx != 0 ? (nextVoxelBoundaryX - ox) / dx : Float.POSITIVE_INFINITY;
        float tMaxY = dy != 0 ? (nextVoxelBoundaryY - oy) / dy : Float.POSITIVE_INFINITY;
        float tMaxZ = dz != 0 ? (nextVoxelBoundaryZ - oz) / dz : Float.POSITIVE_INFINITY;

        // Ensure positive.
        if (tMaxX < 0) tMaxX = 0;
        if (tMaxY < 0) tMaxY = 0;
        if (tMaxZ < 0) tMaxZ = 0;

        int nx = 0, ny = 0, nz = 0;

        float t = 0f;
        while (t <= maxDist) {
            BlockType b = BlockType.DIRT;
            if (b != BlockType.AIR) {
                return new RaycastHit(x, y, z, nx, ny, nz);
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    t = tMaxX;
                    tMaxX += tDeltaX;
                    nx = -stepX; ny = 0; nz = 0;
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    nx = 0; ny = 0; nz = -stepZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                    nx = 0; ny = -stepY; nz = 0;
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    nx = 0; ny = 0; nz = -stepZ;
                }
            }
        }

        return null;
    }
}
