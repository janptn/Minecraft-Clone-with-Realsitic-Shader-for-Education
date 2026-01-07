package minecraftlike.engine;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL33.*;

public final class ShaderProgram implements AutoCloseable {
    private final int programId;

    private ShaderProgram(int programId) {
        this.programId = programId;
    }

    public static ShaderProgram fromResources(String vertexPath, String fragmentPath) {
        String vert = readResourceText(vertexPath);
        String frag = readResourceText(fragmentPath);
        int vs = compile(GL_VERTEX_SHADER, vert);
        int fs = compile(GL_FRAGMENT_SHADER, frag);

        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
            glDeleteProgram(program);
            throw new IllegalStateException("Shader link failed: " + log);
        }

        glDetachShader(program, vs);
        glDetachShader(program, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);

        return new ShaderProgram(program);
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Shader compile failed: " + log);
        }
        return shader;
    }

    private static String readResourceText(String path) {
        try (InputStream in = ShaderProgram.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("Missing resource: " + path);
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void bind() {
        glUseProgram(programId);
    }

    public int id() {
        return programId;
    }

    public void setUniform1i(String name, int v) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) glUniform1i(loc, v);
    }

    public void setUniform1f(String name, float v) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) glUniform1f(loc, v);
    }

    public void setUniform2f(String name, float x, float y) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) glUniform2f(loc, x, y);
    }

    public void setUniform3f(String name, float x, float y, float z) {
        int loc = glGetUniformLocation(programId, name);
        if (loc >= 0) glUniform3f(loc, x, y, z);
    }

    public void setUniformMat4(String name, Matrix4f mat) {
        int loc = glGetUniformLocation(programId, name);
        if (loc < 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            mat.get(fb);
            glUniformMatrix4fv(loc, false, fb);
        }
    }

    @Override
    public void close() {
        glDeleteProgram(programId);
    }
}
