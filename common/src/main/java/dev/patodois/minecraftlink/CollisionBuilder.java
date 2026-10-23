package dev.patodois.minecraftlink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the collision shapes of the blocks around a centre into triangles for a link
 * {@code Collision} snapshot (CONTRACT.md 9.5): counter-clockwise seen from outside, in Minecraft
 * world coordinates (use {@link #map} to move them to link space).
 *
 * <p>The builder is filled one horizontal layer at a time (so the game thread can spread a scan over
 * several ticks), then {@link #triangles()} may run on any thread. A face is left out when it lies on the block
 * boundary and a full 1x1x1 block sits right behind it, and where two boxes of the same block touch back to back (the inside of a
 * stair) both faces are cut away, so a solid ground gives only its top faces.
 *
 * <p>This class knows nothing about Minecraft; the client code feeds it boxes.
 */
public final class CollisionBuilder {
    /** The collision boxes of one block, each {@code {minX, minY, minZ, maxX, maxY, maxZ}} in block-local 0..1 units. */
    public interface Shapes {
        double[][] boxesAt(int x, int y, int z);
    }

    private static final byte EMPTY = 0, PARTIAL = 1, FULL = 2;

    private final int cx, cy, cz, radius, size;
    private final byte[] kind;
    private final Map<Integer, double[][]> partial = new HashMap<>();

    public CollisionBuilder(int centerX, int centerY, int centerZ, int radius) {
        this.cx = centerX;
        this.cy = centerY;
        this.cz = centerZ;
        this.radius = radius;
        this.size = 2 * radius + 1;
        this.kind = new byte[size * size * size];
    }

    public int layers() {
        return size;
    }

    private int idx(int lx, int ly, int lz) {
        return (ly * size + lz) * size + lx;
    }

    /** Reads layer {@code layer} (0 = lowest y, {@code layers() - 1} = highest) from the world. */
    public void scanLayer(Shapes shapes, int layer) {
        int y = cy - radius + layer;
        double r2 = (radius + 0.87) * (radius + 0.87);
        for (int lz = 0; lz < size; lz++) {
            for (int lx = 0; lx < size; lx++) {
                int x = cx - radius + lx, z = cz - radius + lz;
                double dx = x + 0.5 - (cx + 0.5), dy = y + 0.5 - (cy + 0.5), dz = z + 0.5 - (cz + 0.5);
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                double[][] boxes = shapes.boxesAt(x, y, z);
                if (boxes == null || boxes.length == 0) continue;
                int i = idx(lx, layer, lz);
                if (isUnitCube(boxes)) {
                    kind[i] = FULL;
                } else {
                    kind[i] = PARTIAL;
                    partial.put(i, boxes);
                }
            }
        }
    }

    private static boolean isUnitCube(double[][] boxes) {
        if (boxes.length != 1) return false;
        double[] b = boxes[0];
        return b[0] <= 1e-6 && b[1] <= 1e-6 && b[2] <= 1e-6 && b[3] >= 1 - 1e-6 && b[4] >= 1 - 1e-6 && b[5] >= 1 - 1e-6;
    }

    private boolean fullAt(int lx, int ly, int lz) {
        if (lx < 0 || ly < 0 || lz < 0 || lx >= size || ly >= size || lz >= size) return false;
        return kind[idx(lx, ly, lz)] == FULL;
    }

    // ------------------------------------------------------------------ triangulation

    /** One axis-aligned face: {@code axis} 0/1/2 = x/y/z, {@code sign} +1/-1, plane coordinate, the two other axes' ranges. */
    private record Face(int axis, int sign, double plane, double u0, double u1, double v0, double v1) {}

    private static boolean eq(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    /** The triangles, each {@code float[9]} (a, b, c as x, y, z) in world coordinates, CCW seen from outside. */
    public List<float[]> triangles() {
        List<float[]> out = new ArrayList<>();
        for (int ly = 0; ly < size; ly++) {
            for (int lz = 0; lz < size; lz++) {
                for (int lx = 0; lx < size; lx++) {
                    int i = idx(lx, ly, lz);
                    byte k = kind[i];
                    if (k == EMPTY) continue;
                    double[][] boxes = k == FULL ? new double[][] {{0, 0, 0, 1, 1, 1}} : partial.get(i);
                    int wx = cx - radius + lx, wy = cy - radius + ly, wz = cz - radius + lz;
                    emitBlock(out, boxes, wx, wy, wz, lx, ly, lz);
                }
            }
        }
        return out;
    }

    private void emitBlock(List<float[]> out, double[][] boxes, int wx, int wy, int wz, int lx, int ly, int lz) {
        List<Face> faces = new ArrayList<>(boxes.length * 6);
        for (double[] b : boxes) {
            for (int axis = 0; axis < 3; axis++) {
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                for (int sign = -1; sign <= 1; sign += 2) {
                    double plane = sign > 0 ? b[3 + axis] : b[axis];
                    // Skip a face that lies on the block boundary with a full block right behind it.
                    boolean onBoundary = sign > 0 ? eq(plane, 1) : eq(plane, 0);
                    if (onBoundary) {
                        int nx = lx + (axis == 0 ? sign : 0), ny = ly + (axis == 1 ? sign : 0), nz = lz + (axis == 2 ? sign : 0);
                        if (fullAt(nx, ny, nz)) continue;
                    }
                    faces.add(new Face(axis, sign, plane, b[u], b[3 + u], b[v], b[3 + v]));
                }
            }
        }
        // Faces of two boxes of the same block that touch back to back (the inside of a stair) are
        // inside the solid: cut the overlap out of both.
        clipInternal(faces);
        for (Face f : faces) quad(out, f, wx, wy, wz);
    }

    /** Removes, from every pair of coplanar opposite faces, the part where they overlap. */
    private static void clipInternal(List<Face> faces) {
        boolean again = true;
        int guard = 0;
        while (again && guard++ < 16) {
            again = false;
            outer:
            for (int a = 0; a < faces.size(); a++) {
                for (int c = a + 1; c < faces.size(); c++) {
                    Face fa = faces.get(a), fc = faces.get(c);
                    if (fa.axis != fc.axis || fa.sign != -fc.sign || !eq(fa.plane, fc.plane)) continue;
                    double u0 = Math.max(fa.u0, fc.u0), u1 = Math.min(fa.u1, fc.u1);
                    double v0 = Math.max(fa.v0, fc.v0), v1 = Math.min(fa.v1, fc.v1);
                    if (u1 - u0 < 1e-6 || v1 - v0 < 1e-6) continue;
                    faces.remove(c);
                    faces.remove(a);
                    subtract(faces, fa, u0, u1, v0, v1);
                    subtract(faces, fc, u0, u1, v0, v1);
                    again = true;
                    break outer;
                }
            }
        }
    }

    /** Adds the pieces of {@code f} that are left after removing the rectangle (u0..u1, v0..v1) inside it. */
    private static void subtract(List<Face> out, Face f, double u0, double u1, double v0, double v1) {
        if (u0 - f.u0 > 1e-6) out.add(new Face(f.axis, f.sign, f.plane, f.u0, u0, f.v0, f.v1));
        if (f.u1 - u1 > 1e-6) out.add(new Face(f.axis, f.sign, f.plane, u1, f.u1, f.v0, f.v1));
        if (v0 - f.v0 > 1e-6) out.add(new Face(f.axis, f.sign, f.plane, u0, u1, f.v0, v0));
        if (f.v1 - v1 > 1e-6) out.add(new Face(f.axis, f.sign, f.plane, u0, u1, v1, f.v1));
    }

    /** Two triangles for a face; (axis, u, v) is a cyclic permutation of (x, y, z) so u x v points along +axis. */
    private static void quad(List<float[]> out, Face f, int wx, int wy, int wz) {
        int a = f.axis, u = (a + 1) % 3, v = (a + 2) % 3;
        double[] o = {wx, wy, wz};
        float[][] p = new float[4][3];
        double[][] uv = {{f.u0, f.v0}, {f.u1, f.v0}, {f.u1, f.v1}, {f.u0, f.v1}};
        for (int i = 0; i < 4; i++) {
            p[i][a] = (float) (o[a] + f.plane);
            p[i][u] = (float) (o[u] + uv[i][0]);
            p[i][v] = (float) (o[v] + uv[i][1]);
        }
        if (f.sign > 0) {
            out.add(tri(p[0], p[1], p[2]));
            out.add(tri(p[0], p[2], p[3]));
        } else {
            out.add(tri(p[0], p[2], p[1]));
            out.add(tri(p[0], p[3], p[2]));
        }
    }

    private static float[] tri(float[] a, float[] b, float[] c) {
        return new float[] {a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]};
    }

    // ------------------------------------------------------------------ mapping and checks

    /** Moves triangles from Minecraft world space to link space; a mirroring map swaps two vertices to keep CCW. */
    public static List<float[]> map(List<float[]> tris, Mapping m) {
        if (m == Mapping.IDENTITY) return tris;
        List<float[]> out = new ArrayList<>(tris.size());
        for (float[] t : tris) {
            float[] r = new float[9];
            for (int v = 0; v < 3; v++) {
                double[] p = m.toLinkPos(t[v * 3], t[v * 3 + 1], t[v * 3 + 2]);
                r[v * 3] = (float) p[0];
                r[v * 3 + 1] = (float) p[1];
                r[v * 3 + 2] = (float) p[2];
            }
            if (m.mirrors()) {
                for (int k = 0; k < 3; k++) {
                    float tmp = r[3 + k];
                    r[3 + k] = r[6 + k];
                    r[6 + k] = tmp;
                }
            }
            out.add(r);
        }
        return out;
    }

    /** A cheap content hash, to skip sending an unchanged snapshot. */
    public static long hash(List<float[]> tris) {
        long h = 1125899906842597L;
        for (float[] t : tris) {
            for (float f : t) h = 31 * h + Float.floatToIntBits(f);
        }
        return h * 31 + tris.size();
    }

    /** The outward normal of a CCW triangle, not normalised. */
    public static double[] normal(float[] t) {
        double[] a = {t[3] - t[0], t[4] - t[1], t[5] - t[2]};
        double[] b = {t[6] - t[0], t[7] - t[1], t[8] - t[2]};
        return Mapping.cross(a, b);
    }
}
