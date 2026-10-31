package dev.patodois.minecraftlink.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.minecraftlink.LinkSettings;
import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Endpoint;
import dev.patodois.rewritelink.Tokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The optional link token (contract revision 6) through two real {@link Session}s over loopback, with a real endpoint file. */
class TokenSessionTest {
    @BeforeAll
    static void version() {
        SharedConstants.tryDetectVersion();
    }

    private static LinkSettings settings(boolean tokenAuth, Path file, int port) {
        LinkSettings s = new LinkSettings();
        s.port = port;
        s.tokenAuth = tokenAuth;
        s.endpointFile = file == null ? "" : file.toString();
        s.guestEntity = 3;
        return s;
    }

    private static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(5);
        }
        return c.getAsBoolean();
    }

    private static List<String> drain(Session s) {
        List<String> out = new ArrayList<>();
        for (String m; (m = s.messages.poll()) != null; ) out.add(m);
        return out;
    }

    private static String tokenOf(Path file) throws Exception {
        return Endpoint.read(file).token();
    }

    @Test
    void hostWritesTheEndpointFileTheGuestReadsAndTheHostRemovesIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rewrite-link").resolve("mc.json"); // the directory does not exist yet: the mod makes it
        Session host = Session.open(Role.HOST, settings(true, file, 0), "host");
        Session guest = null;
        try {
            assertTrue(Files.isRegularFile(file), "the host wrote the endpoint file after binding");
            assertEquals(host.controlPort, Endpoint.read(file).controlPort(), "with the port it really bound");
            assertEquals(64, tokenOf(file).length());
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
            }
            // the guest is told nothing but the file; its own port setting (the default) is not the host's port
            guest = Session.open(Role.GUEST, settings(true, file, 1), "guest");
            Session g = guest;
            assertTrue(waitFor(() -> host.isConnected() && g.isConnected(), 6000), "connected with the token from the file");
            String token = tokenOf(file);
            for (Session s : new Session[] {host, guest}) {
                assertTrue(s.describe().contains("token auth on"), s.describe());
                assertFalse(s.describe().contains(token), "the status line never holds the token");
                for (String m : drain(s)) assertFalse(m.contains(token), "no notice holds the token: " + m);
            }
            assertTrue(guest.describe().startsWith("guest on 127.0.0.1:" + host.controlPort + ","), "the guest shows the port of the file: " + guest.describe());
            assertEquals(0, host.unauthorized.get());
            guest.closeBlocking();
            guest = null;
            host.closeBlocking();
            assertFalse(Files.exists(file), "a clean shutdown removes the endpoint file");
            assertTrue(Files.isDirectory(file.getParent()), "and leaves the directory");
        } finally {
            if (guest != null) guest.closeBlocking();
            host.closeBlocking();
        }
    }

    @Test
    void aGuestWithoutTheTokenIsRefusedAndBothSidesSayUnauthorized(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mc.json");
        Session host = Session.open(Role.HOST, settings(true, file, 0), "host");
        Session guest = Session.open(Role.GUEST, settings(false, null, host.controlPort), "guest");
        try {
            assertTrue(waitFor(() -> host.unauthorized.get() > 0 && guest.unauthorized.get() > 0, 6000), "both sides saw the refusal");
            assertFalse(host.isConnected());
            assertFalse(guest.isConnected());
            String token = tokenOf(file);
            List<String> hm = drain(host), gm = drain(guest);
            assertEquals(1, hm.size(), "the host tells the player once, not for every retry: " + hm);
            assertTrue(hm.get(0).contains("unauthorized"), hm.get(0));
            assertEquals(1, gm.size(), "the guest too: " + gm);
            assertTrue(gm.get(0).contains("unauthorized") && gm.get(0).contains("token_auth"), gm.get(0));
            assertTrue(host.describe().contains(" refused as unauthorized"), host.describe());
            for (String m : hm) assertFalse(m.contains(token));
            for (String m : gm) assertFalse(m.contains(token));
            assertFalse(host.describe().contains(token));
        } finally {
            guest.closeBlocking();
            host.closeBlocking();
        }
    }

    @Test
    void aGuestWithAWrongTokenIsRefused(@TempDir Path dir) throws Exception {
        Path hostFile = dir.resolve("host.json");
        Path guestFile = dir.resolve("guest.json");
        Session host = Session.open(Role.HOST, settings(true, hostFile, 0), "host");
        String wrong = Tokens.generate();
        assertNotEquals(wrong, tokenOf(hostFile));
        Endpoint.write(guestFile, host.controlPort, wrong);
        Session guest = Session.open(Role.GUEST, settings(true, guestFile, 1), "guest");
        try {
            assertTrue(waitFor(() -> host.unauthorized.get() > 0 && guest.unauthorized.get() > 0, 6000), "refused");
            assertFalse(host.isConnected() || guest.isConnected());
            List<String> gm = drain(guest);
            assertTrue(gm.get(0).contains("did not accept the token"), gm.get(0));
            for (String m : gm) assertFalse(m.contains(wrong) || m.contains(tokenOf(hostFile)));
            // the host restarts with a new token and port; a guest that reads the file again connects (here: we rewrite the file by hand)
            Endpoint.write(guestFile, host.controlPort, tokenOf(hostFile));
            assertTrue(waitFor(() -> host.isConnected() && guest.isConnected(), 6000), "with the right token in the file it connects without restarting");
        } finally {
            guest.closeBlocking();
            host.closeBlocking();
        }
    }

    @Test
    void aGuestRefusesAnEndpointFileThatOthersCanRead(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"));
        Path file = dir.resolve("mc.json");
        Session host = Session.open(Role.HOST, settings(true, file, 0), "host");
        String token = tokenOf(file); // read it while the file is still private: link-java refuses the group-readable one, as the contract says
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        Session guest = Session.open(Role.GUEST, settings(true, file, 1), "guest");
        try {
            Thread.sleep(1500); // several retries
            assertFalse(host.isConnected() || guest.isConnected(), "never connected: the file is group-readable");
            assertEquals(0, host.unauthorized.get(), "the guest never even sent a hello");
            String d = guest.describe();
            assertTrue(d.contains("endpoint file:") && d.contains("group or to others"), d);
            assertFalse(d.contains(token));
        } finally {
            guest.closeBlocking();
            host.closeBlocking();
        }
    }

    @Test
    void withoutTokenAuthNothingChanges(@TempDir Path dir) throws Exception {
        Session host = Session.open(Role.HOST, settings(false, dir.resolve("never.json"), 0), "host");
        Session guest = Session.open(Role.GUEST, settings(false, null, host.controlPort), "guest");
        try {
            assertTrue(waitFor(() -> host.isConnected() && guest.isConnected(), 6000));
            assertFalse(Files.exists(dir.resolve("never.json")), "no endpoint file unless token_auth is on");
            assertFalse(host.describe().contains("token"), host.describe());
        } finally {
            guest.closeBlocking();
            host.closeBlocking();
        }
    }
}
