package minecraftlike.engine;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.EXTTextureFilterAnisotropic.*;

public final class Texture implements AutoCloseable {
    private final int id;

    private Texture(int id) {
        this.id = id;
    }

    public static Texture fromFile(Path path) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);

        // Minecraft-ish filtering.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);

        STBImage.stbi_set_flip_vertically_on_load(true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);

            ByteBuffer data = STBImage.stbi_load(path.toString(), w, h, c, 4);
            if (data == null) {
                glDeleteTextures(tex);
                throw new IllegalArgumentException("Failed to load texture: " + path + " : " + STBImage.stbi_failure_reason());
            }

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w.get(0), h.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            glGenerateMipmap(GL_TEXTURE_2D);

            STBImage.stbi_image_free(data);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        return new Texture(tex);
    }

    public static Texture fromFileSmooth(Path path) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);

        STBImage.stbi_set_flip_vertically_on_load(true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);

            ByteBuffer data = STBImage.stbi_load(path.toString(), w, h, c, 4);
            if (data == null) {
                glDeleteTextures(tex);
                throw new IllegalArgumentException("Failed to load texture: " + path + " : " + STBImage.stbi_failure_reason());
            }

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w.get(0), h.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            glGenerateMipmap(GL_TEXTURE_2D);

            STBImage.stbi_image_free(data);
        }

        // Mild anisotropy if supported.
        if (glfwHasAnisotropySupport()) {
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, 8.0f);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        return new Texture(tex);
    }

    public static Texture fromRgba(int width, int height, ByteBuffer rgba) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);

        // Minecraft-ish filtering.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        // With padding in the atlas we can safely use mipmaps.
        // Use hard mip transitions (Minecraft style): no trilinear blending.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        glGenerateMipmap(GL_TEXTURE_2D);

        // Default anisotropy (if supported). Can be changed at runtime.
        if (glfwHasAnisotropySupport()) {
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, 4.0f);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        return new Texture(tex);
    }

    public void bind() {
        glBindTexture(GL_TEXTURE_2D, id);
    }

    public void setFiltering(boolean smooth, float anisotropy, float lodBias) {
        glBindTexture(GL_TEXTURE_2D, id);
        if (smooth) {
            // Smooth/trilinear (more "polished")
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        } else {
            // Pixelated (Minecraft-ish). Keep mipmaps but avoid trilinear blending.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        }

        // Positive bias forces lower mip levels (less sharp / less "HD" look).
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, lodBias);

        if (glfwHasAnisotropySupport()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var fb = stack.mallocFloat(1);
                glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, fb);
                float max = fb.get(0);
                float a = Math.max(1.0f, Math.min(anisotropy, max));
                glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, a);
            }
        }
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static boolean glfwHasAnisotropySupport() {
        return org.lwjgl.opengl.GL.getCapabilities().GL_EXT_texture_filter_anisotropic;
    }

    @Override
    public void close() {
        glDeleteTextures(id);
    }
}
