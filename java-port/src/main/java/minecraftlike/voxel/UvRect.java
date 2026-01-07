package minecraftlike.voxel;

public record UvRect(float u0, float v0, float u1, float v1) {
    public static UvRect full() {
        return new UvRect(0f, 0f, 1f, 1f);
    }
}
