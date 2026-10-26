package dev.patodois.minecraftlink.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.patodois.minecraftlink.CameraRing;
import dev.patodois.minecraftlink.Reproject;
import dev.patodois.rewritelink.LinkEvent;
import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Host side of the pixel channel: shows a guest's rendered frames inside the world.
 *
 * <p>Three stages, on three threads:
 * <ol>
 *   <li><b>link thread</b>: the {@code Frame} datagram arrives ({@code Session.latestFrame});</li>
 *   <li><b>frame worker</b> (own thread): maps the announced pixel file read-only
 *       ({@link PixelFile.Reader}, seqlock), copies the slot (colour and, if the file has it, the
 *       linear depth plane), looks up the camera the guest rendered with in the ring of sent cameras
 *       (CONTRACT.md 9.7) and reprojects the frame to the camera presented now
 *       ({@link Reproject}); with no usable camera, or no depth, it is used as it is;</li>
 *   <li><b>render thread</b>: after the world has been drawn, uploads the newest finished result to two textures (RGBA8
 *       colour, R32F depth) and runs one fullscreen pass into the main render target. The pass reads the main target's own
 *       depth texture (Minecraft 1.20.1 keeps its depth in a texture, so nothing is copied), converts it to linear metres and
 *       keeps the guest's pixel only where it is nearer, alpha-blended over the world. A guest without depth is drawn as a
 *       plain overlay.</li>
 * </ol>
 * The render thread never waits for the worker: it uploads the newest finished result, if any.
 *
 * <p>GL: Minecraft 1.20.1 renders with OpenGL 3.2 core on macOS (4.1 is what the driver reports for it), through its own
 * {@code RenderSystem} and {@code GlStateManager}, which cache part of the state. The pass uses its own GLSL 150 program and an
 * empty vertex array (the vertex shader makes a fullscreen triangle from {@code gl_VertexID}). Everything it changes (framebuffer,
 * viewport, program, vertex array, texture units 0 to 2, blend, depth, cull, scissor, colour mask, unpack alignment) is read
 * back first and put back afterwards through {@code GlStateManager}, so the game's cache and the real state still agree.
 */
final class FrameOverlay {
    /** A finished frame in the present camera's view: same size as the guest image. */
    private record Composed(long version, int width, int height, byte[] rgba, float[] depth, boolean reprojected, long guestTimeUs) {}

    // ---- shared
    private final AtomicReference<Composed> composed = new AtomicReference<>();
    private final AtomicReference<Wire.Camera> present = new AtomicReference<>();
    private volatile Thread worker;
    private volatile boolean stop;
    volatile long framesShown;
    volatile long framesReprojected;
    volatile String note = "";
    /** GL_VERSION, GLSL version and the like, logged once and shown by /link status. */
    volatile String glInfo = "";

    /**
     * The most pixels per frame this mod takes. CONTRACT 9.6 lets a file be 2^26 pixels (8192 x 8192) and a receiver refuse
     * larger ones of its own accord: the reprojection runs on the CPU and keeps several copies of the frame, so a 4 Mpixel
     * frame (2560 x 1600) already costs about a hundred megabytes. A bigger file is refused with a note.
     */
    static final long MAX_PIXELS = 4L << 20;

    // ---- render thread only
    private int program, vao, colorTex, depthTex;
    private int uHostDepth, uGuestColor, uGuestDepth, uDepthParams, uFlags;
    private int texW, texH;
    private long uploadedVersion;
    private boolean hasDepthUploaded;
    private boolean broken;
    private ByteBuffer rgbaBuf, depthBuf;

    void offerPresentCamera(Wire.Camera c) {
        present.set(c);
    }

    /** Starts the worker for a session. */
    void start(Session s) {
        close();
        stop = false;
        framesShown = 0;
        framesReprojected = 0;
        Thread t = new Thread(() -> workerLoop(s), "minecraft-link-frames");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Stops the worker (the render-side textures are released on the next render-thread call). */
    void close() {
        stop = true;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        composed.set(null);
        needRelease = true;
    }

    private volatile boolean needRelease;

    // ------------------------------------------------------------------ worker

    private void workerLoop(Session s) {
        PixelFile.Reader reader = null;
        String readerPath = null;
        PixelFile.Frame current = null;
        long currentVersion = 0, composedFor = -1;
        int composedCameraSeq = Integer.MIN_VALUE;
        byte[] outRgba = null;
        float[] outDepth = null;
        long version = 0;
        String failedPath = null;
        try {
            while (!stop) {
                var fc = s.frameChannel.get();
                String wantPath = fc == null ? null : fc.path();
                if (wantPath == null ? readerPath != null : !wantPath.equals(readerPath)) {
                    if (reader != null) reader.close();
                    reader = null;
                    readerPath = null;
                    current = null;
                    if (wantPath != null && !wantPath.equals(failedPath)) {
                        try {
                            reader = PixelFile.Reader.open(Path.of(wantPath));
                            if ((long) reader.width * reader.height > MAX_PIXELS) {
                                String why = reader.width + "x" + reader.height + " is more than the " + MAX_PIXELS + " pixels this mod takes";
                                reader.close();
                                reader = null;
                                throw new java.io.IOException(why);
                            }
                            readerPath = wantPath;
                            LinkManager.LOG.info("pixel file {} mapped: {}x{}, {} slots, depth plane: {}", wantPath, reader.width, reader.height,
                                    reader.slots, reader.hasDepth());
                            note = "pixel file " + reader.width + "x" + reader.height + (reader.hasDepth() ? " with depth" : " colour only");
                        } catch (java.io.IOException | RuntimeException e) {
                            failedPath = wantPath;
                            note = "cannot use the pixel file: " + e.getMessage();
                            LinkManager.LOG.warn("cannot use the pixel file {}: {}", wantPath, e.toString());
                        }
                    }
                }
                LinkEvent.Frame ev = s.latestFrame.getAndSet(null);
                if (ev != null && reader != null) {
                    Wire.FrameReady fr = ev.frame();
                    // The size-aware call: a frame whose datagram size disagrees with its slot header is skipped (CONTRACT 9.6, reader step 2).
                    PixelFile.Frame f = reader.read(fr, true);
                    if (f != null) {
                        current = f;
                        currentVersion++;
                    }
                }
                Wire.Camera pc = present.get();
                if (current != null && pc != null) {
                    int camKey = System.identityHashCode(pc);
                    if (currentVersion != composedFor || camKey != composedCameraSeq) {
                        composedFor = currentVersion;
                        composedCameraSeq = camKey;
                        CameraRing.Entry rc = s.cameraRing.get(current.cameraSeq());
                        int n = current.width() * current.height();
                        boolean reproject = rc != null && current.depth() != null;
                        if (reproject) {
                            if (outRgba == null || outRgba.length != n * 4) {
                                outRgba = new byte[n * 4];
                                outDepth = new float[n];
                            }
                            Reproject.run(current.rgba(), current.depth(), current.width(), current.height(), rc.view(), rc.proj(), pc.view(),
                                    pc.proj(), current.width(), current.height(), outRgba, outDepth);
                            composed.set(new Composed(++version, current.width(), current.height(), outRgba.clone(), outDepth.clone(), true,
                                    current.timeUs()));
                            framesReprojected++;
                        } else {
                            composed.set(new Composed(++version, current.width(), current.height(), current.rgba(), current.depth(), false,
                                    current.timeUs()));
                        }
                    }
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            LinkManager.LOG.warn("frame worker failed", e);
            note = "frame worker failed: " + e;
        } finally {
            if (reader != null) reader.close();
        }
    }

    // ------------------------------------------------------------------ render thread

    /** The state the pass changes, read before and put back after. */
    private static final class Saved {
        int fbo, vao, program, activeTexture, alignment, rowLength, skipPixels, skipRows;
        int[] viewport = new int[4];
        int[] tex = new int[3];
        boolean blend, depthTest, depthMask, cull, scissor;
        int srcRgb, dstRgb, srcAlpha, dstAlpha;
        boolean[] colorMask = new boolean[4];

        static Saved read() {
            Saved v = new Saved();
            v.fbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            v.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            v.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            v.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            v.alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            v.rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            v.skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
            v.skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, v.viewport);
            for (int i = 0; i < 3; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                v.tex[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            GL13.glActiveTexture(v.activeTexture);
            v.blend = GL11.glIsEnabled(GL11.GL_BLEND);
            v.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            v.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            v.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            v.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            v.srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            v.dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            v.srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            v.dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            java.nio.ByteBuffer cm = org.lwjgl.BufferUtils.createByteBuffer(16);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, cm);
            for (int i = 0; i < 4; i++) v.colorMask[i] = cm.get(i) != 0;
            return v;
        }

        void restore() {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            for (int i = 0; i < 3; i++) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(tex[i]);
            }
            GlStateManager._activeTexture(activeTexture);
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glUseProgram(program);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
            GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            GlStateManager._depthMask(depthMask);
            if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if (scissor) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
            GlStateManager._colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        }
    }

    /**
     * End of the level, main target still bound: upload the newest finished frame and draw it where it is nearer than the world.
     * Does nothing until the worker has produced a frame.
     */
    void draw(Minecraft mc, Session s) {
        if (broken) return;
        releaseIfNeeded();
        // Frames that stopped arriving are not drawn: the last picture must not hang in the world (and the placeholder is back, see visible()).
        if (!s.framesFresh()) return;
        Composed c = composed.get();
        if (c == null && uploadedVersion == 0) return;
        RenderTarget target = mc.getMainRenderTarget();
        if (target.width <= 0 || target.height <= 0 || !target.useDepth) return;
        Saved saved = null;
        try {
            RenderSystem.assertOnRenderThread();
            ensureProgram();
            saved = Saved.read();
            if (c != null && c.version != uploadedVersion) upload(c);
            if (colorTex == 0 || uploadedVersion == 0) return;

            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
            GlStateManager._viewport(0, 0, target.width, target.height);
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableCull();
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._enableBlend();
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(target.getDepthTextureId());
            GlStateManager._activeTexture(GL13.GL_TEXTURE1);
            GlStateManager._bindTexture(colorTex);
            GlStateManager._activeTexture(GL13.GL_TEXTURE2);
            GlStateManager._bindTexture(depthTex);
            GlStateManager._glUseProgram(program);
            GL20.glUniform1i(uHostDepth, 0);
            GL20.glUniform1i(uGuestColor, 1);
            GL20.glUniform1i(uGuestDepth, 2);
            // Minecraft's own near/far (blocks) and depth convention, link metres per block, and whether the guest has depth.
            GL20.glUniform4f(uDepthParams, (float) CameraSampler.hostNear, (float) CameraSampler.hostFar, CameraSampler.hostDepthKind,
                    (float) s.mapping.scale);
            GL20.glUniform4f(uFlags, hasDepthUploaded ? 1f : 0f, 0f, 0f, 0f);
            GlStateManager._glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            framesShown++;
        } catch (RuntimeException e) {
            broken = true;
            note = "frame pass failed: " + e;
            LinkManager.LOG.error("the frame pass failed, frames are off for this run", e);
        } finally {
            if (saved != null) saved.restore();
        }
    }

    /** Uploads a finished frame to the two textures (created or resized as needed). Texture unit 1 and 2 are used; the caller restores them. */
    private void upload(Composed c) {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        boolean create = colorTex == 0 || texW != c.width || texH != c.height;
        if (create) {
            release();
            colorTex = GL11.glGenTextures();
            depthTex = GL11.glGenTextures();
            texW = c.width;
            texH = c.height;
        }
        if (rgbaBuf == null || rgbaBuf.capacity() != c.rgba.length) rgbaBuf = org.lwjgl.BufferUtils.createByteBuffer(c.rgba.length);
        rgbaBuf.clear();
        rgbaBuf.put(c.rgba).flip();
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(colorTex);
        if (create) {
            setupTexture();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, c.width, c.height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgbaBuf);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, c.width, c.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgbaBuf);
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE2);
        GlStateManager._bindTexture(depthTex);
        hasDepthUploaded = c.depth != null;
        if (c.depth != null) {
            int bytes = c.depth.length * 4;
            if (depthBuf == null || depthBuf.capacity() != bytes) depthBuf = org.lwjgl.BufferUtils.createByteBuffer(bytes).order(ByteOrder.nativeOrder());
            depthBuf.clear();
            depthBuf.asFloatBuffer().put(c.depth);
            depthBuf.limit(bytes);
            if (create) {
                setupTexture();
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, c.width, c.height, 0, GL11.GL_RED, GL11.GL_FLOAT, depthBuf);
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, c.width, c.height, GL11.GL_RED, GL11.GL_FLOAT, depthBuf);
            }
        } else if (create) {
            setupTexture();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, 1, 1, 0, GL11.GL_RED, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
        }
        uploadedVersion = c.version;
    }

    private static void setupTexture() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    // ---- program

    private static String resource(String name) throws IOException {
        try (InputStream in = FrameOverlay.class.getResourceAsStream("/assets/minecraft_link/shaders/core/" + name)) {
            if (in == null) throw new IOException("missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int compile(int type, String source, String name) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, source);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(sh, 4096);
            GL20.glDeleteShader(sh);
            throw new IllegalStateException("cannot compile " + name + ": " + log);
        }
        return sh;
    }

    private void ensureProgram() {
        if (program != 0) return;
        try {
            int vs = compile(GL20.GL_VERTEX_SHADER, resource("link_frame.vsh"), "link_frame.vsh");
            int fs = compile(GL20.GL_FRAGMENT_SHADER, resource("link_frame.fsh"), "link_frame.fsh");
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
                String log = GL20.glGetProgramInfoLog(p, 4096);
                GL20.glDeleteProgram(p);
                throw new IllegalStateException("cannot link the frame program: " + log);
            }
            program = p;
            uHostDepth = GL20.glGetUniformLocation(p, "HostDepth");
            uGuestColor = GL20.glGetUniformLocation(p, "GuestColor");
            uGuestDepth = GL20.glGetUniformLocation(p, "GuestDepth");
            uDepthParams = GL20.glGetUniformLocation(p, "DepthParams");
            uFlags = GL20.glGetUniformLocation(p, "Flags");
            // An empty vertex array: the vertex shader needs no attributes, but core profile wants a VAO bound to draw.
            int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            GL30.glBindVertexArray(prevVao);
            glInfo = "GL_VERSION " + GL11.glGetString(GL11.GL_VERSION) + ", GLSL " + GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION)
                    + ", renderer " + GL11.glGetString(GL11.GL_RENDERER) + ", vendor " + GL11.glGetString(GL11.GL_VENDOR);
            LinkManager.LOG.info("frame pass ready: {}", glInfo);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Render thread, every frame, session or not: deletes the textures after the link stopped (the worker has no GL access, and
     * {@link #draw} only runs while a host session exists).
     */
    void releaseIfNeeded() {
        if (needRelease) {
            needRelease = false;
            release();
        }
    }

    /**
     * Whether a guest frame is on screen this frame: frames arrived within the last second, the host draws them, and one has
     * been uploaded. {@link RemoteEntities} hides the placeholder exactly then, so the overlay and the placeholder never
     * show together and never both disappear. Render thread.
     */
    boolean visible(Session s) {
        return !broken && colorTex != 0 && uploadedVersion != 0 && s.framesFresh();
    }

    /** Render thread. Deletes the GL objects of the guest frame (the program and vertex array stay). */
    private void release() {
        if (colorTex != 0) GL11.glDeleteTextures(colorTex);
        if (depthTex != 0) GL11.glDeleteTextures(depthTex);
        colorTex = depthTex = 0;
        uploadedVersion = 0;
        texW = texH = 0;
        rgbaBuf = depthBuf = null; // direct memory goes with the garbage collector once nothing holds the buffers
    }
}
