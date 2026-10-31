package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenAuthTest {
    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void defaultLocationsFollowTheContract() {
        assertEquals(Paths.get("/Users/u/Library/Application Support/rewrite-link/minecraft-java.json"),
                TokenAuth.defaultFile("Mac OS X", env("HOME", "/Users/u"), "/other"));
        assertEquals(Paths.get("/run/user/1000/rewrite-link/minecraft-java.json"),
                TokenAuth.defaultFile("Linux", env("HOME", "/home/u", "XDG_RUNTIME_DIR", "/run/user/1000"), "/home/u"));
        assertEquals(Paths.get("/home/u/.cache/rewrite-link/minecraft-java.json"),
                TokenAuth.defaultFile("Linux", env("HOME", "/home/u"), "/x"));
        assertEquals(Paths.get("C:\\Users\\u\\AppData\\Local", "rewrite-link", "minecraft-java.json"),
                TokenAuth.defaultFile("Windows 11", env("LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local"), "C:\\Users\\u"));
        // $HOME wins over user.home (a launcher or a test may set it)
        assertEquals(Paths.get("/h/Library/Application Support/rewrite-link/minecraft-java.json"),
                TokenAuth.defaultFile("Mac OS X", env("HOME", "/h"), "/u"));
        assertEquals(Paths.get("/u/.cache/rewrite-link/minecraft-java.json"), TokenAuth.defaultFile("Linux", env(), "/u"));
    }

    @Test
    void theDirectoryIsMadeOwnerOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a").resolve("b").resolve("f.json");
        TokenAuth.prepareDirectory(file);
        assertTrue(Files.isDirectory(file.getParent()));
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        }
        TokenAuth.prepareDirectory(file); // already there: no error
    }

    @Test
    void recognisesTheRefusalOnBothSides() {
        assertTrue(TokenAuth.isUnauthorized("unauthorized"));
        assertTrue(TokenAuth.isUnauthorized("peer said bye: unauthorized"));
        assertFalse(TokenAuth.isUnauthorized("busy"));
        assertFalse(TokenAuth.isUnauthorized(null));
    }
}
