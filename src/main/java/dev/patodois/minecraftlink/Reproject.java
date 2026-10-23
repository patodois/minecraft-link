package dev.patodois.minecraftlink;

/**
 * Reprojects a guest frame from the camera it was rendered with to the camera the host presents now
 * (CONTRACT.md 9.7), on the CPU. Every non-empty source pixel (alpha above 0, finite positive linear
 * depth) is unprojected with the render camera, projected with the present camera and written to the
 * destination pixels its warped square covers, keeping the smaller depth where two land on one. What
 * nothing lands on stays transparent with depth {@code +inf}. Meant for guest images of up to a few
 * hundred thousand pixels; it runs on a helper thread, never on the render thread.
 *
 * <p>Conventions: pixel (0,0) is the top-left corner, its centre at (x + .5, y + .5); NDC has +x right
 * and +y up, so {@code ndc_x = (x + .5) / w * 2 - 1} and {@code ndc_y = 1 - (y + .5) / h * 2}. The
 * depth plane is the linear view depth in metres (the distance along the camera's forward axis).
 */
public final class Reproject {
    private Reproject() {}

    /** A warped square wider than this many destination pixels is written to its centre pixel only. */
    private static final double MAX_FOOTPRINT = 4.0;

    /**
     * @param outRgba   {@code dw * dh * 4} bytes, overwritten (transparent where nothing lands)
     * @param outDepth  {@code dw * dh} floats, overwritten ({@code +inf} where nothing lands)
     * @return the number of source pixels that landed inside the destination
     */
    public static int run(byte[] rgba, float[] depth, int w, int h, float[] viewRender, float[] projRender, float[] viewPresent,
                          float[] projPresent, int dw, int dh, byte[] outRgba, float[] outDepth) {
        java.util.Arrays.fill(outRgba, (byte) 0);
        java.util.Arrays.fill(outDepth, Float.POSITIVE_INFINITY);
        float[] invRender = CameraMath.invert(viewRender);
        if (invRender == null) return 0;
        float[] m = CameraMath.mul(viewPresent, invRender); // render view space -> present view space
        double p0 = projRender[0], p5 = projRender[5], p8 = projRender[8], p9 = projRender[9];
        if (p0 == 0 || p5 == 0) return 0;
        int landed = 0;
        double[] out = new double[3];
        double[][] c = new double[4][3];
        boolean[] ok = new boolean[4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double t = depth[i];
                if ((rgba[i * 4 + 3] & 0xFF) == 0 || !(t > 0) || Double.isInfinite(t)) continue;
                if (!warp(x + 0.5, y + 0.5, t, w, h, p0, p5, p8, p9, m, projPresent, dw, dh, out)) continue;
                double cx = out[0], cy = out[1], depthOut = out[2];
                int x0 = (int) Math.floor(cx), x1 = x0, y0 = (int) Math.floor(cy), y1 = y0;
                // the pixel's square, warped at the pixel's own depth
                boolean all = true;
                double minx = 1e300, maxx = -1e300, miny = 1e300, maxy = -1e300;
                for (int k = 0; k < 4; k++) {
                    ok[k] = warp(x + (k & 1), y + (k >> 1), t, w, h, p0, p5, p8, p9, m, projPresent, dw, dh, c[k]);
                    if (!ok[k]) {
                        all = false;
                        break;
                    }
                    minx = Math.min(minx, c[k][0]);
                    maxx = Math.max(maxx, c[k][0]);
                    miny = Math.min(miny, c[k][1]);
                    maxy = Math.max(maxy, c[k][1]);
                }
                if (all && maxx - minx <= MAX_FOOTPRINT && maxy - miny <= MAX_FOOTPRINT) {
                    int fx0 = (int) Math.ceil(minx - 0.5), fx1 = (int) Math.floor(maxx - 0.5);
                    int fy0 = (int) Math.ceil(miny - 0.5), fy1 = (int) Math.floor(maxy - 0.5);
                    if (fx0 <= fx1 && fy0 <= fy1) {
                        x0 = fx0;
                        x1 = fx1;
                        y0 = fy0;
                        y1 = fy1;
                    }
                }
                boolean any = false;
                for (int yy = Math.max(y0, 0); yy <= Math.min(y1, dh - 1); yy++) {
                    for (int xx = Math.max(x0, 0); xx <= Math.min(x1, dw - 1); xx++) {
                        int d = yy * dw + xx;
                        if (depthOut < outDepth[d]) {
                            outDepth[d] = (float) depthOut;
                            System.arraycopy(rgba, i * 4, outRgba, d * 4, 4);
                            any = true;
                        }
                    }
                }
                if (any) landed++;
            }
        }
        return landed;
    }

    /** Source grid point (px, py) at linear depth t to destination window coordinates and present-camera depth. */
    private static boolean warp(double px, double py, double t, int w, int h, double p0, double p5, double p8, double p9, float[] m,
                                float[] projPresent, int dw, int dh, double[] out) {
        double ndcX = px / w * 2 - 1, ndcY = 1 - py / h * 2;
        // clip = proj * (xv, yv, -t, 1) has w_clip = t, so ndc * t = p0 * xv + p8 * (-t)  (same for y)
        double xv = t * (ndcX + p8) / p0, yv = t * (ndcY + p9) / p5;
        double[] v = CameraMath.transform(m, xv, yv, -t, 1);
        double tp = -v[2];
        if (!(tp > 0)) return false;
        double[] clip = CameraMath.transform(projPresent, v[0], v[1], v[2], 1);
        if (!(clip[3] > 0)) return false;
        double nx = clip[0] / clip[3], ny = clip[1] / clip[3];
        out[0] = (nx + 1) / 2 * dw;
        out[1] = (1 - ny) / 2 * dh;
        out[2] = tp;
        return Math.abs(out[0]) < 1e6 && Math.abs(out[1]) < 1e6;
    }
}
