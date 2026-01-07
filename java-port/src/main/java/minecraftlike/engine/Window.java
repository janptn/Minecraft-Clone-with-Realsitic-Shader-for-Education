package minecraftlike.engine;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class Window implements AutoCloseable {
    private final long handle;
    private int width;
    private int height;

    private boolean vsync;
    private boolean relativeMouseMode;

    private double lastMouseX;
    private double lastMouseY;
    private float mouseDeltaX;
    private float mouseDeltaY;

    private final boolean[] keyDown = new boolean[512];
    private final boolean[] keyPressed = new boolean[512];

    private final boolean[] mouseDown = new boolean[16];
    private final boolean[] mousePressed = new boolean[16];
    private float scrollDeltaY;

    private Window(long handle, int width, int height) {
        this.handle = handle;
        this.width = width;
        this.height = height;

        glfwSetKeyCallback(handle, (w, key, scancode, action, mods) -> {
            if (key < 0 || key >= keyDown.length) return;
            if (action == GLFW_PRESS) {
                keyDown[key] = true;
                keyPressed[key] = true;
            } else if (action == GLFW_RELEASE) {
                keyDown[key] = false;
            }
        });

        glfwSetFramebufferSizeCallback(handle, (w, newW, newH) -> {
            this.width = Math.max(1, newW);
            this.height = Math.max(1, newH);
        });

        glfwSetCursorPosCallback(handle, (w, x, y) -> {
            double dx = x - lastMouseX;
            double dy = y - lastMouseY;
            lastMouseX = x;
            lastMouseY = y;

            if (relativeMouseMode) {
                mouseDeltaX += (float) dx;
                mouseDeltaY += (float) dy;
            }
        });

        glfwSetMouseButtonCallback(handle, (w, button, action, mods) -> {
            if (button < 0 || button >= mouseDown.length) return;
            if (action == GLFW_PRESS) {
                mouseDown[button] = true;
                mousePressed[button] = true;
            } else if (action == GLFW_RELEASE) {
                mouseDown[button] = false;
            }
        });

        glfwSetScrollCallback(handle, (w, xoff, yoff) -> {
            scrollDeltaY += (float) yoff;
        });
    }

    public static Window create(String title, int width, int height) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        long handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            throw new IllegalStateException("Failed to create GLFW window");
        }

        glfwMakeContextCurrent(handle);
        GL.createCapabilities();

        glfwShowWindow(handle);

        Window window = new Window(handle, width, height);

        glfwGetCursorPos(handle, new double[]{window.lastMouseX}, new double[]{window.lastMouseY});
        return window;
    }

    public void pollEvents() {
        mouseDeltaX = 0.0f;
        mouseDeltaY = 0.0f;
        scrollDeltaY = 0.0f;
        for (int i = 0; i < keyPressed.length; i++) keyPressed[i] = false;
        for (int i = 0; i < mousePressed.length; i++) mousePressed[i] = false;
        glfwPollEvents();
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void setVsync(boolean enabled) {
        this.vsync = enabled;
        glfwSwapInterval(enabled ? 1 : 0);
    }

    public boolean isVsync() {
        return vsync;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean isKeyDown(int key) {
        return key >= 0 && key < keyDown.length && keyDown[key];
    }

    public boolean wasKeyPressed(int key) {
        return key >= 0 && key < keyPressed.length && keyPressed[key];
    }

    public void setRelativeMouseMode(boolean enabled) {
        this.relativeMouseMode = enabled;
        glfwSetInputMode(handle, GLFW_CURSOR, enabled ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        // Reset deltas so first frame after toggling doesn't jump.
        mouseDeltaX = 0.0f;
        mouseDeltaY = 0.0f;
    }

    public boolean isRelativeMouseMode() {
        return relativeMouseMode;
    }

    public float mouseDeltaX() {
        return mouseDeltaX;
    }

    public float mouseDeltaY() {
        return mouseDeltaY;
    }

    public float mouseX() {
        return (float) lastMouseX;
    }

    public float mouseY() {
        return (float) lastMouseY;
    }

    public boolean isMouseDown(int button) {
        return button >= 0 && button < mouseDown.length && mouseDown[button];
    }

    public boolean wasMousePressed(int button) {
        return button >= 0 && button < mousePressed.length && mousePressed[button];
    }

    public float scrollDeltaY() {
        return scrollDeltaY;
    }

    @Override
    public void close() {
        glfwDestroyWindow(handle);
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }
}
