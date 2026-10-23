package dev.patodois.minecraftlink;

import dev.patodois.rewritelink.Wire;

/**
 * The poses of one remote entity, resolved at a point in time. Poses carry the time they were
 * sampled ({@code time_us}, wall clock); the receiver shows the entity at {@code now - delay}, so
 * there are normally two samples around that moment and the result is an interpolation. If the
 * newest sample is older than that moment the entity is extrapolated with its velocity, for at most
 * {@link #MAX_EXTRAPOLATION_US}. A pose with the TELEPORT flag is never interpolated from the one
 * before it.
 */
public final class PoseTrack {
    public static final long MAX_EXTRAPOLATION_US = 250_000;

    private static final int CAP = 16;
    private final Wire.Pose[] ring = new Wire.Pose[CAP];
    private int count;
    private float[] lastGoodScale = {1, 1, 1};

    private static boolean finite(float[] v) {
        for (float f : v) if (!Float.isFinite(f)) return false;
        return true;
    }

    /**
     * Adds a pose, keeping time order; a pose older than the newest by more than the ring holds is ignored.
     * A guest is another program: a pose with a NaN or infinite position or rotation is dropped, and a NaN or
     * infinite scale is ignored (the entity keeps the last finite scale, 1 before any).
     */
    public synchronized void add(Wire.Pose p) {
        if (!finite(p.translation()) || !finite(p.rotation())) return;
        boolean scaleOk = finite(p.scale());
        if (scaleOk) lastGoodScale = p.scale().clone();
        boolean velOk = finite(p.velocity()), angOk = finite(p.angularVelocity());
        if (!scaleOk || !velOk || !angOk) {
            p = new Wire.Pose(p.entity(), p.flags(), p.timeUs(), p.translation(), p.rotation(), scaleOk ? p.scale() : lastGoodScale.clone(),
                    velOk ? p.velocity() : new float[3], angOk ? p.angularVelocity() : new float[3]);
        }
        // Insert in time order (poses are normally in order, the link already dropped stale ones).
        int pos = count;
        while (pos > 0 && Long.compareUnsigned(ring[pos - 1].timeUs(), p.timeUs()) > 0) pos--;
        if (count == CAP) {
            if (pos == 0) return;
            System.arraycopy(ring, 1, ring, 0, CAP - 1);
            pos--;
            count--;
        }
        System.arraycopy(ring, pos, ring, pos + 1, count - pos);
        ring[pos] = p;
        count++;
    }

    public synchronized boolean isEmpty() {
        return count == 0;
    }

    public synchronized Wire.Pose newest() {
        return count == 0 ? null : ring[count - 1];
    }

    public synchronized void clear() {
        count = 0;
    }

    /** The resolved state at {@code atUs}: translation, rotation (x, y, z, w) and scale. Null when empty. */
    public synchronized Sample at(long atUs) {
        if (count == 0) return null;
        Wire.Pose first = ring[0], last = ring[count - 1];
        if (Long.compareUnsigned(atUs, first.timeUs()) <= 0) return of(first, 0);
        if (Long.compareUnsigned(atUs, last.timeUs()) >= 0) {
            long dt = Math.min(atUs - last.timeUs(), MAX_EXTRAPOLATION_US);
            return of(last, dt * 1e-6);
        }
        for (int i = count - 1; i > 0; i--) {
            Wire.Pose a = ring[i - 1], b = ring[i];
            if (Long.compareUnsigned(atUs, a.timeUs()) >= 0) {
                if ((b.flags() & Wire.POSE_TELEPORT) != 0) return of(a, 0);
                double span = (double) (b.timeUs() - a.timeUs());
                double t = span <= 0 ? 1 : (atUs - a.timeUs()) / span;
                return lerp(a, b, t);
            }
        }
        return of(first, 0);
    }

    /** Resolved state of one entity. */
    public record Sample(double[] translation, float[] rotation, float[] scale) {}

    private static Sample of(Wire.Pose p, double extrapolateSeconds) {
        float[] t = p.translation(), v = p.velocity();
        double[] pos = {t[0] + v[0] * extrapolateSeconds, t[1] + v[1] * extrapolateSeconds, t[2] + v[2] * extrapolateSeconds};
        float[] rot = p.rotation().clone();
        if (extrapolateSeconds > 0) {
            float[] w = p.angularVelocity();
            double wl = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
            if (wl > 1e-6) {
                double angle = wl * extrapolateSeconds;
                double s = Math.sin(angle / 2) / wl;
                float[] dq = {(float) (w[0] * s), (float) (w[1] * s), (float) (w[2] * s), (float) Math.cos(angle / 2)};
                rot = mulQuat(dq, rot); // the angular velocity is in world space, so the delta goes on the left
            }
        }
        return new Sample(pos, rot, p.scale().clone());
    }

    private static Sample lerp(Wire.Pose a, Wire.Pose b, double t) {
        float[] ta = a.translation(), tb = b.translation();
        double[] pos = {ta[0] + (tb[0] - ta[0]) * t, ta[1] + (tb[1] - ta[1]) * t, ta[2] + (tb[2] - ta[2]) * t};
        float[] sa = a.scale(), sb = b.scale();
        float[] sc = {(float) (sa[0] + (sb[0] - sa[0]) * t), (float) (sa[1] + (sb[1] - sa[1]) * t), (float) (sa[2] + (sb[2] - sa[2]) * t)};
        return new Sample(pos, slerp(a.rotation(), b.rotation(), t), sc);
    }

    public static float[] mulQuat(float[] a, float[] b) {
        float x = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1];
        float y = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0];
        float z = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3];
        float w = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2];
        return normalize(new float[] {x, y, z, w});
    }

    public static float[] normalize(float[] q) {
        double n = Math.sqrt((double) q[0] * q[0] + (double) q[1] * q[1] + (double) q[2] * q[2] + (double) q[3] * q[3]);
        if (n < 1e-12) return new float[] {0, 0, 0, 1};
        return new float[] {(float) (q[0] / n), (float) (q[1] / n), (float) (q[2] / n), (float) (q[3] / n)};
    }

    public static float[] slerp(float[] a, float[] b, double t) {
        double dot = (double) a[0] * b[0] + (double) a[1] * b[1] + (double) a[2] * b[2] + (double) a[3] * b[3];
        float[] bb = b;
        if (dot < 0) {
            dot = -dot;
            bb = new float[] {-b[0], -b[1], -b[2], -b[3]};
        }
        double ka, kb;
        if (dot > 0.9995) {
            ka = 1 - t;
            kb = t;
        } else {
            double theta = Math.acos(dot), sin = Math.sin(theta);
            ka = Math.sin((1 - t) * theta) / sin;
            kb = Math.sin(t * theta) / sin;
        }
        return normalize(new float[] {(float) (ka * a[0] + kb * bb[0]), (float) (ka * a[1] + kb * bb[1]),
                (float) (ka * a[2] + kb * bb[2]), (float) (ka * a[3] + kb * bb[3])});
    }
}
