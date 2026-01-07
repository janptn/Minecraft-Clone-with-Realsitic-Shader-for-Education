package minecraftlike.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Assets {
    private Assets() {}

    public static Path findTexture(String fileName) {
        // When running from repo root: textures/<file>
        Path p1 = Paths.get("textures", fileName);
        if (Files.exists(p1)) return p1;

        // When running from java-port/: ../textures/<file>
        Path p2 = Paths.get("..", "textures", fileName);
        if (Files.exists(p2)) return p2;

        // Fallback: current dir.
        Path p3 = Paths.get(fileName);
        if (Files.exists(p3)) return p3;

        throw new IllegalArgumentException("Texture not found: " + fileName + " (tried textures/, ../textures/, ./)");
    }
}
