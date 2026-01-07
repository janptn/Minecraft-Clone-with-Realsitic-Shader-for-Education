package minecraftlike.engine;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

public final class SkyboxRenderer implements AutoCloseable {
    private final ShaderProgram shader;
    private final int vao;
    private final int vbo;

    public SkyboxRenderer() {
        this.shader = ShaderProgram.fromResources("shaders/skybox.vert", "shaders/skybox.frag");

        this.vao = glGenVertexArrays();
        this.vbo = glGenBuffers();

        // Fullscreen quad: pos2, uv2
        float[] verts = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
             1f,  1f, 1f, 1f,

            -1f, -1f, 0f, 0f,
             1f,  1f, 1f, 1f,
            -1f,  1f, 0f, 1f,
        };

        FloatBuffer fb = MemoryUtil.memAllocFloat(verts.length);
        fb.put(verts).flip();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);
        MemoryUtil.memFree(fb);
    }

    public void draw(Texture skyTex, Matrix4f invProj, Matrix4f invViewRot) {
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        shader.bind();
        shader.setUniformMat4("uInvProj", invProj);
        shader.setUniformMat4("uInvViewRot", invViewRot);

        glActiveTexture(GL_TEXTURE0);
        skyTex.bind();
        shader.setUniform1i("uSky", 0);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);

        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    @Override
    public void close() {
        shader.close();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
    }
}
