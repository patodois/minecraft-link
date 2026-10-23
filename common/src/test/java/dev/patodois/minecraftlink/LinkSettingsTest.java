package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

class LinkSettingsTest {
    @Test
    void defaultsRoundTripThroughJson() {
        LinkSettings d = new LinkSettings();
        LinkSettings back = LinkSettings.parse(d.toJson());
        assertEquals("host", back.role);
        assertEquals(47800, back.port);
        assertFalse(back.flipX || back.flipZ);
        assertEquals(1.0, back.scale);
        assertArrayEquals(new double[] {0, 0, 0}, back.offset);
        assertEquals(16, back.collisionRadius);
        assertTrue(back.showFrames);
    }

    @Test
    void readsOverridesAndIgnoresUnknownKeys() {
        LinkSettings s = LinkSettings.parse("{\"role\":\"Guest\",\"port\":47900,\"flip_z\":true,\"scale\":0.5,\"offset\":[1,2,3],\"future\":7}");
        assertEquals("guest", s.role);
        assertEquals(47900, s.port);
        assertTrue(s.flipZ);
        assertEquals(0.5, s.mapping().scale);
        assertArrayEquals(new double[] {1, 2, 3}, s.offset);
    }

    @Test
    void rejectsNonsense() {
        assertThrows(JsonParseException.class, () -> LinkSettings.parse("{\"role\":\"spectator\"}"));
        assertThrows(JsonParseException.class, () -> LinkSettings.parse("{\"port\":0}"));
        assertThrows(JsonParseException.class, () -> LinkSettings.parse("{\"scale\":-1}"));
        assertThrows(JsonParseException.class, () -> LinkSettings.parse("{\"offset\":[1,2]}"));
    }
}
