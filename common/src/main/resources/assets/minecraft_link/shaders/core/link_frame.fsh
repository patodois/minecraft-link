#version 330
#extension GL_ARB_separate_shader_objects : require

// Draws a guest's frame (colour and linear depth in metres, CONTRACT.md 9.7) into the main target,
// keeping only the pixels that are nearer than what Minecraft drew there.

uniform sampler2D HostDepth;
uniform sampler2D GuestColor;
uniform sampler2D GuestDepth;

layout(std140) uniform LinkFrame {
    // near and far of Minecraft's projection in blocks, the depth convention of its depth buffer
    // (see CameraMath.DEPTH_*; -1 = reversed with a -1..1 clip range), link metres per block
    vec4 depthParams;
    // x: 1 when the guest frame has a depth plane, 0 to paste it as an overlay
    vec4 flags;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// The hardware depth value d (as stored in the depth buffer, 0..1) to linear metres in front of the camera.
float hostLinearDepth(float d) {
    float n = depthParams.x;
    float f = depthParams.y;
    int kind = int(depthParams.z);
    if (kind == 2 || kind == -1) {
        // reversed: near -> 1, far -> 0 (the same formula whether the clip range was 0..1 or -1..1,
        // because d is the window depth in both)
        return n * f / (n + d * (f - n));
    } else if (kind == 1) {
        return f * n / (f - d * (f - n));
    } else {
        float ndc = d * 2.0 - 1.0;
        return 2.0 * f * n / ((f + n) - ndc * (f - n));
    }
}

void main() {
    // Rows of the guest image run top to bottom, texCoord.y runs from the bottom of the screen up.
    vec2 g = vec2(texCoord.x, 1.0 - texCoord.y);
    vec4 c = texture(GuestColor, g);
    if (c.a <= 0.0) {
        discard;
    }
    if (flags.x > 0.5) {
        float tg = texture(GuestDepth, g).r;
        if (!(tg > 0.0) || isinf(tg)) {
            discard;
        }
        float th = hostLinearDepth(texture(HostDepth, texCoord).r) * depthParams.w;
        if (!(tg < th)) {
            discard; // the host's surface is nearer, or equally near: the host wins
        }
    }
    fragColor = c;
}
