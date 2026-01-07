package minecraftlike.voxel;

final class FloatList {
    private float[] data;
    private int size;

    FloatList(int initialCapacity) {
        this.data = new float[Math.max(16, initialCapacity)];
    }

    void add(float v) {
        if (size >= data.length) {
            float[] n = new float[data.length * 2];
            System.arraycopy(data, 0, n, 0, data.length);
            data = n;
        }
        data[size++] = v;
    }

    void add8(float a, float b, float c, float d, float e, float f, float g, float h) {
        ensure(8);
        data[size++] = a;
        data[size++] = b;
        data[size++] = c;
        data[size++] = d;
        data[size++] = e;
        data[size++] = f;
        data[size++] = g;
        data[size++] = h;
    }

    private void ensure(int n) {
        if (size + n <= data.length) return;
        int cap = data.length;
        while (cap < size + n) cap *= 2;
        float[] out = new float[cap];
        System.arraycopy(data, 0, out, 0, size);
        data = out;
    }

    float[] toArray() {
        float[] out = new float[size];
        System.arraycopy(data, 0, out, 0, size);
        return out;
    }

    int size() {
        return size;
    }
}
