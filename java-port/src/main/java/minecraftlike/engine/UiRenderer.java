package minecraftlike.engine;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

public final class UiRenderer implements AutoCloseable {
    private final ShaderProgram solidShader;
    private final ShaderProgram texShader;

    private final int vaoSolid;
    private final int vboSolid;

    private final int vaoTex;
    private final int vboTex;

    public UiRenderer() {
        this.solidShader = ShaderProgram.fromResources("shaders/ui.vert", "shaders/ui.frag");
        this.texShader = ShaderProgram.fromResources("shaders/ui_tex.vert", "shaders/ui_tex.frag");

        this.vaoSolid = glGenVertexArrays();
        this.vboSolid = glGenBuffers();

        glBindVertexArray(vaoSolid);
        glBindBuffer(GL_ARRAY_BUFFER, vboSolid);
        glBufferData(GL_ARRAY_BUFFER, 6L * 2L * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);

        this.vaoTex = glGenVertexArrays();
        this.vboTex = glGenBuffers();
        glBindVertexArray(vaoTex);
        glBindBuffer(GL_ARRAY_BUFFER, vboTex);
        // 6 vertices * (pos2 + uv2)
        glBufferData(GL_ARRAY_BUFFER, 6L * 4L * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    public void drawRect(float x, float y, float w, float h, int screenW, int screenH, float r, float g, float b, float a) {
        // Two triangles, pixel-space positions.
        float x0 = x;
        float y0 = y;
        float x1 = x + w;
        float y1 = y + h;

        float[] verts = {
                x0, y0,
                x1, y0,
                x1, y1,

                x0, y0,
                x1, y1,
                x0, y1,
        };

        FloatBuffer fb = MemoryUtil.memAllocFloat(verts.length);
        fb.put(verts).flip();

        glBindVertexArray(vaoSolid);
        glBindBuffer(GL_ARRAY_BUFFER, vboSolid);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(fb);

        solidShader.bind();
        int pid = solidShader.id();
        int loc = glGetUniformLocation(pid, "uColor");
        if (loc >= 0) glUniform4f(loc, r, g, b, a);

        int loc2 = glGetUniformLocation(pid, "uScreen");
        if (loc2 >= 0) glUniform2f(loc2, (float) screenW, (float) screenH);

        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
    }

    public void drawTexturedRect(
            float x, float y, float w, float h,
            int screenW, int screenH,
            float u0, float v0, float u1, float v1,
            float alpha
    ) {
        float x0 = x;
        float y0 = y;
        float x1 = x + w;
        float y1 = y + h;

        float[] verts = {
                x0, y0, u0, v0,
                x1, y0, u1, v0,
                x1, y1, u1, v1,

                x0, y0, u0, v0,
                x1, y1, u1, v1,
                x0, y1, u0, v1,
        };

        FloatBuffer fb = MemoryUtil.memAllocFloat(verts.length);
        fb.put(verts).flip();

        glBindVertexArray(vaoTex);
        glBindBuffer(GL_ARRAY_BUFFER, vboTex);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(fb);

        texShader.bind();
        int pid = texShader.id();

        int locScreen = glGetUniformLocation(pid, "uScreen");
        if (locScreen >= 0) glUniform2f(locScreen, (float) screenW, (float) screenH);

        int locAlpha = glGetUniformLocation(pid, "uAlpha");
        if (locAlpha >= 0) glUniform1f(locAlpha, alpha);

        int locTex = glGetUniformLocation(pid, "uTex");
        if (locTex >= 0) glUniform1i(locTex, 0);

        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
    }

    @Override
    public void close() {
        solidShader.close();
        texShader.close();
        glDeleteBuffers(vboSolid);
        glDeleteVertexArrays(vaoSolid);
        glDeleteBuffers(vboTex);
        glDeleteVertexArrays(vaoTex);
    }
}
