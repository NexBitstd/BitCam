package dev.nexbit.bitcam.fabric.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleContentLayout;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleVisuals;
import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.BitCamPreviewSession;
import dev.nexbit.bitcam.clientcommon.CameraCaptureMode;
import dev.nexbit.bitcam.clientcommon.CameraDeviceInfo;
import dev.nexbit.bitcam.clientcommon.LocalPreviewFrame;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleContentMode;
import dev.nexbit.bitcam.protocol.udp.BitCamBubblePreset;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleRenderMode;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleShape;
import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

public final class BitCamSettingsScreen extends Screen {
    private static final int CONTENT_TOP = 66;
    private static final int SIDE_PADDING = 18;
    private static final int ROW_HEIGHT = 22;

    private final Screen parent;
    private final Minecraft client;
    private final BitCamClientCoordinator coordinator;
    private final BitCamPreviewSession previewSession = new BitCamPreviewSession();
    private final PreviewTexture previewTexture;

    private Tab activeTab = Tab.CAMERA;
    private List<CameraDeviceInfo> cameras = List.of();
    private List<CameraCaptureMode> supportedCameraModes = List.of();
    private List<CameraCaptureMode> cameraModeValues = List.of(CameraCaptureMode.AUTO);
    private Button toggleStreamingButton;
    private PlayerCameraList playerCameraList;
    private int listRefreshCooldown;

    public BitCamSettingsScreen(Screen parent, BitCamClientCoordinator coordinator) {
        super(Component.translatable("screen.bitcam.settings"));
        this.parent = parent;
        this.client = Minecraft.getInstance();
        this.coordinator = coordinator;
        this.previewTexture = new PreviewTexture(this.client);
    }

    @Override
    protected void init() {
        this.cameras = this.coordinator.cameras();
        this.supportedCameraModes = this.coordinator.cameraModes(this.coordinator.selectedCameraId());
        this.cameraModeValues = this.availableCameraModes();
        this.clearWidgets();
        this.addCommonWidgets();

        switch (this.activeTab) {
            case CAMERA -> this.addCameraTabWidgets();
            case BUBBLE -> this.addBubbleTabWidgets();
            case PLAYERS -> this.addPlayersTabWidgets();
        }

        this.refreshPreviewSource();
    }

    @Override
    public void tick() {
        if (this.toggleStreamingButton != null) {
            this.toggleStreamingButton.setMessage(this.streamingLabel());
        }

        if (this.activeTab == Tab.PLAYERS && this.playerCameraList != null) {
            if (this.listRefreshCooldown-- <= 0) {
                this.playerCameraList.refreshEntries();
                this.listRefreshCooldown = 20;
            }
        }
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    @Override
    public void removed() {
        this.previewSession.close();
        this.previewTexture.close();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderActiveTabBackdrop(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawString(this.font, this.title, SIDE_PADDING, 18, 0xFFFFFF, true);
        guiGraphics.drawString(this.font, this.tabDescription(), SIDE_PADDING, 34, 0xA9B2BE, false);

        if (this.activeTab == Tab.CAMERA) {
            this.renderInitialSetupPrompt(guiGraphics);
            this.renderCameraStatus(guiGraphics);
        } else if (this.activeTab == Tab.PLAYERS && (this.playerCameraList == null || this.playerCameraList.children().isEmpty())) {
            this.renderPlayersEmptyState(guiGraphics);
        }
    }

    private void renderActiveTabBackdrop(GuiGraphics guiGraphics) {
        if (this.activeTab == Tab.CAMERA || this.activeTab == Tab.BUBBLE) {
            this.renderPreviewPanel(guiGraphics);
            return;
        }

        if (this.activeTab == Tab.PLAYERS) {
            this.renderPlayersPanel(guiGraphics);
        }
    }

    private void addCommonWidgets() {
        int buttonY = 42;
        int tabWidth = 92;
        int buttonX = SIDE_PADDING;

        for (Tab tab : Tab.values()) {
            boolean active = tab == this.activeTab;
            Button button = Button.builder(tab.label(), ignored -> {
                this.activeTab = tab;
                this.init();
            }).bounds(buttonX, buttonY, tabWidth, 20).build();
            button.active = !active;
            this.addRenderableWidget(button);
            buttonX += tabWidth + 6;
        }

        this.addRenderableWidget(
            Button.builder(Component.translatable("gui.done"), ignored -> this.onClose())
                .bounds(this.width - SIDE_PADDING - 90, this.height - 28, 90, 20)
                .build()
        );
    }

    private void addCameraTabWidgets() {
        int left = SIDE_PADDING;
        int leftWidth = Math.max(220, (this.width / 2) - (SIDE_PADDING * 2));
        int y = CONTENT_TOP;

        this.addRenderableWidget(
            this.cameraCycleButton(left, y, leftWidth)
        );
        y += 30;

        this.addRenderableWidget(
            this.cameraModeCycleButton(left, y, leftWidth)
        );
        y += 30;

        this.addRenderableWidget(
            Button.builder(Component.translatable("screen.bitcam.camera.refresh"), ignored -> {
                this.cameras = this.coordinator.refreshCameras();
                this.init();
            }).bounds(left, y, leftWidth, 20).build()
        );
        y += 30;

        this.toggleStreamingButton = this.addRenderableWidget(
            Button.builder(this.streamingLabel(), ignored -> {
                this.toggleStreaming();
                this.init();
            }).bounds(left, y, leftWidth, 20).build()
        );
        this.toggleStreamingButton.active = !this.cameras.isEmpty();
        y += 30;

        Button previewButton = Button.builder(Component.translatable("screen.bitcam.camera.preview_restart"), ignored -> {
            this.refreshPreviewSource();
        }).bounds(left, y, leftWidth, 20).build();
        previewButton.active = !this.coordinator.streamingEnabled() && !this.cameras.isEmpty();
        this.addRenderableWidget(previewButton);
    }

    private void addBubbleTabWidgets() {
        int left = SIDE_PADDING;
        int leftWidth = Math.max(220, (this.width / 2) - (SIDE_PADDING * 2));
        int y = CONTENT_TOP;
        BitCamBubbleStyle style = this.coordinator.bubbleStyle();

        this.addRenderableWidget(
            CycleButton.<BitCamBubblePreset>builder(preset -> Component.translatable("screen.bitcam.bubble.preset." + preset.name().toLowerCase()))
                .withValues(List.of(BitCamBubblePreset.values()))
                .withInitialValue(style.preset())
                .create(left, y, leftWidth, 20, Component.translatable("screen.bitcam.bubble.preset"), (button, value) -> {
                    this.updateBubbleStyle(this.coordinator.bubbleStyle().withPreset(value));
                })
        );
        y += 30;

        this.addRenderableWidget(
            CycleButton.<BitCamBubbleShape>builder(shape -> Component.translatable("screen.bitcam.bubble.shape." + shape.name().toLowerCase()))
                .withValues(List.of(BitCamBubbleShape.values()))
                .withInitialValue(style.shape())
                .create(left, y, leftWidth, 20, Component.translatable("screen.bitcam.bubble.shape"), (button, value) -> {
                    this.updateBubbleStyle(this.coordinator.bubbleStyle().withShape(value));
                })
        );
        y += 30;

        this.addRenderableWidget(
            CycleButton.<BitCamBubbleRenderMode>builder(mode -> Component.translatable("screen.bitcam.bubble.mode." + mode.name().toLowerCase()))
                .withValues(List.of(BitCamBubbleRenderMode.values()))
                .withInitialValue(style.renderMode())
                .create(left, y, leftWidth, 20, Component.translatable("screen.bitcam.bubble.mode"), (button, value) -> {
                    this.updateBubbleStyle(this.coordinator.bubbleStyle().withRenderMode(value));
                })
        );
        y += 30;

        this.addRenderableWidget(
            CycleButton.<BitCamBubbleContentMode>builder(mode -> Component.translatable("screen.bitcam.bubble.fill." + mode.name().toLowerCase()))
                .withValues(List.of(BitCamBubbleContentMode.values()))
                .withInitialValue(style.contentMode())
                .create(left, y, leftWidth, 20, Component.translatable("screen.bitcam.bubble.fill"), (button, value) -> {
                    this.updateBubbleStyle(this.coordinator.bubbleStyle().withContentMode(value));
                })
        );
        y += 30;

        this.addRenderableWidget(
            new PercentSlider(
                left,
                y,
                leftWidth,
                Component.translatable("screen.bitcam.bubble.scale"),
                style.scalePercent(),
                60,
                180,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withScalePercent(value))
            )
        );
        y += 28;

        this.addRenderableWidget(
            new PercentSlider(
                left,
                y,
                leftWidth,
                Component.translatable("screen.bitcam.bubble.offset_horizontal"),
                style.xOffsetPercent(),
                -BitCamBubbleStyle.BUBBLE_OFFSET_MAX,
                BitCamBubbleStyle.BUBBLE_OFFSET_MAX,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withXOffsetPercent(value))
            )
        );
        y += 28;

        this.addRenderableWidget(
            new PercentSlider(
                left,
                y,
                leftWidth,
                Component.translatable("screen.bitcam.bubble.offset_vertical"),
                style.yOffsetPercent(),
                -BitCamBubbleStyle.BUBBLE_OFFSET_MAX,
                BitCamBubbleStyle.BUBBLE_OFFSET_MAX,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withYOffsetPercent(value))
            )
        );
        y += 28;

        this.addRenderableWidget(
            new PercentSlider(
                left,
                y,
                leftWidth,
                Component.translatable("screen.bitcam.bubble.opacity"),
                style.opacityPercent(),
                35,
                100,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withOpacityPercent(value))
            )
        );
        y += 28;

        this.addRenderableWidget(
            new PercentSlider(
                left,
                y,
                leftWidth,
                Component.translatable("screen.bitcam.bubble.camera_zoom"),
                style.contentZoomPercent(),
                100,
                250,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withContentZoomPercent(value))
            )
        );
        y += 28;

        this.addRenderableWidget(
            new PercentSlider(
                left,
                y,
                leftWidth,
                Component.translatable("screen.bitcam.bubble.camera_offset_horizontal"),
                style.contentXOffsetPercent(),
                0,
                200,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withContentXOffsetPercent(value))
            )
        );
        y += 28;

        this.addRenderableWidget(
            new PercentSlider(
                left,
                y,
                leftWidth,
                Component.translatable("screen.bitcam.bubble.camera_offset_vertical"),
                style.contentYOffsetPercent(),
                0,
                200,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withContentYOffsetPercent(value))
            )
        );
    }

    private void addPlayersTabWidgets() {
        int listLeft = SIDE_PADDING + 18;
        int listTop = CONTENT_TOP + 44;
        int listWidth = this.width - (SIDE_PADDING * 2) - 36;
        int listBottom = this.height - 48;

        this.playerCameraList = this.addRenderableWidget(new PlayerCameraList(this.client, listLeft, listWidth, listBottom, listTop, ROW_HEIGHT));
        this.playerCameraList.refreshEntries();
        this.listRefreshCooldown = 20;
    }

    private CycleButton<CameraDeviceInfo> cameraCycleButton(int x, int y, int width) {
        List<CameraDeviceInfo> values = this.cameras.isEmpty()
            ? List.of(new CameraDeviceInfo("", Component.translatable("screen.bitcam.camera.none").getString()))
            : this.cameras;

        CameraDeviceInfo selected = values.getFirst();
        String selectedId = this.coordinator.selectedCameraId();
        for (CameraDeviceInfo camera : values) {
            if (camera.id().equals(selectedId)) {
                selected = camera;
                break;
            }
        }

        CycleButton<CameraDeviceInfo> button = CycleButton.<CameraDeviceInfo>builder(camera -> Component.literal(camera.name()))
            .withValues(values)
            .withInitialValue(selected)
            .create(x, y, width, 20, Component.translatable("screen.bitcam.camera.select"), (cycleButton, value) -> {
                if (this.cameras.isEmpty()) {
                    return;
                }

                this.coordinator.selectCamera(value);
                this.init();
            });
        button.active = !this.cameras.isEmpty();
        return button;
    }

    private CycleButton<CameraCaptureMode> cameraModeCycleButton(int x, int y, int width) {
        CameraCaptureMode selected = this.coordinator.selectedCaptureMode();
        if (!this.cameraModeValues.contains(selected)) {
            selected = CameraCaptureMode.AUTO;
        }

        CycleButton<CameraCaptureMode> button = CycleButton.<CameraCaptureMode>builder(this::cameraModeLabel)
            .withValues(this.cameraModeValues)
            .withInitialValue(selected)
            .create(x, y, width, 20, Component.translatable("screen.bitcam.camera.mode"), (cycleButton, value) -> {
                this.coordinator.selectCaptureMode(value);
                if (!this.coordinator.streamingEnabled()) {
                    this.previewSession.restart(this.coordinator.selectedCameraId(), value);
                }
            });
        button.active = !this.cameras.isEmpty();
        return button;
    }

    private void refreshPreviewSource() {
        if (this.coordinator.streamingEnabled()) {
            this.previewSession.stop();
            return;
        }

        this.previewSession.restart(this.coordinator.selectedCameraId(), this.coordinator.selectedCaptureMode());
    }

    private void toggleStreaming() {
        boolean wasStreaming = this.coordinator.streamingEnabled();
        if (!wasStreaming) {
            this.previewSession.stop();
        }

        this.coordinator.toggleStreaming();

        if (wasStreaming) {
            this.refreshPreviewSource();
        }
    }

    private void updateBubbleStyle(BitCamBubbleStyle style) {
        this.coordinator.updateBubbleStyle(style);
    }

    private LocalPreviewFrame currentPreviewFrame() {
        if (this.coordinator.streamingEnabled()) {
            return this.coordinator.previewStore().frame();
        }

        return this.previewSession.previewStore().frame();
    }

    private void renderPreviewPanel(GuiGraphics guiGraphics) {
        int panelX = (this.width / 2) + 12;
        int panelY = CONTENT_TOP;
        int panelWidth = this.width - panelX - SIDE_PADDING;
        int panelHeight = this.height - panelY - 38;

        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0x7A11151A);
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xCC344250);
        guiGraphics.drawString(this.font, Component.translatable("screen.bitcam.preview.title"), panelX + 10, panelY + 10, 0xFFFFFF, true);
        guiGraphics.drawString(this.font, Component.translatable("screen.bitcam.preview.subtitle"), panelX + 10, panelY + 24, 0xA9B2BE, false);

        LocalPreviewFrame frame = this.currentPreviewFrame();
        BitCamBubbleStyle style = this.coordinator.bubbleStyle();
        BitCamBubbleVisuals visuals = BitCamBubbleVisuals.fromStyle(style);

        int previewAreaX = panelX + 18;
        int previewAreaY = panelY + 44;
        int previewAreaWidth = panelWidth - 36;
        int previewAreaHeight = panelHeight - 62;
        int leftPanelWidth = Math.max(220, (this.width / 2) - (SIDE_PADDING * 2));

        guiGraphics.fill(SIDE_PADDING - 2, CONTENT_TOP - 4, SIDE_PADDING + leftPanelWidth + 2, this.height - 38, 0x6610151D);
        guiGraphics.fill(SIDE_PADDING - 2, CONTENT_TOP - 4, SIDE_PADDING + leftPanelWidth + 2, CONTENT_TOP - 3, 0xAA344250);

        guiGraphics.fill(previewAreaX, previewAreaY, previewAreaX + previewAreaWidth, previewAreaY + previewAreaHeight, 0x331A2128);

        float aspectRatio;
        if (style.shape() == BitCamBubbleShape.CIRCLE || style.shape() == BitCamBubbleShape.SQUARE) {
            aspectRatio = 1.0F;
        } else {
            aspectRatio = frame != null ? BitCamBubbleContentLayout.displayAspectRatio(frame.width(), frame.height(), style) : 16.0F / 9.0F;
        }
        int baseHeight = Math.min(previewAreaHeight - 32, 112);
        int bubbleHeight = Math.max(52, Math.round(baseHeight * (style.scalePercent() / 100.0F)));
        int bubbleWidth = Math.round(bubbleHeight * aspectRatio);
        if (bubbleWidth > previewAreaWidth - 30) {
            float shrink = (previewAreaWidth - 30) / (float) bubbleWidth;
            bubbleWidth = Math.round(bubbleWidth * shrink);
            bubbleHeight = Math.round(bubbleHeight * shrink);
        }

        float bubbleOffsetScale = 1.0F / BitCamBubbleStyle.BUBBLE_OFFSET_MAX;
        float horizontalTravel = Math.max(56.0F, previewAreaWidth * 0.20F);
        float verticalTravel = Math.max(120.0F, previewAreaHeight * 0.44F);
        int lift = Math.round((style.yOffsetPercent() * bubbleOffsetScale) * verticalTravel);
        int bubbleX = previewAreaX + (previewAreaWidth - bubbleWidth) / 2 + Math.round((style.xOffsetPercent() * bubbleOffsetScale) * horizontalTravel);
        int bubbleY = previewAreaY + (previewAreaHeight - bubbleHeight) / 2 - lift;
        bubbleX = Math.max(previewAreaX + 8, Math.min(previewAreaX + previewAreaWidth - bubbleWidth - 8, bubbleX));
        bubbleY = Math.max(previewAreaY + 8, Math.min(previewAreaY + previewAreaHeight - bubbleHeight - 8, bubbleY));

        guiGraphics.fill(bubbleX - 7, bubbleY - 7, bubbleX + bubbleWidth + 7, bubbleY + bubbleHeight + 7, visuals.borderArgb());
        guiGraphics.fill(bubbleX - 3, bubbleY - 3, bubbleX + bubbleWidth + 3, bubbleY + bubbleHeight + 3, visuals.backgroundArgb());

        if (frame != null) {
            ResourceLocation textureId = this.previewTexture.update(frame, style);
            if (textureId != null) {
                guiGraphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    textureId,
                    bubbleX,
                    bubbleY,
                    0.0F,
                    0.0F,
                    bubbleWidth,
                    bubbleHeight,
                    this.previewTexture.width(),
                    this.previewTexture.height(),
                    this.previewTexture.width(),
                    this.previewTexture.height()
                );
                int opacityOverlay = Math.round(255.0F * (1.0F - (style.opacityPercent() / 100.0F)) * 0.7F);
                if (opacityOverlay > 0) {
                    guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, opacityOverlay << 24);
                }
            } else {
                guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, 0x5512161C);
                guiGraphics.drawCenteredString(this.font, Component.translatable("screen.bitcam.preview.unavailable"), bubbleX + (bubbleWidth / 2), bubbleY + (bubbleHeight / 2) - 4, 0xD2D8DF);
            }
        } else {
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight, 0x5512161C);
            guiGraphics.drawCenteredString(this.font, Component.translatable("screen.bitcam.preview.unavailable"), bubbleX + (bubbleWidth / 2), bubbleY + (bubbleHeight / 2) - 4, 0xD2D8DF);
        }

        int opacityLabelY = previewAreaY + previewAreaHeight - 12;
        guiGraphics.drawString(
            this.font,
            Component.translatable(
                "screen.bitcam.preview.meta",
                this.coordinator.bubbleStyle().preset().name(),
                this.coordinator.bubbleStyle().shape().name(),
                this.coordinator.bubbleStyle().renderMode().name(),
                this.coordinator.bubbleStyle().opacityPercent() + "%"
            ),
            previewAreaX,
            opacityLabelY,
            0x8F9AA7,
            false
        );
    }

    private void renderPlayersPanel(GuiGraphics guiGraphics) {
        int panelX = SIDE_PADDING;
        int panelY = CONTENT_TOP;
        int panelWidth = this.width - (SIDE_PADDING * 2);
        int panelHeight = this.height - panelY - 38;

        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0x7A11151A);
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xCC344250);
        guiGraphics.drawString(this.font, Component.translatable("screen.bitcam.players.panel_title"), panelX + 12, panelY + 10, 0xFFFFFF, true);
        guiGraphics.drawString(this.font, Component.translatable("screen.bitcam.players.panel_subtitle"), panelX + 12, panelY + 24, 0xA9B2BE, false);
    }

    private void renderInitialSetupPrompt(GuiGraphics guiGraphics) {
        if (!this.coordinator.needsInitialSetup()) {
            return;
        }

        int left = SIDE_PADDING;
        int width = Math.max(220, (this.width / 2) - (SIDE_PADDING * 2));
        int top = CONTENT_TOP + 154;
        int bottom = top + 54;
        guiGraphics.fill(left, top, left + width, bottom, 0x7732491B);
        guiGraphics.fill(left, top, left + width, top + 1, 0xFFD59A3D);
        guiGraphics.drawString(this.font, Component.translatable("screen.bitcam.setup.title"), left + 10, top + 8, 0xFFF2D6, true);

        int textY = top + 22;
        for (FormattedCharSequence line : this.font.split(Component.translatable("screen.bitcam.setup.subtitle"), width - 20)) {
            guiGraphics.drawString(this.font, line, left + 10, textY, 0xFFE9C48A, false);
            textY += 10;
        }
    }

    private void renderCameraStatus(GuiGraphics guiGraphics) {
        String statusMessage = this.coordinator.cameraStatusMessage();
        if (statusMessage.isBlank() && !this.cameras.isEmpty()) {
            return;
        }

        if (statusMessage.isBlank()) {
            statusMessage = Component.translatable("screen.bitcam.camera.none").getString();
        }

        int left = SIDE_PADDING;
        int top = this.coordinator.needsInitialSetup() ? CONTENT_TOP + 214 : CONTENT_TOP + 152;
        int width = Math.max(220, (this.width / 2) - (SIDE_PADDING * 2));
        int color = this.cameras.isEmpty() ? 0xE0A7AB : 0xA9B2BE;

        for (FormattedCharSequence line : this.font.split(Component.literal(statusMessage), width)) {
            guiGraphics.drawString(this.font, line, left, top, color, false);
            top += 10;
        }
    }

    private void renderPlayersEmptyState(GuiGraphics guiGraphics) {
        int panelX = SIDE_PADDING;
        int panelY = CONTENT_TOP;
        int panelWidth = this.width - (SIDE_PADDING * 2);
        int panelHeight = this.height - panelY - 38;
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("screen.bitcam.players.empty"),
            panelX + (panelWidth / 2),
            panelY + (panelHeight / 2),
            0xA9B2BE
        );
    }

    private Component streamingLabel() {
        return Component.translatable(
            this.coordinator.streamingEnabled() ? "screen.bitcam.camera.streaming_disable" : "screen.bitcam.camera.streaming_enable"
        );
    }

    private Component tabDescription() {
        return switch (this.activeTab) {
            case CAMERA -> Component.translatable("screen.bitcam.camera.description");
            case BUBBLE -> Component.translatable("screen.bitcam.bubble.description");
            case PLAYERS -> Component.translatable("screen.bitcam.players.description");
        };
    }

    private List<CameraCaptureMode> availableCameraModes() {
        ArrayList<CameraCaptureMode> values = new ArrayList<>();
        values.add(CameraCaptureMode.AUTO);
        for (CameraCaptureMode mode : this.supportedCameraModes) {
            if (mode != null && mode.isSpecified() && !values.contains(mode)) {
                values.add(mode);
            }
        }

        CameraCaptureMode selected = this.coordinator.selectedCaptureMode();
        if (selected != null && selected.isSpecified() && !values.contains(selected)) {
            values.add(1, selected);
        }
        return List.copyOf(values);
    }

    private Component cameraModeLabel(CameraCaptureMode mode) {
        if (mode == null || mode.isAuto()) {
            return Component.translatable("screen.bitcam.camera.mode.auto");
        }

        if (!this.supportedCameraModes.contains(mode)) {
            return Component.translatable("screen.bitcam.camera.mode.unsupported", mode.label());
        }

        return Component.literal(mode.label());
    }

    private enum Tab {
        CAMERA("screen.bitcam.tab.camera"),
        BUBBLE("screen.bitcam.tab.bubble"),
        PLAYERS("screen.bitcam.tab.players");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }

        private Component label() {
            return Component.translatable(this.translationKey);
        }
    }

    private interface IntValueConsumer {
        void accept(int value);
    }

    private static final class PercentSlider extends AbstractSliderButton {
        private final Component label;
        private final int minValue;
        private final int maxValue;
        private final IntValueConsumer consumer;

        private PercentSlider(int x, int y, int width, Component label, int initialValue, int minValue, int maxValue, IntValueConsumer consumer) {
            super(x, y, width, 20, Component.empty(), (initialValue - minValue) / (double) (maxValue - minValue));
            this.label = label;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.consumer = consumer;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(this.label.getString() + ": " + this.currentValue() + "%"));
        }

        @Override
        protected void applyValue() {
            this.consumer.accept(this.currentValue());
            this.updateMessage();
        }

        private int currentValue() {
            return this.minValue + (int) Math.round(this.value * (this.maxValue - this.minValue));
        }
    }

    private final class PlayerCameraList extends ObjectSelectionList<PlayerCameraList.Entry> {
        private final int leftPos;

        private PlayerCameraList(Minecraft minecraft, int leftPos, int width, int bottom, int top, int itemHeight) {
            super(minecraft, width, bottom, top, itemHeight);
            this.leftPos = leftPos;
        }

        private void refreshEntries() {
            this.clearEntries();

            ClientLevel level = BitCamSettingsScreen.this.client.level;
            if (level == null || BitCamSettingsScreen.this.client.player == null) {
                return;
            }

            List<AbstractClientPlayer> players = new ArrayList<>(level.players());
            players.removeIf(player -> player.getUUID().equals(BitCamSettingsScreen.this.client.player.getUUID()));
            players.sort(Comparator.comparing(player -> player.getName().getString(), String.CASE_INSENSITIVE_ORDER));

            for (AbstractClientPlayer player : players) {
                this.addEntry(new Entry(player.getUUID(), player.getName().getString()));
            }
        }

        protected int getScrollbarPosition() {
            return this.leftPos + this.width - 6;
        }

        public int getRowWidth() {
            return this.width - 12;
        }

        public int getRowLeft() {
            return this.leftPos + 6;
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final UUID playerId;
            private final String playerName;

            private Entry(UUID playerId, String playerName) {
                this.playerId = playerId;
                this.playerName = playerName;
            }

            @Override
            public void render(
                GuiGraphics guiGraphics,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick
            ) {
                boolean hidden = BitCamSettingsScreen.this.coordinator.isPlayerHidden(this.playerId);
                int rowColor = hovering ? 0x5A283240 : 0x40202833;
                int pillColor = hidden ? 0x883B1E24 : 0x88354D2F;
                int stateColor = hidden ? 0xFFEE7F87 : 0xFF86F0A7;
                Component state = Component.translatable(hidden ? "screen.bitcam.players.hidden" : "screen.bitcam.players.visible");

                guiGraphics.fill(left, top, left + width, top + height - 1, rowColor);
                guiGraphics.drawString(BitCamSettingsScreen.this.font, this.playerName, left + 8, top + 6, 0xFFFFFF, false);

                int stateWidth = BitCamSettingsScreen.this.font.width(state);
                int pillLeft = left + width - stateWidth - 18;
                guiGraphics.fill(pillLeft - 6, top + 4, left + width - 8, top + 18, pillColor);
                guiGraphics.drawString(BitCamSettingsScreen.this.font, state, pillLeft, top + 7, stateColor, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    boolean hidden = BitCamSettingsScreen.this.coordinator.isPlayerHidden(this.playerId);
                    BitCamSettingsScreen.this.coordinator.setPlayerHidden(this.playerId, !hidden);
                    return true;
                }

                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.playerName);
            }
        }
    }

    private static final class PreviewTexture implements AutoCloseable {
        private final Minecraft client;
        private ResourceLocation textureId;
        private DynamicTexture texture;
        private int lastFrameId = -1;
        private int width = -1;
        private int height = -1;

        private PreviewTexture(Minecraft client) {
            this.client = client;
        }

        private ResourceLocation update(LocalPreviewFrame frame, BitCamBubbleStyle style) {
            if (frame == null) {
                return null;
            }

            if (this.texture == null) {
                this.textureId = ResourceLocation.fromNamespaceAndPath("bitcam", "preview/local");
                this.texture = new DynamicTexture(() -> "bitcam/preview/local", 1, 1, false);
                this.client.getTextureManager().register(this.textureId, this.texture);
            }

            if (this.lastFrameId == frame.frameId()) {
                return this.textureId;
            }

            try {
                NativeImage image = decodePayload(frame.payload(), style);
                if (style.shape() == BitCamBubbleShape.CIRCLE) {
                    this.applyCircleMask(image);
                }
                this.ensureTextureSize(image.getWidth(), image.getHeight());
                NativeImage previousImage = this.texture.getPixels();
                if (previousImage != null) {
                    previousImage.close();
                }

                this.texture.setPixels(image);
                this.texture.upload();
                this.lastFrameId = frame.frameId();
                return this.textureId;
            } catch (IOException ignored) {
                return null;
            }
        }

        private int width() {
            return Math.max(1, this.width);
        }

        private int height() {
            return Math.max(1, this.height);
        }

        private void ensureTextureSize(int width, int height) {
            if (this.texture != null && this.width == width && this.height == height) {
                return;
            }

            if (this.textureId == null) {
                this.textureId = ResourceLocation.fromNamespaceAndPath("bitcam", "preview/local");
            }

            if (this.texture != null) {
                this.client.getTextureManager().release(this.textureId);
                this.texture.close();
            }

            this.texture = new DynamicTexture(() -> "bitcam/preview/local", width, height, false);
            this.client.getTextureManager().register(this.textureId, this.texture);
            this.width = width;
            this.height = height;
            this.lastFrameId = -1;
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

        @Override
        public void close() {
            if (this.textureId != null) {
                this.client.getTextureManager().release(this.textureId);
                if (this.texture != null) {
                    this.texture.close();
                }
                this.textureId = null;
                this.texture = null;
                this.lastFrameId = -1;
                this.width = -1;
                this.height = -1;
            }
        }
    }

    private static NativeImage decodePayload(byte[] payload, BitCamBubbleStyle style) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
            BufferedImage bufferedImage = ImageIO.read(input);
            if (bufferedImage == null) {
                throw new IOException("BitCam preview frame could not be decoded");
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
}
