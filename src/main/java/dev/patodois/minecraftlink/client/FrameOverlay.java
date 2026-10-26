package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.CameraRing;
import dev.patodois.minecraftlink.Reproject;
import dev.patodois.rewritelink.Control;
import dev.patodois.rewritelink.LinkEvent;
import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBTextureFloat;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

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
 *   <li><b>render thread</b> ({@code RenderWorldLastEvent}: the world is in the framebuffer, the hand and the GUI are
 *       not drawn yet): uploads the result to two textures (RGBA8 colour, 32-bit float linear depth), copies the
 *       game's own depth buffer into a {@code GL_DEPTH_COMPONENT} texture with {@code glCopyTexSubImage2D}, and draws one
 *       fullscreen quad with a GLSL 1.20 program that converts the game's depth to linear metres with the contract's
 *       formula and keeps the guest's pixel only where it is nearer (alpha-blended over the world). A guest without
 *       depth is drawn as a plain overlay.</li>
 * </ol>
 * The render thread never waits for the worker: it uploads the newest finished result, if any.
 *
 * <p>GL state: everything the pass touches is bracketed by {@code glPushAttrib(GL_ALL_ATTRIB_BITS)} / {@code glPopAttrib}
 * plus an explicit save and restore of the active texture unit, the texture bindings of units 0 to 2 and the current
 * program, so the game's own state cache ({@code GlStateManager}) still matches the real state afterwards.
 */
final class FrameOverlay {
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;

    private static final String VERT = "#version 120\n"
            + "varying vec2 texCoord;\n"
            + "void main() {\n"
            + "    texCoord = gl_Vertex.xy * 0.5 + 0.5;\n"
            + "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n"
            + "}\n";

    // Draws a guest's frame (colour and linear depth in metres, CONTRACT.md 9.7) over the game's framebuffer,
    // keeping only the pixels that are nearer than what the game drew there.
    private static final String FRAG = "#version 120\n"
            + "uniform sampler2D HostDepth;\n"
            + "uniform sampler2D GuestColor;\n"
            + "uniform sampler2D GuestDepth;\n"
            // near and far of the game's projection in blocks, the depth convention of its depth buffer
            // (see CameraMath.DEPTH_*; -1 = reversed with a -1..1 clip range), link metres per block
            + "uniform vec4 depthParams;\n"
            // x: 1 when the guest frame has a depth plane, 0 to paste it as an overlay
            + "uniform vec4 flags;\n"
            + "varying vec2 texCoord;\n"
            // The hardware depth value d (as stored in the depth buffer, 0..1) to linear metres in front of the camera.
            + "float hostLinearDepth(float d) {\n"
            + "    float n = depthParams.x;\n"
            + "    float f = depthParams.y;\n"
            + "    int kind = int(depthParams.z + 0.5);\n"
            + "    if (kind == 2 || depthParams.z < -0.5) {\n"
            + "        return n * f / (n + d * (f - n));\n"
            + "    } else if (kind == 1) {\n"
            + "        return f * n / (f - d * (f - n));\n"
            + "    }\n"
            + "    float ndc = d * 2.0 - 1.0;\n"
            + "    return 2.0 * f * n / ((f + n) - ndc * (f - n));\n"
            + "}\n"
            + "void main() {\n"
            // Rows of the guest image run top to bottom, texCoord.y runs from the bottom of the screen up.
            + "    vec2 g = vec2(texCoord.x, 1.0 - texCoord.y);\n"
            + "    vec4 c = texture2D(GuestColor, g);\n"
            + "    if (c.a <= 0.0) {\n"
            + "        discard;\n"
            + "    }\n"
            + "    if (flags.x > 0.5) {\n"
            + "        float tg = texture2D(GuestDepth, g).r;\n"
            + "        if (!(tg > 0.0) || tg > 3.0e38) {\n"
            + "            discard;\n"
            + "        }\n"
            + "        float th = hostLinearDepth(texture2D(HostDepth, texCoord).r) * depthParams.w;\n"
            + "        if (!(tg < th)) {\n"
            + "            discard;\n" // the game's surface is nearer, or equally near: the game wins
            + "        }\n"
            + "    }\n"
            + "    gl_FragColor = c;\n"
            + "}\n";

    /** A finished frame in the present camera's view: same size as the guest image. */
    private static final class Composed {
        final long version;
        final int width, height;
        final byte[] rgba;
        final float[] depth;
        final boolean reprojected;
        final long guestTimeUs;

        Composed(long version, int width, int height, byte[] rgba, float[] depth, boolean reprojected, long guestTimeUs) {
            this.version = version;
            this.width = width;
            this.height = height;
            this.rgba = rgba;
            this.depth = depth;
            this.reprojected = reprojected;
            this.guestTimeUs = guestTimeUs;
        }
    }

    // ---- shared
    private final AtomicReference<Composed> composed = new AtomicReference<>();
    private final AtomicReference<Wire.Camera> present = new AtomicReference<>();
    private volatile Thread worker;
    private volatile boolean stop;
    volatile long framesShown;
    volatile long framesReprojected;
    volatile String note = "";
    /** GL_VERSION, GLSL version and framebuffer facts, logged once and shown by /link status. */
    volatile String glInfo = "";

    /**
     * The most pixels per frame this mod takes. CONTRACT 9.6 lets a file be 2^26 pixels (8192 x 8192) and a receiver refuse
     * larger ones of its own accord: the reprojection runs on the CPU and keeps several copies of the frame, so a 4 Mpixel
     * frame (2560 x 1600) already costs about a hundred megabytes. A bigger file is refused with a note.
     */
    static final long MAX_PIXELS = 4L << 20;

    // ---- render thread only
    private int program, uHostDepth, uGuestColor, uGuestDepth, uDepthParams, uFlags;
    private int hostDepthTex, colorTex, depthTex;
    private int hostW, hostH, texW, texH;
    private long uploadedVersion;
    private boolean hasDepthUploaded;
    private boolean broken;
    private boolean loggedFirstDraw;
    private ByteBuffer colorBuf;
    private FloatBuffer depthBuf;

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
                Control.FrameChannel fc = s.frameChannel.get();
                String wantPath = fc == null ? null : fc.path();
                if (wantPath == null ? readerPath != null : !wantPath.equals(readerPath)) {
                    if (reader != null) reader.close();
                    reader = null;
                    readerPath = null;
                    current = null;
                    if (wantPath != null && !wantPath.equals(failedPath)) {
                        try {
                            reader = PixelFile.Reader.open(Paths.get(wantPath));
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

    /**
     * Render thread, at the end of the world pass: uploads the newest finished guest frame, copies the game's depth
     * buffer and draws the guest where it is nearer than the world.
     */
    void draw(Minecraft mc, Session s) {
        if (broken) return;
        releaseIfNeeded();
        // Frames that stopped arriving are not drawn: the last picture must not hang in the world (and the placeholder is back, see visible()).
        if (!s.framesFresh()) return;
        Composed c = composed.get();
        if (c == null && uploadedVersion == 0) return;
        int prevActive = 0, prevProgram = 0, prevTex0 = 0, prevTex1 = 0, prevTex2 = 0;
        boolean pushed = false;
        try {
            prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            prevTex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
            pushed = true;
            drawInner(mc, s, c);
        } catch (RuntimeException | Error e) {
            broken = true;
            note = "frame pass failed: " + e;
            LinkManager.LOG.error("the frame pass failed, frames are off for this run", e);
        } finally {
            if (pushed) {
                GL11.glPopClientAttrib();
                GL11.glPopAttrib();
            }
            GL20.glUseProgram(prevProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
            GL13.glActiveTexture(prevActive);
        }
    }

    private void drawInner(Minecraft mc, Session s, Composed c) {
        if (program == 0 && !createProgram()) {
            broken = true;
            return;
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        if (c != null && c.version != uploadedVersion) upload(c);
        if (colorTex == 0 || uploadedVersion == 0) return;

        // The game's framebuffer: its real size in pixels (on a Retina display the window is not that size).
        int fbW, fbH;
        Framebuffer fb = mc.getFramebuffer();
        if (OpenGlHelper.isFramebufferEnabled() && fb != null) {
            fbW = fb.framebufferWidth;
            fbH = fb.framebufferHeight;
        } else {
            fbW = mc.displayWidth;
            fbH = mc.displayHeight;
        }
        if (fbW <= 0 || fbH <= 0) return;

        // Copy the game's depth (the world, without the hand and the GUI) from the bound framebuffer to a depth texture.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        if (hostDepthTex == 0) hostDepthTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hostDepthTex);
        if (hostW != fbW || hostH != fbH) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, fbW, fbH, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE); // sample the depth value, not a comparison
            hostW = fbW;
            hostH = fbH;
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, fbW, fbH);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);

        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); // straight alpha
        GL20.glUseProgram(program);
        GL20.glUniform1i(uHostDepth, 0);
        GL20.glUniform1i(uGuestColor, 1);
        GL20.glUniform1i(uGuestDepth, 2);
        GL20.glUniform4f(uDepthParams, (float) CameraSampler.hostNear, (float) CameraSampler.hostFar, CameraSampler.hostDepthKind, (float) s.mapping.scale);
        GL20.glUniform4f(uFlags, hasDepthUploaded ? 1f : 0f, 0f, 0f, 0f);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1f, -1f);
        GL11.glVertex2f(1f, -1f);
        GL11.glVertex2f(1f, 1f);
        GL11.glVertex2f(-1f, 1f);
        GL11.glEnd();
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        framesShown++;
        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            LinkManager.LOG.info("first guest frame drawn: framebuffer {}x{}, bound framebuffer object {}, viewport {}", fbW, fbH,
                    GL11.glGetInteger(GL_FRAMEBUFFER_BINDING), fbW + "x" + fbH);
        }
    }

    private static final int GL12_CLAMP_TO_EDGE = 0x812F;

    private void upload(Composed c) {
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        boolean resize = colorTex == 0 || texW != c.width || texH != c.height;
        if (resize) {
            deleteGuestTextures();
            colorTex = GL11.glGenTextures();
            depthTex = GL11.glGenTextures();
            texW = c.width;
            texH = c.height;
            colorBuf = BufferUtils.createByteBuffer(c.width * c.height * 4);
            depthBuf = BufferUtils.createFloatBuffer(c.width * c.height);
        }
        colorBuf.clear();
        colorBuf.put(c.rgba, 0, c.width * c.height * 4).flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        if (resize) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, c.width, c.height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colorBuf);
            nearestClamp();
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, c.width, c.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colorBuf);
        }
        hasDepthUploaded = c.depth != null;
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        if (c.depth != null) {
            depthBuf.clear();
            depthBuf.put(c.depth, 0, c.width * c.height).flip();
            if (resize) {
                // 32-bit float, one channel (ARB_texture_float, part of the macOS GL 2.1 renderers): linear metres
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, ARBTextureFloat.GL_LUMINANCE32F_ARB, c.width, c.height, 0, GL11.GL_LUMINANCE, GL11.GL_FLOAT, depthBuf);
                nearestClamp();
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, c.width, c.height, GL11.GL_LUMINANCE, GL11.GL_FLOAT, depthBuf);
            }
        } else if (resize) {
            nearestClamp();
        }
        uploadedVersion = c.version;
    }

    private static void nearestClamp() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
    }

    private boolean createProgram() {
        ContextCapabilities caps = GLContext.getCapabilities();
        String version = GL11.glGetString(GL11.GL_VERSION);
        String glsl = caps.OpenGL20 ? GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION) : "(no GL 2.0)";
        glInfo = "GL_VERSION " + version + ", GLSL " + glsl + ", renderer " + GL11.glGetString(GL11.GL_RENDERER) + ", vendor "
                + GL11.glGetString(GL11.GL_VENDOR) + ", ARB_texture_float " + caps.GL_ARB_texture_float + ", ARB_depth_texture " + caps.GL_ARB_depth_texture;
        LinkManager.LOG.info("OpenGL: {}", glInfo);
        if (!caps.OpenGL20 || !caps.GL_ARB_texture_float) {
            note = "no GL 2.0 shaders or float textures: frames are off";
            LinkManager.LOG.warn(note);
            return false;
        }
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
        if (vs == 0 || fs == 0) return false;
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        boolean ok = GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != 0;
        String log = GL20.glGetProgramInfoLog(p, 4096);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (!ok) {
            note = "frame program did not link: " + log;
            LinkManager.LOG.error(note);
            GL20.glDeleteProgram(p);
            return false;
        }
        program = p;
        uHostDepth = GL20.glGetUniformLocation(p, "HostDepth");
        uGuestColor = GL20.glGetUniformLocation(p, "GuestColor");
        uGuestDepth = GL20.glGetUniformLocation(p, "GuestDepth");
        uDepthParams = GL20.glGetUniformLocation(p, "depthParams");
        uFlags = GL20.glGetUniformLocation(p, "flags");
        LinkManager.LOG.info("frame program linked (GLSL 1.20){}", log.trim().isEmpty() ? "" : ": " + log.trim());
        return true;
    }

    private int compile(int type, String src) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
            note = "frame shader did not compile: " + GL20.glGetShaderInfoLog(sh, 4096);
            LinkManager.LOG.error(note);
            GL20.glDeleteShader(sh);
            return 0;
        }
        return sh;
    }

    private void deleteGuestTextures() {
        if (colorTex != 0) GL11.glDeleteTextures(colorTex);
        if (depthTex != 0) GL11.glDeleteTextures(depthTex);
        colorTex = depthTex = 0;
        texW = texH = 0;
    }

    /**
     * Render thread, every frame, session or not (or at once when the link is stopped on the game thread): deletes the textures
     * after the link stopped, because the worker has no GL access and {@link #draw} only runs while a host session exists.
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

    /** Render thread: frees the textures (the program stays for the next session). */
    private void release() {
        deleteGuestTextures();
        if (hostDepthTex != 0) GL11.glDeleteTextures(hostDepthTex);
        hostDepthTex = 0;
        hostW = hostH = 0;
        uploadedVersion = 0;
        hasDepthUploaded = false;
        colorBuf = null; // direct memory goes with the garbage collector once nothing holds the buffers
        depthBuf = null;
    }
}
