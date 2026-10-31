package dev.patodois.minecraftlink;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;

/**
 * The places and words of the optional link token (contract revision 6, CONTRACT.md 6.2): where the endpoint file lives by default,
 * making its directory, and telling the {@code unauthorized} answer from other handshake failures. The token itself is made, written,
 * read, sent and compared by link-java ({@code Tokens}, {@code Endpoint}, {@code LinkConfig}); this mod never holds it, so it cannot log it,
 * show it in the chat or put it in a crash report. Java 8 syntax only (the 1.7.10 and 1.12.2 builds share this file).
 */
public final class TokenAuth {
    /** The name both sides know in the recommended path {@code rewrite-link/<name>.json}. */
    public static final String DEFAULT_NAME = "minecraft-java.json";
    /** The reason a host sends when it refuses a guest for its token; a guest sees it as {@code peer said bye: unauthorized}. */
    public static final String UNAUTHORIZED = "unauthorized";

    private TokenAuth() {}

    /**
     * The recommended endpoint file of CONTRACT.md 6.2 for this machine: macOS {@code $HOME/Library/Application Support/rewrite-link/},
     * Windows {@code %LOCALAPPDATA%\rewrite-link\}, other Unix {@code $XDG_RUNTIME_DIR/rewrite-link/} or, without it,
     * {@code $HOME/.cache/rewrite-link/}. {@code $HOME} wins over {@code user.home} where it is set.
     */
    public static Path defaultFile(String osName, Map<String, String> env, String userHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String home = nonEmpty(env.get("HOME")) ? env.get("HOME") : userHome;
        if (os.contains("win")) {
            String local = env.get("LOCALAPPDATA");
            if (nonEmpty(local)) return Paths.get(local, "rewrite-link", DEFAULT_NAME);
            return Paths.get(nonEmpty(userHome) ? userHome : home, "AppData", "Local", "rewrite-link", DEFAULT_NAME);
        }
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "rewrite-link", DEFAULT_NAME);
        String xdg = env.get("XDG_RUNTIME_DIR");
        if (nonEmpty(xdg)) return Paths.get(xdg, "rewrite-link", DEFAULT_NAME);
        return Paths.get(home, ".cache", "rewrite-link", DEFAULT_NAME);
    }

    /** {@link #defaultFile(String, Map, String)} for the running JVM. */
    public static Path defaultFile() {
        return defaultFile(System.getProperty("os.name"), System.getenv(), System.getProperty("user.home"));
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * Makes the directory of the endpoint file when it is missing (link-java does not), owner-only (mode 0700) where the file system has
     * POSIX permissions, as CONTRACT.md 6.2 asks of an application that creates the {@code rewrite-link} directory. An existing directory is left
     * as it is.
     */
    public static void prepareDirectory(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null || Files.isDirectory(dir)) return;
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(dir);
        }
    }

    /** Whether a {@code HandshakeFailed} reason (host: {@code unauthorized}, guest: {@code peer said bye: unauthorized}) is the token refusal. */
    public static boolean isUnauthorized(String reason) {
        return UNAUTHORIZED.equals(reason) || ("peer said bye: " + UNAUTHORIZED).equals(reason);
    }
}
