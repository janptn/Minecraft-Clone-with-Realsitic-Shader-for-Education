package minecraftlike.voxel;

public record RaycastHit(int x, int y, int z, int nx, int ny, int nz) {
    // nx,ny,nz is the face normal of the hit surface pointing outward from the hit block.
}
