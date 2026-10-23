package dev.patodois.minecraftlink;

/**
 * Turns Minecraft's camera matrices into the numbers of a link {@code Camera} (CONTRACT.md 9.3).
 * Matrices are column-major {@code float[16]}, element {@code m[c*4 + r]}, as in JOML and glTF.
 *
 * <p>What Minecraft 26.3 actually does (read from its sources, nothing assumed):
 * <ul>
 *   <li>It renders camera-relative: the view matrix is the rotation matrix {@code R} (the conjugate of
 *       the camera quaternion) and the camera position {@code p} is subtracted from world positions
 *       separately. The full world-to-view matrix is therefore {@code R * translate(-p)}.</li>
 *   <li>The projection comes from JOML's {@code setPerspective(fovY, aspect, near', far', zZeroToOne)}
 *       called with near and far <b>swapped</b>, so depth is reversed (the near plane maps to the
 *       largest depth value). Whether the clip range is 0..1 or -1..1 depends on the GPU backend
 *       (Vulkan and OpenGL with ARB_clip_control use 0..1; OpenGL 4.1 as on macOS uses -1..1).</li>
 *   <li>Near is 0.05 and far is {@code max(4 * render distance * 16, cloud range * 16)} blocks.</li>
 *   <li>The level is drawn with {@code projection * bob}, where {@code bob} is the view-bobbing and
 *       damage-tilt transform. {@link #applyBob} folds it into the view matrix.</li>
 * </ul>
 * The contract's {@code depth_range} knows OpenGL (near -1, far +1), zero-to-one and reversed
 * zero-to-one. {@link #classify} measures which one a matrix is; the one combination the contract
 * has no code for (reversed, -1..1) is rewritten to plain OpenGL with the same frustum, see
 * {@link #normalize}.
 */
public final class CameraMath {
    private CameraMath() {}

    public static final int DEPTH_OPENGL = 0;
    public static final int DEPTH_ZERO_TO_ONE = 1;
    public static final int DEPTH_REVERSED = 2;
    /** Not representable in the contract: near maps to +1 and far to -1. */
    public static final int DEPTH_REVERSED_MINUS_ONE = -1;

    // ------------------------------------------------------------------ 4x4 helpers

    public static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    /** {@code a * b} (apply b first). */
    public static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += (double) a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = (float) s;
            }
        }
        return r;
    }

    public static float[] translation(double x, double y, double z) {
        float[] m = identity();
        m[12] = (float) x;
        m[13] = (float) y;
        m[14] = (float) z;
        return m;
    }

    /** General 4x4 inverse, or null when singular. */
    public static float[] invert(float[] m) {
        double[] a = new double[16];
        for (int i = 0; i < 16; i++) a[i] = m[i];
        double[] inv = new double[16];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        double det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (Math.abs(det) < 1e-30) return null;
        float[] r = new float[16];
        for (int i = 0; i < 16; i++) r[i] = (float) (inv[i] / det);
        return r;
    }

    /** Transforms the point (x, y, z, w) by m. */
    public static double[] transform(float[] m, double x, double y, double z, double w) {
        double[] r = new double[4];
        for (int row = 0; row < 4; row++) {
            r[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row] * w;
        }
        return r;
    }

    // ------------------------------------------------------------------ view

    /**
     * World-to-view in link space: {@code viewRotation * translate(-position) * mappingInverse}.
     * {@code position} is Minecraft's camera position and {@code viewRotation} Minecraft's rotation-only
     * view matrix; the {@link Mapping} says how Minecraft world coordinates turn into link
     * coordinates. With the default mapping the result is {@code R * translate(-p)}.
     */
    public static float[] view(double[] position, float[] viewRotation, Mapping mapping) {
        // link world w' = F*s*w + o   (F = diag(fx, 1, fz)), so w = F*(w' - o)/s.
        float[] mcView = mul(viewRotation, translation(-position[0], -position[1], -position[2]));
        double s = mapping.scale;
        double fx = mapping.flipX ? -1 : 1, fz = mapping.flipZ ? -1 : 1;
        float[] inv = identity();
        inv[0] = (float) (fx / s);
        inv[5] = (float) (1 / s);
        inv[10] = (float) (fz / s);
        inv[12] = (float) (-fx * mapping.offset[0] / s);
        inv[13] = (float) (-mapping.offset[1] / s);
        inv[14] = (float) (-fz * mapping.offset[2] / s);
        return mul(mcView, inv);
    }

    /** Where the camera of a world-to-view matrix is (the point it maps to the origin): {@code -R^T t}. */
    public static double[] cameraPosition(float[] view) {
        double t0 = view[12], t1 = view[13], t2 = view[14];
        double[] p = new double[3];
        for (int i = 0; i < 3; i++) p[i] = -(view[i * 4] * t0 + view[i * 4 + 1] * t1 + view[i * 4 + 2] * t2);
        return p;
    }

    /**
     * Folds the bobbing transform into the view. Minecraft draws the level with
     * {@code cleanProjection * bob}; the contract wants a separate view and projection, so with
     * {@code drawnProjection = cleanProjection * bob} this returns {@code bob * view}.
     * Returns {@code view} unchanged when either matrix is missing or the projection is singular.
     */
    public static float[] applyBob(float[] view, float[] cleanProjection, float[] drawnProjection) {
        if (cleanProjection == null || drawnProjection == null) return view;
        float[] inv = invert(cleanProjection);
        if (inv == null) return view;
        float[] bob = mul(inv, drawnProjection);
        for (float v : bob) if (!Float.isFinite(v)) return view;
        return mul(bob, view);
    }

    // ------------------------------------------------------------------ projection

    /** The clip depth (z / w) a point at {@code distance} metres in front of the camera gets. */
    public static double clipDepthAt(float[] proj, double distance) {
        double[] c = transform(proj, 0, 0, -distance, 1);
        return c[2] / c[3];
    }

    /**
     * Which {@code depth_range} the matrix has, measured on the near and far planes:
     * {@link #DEPTH_OPENGL}, {@link #DEPTH_ZERO_TO_ONE}, {@link #DEPTH_REVERSED},
     * {@link #DEPTH_REVERSED_MINUS_ONE}, or {@code Integer.MIN_VALUE} when it is none of them.
     */
    public static int classify(float[] proj, double near, double far) {
        double zn = clipDepthAt(proj, near), zf = clipDepthAt(proj, far);
        double eps = 2e-3;
        if (close(zn, -1, eps) && close(zf, 1, eps)) return DEPTH_OPENGL;
        if (close(zn, 0, eps) && close(zf, 1, eps)) return DEPTH_ZERO_TO_ONE;
        if (close(zn, 1, eps) && close(zf, 0, eps)) return DEPTH_REVERSED;
        if (close(zn, 1, eps) && close(zf, -1, eps)) return DEPTH_REVERSED_MINUS_ONE;
        return Integer.MIN_VALUE;
    }

    private static boolean close(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    /**
     * The projection to put on the wire and its {@code depth_range}. Matrices that already use a
     * contract convention are sent as they are; a reversed matrix with a -1..1 range is replaced
     * by an OpenGL matrix with the same x and y scales and the same near and far. A matrix that
     * matches nothing (an unexpected renderer) is also replaced, so the numbers always mean what
     * the contract says.
     */
    public static Projection normalize(float[] proj, double near, double far) {
        int kind = classify(proj, near, far);
        if (kind == DEPTH_OPENGL || kind == DEPTH_ZERO_TO_ONE || kind == DEPTH_REVERSED) {
            return new Projection(proj.clone(), kind, false);
        }
        float[] p = proj.clone();
        // Standard OpenGL perspective depth rows (see the contract's Camera helpers).
        p[2] = 0;
        p[6] = 0;
        p[10] = (float) ((far + near) / (near - far));
        p[14] = (float) (2 * far * near / (near - far));
        p[3] = 0;
        p[7] = 0;
        p[11] = -1;
        p[15] = 0;
        return new Projection(p, DEPTH_OPENGL, true);
    }

    /** A projection to send, its contract {@code depth_range} code and whether it was rewritten. */
    public static final class Projection {
        private final float[] matrix;
        private final int depthRange;
        private final boolean rewritten;

        public Projection(float[] matrix, int depthRange, boolean rewritten) {
            this.matrix = matrix;
            this.depthRange = depthRange;
            this.rewritten = rewritten;
        }

        public float[] matrix() {
            return matrix;
        }

        public int depthRange() {
            return depthRange;
        }

        public boolean rewritten() {
            return rewritten;
        }
    }

    /**
     * Near and far distance of a standard OpenGL perspective matrix ({@code gluPerspective}: {@code m[10] = -(f + n) / (f - n)},
     * {@code m[14] = -2 f n / (f - n)}), as {@code {near, far}}; {@code {NaN, NaN}} when the matrix is not one.
     */
    public static double[] glNearFar(float[] proj) {
        double a = proj[10], b = proj[14];
        double near = b / (a - 1.0), far = b / (a + 1.0);
        if (!(near > 0) || !(far > near) || Double.isInfinite(far) || proj[11] != -1f) return new double[] {Double.NaN, Double.NaN};
        return new double[] {near, far};
    }

    /** Vertical field of view in radians from the y scale {@code proj[5]} of a perspective matrix. */
    public static float fovY(float[] proj) {
        return (float) (2 * Math.atan(1.0 / Math.abs(proj[5])));
    }

    // ------------------------------------------------------------------ depth to metres

    /**
     * Linear distance in metres (along the view axis) for a hardware depth value in 0..1 as stored
     * in the depth buffer, for the three conventions Minecraft can have (CONTRACT depth_range).
     * Used by the frame composition shader on the CPU side for tests and documentation; the shader
     * evaluates the same formula.
     */
    public static double linearDepth(double d, double near, double far, int depthRange) {
        switch (depthRange) {
            case DEPTH_OPENGL: {
                double ndc = d * 2 - 1;
                return 2 * far * near / (far + near - ndc * (far - near));
            }
            case DEPTH_ZERO_TO_ONE:
                return far * near / (far - d * (far - near));
            case DEPTH_REVERSED:
            case DEPTH_REVERSED_MINUS_ONE: // d is the window depth (ndc + 1) / 2; the formula is the same
                // d = near*(far - z) / (z*(far - near))  =>  z = far*near / (near + d*(far - near))
                return far * near / (near + d * (far - near));
            default:
                throw new IllegalArgumentException("depth_range " + depthRange);
        }
    }
}
