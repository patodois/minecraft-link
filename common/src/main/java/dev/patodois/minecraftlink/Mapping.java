package dev.patodois.minecraftlink;

import dev.patodois.rewritelink.Quat;

/**
 * Minecraft space to link space and back (CONTRACT.md section 4).
 *
 * <p>Minecraft's world is already right-handed with +Y up and 1 block = 1 m (+X east, +Y up,
 * +Z south, and (east, up, south) is a right-handed triple), so with the default settings a
 * position maps to itself. What differs is how Minecraft names directions:
 *
 * <ul>
 *   <li>{@code yaw} is in degrees, 0 faces <b>+Z</b> (south), and it grows clockwise seen from above:
 *       90 faces -X (west), 180 faces -Z (north), -90 faces +X (east).</li>
 *   <li>{@code pitch} is in degrees, 0 is level, +90 looks straight <b>down</b>, -90 straight up.</li>
 *   <li>The view direction is {@code (-sin(yaw) cos(pitch), -sin(pitch), cos(yaw) cos(pitch))}.</li>
 * </ul>
 *
 * The link's entity front is +Z, so an entity with yaw 0 and pitch 0 has the identity rotation, and
 * in general {@code rotation = Ry(-yaw) * Rx(pitch)} (right-handed rotations about +Y and +X). The
 * camera is the same rotation turned half a turn about Y, because a link camera looks down its own
 * -Z, while Minecraft's camera quaternion looks down +Z; cameras are built separately from the
 * camera's yaw and pitch the way {@code GameRenderer} builds its view (see {@link CameraMath#viewRotation}).
 *
 * <p>Optional settings (all identity by default) for diagnosing a host or guest whose axes turn out
 * different, mirrored or scaled: {@code flipX}, {@code flipZ} (mirror the world; this reverses
 * handedness, so triangle winding is fixed up), {@code scale} (link metres per block) and
 * {@code offset} (link position of the Minecraft origin, after flipping and scaling).
 */
public final class Mapping {
    /** Ticks per second: Minecraft expresses velocities in blocks per tick, the link in m/s. */
    public static final double TICKS_PER_SECOND = 20.0;

    public final boolean flipX;
    public final boolean flipZ;
    public final double scale;
    public final double[] offset;

    public static final Mapping IDENTITY = new Mapping(false, false, 1.0, new double[] {0, 0, 0});

    public Mapping(boolean flipX, boolean flipZ, double scale, double[] offset) {
        this.flipX = flipX;
        this.flipZ = flipZ;
        this.scale = scale;
        this.offset = offset.clone();
    }

    private double fx() {
        return flipX ? -1 : 1;
    }

    private double fz() {
        return flipZ ? -1 : 1;
    }

    /** True when the settings mirror the world (an odd number of flips), which reverses winding. */
    public boolean mirrors() {
        return flipX ^ flipZ;
    }

    // ------------------------------------------------------------------ positions, vectors

    public double[] toLinkPos(double x, double y, double z) {
        return new double[] {fx() * x * scale + offset[0], y * scale + offset[1], fz() * z * scale + offset[2]};
    }

    public double[] fromLinkPos(double x, double y, double z) {
        return new double[] {(x - offset[0]) / scale * fx(), (y - offset[1]) / scale, (z - offset[2]) / scale * fz()};
    }

    /** A direction (no scale, no offset). */
    public double[] toLinkDir(double x, double y, double z) {
        return new double[] {fx() * x, y, fz() * z};
    }

    public double[] fromLinkDir(double x, double y, double z) {
        return new double[] {fx() * x, y, fz() * z};
    }

    /** Blocks per tick to metres per second. */
    public double[] toLinkVelocityPerTick(double dx, double dy, double dz) {
        double k = TICKS_PER_SECOND * scale;
        return new double[] {fx() * dx * k, dy * k, fz() * dz * k};
    }

    // ------------------------------------------------------------------ rotations

    /** Minecraft's view direction for a yaw and pitch in degrees. */
    public static double[] lookDirection(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new double[] {-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp};
    }

    /** The body's up axis after yaw and pitch: world up pitched about the local X axis, then yawed. */
    public static double[] upVector(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        // Rx(pitch) * (0,1,0) = (0, cos p, sin p); then Ry(-yaw) turns (x, z) by -yaw.
        double ux = 0, uy = Math.cos(pitch), uz = Math.sin(pitch);
        double s = Math.sin(-yaw), c = Math.cos(-yaw);
        return new double[] {c * ux + s * uz, uy, -s * ux + c * uz};
    }

    /**
     * The link rotation (quaternion x, y, z, w) of an entity that looks along Minecraft's
     * {@code yaw}/{@code pitch}: front (+Z in link space) along the view direction, up tilted with
     * the pitch. Includes the flips.
     */
    public float[] toLinkRotation(double yawDeg, double pitchDeg) {
        double[] f = lookDirection(yawDeg, pitchDeg);
        double[] u = upVector(yawDeg, pitchDeg);
        return basisToQuat(toLinkDir(f[0], f[1], f[2]), toLinkDir(u[0], u[1], u[2]));
    }

    /** Inverse of {@link #toLinkRotation} for the heading: {@code {yaw, pitch}} in degrees. */
    public double[] fromLinkRotation(float[] q) {
        float[] fl = Quat.rotate(q, new float[] {0, 0, 1});
        double[] f = fromLinkDir(fl[0], fl[1], fl[2]);
        double len = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
        if (len < 1e-9) return new double[] {0, 0};
        double dx = f[0] / len, dy = f[1] / len, dz = f[2] / len;
        double pitch = -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dy))));
        double yaw = (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) ? 0 : Math.toDegrees(Math.atan2(-dx, dz));
        return new double[] {yaw, pitch};
    }

    /** Yaw rate (degrees per tick) to world angular velocity about +Y, rad/s (yaw turns the entity by -yaw). */
    public static double yawRateToAngularY(double degreesPerTick) {
        return -Math.toRadians(degreesPerTick) * TICKS_PER_SECOND;
    }

    /** A rotation with columns (up x front, up, front), as a unit quaternion. */
    static float[] basisToQuat(double[] front, double[] up) {
        double[] f = norm(front);
        double[] x = norm(cross(up, f));
        double[] y = cross(f, x);
        // Rotation matrix m[row][col] with columns x, y, f.
        double m00 = x[0], m01 = y[0], m02 = f[0];
        double m10 = x[1], m11 = y[1], m12 = f[1];
        double m20 = x[2], m21 = y[2], m22 = f[2];
        double tr = m00 + m11 + m22;
        double qx, qy, qz, qw;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1.0) * 2;
            qw = 0.25 * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            qw = (m21 - m12) / s;
            qx = 0.25 * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25 * s;
            qz = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25 * s;
        }
        double n = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        return new float[] {(float) (qx / n), (float) (qy / n), (float) (qz / n), (float) (qw / n)};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] norm(double[] a) {
        double n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return n < 1e-12 ? new double[] {0, 0, 1} : new double[] {a[0] / n, a[1] / n, a[2] / n};
    }
}
