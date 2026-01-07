package minecraftlike.engine;

import static org.lwjgl.opengl.GL33.*;

public final class ShadowCubeMap implements AutoCloseable {
    private int fbo;
    private int depthCube;
    private int size;

    public ShadowCubeMap(int initialSize) {
        this.fbo = 0;
        this.depthCube = 0;
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
    }

    public void beginFace(int faceIndex) {
        int idx = Math.max(0, Math.min(5, faceIndex));
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_DEPTH_ATTACHMENT,
            GL_TEXTURE_CUBE_MAP_POSITIVE_X + idx,
            depthCube,
            0
        );
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    public void end() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void bindDepthCubeTexture(int textureUnit) {
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_CUBE_MAP, depthCube);
    }

    private void ensureSize(int desiredSize) {
        if (desiredSize <= 0) desiredSize = 1;
        if (desiredSize == size && fbo != 0 && depthCube != 0) return;

        size = desiredSize;

        if (depthCube == 0) depthCube = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP, depthCube);

        for (int i = 0; i < 6; i++) {
            glTexImage2D(
                GL_TEXTURE_CUBE_MAP_POSITIVE_X + i,
                0,
                GL_DEPTH_COMPONENT24,
                size,
                size,
                0,
                GL_DEPTH_COMPONENT,
                GL_FLOAT,
                0L
            );
        }

        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

        // IMPORTANT: we want to sample raw depth values in the voxel shader.
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_COMPARE_MODE, GL_NONE);

        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);

        if (fbo == 0) fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, depthCube, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Point shadow framebuffer incomplete: 0x" + Integer.toHexString(status));
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    @Override
    public void close() {
        if (depthCube != 0) glDeleteTextures(depthCube);
        if (fbo != 0) glDeleteFramebuffers(fbo);
    }
}
