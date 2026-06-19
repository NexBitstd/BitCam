package dev.nexbit.bitcam.clientcommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;

/**
 * A fully decoded, display-ready frame. Decoding (JPEG/H.264), content-mode cropping and the circle
 * mask all run on the decode thread, so the render thread only has to upload {@link #abgrPixels} to
 * a texture. Pixels are packed ABGR to match {@code NativeImage#setPixelABGR}.
 *
 * <p>{@link #sourceWidth}/{@link #sourceHeight} are the original capture dimensions (used for the
 * bubble's aspect ratio); {@link #pixelWidth}/{@link #pixelHeight} are the prepared texture size.
 */
public record DecodedFrame(
    int frameId,
    long captureTimeMillis,
    int sourceWidth,
    int sourceHeight,
    int pixelWidth,
    int pixelHeight,
    int[] abgrPixels,
    BitCamBubbleStyle bubbleStyle
) {
}
