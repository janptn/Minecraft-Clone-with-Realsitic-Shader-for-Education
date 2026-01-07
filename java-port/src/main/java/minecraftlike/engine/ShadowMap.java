package minecraftlike.engine;

import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

public final class ShadowMap implements AutoCloseable {
    private int fbo;
    private int depthTex;
    private int size;

    public ShadowMap(int initialSize) {
        this.fbo = 0;
        this.depthTex = 0;
        this.size = 0;
        ensureSize(initialSize);
    }

    public int size() {
        return size;
    }

    public void begin(int desiredSize) {
        ensureSize(desiredSize);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, size, size);
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    public void end() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void bindDepthTexture(int textureUnit) {
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_2D, depthTex);
    }

    private void ensureSize(int desiredSize) {
        if (desiredSize <= 0) desiredSize = 1;
        if (desiredSize == size && fbo != 0 && depthTex != 0) return;

        size = desiredSize;

        if (depthTex == 0) depthTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTex);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);

        // Outside the shadow map should be considered fully lit.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer border = stack.mallocFloat(4);
            border.put(1f).put(1f).put(1f).put(1f).flip();
            glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, border);
        }

        // Hardware depth comparison for sampler2DShadow.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

        glBindTexture(GL_TEXTURE_2D, 0);

        if (fbo == 0) fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Shadow framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    @Override
    public void close() {
        if (depthTex != 0) glDeleteTextures(depthTex);
        if (fbo != 0) glDeleteFramebuffers(fbo);
    }
}
