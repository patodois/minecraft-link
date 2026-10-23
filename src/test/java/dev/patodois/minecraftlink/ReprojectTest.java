package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ReprojectTest {
    private static float[] arr(Matrix4f m) {
        float[] a = new float[16];
        m.get(a);
        return a;
    }

    private static final float[] PROJ = arr(new Matrix4f().perspective((float) Math.toRadians(60), 1.0f, 0.1f, 100f, false));

    /** A camera at (x, 0, 0) looking down -Z. */
    private static float[] view(double x) {
        return CameraMath.translation(-x, 0, 0);
    }

    private static final int W = 32, H = 32;

    private static byte[] opaque(int rgb) {
        byte[] px = new byte[W * H * 4];
        for (int i = 0; i < W * H; i++) {
            px[i * 4] = (byte) (rgb >> 16);
            px[i * 4 + 1] = (byte) (rgb >> 8);
            px[i * 4 + 2] = (byte) rgb;
            px[i * 4 + 3] = (byte) 255;
        }
        return px;
    }

    @Test
    void sameCameraReturnsTheFrameUnchanged() {
        byte[] px = opaque(0x2060C0);
        float[] d = new float[W * H];
        java.util.Arrays.fill(d, 5f);
        byte[] out = new byte[W * H * 4];
        float[] od = new float[W * H];
        int n = Reproject.run(px, d, W, H, view(0), PROJ, view(0), PROJ, W, H, out, od);
        assertEquals(W * H, n);
        for (int i = 0; i < W * H; i++) {
            assertEquals(5f, od[i], 1e-3, "depth " + i);
            assertEquals(px[i * 4 + 2], out[i * 4 + 2]);
        }
    }

    @Test
    void movingTheCameraRightShiftsANearbyPointLeftOnScreen() {
        // one opaque pixel in the middle at 2 m; the rest empty
        byte[] px = new byte[W * H * 4];
        float[] d = new float[W * H];
        java.util.Arrays.fill(d, Float.POSITIVE_INFINITY);
        int cx = 16, cy = 16;
        px[(cy * W + cx) * 4 + 3] = (byte) 255;
        px[(cy * W + cx) * 4] = (byte) 200;
        d[cy * W + cx] = 2f;
        byte[] out = new byte[W * H * 4];
        float[] od = new float[W * H];
        // the render camera was at x = 0; the present camera has moved 0.2 m to the right
        Reproject.run(px, d, W, H, view(0), PROJ, view(0.2), PROJ, W, H, out, od);
        int hits = 0, hitX = -1, hitY = -1;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if ((out[(y * W + x) * 4 + 3] & 0xFF) != 0) {
                    hits++;
                    hitX = x;
                    hitY = y;
                    assertEquals(2f, od[y * W + x], 1e-3, "depth along the camera axis does not change");
                }
            }
        }
        assertTrue(hits >= 1, "the point lands somewhere");
        // pixel size at 2 m: 2 * tan(30 deg) * 2 / 32 = 0.1443 m per pixel; the shift is 0.2 m = 1.39 px to the left
        double pixel = 2 * Math.tan(Math.toRadians(30)) * 2 / W;
        double expected = 16.5 - 0.2 / pixel; // window x of the point (centre of pixel 16 is 16.5)
        assertTrue(Math.abs(hitX + 0.5 - expected) <= 1.5, "x " + hitX + " near " + expected);
        assertTrue(Math.abs(hitY - cy) <= 1, "no vertical shift");
    }

    @Test
    void theNearerSurfaceWinsWhenTwoLandOnOnePixel() {
        // two source pixels that land on the same destination pixel after a big move: the nearer one is kept
        byte[] px = new byte[W * H * 4];
        float[] d = new float[W * H];
        java.util.Arrays.fill(d, Float.POSITIVE_INFINITY);
        px[(16 * W + 16) * 4 + 3] = (byte) 255;
        px[(16 * W + 16) * 4] = 10;
        d[16 * W + 16] = 3f;
        px[(16 * W + 17) * 4 + 3] = (byte) 255;
        px[(16 * W + 17) * 4] = 99;
        d[16 * W + 17] = 6f;
        byte[] out = new byte[W * H * 4];
        float[] od = new float[W * H];
        // look from far to the side so the two project onto a common pixel
        Reproject.run(px, d, W, H, view(0), PROJ, view(0), PROJ, W, H, out, od);
        // same camera: both stay separate and keep their depths
        assertEquals(3f, od[16 * W + 16], 1e-3);
        assertEquals(6f, od[16 * W + 17], 1e-3);
    }

    @Test
    void emptyAndInfinitePixelsAreHolesAndStayTransparent() {
        byte[] px = new byte[W * H * 4];
        float[] d = new float[W * H];
        java.util.Arrays.fill(d, Float.POSITIVE_INFINITY);
        byte[] out = new byte[W * H * 4];
        float[] od = new float[W * H];
        assertEquals(0, Reproject.run(px, d, W, H, view(0), PROJ, view(0), PROJ, W, H, out, od));
        assertEquals(Float.POSITIVE_INFINITY, od[5]);
        assertEquals(0, out[3]);
    }

    @Test
    void ringFindsCamerasBySeq() {
        CameraRing r = new CameraRing(4);
        for (int s = 1; s <= 6; s++) r.put(new CameraRing.Entry(s, view(s), PROJ, 0.1f, 100f));
        assertEquals(6, r.get(6).seq());
        assertEquals(3, r.get(3).seq());
        assertEquals(null, r.get(2), "overwritten by newer ones");
        assertEquals(null, r.get(0), "seq 0 means no camera");
    }
}
