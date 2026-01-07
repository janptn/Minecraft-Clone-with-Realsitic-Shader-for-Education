package minecraftlike.voxel;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

public final class ChunkMesh implements AutoCloseable {
    private final int vao;
    private final int vbo;
    private final int vertexCount;

    public ChunkMesh(float[] vertices, int vertexCount) {
        this.vertexCount = vertexCount;
        System.out.println("ChunkMesh created: vertexCount=" + vertexCount + ", vertices.length=" + vertices.length);
        // Debug: Zeige die ersten 10 UV-Werte
        for (int i = 0; i < Math.min(vertices.length / 10, 10); i++) {
            float u = vertices[i * 10 + 3];
            float v = vertices[i * 10 + 4];
            System.out.println("Vertex " + i + " UV: (" + u + ", " + v + ")");
        }

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length);
        fb.put(vertices).flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);

        MemoryUtil.memFree(fb);

        int stride = (3 + 2 + 3 + 1 + 1) * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, (3L + 2L) * Float.BYTES);
        glEnableVertexAttribArray(2);

        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, (3L + 2L + 3L) * Float.BYTES);
        glEnableVertexAttribArray(3);

        glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, (3L + 2L + 3L + 1L) * Float.BYTES);
        glEnableVertexAttribArray(4);

        glBindVertexArray(0);
    }

    public void draw() {
        if (vertexCount <= 0) {
            return;
        }
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
    }

    public int vertexCount() {
        return vertexCount;
    }

    @Override
    public void close() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
    }
}
