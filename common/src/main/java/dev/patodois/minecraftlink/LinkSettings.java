package dev.patodois.minecraftlink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code config/minecraft-link.json}. Unknown keys are ignored, missing keys take the defaults below. */
public final class LinkSettings {
    /** {@code "host"} (the player is in Minecraft) or {@code "guest"} (Minecraft publishes its player). Default for commands without a role. */
    public String role = "host";
    /** Control port: the host listens on it, the guest connects to it. */
    public int port = 47800;
    /** UDP port of this side, 0 = any free port. */
    public int udpPort = 0;
    /** Mirror the world along X / Z when the other game's axes turn out different. See {@link Mapping}. */
    public boolean flipX = false;
    public boolean flipZ = false;
    /** Link metres per block. */
    public double scale = 1.0;
    /** Link position of the Minecraft origin (after flipping and scaling), metres. */
    public double[] offset = {0, 0, 0};
    /** Host: guest entities are shown this many milliseconds in the past, so there are samples to interpolate between. */
    public int interpDelayMs = 40;
    /** Host: radius of the collision snapshot around the player, in blocks (1 block = 1 m). */
    public int collisionRadius = 16;
    /** Host: least time between collision rescans. */
    public int collisionIntervalMs = 500;
    /** Host: rescan at once when the player has moved this far (blocks) from the last snapshot's centre. */
    public double collisionMoveBlocks = 1.0;
    /** Guest: entity id and description announced in {@code spawn}. */
    public int guestEntity = 1;
    public String guestProvider = "Minecraft";
    public String guestAsset = "minecraft:player";
    public int heartbeatMs = 250;
    public int timeoutMs = 2000;
    /** Host: draw the pixel-file frames of a guest (needs the guest to announce a frame_channel). */
    public boolean showFrames = true;

    public Mapping mapping() {
        return new Mapping(flipX, flipZ, scale, offset);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static LinkSettings parse(String json) throws JsonParseException {
        LinkSettings s = new LinkSettings();
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        s.role = str(o, "role", s.role).toLowerCase(java.util.Locale.ROOT);
        if (!s.role.equals("host") && !s.role.equals("guest")) throw new JsonParseException("role must be host or guest");
        s.port = intIn(o, "port", s.port, 1, 65535);
        s.udpPort = intIn(o, "udp_port", s.udpPort, 0, 65535);
        s.flipX = bool(o, "flip_x", s.flipX);
        s.flipZ = bool(o, "flip_z", s.flipZ);
        s.scale = num(o, "scale", s.scale);
        if (!(s.scale > 0) || !Double.isFinite(s.scale)) throw new JsonParseException("scale must be > 0");
        if (o.has("offset")) {
            JsonArray a = o.getAsJsonArray("offset");
            if (a.size() != 3) throw new JsonParseException("offset needs 3 numbers");
            s.offset = new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
        }
        s.interpDelayMs = intIn(o, "interp_delay_ms", s.interpDelayMs, 0, 1000);
        s.collisionRadius = intIn(o, "collision_radius", s.collisionRadius, 1, 64);
        s.collisionIntervalMs = intIn(o, "collision_interval_ms", s.collisionIntervalMs, 50, 60000);
        s.collisionMoveBlocks = num(o, "collision_move_blocks", s.collisionMoveBlocks);
        s.guestEntity = intIn(o, "guest_entity", s.guestEntity, 0, Integer.MAX_VALUE);
        s.guestProvider = str(o, "guest_provider", s.guestProvider);
        s.guestAsset = str(o, "guest_asset", s.guestAsset);
        // CONTRACT 8: a hello carries 10 to 10000; a configuration outside it sends the nearest value inside it.
        s.heartbeatMs = Math.max(10, Math.min(10000, intIn(o, "heartbeat_ms", s.heartbeatMs, Integer.MIN_VALUE, Integer.MAX_VALUE)));
        s.timeoutMs = intIn(o, "timeout_ms", s.timeoutMs, 100, 600000);
        s.showFrames = bool(o, "show_frames", s.showFrames);
        return s;
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("port", port);
        o.addProperty("udp_port", udpPort);
        o.addProperty("flip_x", flipX);
        o.addProperty("flip_z", flipZ);
        o.addProperty("scale", scale);
        JsonArray off = new JsonArray();
        for (double d : offset) off.add(d);
        o.add("offset", off);
        o.addProperty("interp_delay_ms", interpDelayMs);
        o.addProperty("collision_radius", collisionRadius);
        o.addProperty("collision_interval_ms", collisionIntervalMs);
        o.addProperty("collision_move_blocks", collisionMoveBlocks);
        o.addProperty("guest_entity", guestEntity);
        o.addProperty("guest_provider", guestProvider);
        o.addProperty("guest_asset", guestAsset);
        o.addProperty("heartbeat_ms", heartbeatMs);
        o.addProperty("timeout_ms", timeoutMs);
        o.addProperty("show_frames", showFrames);
        return GSON.toJson(o) + "\n";
    }

    /** Reads the file, writing the defaults first when it does not exist. Falls back to defaults (and says why) when it is broken. */
    public static LinkSettings load(Path file, java.util.function.Consumer<String> warn) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, new LinkSettings().toJson(), StandardCharsets.UTF_8);
                return new LinkSettings();
            }
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            warn.accept("could not use " + file + ": " + e.getMessage() + "; using the defaults");
            return new LinkSettings();
        }
    }

    private static String str(JsonObject o, String k, String d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? d : e.getAsString();
    }

    private static boolean bool(JsonObject o, String k, boolean d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? d : e.getAsBoolean();
    }

    private static double num(JsonObject o, String k, double d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? d : e.getAsDouble();
    }

    private static int intIn(JsonObject o, String k, int d, int lo, int hi) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return d;
        int v = e.getAsInt();
        if (v < lo || v > hi) throw new JsonParseException(k + " must be " + lo + ".." + hi);
        return v;
    }
}
