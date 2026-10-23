package dev.patodois.minecraftlink.client;

/** Handoff between the renderer mixin and the camera sampler (both run on the render thread). */
public final class CameraCapture {
    private CameraCapture() {}

    /** The projection times the view bobbing that Minecraft hands to the renderer this frame, or null. */
    public static float[] drawnProjection;
}
