package minecraftlike.engine;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

public final class Mesh implements AutoCloseable {
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;

    private Mesh(int vao, int vbo, int ebo, int indexCount) {
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
        this.indexCount = indexCount;
    }

    public static Mesh unitCube() {
        // Interleaved: pos (3), uv (2), color (3)
        float[] verts = {
                // Front (+Z)
                -0.5f, -0.5f,  0.5f,  0f, 0f,  1f, 1f, 1f,
                 0.5f, -0.5f,  0.5f,  1f, 0f,  1f, 1f, 1f,
                 0.5f,  0.5f,  0.5f,  1f, 1f,  1f, 1f, 1f,
                -0.5f,  0.5f,  0.5f,  0f, 1f,  1f, 1f, 1f,

                // Back (-Z)
                 0.5f, -0.5f, -0.5f,  0f, 0f,  1f, 1f, 1f,
                -0.5f, -0.5f, -0.5f,  1f, 0f,  1f, 1f, 1f,
                -0.5f,  0.5f, -0.5f,  1f, 1f,  1f, 1f, 1f,
                 0.5f,  0.5f, -0.5f,  0f, 1f,  1f, 1f, 1f,

                // Left (-X)
                -0.5f, -0.5f, -0.5f,  0f, 0f,  1f, 1f, 1f,
                -0.5f, -0.5f,  0.5f,  1f, 0f,  1f, 1f, 1f,
                -0.5f,  0.5f,  0.5f,  1f, 1f,  1f, 1f, 1f,
                -0.5f,  0.5f, -0.5f,  0f, 1f,  1f, 1f, 1f,

                // Right (+X)
                 0.5f, -0.5f,  0.5f,  0f, 0f,  1f, 1f, 1f,
                 0.5f, -0.5f, -0.5f,  1f, 0f,  1f, 1f, 1f,
                 0.5f,  0.5f, -0.5f,  1f, 1f,  1f, 1f, 1f,
                 0.5f,  0.5f,  0.5f,  0f, 1f,  1f, 1f, 1f,

                // Top (+Y)
                -0.5f,  0.5f,  0.5f,  0f, 0f,  1f, 1f, 1f,
                 0.5f,  0.5f,  0.5f,  1f, 0f,  1f, 1f, 1f,
                 0.5f,  0.5f, -0.5f,  1f, 1f,  1f, 1f, 1f,
                -0.5f,  0.5f, -0.5f,  0f, 1f,  1f, 1f, 1f,

                // Bottom (-Y)
                -0.5f, -0.5f, -0.5f,  0f, 0f,  1f, 1f, 1f,
                 0.5f, -0.5f, -0.5f,  1f, 0f,  1f, 1f, 1f,
                 0.5f, -0.5f,  0.5f,  1f, 1f,  1f, 1f, 1f,
                -0.5f, -0.5f,  0.5f,  0f, 1f,  1f, 1f, 1f,
        };

        int[] indices = {
                0, 1, 2, 0, 2, 3,
                4, 5, 6, 4, 6, 7,
                8, 9, 10, 8, 10, 11,
                12, 13, 14, 12, 14, 15,
                16, 17, 18, 16, 18, 19,
                20, 21, 22, 20, 22, 23
        };

        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        int ebo = glGenBuffers();

        glBindVertexArray(vao);

        FloatBuffer vb = MemoryUtil.memAllocFloat(verts.length);
        vb.put(verts).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vb, GL_STATIC_DRAW);
        MemoryUtil.memFree(vb);

        IntBuffer ib = MemoryUtil.memAllocInt(indices.length);
        ib.put(indices).flip();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
        MemoryUtil.memFree(ib);

        int stride = (3 + 2 + 3) * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, (3L + 2L) * Float.BYTES);
        glEnableVertexAttribArray(2);

        glBindVertexArray(0);

        return new Mesh(vao, vbo, ebo, indices.length);
    }

    public void draw() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
    }

    @Override
    public void close() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
    }
}
