package dev.patodois.minecraftlink.client;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.patodois.minecraftlink.CameraMath;
import dev.patodois.minecraftlink.CameraRing;
import dev.patodois.minecraftlink.Reproject;
import dev.patodois.rewritelink.LinkEvent;
import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

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
 *   <li><b>render thread</b>: uploads the result to two textures (RGBA8 colour, R32F depth) at the start of
 *       the frame, and after the world has been drawn runs one fullscreen pass that converts
 *       Minecraft's own depth buffer to linear metres and keeps the guest's pixel only where it is
 *       nearer (alpha-blended over the world). A guest without depth is drawn as a plain overlay.</li>
 * </ol>
 * The render thread never waits for the worker: it uploads the newest finished result, if any.
 */
final class FrameOverlay {
    private static final Identifier FRAG = Identifier.fromNamespaceAndPath("minecraft_link", "core/link_frame");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("minecraft_link", "pipeline/link_frame"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(FRAG)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("HostDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("GuestColor", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("GuestDepth", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("LinkFrame", UniformType.UNIFORM_BUFFER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();

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

    // ---- render thread only
    private GpuTexture colorTex, depthTex;
    private GpuTextureView colorView, depthView;
    private int texW, texH;
    private long uploadedVersion;
    private GpuBuffer ubo;
    private boolean hasDepthUploaded;
    private boolean broken;

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
                    PixelFile.Frame f = reader.read(fr.frame(), fr.slot(), true);
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

    /** Start of a frame, no render pass open: upload the newest finished frame. */
    void upload(Minecraft mc, Session s) {
        if (broken) return;
        try {
            if (needRelease) {
                needRelease = false;
                release();
            }
            Composed c = composed.get();
            if (c == null || c.version == uploadedVersion) return;
            GpuDevice dev = RenderSystem.getDevice();
            if (colorTex == null || texW != c.width || texH != c.height) {
                release();
                colorTex = dev.createTexture("minecraft-link guest colour", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM, c.width, c.height, 1, 1);
                depthTex = dev.createTexture("minecraft-link guest depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.R32_FLOAT, c.width, c.height, 1, 1);
                colorView = dev.createTextureView(colorTex);
                depthView = dev.createTextureView(depthTex);
                texW = c.width;
                texH = c.height;
                ubo = dev.createBuffer(() -> "minecraft-link frame uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 32L);
            }
            CommandEncoder enc = dev.createCommandEncoder();
            ByteBuffer rgba = ByteBuffer.allocateDirect(c.rgba.length).order(ByteOrder.nativeOrder());
            rgba.put(c.rgba).flip();
            enc.writeToTexture(colorTex, rgba, 0, 0, 0, 0, c.width, c.height);
            hasDepthUploaded = c.depth != null;
            if (c.depth != null) {
                ByteBuffer d = ByteBuffer.allocateDirect(c.depth.length * 4).order(ByteOrder.nativeOrder());
                d.asFloatBuffer().put(c.depth);
                enc.writeToTexture(depthTex, d, 0, 0, 0, 0, c.width, c.height);
            }
            uploadedVersion = c.version;
        } catch (RuntimeException e) {
            broken = true;
            note = "frame upload failed: " + e;
            LinkManager.LOG.error("frame upload failed, frames are off for this run", e);
        }
    }

    /** End of the level's main pass: draw the guest's frame where it is nearer than the world. */
    void draw(Minecraft mc, Session s) {
        if (broken || colorView == null || uploadedVersion == 0) return;
        try {
            var target = mc.gameRenderer.mainRenderTarget();
            GpuTextureView out = target.getColorTextureView(), hostDepth = target.getDepthTextureView();
            if (out == null || hostDepth == null) return;
            GpuDevice dev = RenderSystem.getDevice();
            CommandEncoder enc = dev.createCommandEncoder();
            ByteBuffer u = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
            // Minecraft's own near/far (blocks) and depth convention, link metres per block, and whether the guest has depth.
            Std140Builder.intoBuffer(u)
                    .putVec4((float) CameraSampler.hostNear, (float) CameraSampler.hostFar, CameraSampler.hostDepthKind, (float) s.mapping.scale)
                    .putVec4(hasDepthUploaded ? 1f : 0f, 0f, 0f, 0f);
            u.rewind();
            enc.writeToBuffer(new GpuBufferSlice(ubo, 0L, 32L), u);
            GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = enc.createRenderPass(() -> "minecraft-link guest frame", out, Optional.empty(), null, OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                pass.setUniform("LinkFrame", ubo);
                pass.setUniform("HostDepth", hostDepth, nearest);
                pass.setUniform("GuestColor", colorView, nearest);
                pass.setUniform("GuestDepth", depthView, nearest);
                pass.draw(3, 1, 0, 0);
            }
            framesShown++;
        } catch (RuntimeException e) {
            broken = true;
            note = "frame pass failed: " + e;
            LinkManager.LOG.error("the frame pass failed, frames are off for this run", e);
        }
    }

    private void release() {
        if (colorView != null) colorView.close();
        if (depthView != null) depthView.close();
        if (colorTex != null) colorTex.close();
        if (depthTex != null) depthTex.close();
        if (ubo != null) ubo.close();
        colorView = depthView = null;
        colorTex = depthTex = null;
        ubo = null;
        uploadedVersion = 0;
        texW = texH = 0;
    }
}
