package dev.patodois.minecraftlink;

import dev.patodois.rewritelink.Wire;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The size limit of a link {@code Collision} snapshot (CONTRACT.md 9.5, revision 5): at most
 * {@link Wire#MAX_COLLISION_CHUNKS} (4096) chunks of {@link Wire#MAX_TRIANGLES} (226) triangles, that is
 * {@link #MAX_TRIANGLES} = 925,696 triangles. link-java throws a {@code WireException} for a bigger snapshot, and the
 * session code reads that exception as "not connected", so a snapshot over the limit must never reach the library:
 * this class cuts it down first, the way the contract asks a sender to ("it keeps the triangles nearest to
 * {@code center} and sends those"), and says what it did so the mod can tell the player.
 *
 * <p>Nearest means the distance from {@code center} to the closest point of the triangle. Ties are broken by the
 * triangles' order in the input, which the result keeps, so the same input always gives the same snapshot. The
 * radius that the snapshot announces shrinks to the distance of the nearest triangle that was dropped: inside that
 * sphere the snapshot is complete, and nothing is claimed beyond it (a guest would otherwise treat the dropped area as
 * empty air and fall through the floor).
 *
 * <p>Plain Java 8, no Minecraft classes.
 */
public final class CollisionCap {
    private CollisionCap() {}

    /** 4096 chunks * 226 triangles = 925,696: the most triangles of one snapshot. */
    public static final int MAX_TRIANGLES = Wire.MAX_COLLISION_CHUNKS * Wire.MAX_TRIANGLES;

    /** What {@link #limit} made of a snapshot. */
    public static final class Result {
        /** The triangles to send: the input list itself when nothing was dropped, else a new list in the input's order. */
        public final List<float[]> triangles;
        /** How many triangles there were before the cut. */
        public final int offered;
        /** How many were dropped; 0 when the snapshot fit. */
        public final int dropped;
        /** The radius to announce: the one asked for, or less when triangles were dropped (the snapshot is complete within it). */
        public final float radius;

        Result(List<float[]> triangles, int offered, int dropped, float radius) {
            this.triangles = triangles;
            this.offered = offered;
            this.dropped = dropped;
            this.radius = radius;
        }

        public boolean trimmed() {
            return dropped > 0;
        }
    }

    /** {@link #limit(List, float[], float, int)} with the contract's {@link #MAX_TRIANGLES}. */
    public static Result limit(List<float[]> tris, float[] center, float radius) {
        return limit(tris, center, radius, MAX_TRIANGLES);
    }

    /**
     * Keeps the {@code max} triangles nearest to {@code center}. A list of at most {@code max} triangles is returned as it is.
     *
     * @param tris   triangles as {@code float[9]} (a, b, c as x, y, z), in link space, like {@code center}
     * @param radius the radius the snapshot would announce when nothing is dropped
     */
    public static Result limit(List<float[]> tris, float[] center, float radius, int max) {
        if (max < 0) throw new IllegalArgumentException("max " + max);
        int n = tris.size();
        if (n <= max) return new Result(tris, n, 0, radius);

        // Squared distance of every triangle to the centre.
        float[] key = new float[n];
        for (int i = 0; i < n; i++) key[i] = distSq(tris.get(i), center);

        // The max-th smallest key is the cut: everything below it stays, and so do as many of the ones equal to it as fit.
        float cut = Float.POSITIVE_INFINITY;
        int below = 0;
        if (max > 0) {
            float[] sorted = key.clone();
            Arrays.sort(sorted);
            cut = sorted[max - 1];
            for (float k : key) if (k < cut) below++;
        }
        int ties = max - below;

        List<float[]> out = new ArrayList<float[]>(max);
        float nearestDropped = Float.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            float k = key[i];
            if (max > 0 && (k < cut || (k == cut && ties > 0))) {
                if (k == cut) ties--;
                out.add(tris.get(i));
            } else if (k < nearestDropped) {
                nearestDropped = k;
            }
        }
        float covered = (float) Math.sqrt(nearestDropped);
        // sqrt rounds to nearest: step down so the announced sphere never reaches the nearest dropped triangle.
        if (covered > 0) covered = Math.nextDown(covered);
        return new Result(out, n, n - out.size(), Math.min(radius, covered));
    }

    // ------------------------------------------------------------------ distance

    /** Squared distance from point {@code p} to the closest point of triangle {@code t} ({@code float[9]}); NaN counts as far. */
    static float distSq(float[] t, float[] p) {
        double ax = t[0], ay = t[1], az = t[2], bx = t[3], by = t[4], bz = t[5], cx = t[6], cy = t[7], cz = t[8];
        double px = p[0], py = p[1], pz = p[2];
        double best = Math.min(segSq(ax, ay, az, bx, by, bz, px, py, pz),
                Math.min(segSq(bx, by, bz, cx, cy, cz, px, py, pz), segSq(cx, cy, cz, ax, ay, az, px, py, pz)));
        // The closest point may lie inside the face: then it is the plane distance, if p projects into the triangle.
        double ux = bx - ax, uy = by - ay, uz = bz - az, vx = cx - ax, vy = cy - ay, vz = cz - az;
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double nn = nx * nx + ny * ny + nz * nz;
        if (nn > 1e-24) {
            double wx = px - ax, wy = py - ay, wz = pz - az;
            boolean inside = edgeSide(ux, uy, uz, wx, wy, wz, nx, ny, nz)
                    && edgeSide(cx - bx, cy - by, cz - bz, px - bx, py - by, pz - bz, nx, ny, nz)
                    && edgeSide(ax - cx, ay - cy, az - cz, px - cx, py - cy, pz - cz, nx, ny, nz);
            if (inside) {
                double d = wx * nx + wy * ny + wz * nz;
                best = Math.min(best, d * d / nn);
            }
        }
        return Double.isNaN(best) ? Float.POSITIVE_INFINITY : (float) best;
    }

    /** Whether {@code w} is on the inner side of the edge {@code e} of a triangle with normal {@code n}. */
    private static boolean edgeSide(double ex, double ey, double ez, double wx, double wy, double wz, double nx, double ny, double nz) {
        double cx = ey * wz - ez * wy, cy = ez * wx - ex * wz, cz = ex * wy - ey * wx;
        return cx * nx + cy * ny + cz * nz >= 0;
    }

    private static double segSq(double ax, double ay, double az, double bx, double by, double bz, double px, double py, double pz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len2 = dx * dx + dy * dy + dz * dz;
        double s = len2 > 0 ? ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / len2 : 0;
        s = Math.max(0, Math.min(1, s));
        double qx = ax + s * dx - px, qy = ay + s * dy - py, qz = az + s * dz - pz;
        return qx * qx + qy * qy + qz * qz;
    }
}
