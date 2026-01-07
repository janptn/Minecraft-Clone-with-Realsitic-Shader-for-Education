package minecraftlike.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class Camera {
    public final Vector3f position = new Vector3f();
    public float yaw = 0.0f;
    public float pitch = 0.0f;

    public Matrix4f viewMatrix() {
        Matrix4f view = new Matrix4f();
        view.rotateX((float) Math.toRadians(pitch));
        view.rotateY((float) Math.toRadians(yaw));
        view.translate(-position.x, -position.y, -position.z);
        return view;
    }
}
