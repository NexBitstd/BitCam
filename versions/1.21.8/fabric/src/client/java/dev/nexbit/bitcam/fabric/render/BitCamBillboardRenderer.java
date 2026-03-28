package dev.nexbit.bitcam.fabric.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleContentLayout;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleVisuals;
import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.LocalPreviewFrame;
import dev.nexbit.bitcam.clientcommon.RemoteFrame;
import dev.nexbit.bitcam.clientcommon.RemoteFrameStore;
import dev.nexbit.bitcam.common.BitCamMetadata;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleRenderMode;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleShape;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

public final class BitCamBillboardRenderer implements AutoCloseable {
    private static final long FRAME_TTL_MS = 10_000L;

    private final Minecraft client;
    private final Supplier<BitCamClientCoordinator> coordinatorSupplier;
    private final ByteBufferBuilder immediateBubbleBuffer = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private final MultiBufferSource.BufferSource immediateBubbleConsumers = MultiBufferSource.immediate(this.immediateBubbleBuffer);
    private final Map<UUID, StreamTexture> textures = new HashMap<>();
    private StreamTexture whiteTexture;
    private StreamTexture circleMaskTexture;

    public BitCamBillboardRenderer(Minecraft client, Supplier<BitCamClientCoordinator> coordinatorSupplier) {
        this.client = client;
        this.coordinatorSupplier = coordinatorSupplier;
    }

    public void tick() {
        BitCamClientCoordinator coordinator = this.coordinatorSupplier.get();
        RemoteFrameStore frameStore = coordinator == null ? null : coordinator.frameStore();
        if (frameStore != null) {
            frameStore.pruneExpired(FRAME_TTL_MS);
        }

        Set<UUID> activeStreams = new HashSet<>();
        if (frameStore != null) {
            activeStreams.addAll(frameStore.snapshot().keySet());
        }
        if (this.client.player != null && coordinator != null && coordinator.previewStore().frame() != null) {
            activeStreams.add(this.client.player.getUUID());
        }
        this.cleanupUnused(activeStreams);
    }

    public void renderFirstPersonPreview(WorldRenderContext context) {
        PoseStack matrices = context.matrixStack();
        MultiBufferSource consumers = context.consumers();
        BitCamClientCoordinator coordinator = this.coordinatorSupplier.get();
        if (matrices == null || consumers == null || coordinator == null || this.client.player == null || this.client.level == null) {
            return;
        }

        if (this.client.screen != null) {
            return;
        }

        if (!this.client.options.getCameraType().isFirstPerson()) {
            return;
        }

        LocalPreviewFrame localFrame = coordinator.previewStore().frame();
        if (localFrame == null) {
            return;
        }

        StreamTexture texture = this.updateTexture(this.client.player.getUUID(), localFrame.frameId(), localFrame.payload(), coordinator.bubbleStyle());
        if (texture == null) {
            return;
        }

        this.renderFirstPersonBubble(
            matrices,
            consumers,
            this.client.player,
            localFrame.width(),
            localFrame.height(),
            coordinator.bubbleStyle(),
            texture.textureId()
        );
    }

    public void renderPlayerLayer(
        PoseStack matrices,
        MultiBufferSource consumers,
        int packedLight,
        PlayerRenderState renderState
    ) {
        BitCamClientCoordinator coordinator = this.coordinatorSupplier.get();
        RemoteFrameStore frameStore = coordinator == null ? null : coordinator.frameStore();
        if (coordinator == null || frameStore == null || this.client.level == null) {
            return;
        }

        Entity entity = this.client.level.getEntity(renderState.id);
        if (!(entity instanceof AbstractClientPlayer player) || coordinator.isPlayerHidden(player.getUUID())) {
            return;
        }

        RenderFrameData frameData = this.resolvePlayerFrame(player, coordinator, frameStore);
        if (frameData == null) {
            return;
        }

        this.immediateBubbleConsumers.endBatch();
        this.renderPlayerBubble(
            matrices,
            this.immediateBubbleConsumers,
            player,
            (float) renderState.boundingBoxHeight,
            renderState.bodyRot,
            renderState.yRot,
            renderState.xRot,
            frameData.width(),
            frameData.height(),
            frameData.bubbleStyle(),
            frameData.textureId()
        );
        this.immediateBubbleConsumers.endBatch();
    }

    private RenderFrameData resolvePlayerFrame(AbstractClientPlayer player, BitCamClientCoordinator coordinator, RemoteFrameStore frameStore) {
        RemoteFrame remoteFrame = frameStore.frame(player.getUUID());
        if (remoteFrame != null && System.currentTimeMillis() - remoteFrame.receivedAtMillis() <= FRAME_TTL_MS) {
            StreamTexture texture = this.updateTexture(remoteFrame);
            if (texture != null) {
                return new RenderFrameData(remoteFrame.width(), remoteFrame.height(), remoteFrame.bubbleStyle(), texture.textureId());
            }
        }

        if (this.client.player == player) {
            LocalPreviewFrame localFrame = coordinator.previewStore().frame();
            if (localFrame != null) {
                StreamTexture texture = this.updateTexture(player.getUUID(), localFrame.frameId(), localFrame.payload(), coordinator.bubbleStyle());
                if (texture != null) {
                    return new RenderFrameData(localFrame.width(), localFrame.height(), coordinator.bubbleStyle(), texture.textureId());
                }
            }
        }

        return null;
    }

    private void renderPlayerBubble(
        PoseStack matrices,
        MultiBufferSource consumers,
        AbstractClientPlayer player,
        float boundingBoxHeight,
        float bodyYaw,
        float viewYaw,
        float pitch,
        int width,
        int height,
        BitCamBubbleStyle bubbleStyle,
        ResourceLocation textureId
    ) {
        BitCamBubbleVisuals visuals = BitCamBubbleVisuals.fromStyle(bubbleStyle);
        float aspectRatio = BitCamBubbleContentLayout.displayAspectRatio(width, height, bubbleStyle);
        float scale = bubbleStyle.scalePercent() / 100.0F;
        float halfHeight = 0.35F * scale;
        float halfWidth = halfHeight * aspectRatio;
        float backgroundPadding = 0.03F * scale;
        float borderPadding = 0.06F * scale;
        int videoAlpha = Math.max(0, Math.min(255, Math.round(255.0F * (bubbleStyle.opacityPercent() / 100.0F))));
        double horizontalOffset = this.computeHorizontalOffset(bubbleStyle, scale);
        double verticalOffset = this.computeWorldVerticalOffset(bubbleStyle, scale);
        boolean flipHorizontalUv = bubbleStyle.renderMode() == BitCamBubbleRenderMode.BILLBOARD;

        matrices.pushPose();
        matrices.translate(0.0D, -(boundingBoxHeight + verticalOffset), 0.0D);
        this.applyPlayerLayerOrientation(matrices, bubbleStyle.renderMode(), bodyYaw, viewYaw, pitch);
        matrices.translate(horizontalOffset, 0.0D, 0.0D);
        this.renderBubbleGeometry(matrices, consumers, bubbleStyle.shape(), halfWidth, halfHeight, borderPadding, backgroundPadding, visuals, textureId, videoAlpha, true, flipHorizontalUv);
        matrices.popPose();
    }

    private StreamTexture updateTexture(RemoteFrame frame) {
        return this.updateTexture(frame.streamerId(), frame.frameId(), frame.payload(), frame.bubbleStyle());
    }

    private StreamTexture updateTexture(UUID streamerId, int frameId, byte[] payload, BitCamBubbleStyle bubbleStyle) {
        StreamTexture streamTexture = this.textures.computeIfAbsent(streamerId, this::createTexture);

        if (streamTexture.lastFrameId() == frameId) {
            return streamTexture;
        }

        try {
            NativeImage image = decodePayload(payload, bubbleStyle);
            if (bubbleStyle.shape() == BitCamBubbleShape.CIRCLE) {
                this.applyCircleMask(image);
            }
            streamTexture = this.ensureTextureSize(streamerId, streamTexture, image.getWidth(), image.getHeight());
            NativeImage previousImage = streamTexture.texture().getPixels();
            if (previousImage != null) {
                previousImage.close();
            }

            streamTexture.texture().setPixels(image);
            streamTexture.texture().upload();
            streamTexture.lastFrameId(frameId);
            return streamTexture;
        } catch (IOException exception) {
            return null;
        }
    }

    private void renderFirstPersonBubble(
        PoseStack matrices,
        MultiBufferSource consumers,
        AbstractClientPlayer player,
        int width,
        int height,
        BitCamBubbleStyle bubbleStyle,
        ResourceLocation textureId
    ) {
        BitCamBubbleVisuals visuals = BitCamBubbleVisuals.fromStyle(bubbleStyle);
        float aspectRatio = BitCamBubbleContentLayout.displayAspectRatio(width, height, bubbleStyle);
        float scale = bubbleStyle.scalePercent() / 100.0F;
        float halfHeight = 0.35F * scale;
        float halfWidth = halfHeight * aspectRatio;
        float backgroundPadding = 0.03F * scale;
        float borderPadding = 0.06F * scale;
        int videoAlpha = Math.max(0, Math.min(255, Math.round(255.0F * (bubbleStyle.opacityPercent() / 100.0F))));
        double horizontalOffset = this.computeHorizontalOffset(bubbleStyle, scale);
        double verticalOffset = this.computeFirstPersonVerticalOffset(bubbleStyle, scale);
        boolean flipHorizontalUv = bubbleStyle.renderMode() == BitCamBubbleRenderMode.BILLBOARD;

        matrices.pushPose();
        matrices.translate(0.0D, verticalOffset, -1.1D);
        this.applyFreeOrientation(matrices, player, bubbleStyle.renderMode());
        matrices.translate(horizontalOffset, 0.0D, 0.0D);
        this.renderBubbleGeometry(matrices, consumers, bubbleStyle.shape(), halfWidth, halfHeight, borderPadding, backgroundPadding, visuals, textureId, videoAlpha, false, flipHorizontalUv);
        matrices.popPose();
    }

    private double computeHorizontalOffset(BitCamBubbleStyle bubbleStyle, float scale) {
        double normalizedOffset = bubbleStyle.xOffsetPercent() / (double) BitCamBubbleStyle.BUBBLE_OFFSET_MAX;
        return normalizedOffset * (0.48D + (0.10D * scale));
    }

    private double computeWorldVerticalOffset(BitCamBubbleStyle bubbleStyle, float scale) {
        double normalizedOffset = bubbleStyle.yOffsetPercent() / (double) BitCamBubbleStyle.BUBBLE_OFFSET_MAX;
        return (0.26D + (0.14D * scale)) + (normalizedOffset * (1.10D + (0.20D * scale)));
    }

    private double computeFirstPersonVerticalOffset(BitCamBubbleStyle bubbleStyle, float scale) {
        double normalizedOffset = bubbleStyle.yOffsetPercent() / (double) BitCamBubbleStyle.BUBBLE_OFFSET_MAX;
        return (0.28D + (0.14D * scale)) + (normalizedOffset * (0.82D + (0.16D * scale)));
    }

    private void renderBubbleGeometry(
        PoseStack matrices,
        MultiBufferSource consumers,
        BitCamBubbleShape shape,
        float halfWidth,
        float halfHeight,
        float borderPadding,
        float backgroundPadding,
        BitCamBubbleVisuals visuals,
        ResourceLocation textureId,
        int videoAlpha,
        boolean flipVerticalUv,
        boolean flipHorizontalUv
    ) {
        StreamTexture shapeTexture = this.getShapeTexture(shape);
        PoseStack.Pose pose = matrices.last();
        RenderType bubbleRenderType = RenderType.entityTranslucent(shapeTexture.textureId());
        VertexConsumer bubbleConsumer = consumers.getBuffer(bubbleRenderType);
        this.addQuad(bubbleConsumer, pose, halfWidth + borderPadding, halfHeight + borderPadding, -0.002F, visuals.borderArgb(), flipVerticalUv, flipHorizontalUv);
        this.addQuad(bubbleConsumer, pose, halfWidth + backgroundPadding, halfHeight + backgroundPadding, -0.001F, visuals.backgroundArgb(), flipVerticalUv, flipHorizontalUv);

        VertexConsumer videoConsumer = consumers.getBuffer(RenderType.entityTranslucentEmissive(textureId));
        this.addQuad(videoConsumer, pose, halfWidth, halfHeight, 0.0F, 0x00FFFFFF | (videoAlpha << 24), flipVerticalUv, flipHorizontalUv);
    }

    private StreamTexture createTexture(UUID streamerId) {
        ResourceLocation textureId = ResourceLocation.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "stream/" + streamerId);
        DynamicTexture texture = new DynamicTexture(() -> "bitcam/" + streamerId, 1, 1, false);
        this.client.getTextureManager().register(textureId, texture);
        return new StreamTexture(textureId, texture);
    }

    private StreamTexture ensureTextureSize(UUID streamerId, StreamTexture streamTexture, int width, int height) {
        if (streamTexture.width() == width && streamTexture.height() == height) {
            return streamTexture;
        }

        this.client.getTextureManager().release(streamTexture.textureId());
        streamTexture.texture().close();

        StreamTexture resized = new StreamTexture(
            streamTexture.textureId(),
            new DynamicTexture(() -> "bitcam/" + streamerId, width, height, false),
            width,
            height
        );
        this.client.getTextureManager().register(resized.textureId(), resized.texture());
        this.textures.put(streamerId, resized);
        return resized;
    }

    private StreamTexture getWhiteTexture() {
        if (this.whiteTexture != null) {
            return this.whiteTexture;
        }

        ResourceLocation textureId = ResourceLocation.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "stream/white");
        DynamicTexture texture = new DynamicTexture(() -> "bitcam/white", 1, 1, false);
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, 0xFFFFFFFF);
        texture.setPixels(image);
        texture.upload();
        this.client.getTextureManager().register(textureId, texture);
        this.whiteTexture = new StreamTexture(textureId, texture);
        return this.whiteTexture;
    }

    private StreamTexture getShapeTexture(BitCamBubbleShape shape) {
        if (shape == BitCamBubbleShape.CIRCLE) {
            if (this.circleMaskTexture != null) {
                return this.circleMaskTexture;
            }

            ResourceLocation textureId = ResourceLocation.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "stream/circle-mask");
            DynamicTexture texture = new DynamicTexture(() -> "bitcam/circle-mask", 64, 64, true);
            NativeImage image = new NativeImage(64, 64, true);
            int radiusSquared = 31 * 31;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int dx = x - 31;
                    int dy = y - 31;
                    image.setPixel(x, y, (dx * dx + dy * dy) <= radiusSquared ? 0xFFFFFFFF : 0x00FFFFFF);
                }
            }
            texture.setPixels(image);
            texture.upload();
            this.client.getTextureManager().register(textureId, texture);
            this.circleMaskTexture = new StreamTexture(textureId, texture);
            return this.circleMaskTexture;
        }

        return this.getWhiteTexture();
    }

    private void applyCircleMask(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        float radius = Math.min(width, height) * 0.5F;
        float centerX = width * 0.5F;
        float centerY = height * 0.5F;
        float radiusSquared = radius * radius;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float dx = (x + 0.5F) - centerX;
                float dy = (y + 0.5F) - centerY;
                if ((dx * dx) + (dy * dy) <= radiusSquared) {
                    continue;
                }

                image.setPixel(x, y, image.getPixel(x, y) & 0x00FFFFFF);
            }
        }
    }

    private void applyPlayerLayerOrientation(PoseStack matrices, BitCamBubbleRenderMode renderMode, float bodyYaw, float viewYaw, float pitch) {
        matrices.mulPose(Axis.YP.rotationDegrees(bodyYaw - 180.0F));

        switch (renderMode) {
            case BILLBOARD -> matrices.mulPose(this.client.gameRenderer.getMainCamera().rotation());
            case VERTICAL_BILLBOARD -> matrices.mulPose(Axis.YP.rotationDegrees(180.0F - this.client.gameRenderer.getMainCamera().getYRot()));
            case HORIZONTAL_BILLBOARD -> {
                matrices.mulPose(Axis.YP.rotationDegrees(180.0F - this.client.gameRenderer.getMainCamera().getYRot()));
                matrices.mulPose(Axis.XP.rotationDegrees(90.0F));
            }
            case HEAD_LOCKED -> {
                matrices.mulPose(Axis.YP.rotationDegrees(180.0F - viewYaw));
                matrices.mulPose(Axis.XP.rotationDegrees(pitch));
            }
        }
    }

    private void applyFreeOrientation(PoseStack matrices, AbstractClientPlayer player, BitCamBubbleRenderMode renderMode) {
        switch (renderMode) {
            case BILLBOARD -> matrices.mulPose(this.client.gameRenderer.getMainCamera().rotation());
            case VERTICAL_BILLBOARD -> matrices.mulPose(Axis.YP.rotationDegrees(180.0F - this.client.gameRenderer.getMainCamera().getYRot()));
            case HORIZONTAL_BILLBOARD -> {
                matrices.mulPose(Axis.YP.rotationDegrees(180.0F - this.client.gameRenderer.getMainCamera().getYRot()));
                matrices.mulPose(Axis.XP.rotationDegrees(90.0F));
            }
            case HEAD_LOCKED -> {
                matrices.mulPose(Axis.YP.rotationDegrees(180.0F - player.getYHeadRot()));
                matrices.mulPose(Axis.XP.rotationDegrees(player.getXRot()));
            }
        }
    }

    private void addQuad(VertexConsumer consumer, PoseStack.Pose pose, float halfWidth, float halfHeight, float z, int argb, boolean flipVerticalUv, boolean flipHorizontalUv) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        float leftU = flipHorizontalUv ? 1.0F : 0.0F;
        float rightU = flipHorizontalUv ? 0.0F : 1.0F;
        float bottomV = flipVerticalUv ? 0.0F : 1.0F;
        float topV = flipVerticalUv ? 1.0F : 0.0F;

        consumer.addVertex(pose, -halfWidth, -halfHeight, z)
            .setColor(red, green, blue, alpha)
            .setUv(leftU, bottomV)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightTexture.FULL_BRIGHT)
            .setNormal(pose, 0.0F, 0.0F, -1.0F);
        consumer.addVertex(pose, halfWidth, -halfHeight, z)
            .setColor(red, green, blue, alpha)
            .setUv(rightU, bottomV)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightTexture.FULL_BRIGHT)
            .setNormal(pose, 0.0F, 0.0F, -1.0F);
        consumer.addVertex(pose, halfWidth, halfHeight, z)
            .setColor(red, green, blue, alpha)
            .setUv(rightU, topV)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightTexture.FULL_BRIGHT)
            .setNormal(pose, 0.0F, 0.0F, -1.0F);
        consumer.addVertex(pose, -halfWidth, halfHeight, z)
            .setColor(red, green, blue, alpha)
            .setUv(leftU, topV)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightTexture.FULL_BRIGHT)
            .setNormal(pose, 0.0F, 0.0F, -1.0F);
    }

    private static NativeImage decodePayload(byte[] payload, BitCamBubbleStyle style) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
            BufferedImage bufferedImage = ImageIO.read(input);
            if (bufferedImage == null) {
                throw new IOException("BitCam stream frame could not be decoded");
            }

            bufferedImage = BitCamBubbleContentLayout.prepareImage(bufferedImage, style);
            NativeImage image = new NativeImage(bufferedImage.getWidth(), bufferedImage.getHeight(), true);
            for (int y = 0; y < bufferedImage.getHeight(); y++) {
                for (int x = 0; x < bufferedImage.getWidth(); x++) {
                    image.setPixelABGR(x, y, argbToAbgr(bufferedImage.getRGB(x, y)));
                }
            }
            return image;
        }
    }

    private static int argbToAbgr(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private void cleanupUnused(Set<UUID> activeStreams) {
        Set<UUID> staleIds = new HashSet<>(this.textures.keySet());
        staleIds.removeAll(activeStreams);

        for (UUID staleId : staleIds) {
            StreamTexture texture = this.textures.remove(staleId);
            if (texture == null) {
                continue;
            }

            this.client.getTextureManager().release(texture.textureId());
            texture.texture().close();
        }
    }

    @Override
    public void close() {
        this.immediateBubbleConsumers.endBatch();
        this.cleanupUnused(Set.of());
        if (this.whiteTexture != null) {
            this.client.getTextureManager().release(this.whiteTexture.textureId());
            this.whiteTexture.texture().close();
            this.whiteTexture = null;
        }
        if (this.circleMaskTexture != null) {
            this.client.getTextureManager().release(this.circleMaskTexture.textureId());
            this.circleMaskTexture.texture().close();
            this.circleMaskTexture = null;
        }
    }

    private static final class StreamTexture {
        private final ResourceLocation textureId;
        private final DynamicTexture texture;
        private final int width;
        private final int height;
        private int lastFrameId = -1;

        private StreamTexture(ResourceLocation textureId, DynamicTexture texture) {
            this(textureId, texture, 1, 1);
        }

        private StreamTexture(ResourceLocation textureId, DynamicTexture texture, int width, int height) {
            this.textureId = textureId;
            this.texture = texture;
            this.width = width;
            this.height = height;
        }

        private ResourceLocation textureId() {
            return this.textureId;
        }

        private DynamicTexture texture() {
            return this.texture;
        }

        private int width() {
            return this.width;
        }

        private int height() {
            return this.height;
        }

        private int lastFrameId() {
            return this.lastFrameId;
        }

        private void lastFrameId(int lastFrameId) {
            this.lastFrameId = lastFrameId;
        }
    }

    private record RenderFrameData(int width, int height, BitCamBubbleStyle bubbleStyle, ResourceLocation textureId) {
    }
}
