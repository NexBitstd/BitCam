package dev.nexbit.bitcam.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleContentLayout;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleFacing;
import dev.nexbit.bitcam.clientcommon.BitCamBubblePlacement;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleVisuals;
import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.DecodedFrame;
import dev.nexbit.bitcam.clientcommon.LocalPreviewFrame;
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
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public final class BitCamBillboardRenderer implements AutoCloseable {
    private static final long FRAME_TTL_MS = 10_000L;

    private final Minecraft client;
    private final Supplier<BitCamClientCoordinator> coordinatorSupplier;
    private final ByteBufferBuilder bubbleBuffer = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private final MultiBufferSource.BufferSource bubbleConsumers = MultiBufferSource.immediate(this.bubbleBuffer);
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

    public void renderPlayerBubble(
        PoseStack matrices,
        AbstractClientPlayer player,
        float bbHeight,
        float bodyYaw,
        float viewYaw,
        float pitch,
        @Nullable ModelPart headPart
    ) {
        BitCamClientCoordinator coordinator = this.coordinatorSupplier.get();
        RemoteFrameStore frameStore = coordinator == null ? null : coordinator.frameStore();
        if (coordinator == null || frameStore == null || this.client.level == null) {
            return;
        }

        if (coordinator.isPlayerHidden(player.getUUID())) {
            return;
        }

        if (this.client.player != player && !coordinator.remotePreviewEnabled()) {
            return;
        }

        RenderFrameData frameData = this.resolvePlayerFrame(player, coordinator, frameStore);
        if (frameData == null) {
            return;
        }

        this.bubbleConsumers.endBatch();
        this.renderPlayerBubbleGeometry(
            matrices, this.bubbleConsumers,
            player, bbHeight, bodyYaw, viewYaw, pitch, headPart,
            frameData.width(), frameData.height(), frameData.bubbleStyle(), frameData.textureId()
        );
        this.bubbleConsumers.endBatch();
    }

    public void renderHudPreview(PoseStack matrices, MultiBufferSource consumers, AbstractClientPlayer player) {
        BitCamClientCoordinator coordinator = this.coordinatorSupplier.get();
        if (coordinator == null || this.client.level == null) {
            return;
        }

        if (this.client.screen != null || !this.client.options.getCameraType().isFirstPerson()) {
            return;
        }

        LocalPreviewFrame localFrame = coordinator.previewStore().frame();
        if (localFrame == null) {
            return;
        }

        StreamTexture texture = this.updateTexture(player.getUUID(), localFrame.frameId(), localFrame.payload(), coordinator.bubbleStyle());
        if (texture == null) {
            return;
        }

        BitCamBubbleStyle bubbleStyle = coordinator.bubbleStyle();
        BitCamBubbleVisuals visuals = BitCamBubbleVisuals.fromStyle(bubbleStyle);
        float aspectRatio = BitCamBubbleContentLayout.displayAspectRatio(localFrame.width(), localFrame.height(), bubbleStyle);
        float scale = bubbleStyle.scalePercent() / 100.0F;
        float halfHeight = 0.35F * scale;
        float halfWidth = halfHeight * aspectRatio;
        float backgroundPadding = 0.03F * scale;
        float borderPadding = 0.06F * scale;
        int videoAlpha = clampAlpha(bubbleStyle.opacityPercent());
        double horizontalOffset = BitCamBubblePlacement.horizontalOffset(bubbleStyle, scale);
        double verticalOffset = BitCamBubblePlacement.firstPersonVerticalOffset(bubbleStyle, scale);

        matrices.pushPose();
        this.applyFreeOrientation(matrices, player, bubbleStyle.renderMode());
        matrices.translate(horizontalOffset, verticalOffset, -1.1D);
        this.renderBubbleGeometry(matrices, consumers, bubbleStyle.shape(), halfWidth, halfHeight, borderPadding, backgroundPadding, visuals, texture.textureId(), videoAlpha, false, false);
        matrices.popPose();
    }

    @Override
    public void close() {
        this.bubbleConsumers.endBatch();
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

    private RenderFrameData resolvePlayerFrame(AbstractClientPlayer player, BitCamClientCoordinator coordinator, RemoteFrameStore frameStore) {
        DecodedFrame remoteFrame = frameStore.frame(player.getUUID());
        if (remoteFrame != null) {
            StreamTexture texture = this.uploadDecodedTexture(player.getUUID(), remoteFrame);
            if (texture != null) {
                return new RenderFrameData(remoteFrame.sourceWidth(), remoteFrame.sourceHeight(), remoteFrame.bubbleStyle(), texture.textureId());
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

    private void renderPlayerBubbleGeometry(
        PoseStack matrices,
        MultiBufferSource consumers,
        AbstractClientPlayer player,
        float bbHeight,
        float bodyYaw,
        float viewYaw,
        float pitch,
        @Nullable ModelPart headPart,
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
        int videoAlpha = clampAlpha(bubbleStyle.opacityPercent());
        double horizontalOffset = BitCamBubblePlacement.horizontalOffset(bubbleStyle, scale);

        matrices.pushPose();
        // Step 1: Position the anchor in the layer-inherited coordinate frame.
        // LivingEntityRenderer leaves the PoseStack in Y-down space (scale(-1,-1,1) applied),
        // so the existing applyPlayerLayerAnchor translations are intentionally Y-down.
        this.applyPlayerLayerAnchor(matrices, headPart, bbHeight, bubbleStyle, scale);
        // Step 2: Undo the two transforms applied by LivingEntityRenderer before renderLayers():
        //   setupRotations()  → Ry(180 - bodyRot)
        //   renderLayers()    → scale(-1, -1, 1)
        // Inverse = scale(-1,-1,1) * Ry(bodyRot - 180), restoring a clean world-space frame.
        matrices.scale(-1.0F, -1.0F, 1.0F);
        matrices.mulPose(Axis.YP.rotationDegrees(bodyYaw - 180.0F));
        // Step 3: Apply mode-specific orientation in clean world space.
        this.applyPlayerLayerOrientation(matrices, player, bubbleStyle, bodyYaw, viewYaw, pitch, scale);
        matrices.translate(horizontalOffset, 0.0D, 0.0D);
        // flipVerticalUv=false: Y is now up (scale flip undone), so no UV correction needed.
        this.renderBubbleGeometry(matrices, consumers, bubbleStyle.shape(), halfWidth, halfHeight, borderPadding, backgroundPadding, visuals, textureId, videoAlpha, false, false);
        matrices.popPose();
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
        VertexConsumer bubbleConsumer = consumers.getBuffer(RenderType.entityTranslucent(shapeTexture.textureId()));
        this.addQuad(bubbleConsumer, pose, halfWidth + borderPadding, halfHeight + borderPadding, -0.002F, visuals.borderArgb(), flipVerticalUv, flipHorizontalUv);
        this.addQuad(bubbleConsumer, pose, halfWidth + backgroundPadding, halfHeight + backgroundPadding, -0.001F, visuals.backgroundArgb(), flipVerticalUv, flipHorizontalUv);

        VertexConsumer videoConsumer = consumers.getBuffer(RenderType.entityTranslucentEmissive(textureId));
        this.addQuad(videoConsumer, pose, halfWidth, halfHeight, 0.0F, 0x00FFFFFF | (videoAlpha << 24), flipVerticalUv, flipHorizontalUv);
    }

    private void applyPlayerLayerAnchor(PoseStack matrices, @Nullable ModelPart headPart, float bbHeight, BitCamBubbleStyle bubbleStyle, float scale) {
        if (headPart != null) {
            matrices.translate(headPart.x / 16.0F, headPart.y / 16.0F, headPart.z / 16.0F);
            matrices.translate(0.0D, -BitCamBubblePlacement.headPivotVerticalOffset(bubbleStyle, scale), 0.0D);
            return;
        }

        matrices.translate(0.0D, -(bbHeight + BitCamBubblePlacement.worldVerticalOffset(bubbleStyle, scale)), 0.0D);
    }

    private void applyPlayerLayerOrientation(PoseStack matrices, AbstractClientPlayer player, BitCamBubbleStyle bubbleStyle, float bodyRot, float viewYaw, float pitch, float scale) {
        // viewYaw = renderState.yRot = head-to-body delta; absolute head world yaw = bodyRot + viewYaw.
        float headAbsoluteYaw = bodyRot + viewYaw;

        switch (bubbleStyle.renderMode()) {
            // Fully faces the viewer's position via a look-at computed from the bubble anchor and the
            // viewer, not the camera's view plane — so off-centre bubbles still turn toward the viewer.
            // Own bubble targets the real camera; remote bubbles target the local player's position.
            case BILLBOARD ->
                this.applyBillboardFacing(matrices, player, bubbleStyle, scale);
            // Horizontally fixed to the streamer's head yaw; vertically tilts toward viewer position.
            case VERTICAL_BILLBOARD -> {
                BitCamBubbleFacing facing = this.viewerFacing(player, bubbleStyle, scale);
                matrices.mulPose(Axis.YP.rotationDegrees(180.0F - headAbsoluteYaw));
                matrices.mulPose(Axis.XP.rotationDegrees(-facing.pitchDegrees() + 180.0F));
                matrices.mulPose(Axis.ZP.rotationDegrees(180.0F));
            }
            // Faces the viewer horizontally (yaw tracks viewer position); lies flat at 90°.
            case HORIZONTAL_BILLBOARD -> {
                BitCamBubbleFacing facing = this.viewerFacing(player, bubbleStyle, scale);
                matrices.mulPose(Axis.YP.rotationDegrees(180.0F - facing.yawDegrees()));
                matrices.mulPose(Axis.XP.rotationDegrees(180.0F));
                matrices.mulPose(Axis.ZP.rotationDegrees(180.0F));
            }
            // Mirrors the streamer's absolute head rotation (yaw + pitch).
            case HEAD_LOCKED -> {
                matrices.mulPose(Axis.YP.rotationDegrees(180.0F - headAbsoluteYaw));
                matrices.mulPose(Axis.XP.rotationDegrees(-pitch + 180.0F));
                matrices.mulPose(Axis.ZP.rotationDegrees(180.0F));

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

    private void applyBillboardFacing(PoseStack matrices, AbstractClientPlayer player, BitCamBubbleStyle bubbleStyle, float scale) {
        Vec3 anchor = this.bubbleAnchorPosition(player, bubbleStyle, scale);
        Vec3 viewer = this.billboardViewerPosition(player);
        double dx = anchor.x - viewer.x;
        double dy = anchor.y - viewer.y;
        double dz = anchor.z - viewer.z;
        double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));
        float yaw = (float) Math.atan2(-dx, dz);
        float pitch = (float) Math.atan2(-dy, horizontalDistance);
        // Reproduce Camera#rotation() for a virtual camera placed at the viewer and aimed at the
        // bubble, so the quad squarely faces the viewer's position in clean world space.
        matrices.mulPose(new Quaternionf().rotationYXZ((float) Math.PI - yaw, -pitch, 0.0F));
    }

    private BitCamBubbleFacing viewerFacing(AbstractClientPlayer player, BitCamBubbleStyle bubbleStyle, float scale) {
        Vec3 anchor = this.bubbleAnchorPosition(player, bubbleStyle, scale);
        Vec3 viewer = this.billboardViewerPosition(player);
        return BitCamBubbleFacing.faceViewer(anchor.x, anchor.y, anchor.z, viewer.x, viewer.y, viewer.z);
    }

    private Vec3 bubbleAnchorPosition(AbstractClientPlayer player, BitCamBubbleStyle bubbleStyle, float scale) {
        return new Vec3(
            player.getX(),
            player.getY() + player.getBbHeight() + BitCamBubblePlacement.worldVerticalOffset(bubbleStyle, scale),
            player.getZ()
        );
    }

    private Vec3 billboardViewerPosition(AbstractClientPlayer player) {
        // The local player's own bubble always targets the real camera, so it stays readable in
        // third person where the camera sits offset behind the body.
        if (this.client.player == null || this.client.player == player) {
            return this.client.gameRenderer.getMainCamera().getPosition();
        }
        // Remote bubbles target the local player's position (not the camera), so they stay aimed at
        // "me" instead of swinging while the third-person camera is orbited.
        return this.client.player.getEyePosition();
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

        consumer.addVertex(pose, -halfWidth, -halfHeight, z).setColor(red, green, blue, alpha).setUv(leftU, bottomV).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, -1.0F);
        consumer.addVertex(pose, halfWidth, -halfHeight, z).setColor(red, green, blue, alpha).setUv(rightU, bottomV).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, -1.0F);
        consumer.addVertex(pose, halfWidth, halfHeight, z).setColor(red, green, blue, alpha).setUv(rightU, topV).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, -1.0F);
        consumer.addVertex(pose, -halfWidth, halfHeight, z).setColor(red, green, blue, alpha).setUv(leftU, topV).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, -1.0F);
    }

    private StreamTexture uploadDecodedTexture(UUID streamerId, DecodedFrame frame) {
        StreamTexture streamTexture = this.textures.computeIfAbsent(streamerId, this::createTexture);

        if (streamTexture.lastFrameId() == frame.frameId()) {
            return streamTexture;
        }

        // Pixels were decoded, cropped and masked off-thread — here we only copy them into a
        // NativeImage and upload, so the render thread never touches JPEG/H.264 decoding.
        streamTexture = this.ensureTextureSize(streamerId, streamTexture, frame.pixelWidth(), frame.pixelHeight());
        NativeImage image = new NativeImage(frame.pixelWidth(), frame.pixelHeight(), true);
        int[] abgr = frame.abgrPixels();
        for (int y = 0; y < frame.pixelHeight(); y++) {
            for (int x = 0; x < frame.pixelWidth(); x++) {
                image.setPixelABGR(x, y, abgr[(y * frame.pixelWidth()) + x]);
            }
        }

        NativeImage previous = streamTexture.texture().getPixels();
        if (previous != null) {
            previous.close();
        }
        streamTexture.texture().setPixels(image);
        streamTexture.texture().upload();
        streamTexture.lastFrameId(frame.frameId());
        return streamTexture;
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
            NativeImage previous = streamTexture.texture().getPixels();
            if (previous != null) {
                previous.close();
            }
            streamTexture.texture().setPixels(image);
            streamTexture.texture().upload();
            streamTexture.lastFrameId(frameId);
            return streamTexture;
        } catch (IOException exception) {
            return null;
        }
    }

    private StreamTexture createTexture(UUID streamerId) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "stream/" + streamerId);
        DynamicTexture texture = new DynamicTexture(() -> "bitcam/" + streamerId, 1, 1, false);
        this.client.getTextureManager().register(id, texture);
        return new StreamTexture(id, texture);
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

        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "stream/white");
        DynamicTexture texture = new DynamicTexture(() -> "bitcam/white", 1, 1, false);
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, 0xFFFFFFFF);
        texture.setPixels(image);
        texture.upload();
        this.client.getTextureManager().register(id, texture);
        this.whiteTexture = new StreamTexture(id, texture);
        return this.whiteTexture;
    }

    private StreamTexture getShapeTexture(BitCamBubbleShape shape) {
        if (shape != BitCamBubbleShape.CIRCLE) {
            return this.getWhiteTexture();
        }

        if (this.circleMaskTexture != null) {
            return this.circleMaskTexture;
        }

        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "stream/circle-mask");
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
        this.client.getTextureManager().register(id, texture);
        this.circleMaskTexture = new StreamTexture(id, texture);
        return this.circleMaskTexture;
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
                if ((dx * dx) + (dy * dy) > radiusSquared) {
                    image.setPixel(x, y, image.getPixel(x, y) & 0x00FFFFFF);
                }
            }
        }
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

    private static int clampAlpha(int opacityPercent) {
        return Math.max(0, Math.min(255, Math.round(255.0F * (opacityPercent / 100.0F))));
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

        private ResourceLocation textureId() { return this.textureId; }
        private DynamicTexture texture() { return this.texture; }
        private int width() { return this.width; }
        private int height() { return this.height; }
        private int lastFrameId() { return this.lastFrameId; }
        private void lastFrameId(int lastFrameId) { this.lastFrameId = lastFrameId; }
    }

    private record RenderFrameData(int width, int height, BitCamBubbleStyle bubbleStyle, ResourceLocation textureId) {}
}
