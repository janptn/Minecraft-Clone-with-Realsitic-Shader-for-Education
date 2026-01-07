package minecraftlike.engine;

public final class Time {
    private long lastNanos = System.nanoTime();
    private float deltaSeconds = 0.0f;
    private float totalSeconds = 0.0f;

    public void update() {
        long now = System.nanoTime();
        long dt = now - lastNanos;
        lastNanos = now;
        deltaSeconds = Math.max(0.0f, dt / 1_000_000_000.0f);
        totalSeconds += deltaSeconds;
    }

    public float deltaSeconds() {
        return deltaSeconds;
    }

    public float totalSeconds() {
        return totalSeconds;
    }
}
