#version 150

// Draws a guest's frame (colour and linear depth in metres, CONTRACT.md 9.7) into the main target,
// keeping only the pixels that are nearer than what Minecraft drew there.

uniform sampler2D HostDepth;   // Minecraft's depth texture (window depth, 0..1)
uniform sampler2D GuestColor;
uniform sampler2D GuestDepth;

// x, y: near and far of Minecraft's projection in blocks; z: the depth convention of its depth buffer
// (see CameraMath.DEPTH_*: 0 = OpenGL -1..1, 1 = zero to one, 2 / -1 = reversed); w: link metres per block
uniform vec4 DepthParams;
// x: 1 when the guest frame has a depth plane, 0 to paste it as an overlay
uniform vec4 Flags;

in vec2 texCoord;
out vec4 fragColor;

// The hardware depth value d (as stored in the depth buffer, 0..1) to linear metres in front of the camera.
float hostLinearDepth(float d) {
    float n = DepthParams.x;
    float f = DepthParams.y;
    int kind = int(DepthParams.z);
    if (kind == 2 || kind == -1) {
        // reversed: near -> 1, far -> 0 (the same formula whether the clip range was 0..1 or -1..1,
        // because d is the window depth in both)
        return n * f / (n + d * (f - n));
    } else if (kind == 1) {
        return f * n / (f - d * (f - n));
    } else {
        // OpenGL: ndc z in -1..1, window depth d = (ndc + 1) / 2. Minecraft 1.20.1 uses this one.
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
    if (Flags.x > 0.5) {
        float tg = texture(GuestDepth, g).r;
        if (!(tg > 0.0) || isinf(tg)) {
            discard;
        }
        float th = hostLinearDepth(texture(HostDepth, texCoord).r) * DepthParams.w;
        if (!(tg < th)) {
            discard; // the host's surface is nearer, or equally near: the host wins
        }
    }
    fragColor = c;
}
