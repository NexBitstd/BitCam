package dev.nexbit.bitcam.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.nexbit.bitcam.clientcommon.BitCamBubblePlacement;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleContentLayout;
import dev.nexbit.bitcam.clientcommon.BitCamBubbleVisuals;
import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
import dev.nexbit.bitcam.clientcommon.BitCamPreviewSession;
import dev.nexbit.bitcam.clientcommon.CameraCaptureMode;
import dev.nexbit.bitcam.clientcommon.CameraDeviceInfo;
import dev.nexbit.bitcam.clientcommon.LocalPreviewFrame;
import dev.nexbit.bitcam.protocol.signal.BitCamStreamQualityProfile;
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
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.imageio.ImageIO;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

public final class BitCamSettingsScreen extends Screen {
    private static final int TOP_BAR_Y = 28;
    private static final int TOP_BAR_HEIGHT = 28;
    private static final int CONTENT_TOP = 92;
    private static final int SIDE_PADDING = 28;
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_SPACING = 28;
    private static final int SECTION_GAP = 18;
    private static final int TAB_WIDTH = 74;
    private static final int COLUMN_GAP = 34;
    private static final int STAGE_BOTTOM_PADDING = 70;
    private static final int LABEL_COLUMN_WIDTH = 120;
    private static final int LABEL_CONTROL_GAP = 16;

    private final Screen parent;
    private final Minecraft client;
    private final BitCamClientCoordinator coordinator;
    private final BitCamPreviewSession previewSession = new BitCamPreviewSession();
    private final PreviewTexture previewTexture;
    private final List<StyledWidget> styledWidgets = new ArrayList<>();

    private Tab activeTab = Tab.CAMERA;
    private List<CameraDeviceInfo> cameras = List.of();
    private List<CameraCaptureMode> supportedCameraModes = List.of();
    private List<CameraCaptureMode> cameraModeValues = List.of(CameraCaptureMode.AUTO);
    private Button toggleStreamingButton;
    private Button topToggleStreamingButton;
    private Button togglePreviewButton;
    private Button topTogglePreviewButton;
    private EditBox playerSearchField;
    private PlayerCameraList playerCameraList;
    private FlatSelectWidget<?> openSelect;
    private boolean waitingForCameraInitialization;
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
        this.styledWidgets.clear();
        this.toggleStreamingButton = null;
        this.topToggleStreamingButton = null;
        this.togglePreviewButton = null;
        this.topTogglePreviewButton = null;
        this.playerSearchField = null;
        this.playerCameraList = null;
        this.openSelect = null;
        this.waitingForCameraInitialization = this.coordinator.isCameraInitializing();
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
        if (this.waitingForCameraInitialization && !this.coordinator.isCameraInitializing()) {
            this.waitingForCameraInitialization = false;
            this.init();
            return;
        }

        if (this.toggleStreamingButton != null) {
            this.toggleStreamingButton.setMessage(this.streamingLabel());
            this.toggleStreamingButton.active = !this.cameras.isEmpty() && !this.coordinator.isCameraStarting();
        }
        if (this.topToggleStreamingButton != null) {
            this.topToggleStreamingButton.setMessage(this.streamingLabel());
            this.topToggleStreamingButton.active = !this.cameras.isEmpty() && !this.coordinator.isCameraStarting();
        }
        if (this.togglePreviewButton != null) {
            this.togglePreviewButton.setMessage(this.remotePreviewLabel());
        }
        if (this.topTogglePreviewButton != null) {
            this.topTogglePreviewButton.setMessage(this.remotePreviewLabel());
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
        if (!this.coordinator.streamingEnabled()) {
            this.coordinator.previewStore().clear();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderSceneBackdrop(guiGraphics, mouseX, mouseY);
        this.renderChromeBackdrop(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderStyledWidgets(guiGraphics, mouseX, mouseY);
        this.renderChromeForeground(guiGraphics);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.openSelect != null) {
            FlatSelectWidget<?> select = this.openSelect;
            if (select.handleDropdownClick(mouseX, mouseY, button)) {
                return true;
            }

            if (button == 0 && select.isMouseOver(mouseX, mouseY)) {
                select.closeDropdown();
                return true;
            }

            this.openSelect = null;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.openSelect != null && this.openSelect.handleScroll(mouseX, mouseY, verticalAmount)) {
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && this.openSelect != null) {
            this.openSelect.closeDropdown();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void addCommonWidgets() {
        int buttonY = TOP_BAR_Y + 4;

        Component bitCamLabel = Component.literal("BitCam").withStyle(ChatFormatting.BOLD);
        this.addRenderableWidget(new StringWidget(SIDE_PADDING, buttonY + 7, this.font.width(bitCamLabel), 10, bitCamLabel, this.font));

        int tabsWidth = (Tab.values().length * TAB_WIDTH) + ((Tab.values().length - 1) * 6);
        int buttonX = (this.width - tabsWidth) / 2;



        for (Tab tab : Tab.values()) {
            boolean active = tab == this.activeTab;
            Button button = Button.builder(tab.label(), ignored -> {
                this.activeTab = tab;
                this.init();
            }).bounds(buttonX, buttonY, TAB_WIDTH, 20).build();
            button.active = !active;
            this.addStyledWidget(button, Component.empty(), WidgetSkin.TAB, false);
            buttonX += TAB_WIDTH + 6;
        }

        int closeWidth = 20;
        int quickWidth = 42;
        int toggleWidth = 20;
        int quickX = this.width - SIDE_PADDING - closeWidth - 6 - quickWidth;
        int toggleX = quickX - 6 - toggleWidth;
        int previewToggleX = toggleX - 6 - toggleWidth;

        this.topTogglePreviewButton = this.addStyledWidget(
            Button.builder(this.remotePreviewLabel(), ignored -> {
                this.coordinator.toggleRemotePreview();
                this.init();
            }).bounds(previewToggleX, buttonY, toggleWidth, 20).build(),
            Component.empty(),
            WidgetSkin.ACTION,
            false,
            ResourceLocation.fromNamespaceAndPath("bitcam", "textures/gui/monitor.png"),
            this.coordinator::remotePreviewEnabled
        );

        this.topToggleStreamingButton = this.addStyledWidget(
            Button.builder(this.streamingLabel(), ignored -> {
                this.toggleStreaming();
                this.init();
            }).bounds(toggleX, buttonY, toggleWidth, 20).build(),
            Component.empty(),
            WidgetSkin.ACTION,
            false,
            ResourceLocation.fromNamespaceAndPath("bitcam", "textures/gui/camera.png"),
            this.coordinator::streamingEnabled
        );
        this.topToggleStreamingButton.active = !this.cameras.isEmpty();

        this.addStyledWidget(
            Button.builder(this.quickActionLabel(), ignored -> this.handleQuickAction())
                .bounds(quickX, buttonY, quickWidth, 20)
                .build(),
            Component.empty(),
            WidgetSkin.TOP_ACTION,
            false
        );
        this.addStyledWidget(
            Button.builder(Component.literal("X"), ignored -> this.onClose())
                .bounds(this.width - SIDE_PADDING - closeWidth, buttonY, closeWidth, 20)
                .build(),
            Component.empty(),
            WidgetSkin.TOP_ACTION,
            false
        );
    }

    private void addCameraTabWidgets() {
        int controlX = this.contentControlX();
        int controlWidth = this.contentControlWidth();
        int y = CONTENT_TOP + 22;

        this.addStyledWidget(
            this.cameraCycleButton(controlX, y, controlWidth),
            Component.translatable("screen.bitcam.camera.select"),
            WidgetSkin.VALUE,
            false
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            this.cameraModeCycleButton(controlX, y, controlWidth),
            Component.translatable("screen.bitcam.camera.mode"),
            WidgetSkin.VALUE,
            false
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            this.serverQualityCycleButton(controlX, y, controlWidth),
            Component.translatable("screen.bitcam.camera.server_quality"),
            WidgetSkin.VALUE,
            false
        );
        y += ROW_SPACING + SECTION_GAP;

        this.addStyledWidget(
            Button.builder(Component.translatable("screen.bitcam.camera.refresh"), ignored -> {
                this.cameras = this.coordinator.refreshCameras();
                this.init();
            }).bounds(controlX, y, controlWidth, 20).build(),
            Component.empty(),
            WidgetSkin.ACTION,
            false
        );
        y += ROW_SPACING;

        this.toggleStreamingButton = this.addStyledWidget(
            Button.builder(this.streamingLabel(), ignored -> {
                this.toggleStreaming();
                this.init();
            }).bounds(controlX, y, controlWidth, 20).build(),
            Component.empty(),
            WidgetSkin.ACTION,
            false,
            null,
            this.coordinator::streamingEnabled
        );
        this.toggleStreamingButton.active = !this.cameras.isEmpty();
        y += ROW_SPACING;

        Button previewButton = Button.builder(Component.translatable("screen.bitcam.camera.preview_restart"), ignored -> {
            this.refreshPreviewSource();
        }).bounds(controlX, y, controlWidth, 20).build();
        previewButton.active = !this.coordinator.streamingEnabled() && !this.cameras.isEmpty();
        this.addStyledWidget(previewButton, Component.empty(), WidgetSkin.ACTION, false);
        y += ROW_SPACING;

        this.togglePreviewButton = this.addStyledWidget(
            Button.builder(this.remotePreviewLabel(), ignored -> {
                this.coordinator.toggleRemotePreview();
                this.init();
            }).bounds(controlX, y, controlWidth, 20).build(),
            Component.empty(),
            WidgetSkin.ACTION,
            false,
            null,
            this.coordinator::remotePreviewEnabled
        );
    }

    private void addBubbleTabWidgets() {
        int controlX = this.contentControlX();
        int controlWidth = this.contentControlWidth();
        int y = CONTENT_TOP + 22;
        BitCamBubbleStyle style = this.coordinator.bubbleStyle();

        this.addStyledWidget(
            this.selectWidget(
                controlX,
                y,
                controlWidth,
                List.of(BitCamBubblePreset.values()),
                style.preset(),
                preset -> Component.translatable("screen.bitcam.bubble.preset." + preset.name().toLowerCase()),
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withPreset(value))
            ),
            Component.translatable("screen.bitcam.bubble.preset"),
            WidgetSkin.VALUE,
            false
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            this.selectWidget(
                controlX,
                y,
                controlWidth,
                List.of(BitCamBubbleShape.values()),
                style.shape(),
                shape -> Component.translatable("screen.bitcam.bubble.shape." + shape.name().toLowerCase()),
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withShape(value))
            ),
            Component.translatable("screen.bitcam.bubble.shape"),
            WidgetSkin.VALUE,
            false
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            this.selectWidget(
                controlX,
                y,
                controlWidth,
                List.of(BitCamBubbleRenderMode.values()),
                style.renderMode(),
                mode -> Component.translatable("screen.bitcam.bubble.mode." + mode.name().toLowerCase()),
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withRenderMode(value))
            ),
            Component.translatable("screen.bitcam.bubble.mode"),
            WidgetSkin.VALUE,
            false
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            this.selectWidget(
                controlX,
                y,
                controlWidth,
                List.of(BitCamBubbleContentMode.values()),
                style.contentMode(),
                mode -> Component.translatable("screen.bitcam.bubble.fill." + mode.name().toLowerCase()),
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withContentMode(value))
            ),
            Component.translatable("screen.bitcam.bubble.fill"),
            WidgetSkin.VALUE,
            false
        );
        y += ROW_SPACING + SECTION_GAP;

        this.addStyledWidget(
            new PercentSlider(
                controlX,
                y,
                controlWidth,
                Component.translatable("screen.bitcam.bubble.scale"),
                style.scalePercent(),
                60,
                180,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withScalePercent(value))
            ),
            Component.translatable("screen.bitcam.bubble.scale"),
            WidgetSkin.SLIDER,
            true
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            new PercentSlider(
                controlX,
                y,
                controlWidth,
                Component.translatable("screen.bitcam.bubble.offset_horizontal"),
                style.xOffsetPercent(),
                -BitCamBubbleStyle.BUBBLE_OFFSET_MAX,
                BitCamBubbleStyle.BUBBLE_OFFSET_MAX,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withXOffsetPercent(value))
            ),
            Component.translatable("screen.bitcam.bubble.offset_horizontal"),
            WidgetSkin.SLIDER,
            true
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            new PercentSlider(
                controlX,
                y,
                controlWidth,
                Component.translatable("screen.bitcam.bubble.offset_vertical"),
                style.yOffsetPercent(),
                -BitCamBubbleStyle.BUBBLE_OFFSET_MAX,
                BitCamBubbleStyle.BUBBLE_OFFSET_MAX,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withYOffsetPercent(value))
            ),
            Component.translatable("screen.bitcam.bubble.offset_vertical"),
            WidgetSkin.SLIDER,
            true
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            new PercentSlider(
                controlX,
                y,
                controlWidth,
                Component.translatable("screen.bitcam.bubble.opacity"),
                style.opacityPercent(),
                35,
                100,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withOpacityPercent(value))
            ),
            Component.translatable("screen.bitcam.bubble.opacity"),
            WidgetSkin.SLIDER,
            true
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            new PercentSlider(
                controlX,
                y,
                controlWidth,
                Component.translatable("screen.bitcam.bubble.camera_zoom"),
                style.contentZoomPercent(),
                100,
                250,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withContentZoomPercent(value))
            ),
            Component.translatable("screen.bitcam.bubble.camera_zoom"),
            WidgetSkin.SLIDER,
            true
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            new PercentSlider(
                controlX,
                y,
                controlWidth,
                Component.translatable("screen.bitcam.bubble.camera_offset_horizontal"),
                style.contentXOffsetPercent(),
                0,
                200,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withContentXOffsetPercent(value))
            ),
            Component.translatable("screen.bitcam.bubble.camera_offset_horizontal"),
            WidgetSkin.SLIDER,
            true
        );
        y += ROW_SPACING;

        this.addStyledWidget(
            new PercentSlider(
                controlX,
                y,
                controlWidth,
                Component.translatable("screen.bitcam.bubble.camera_offset_vertical"),
                style.contentYOffsetPercent(),
                0,
                200,
                value -> this.updateBubbleStyle(this.coordinator.bubbleStyle().withContentYOffsetPercent(value))
            ),
            Component.translatable("screen.bitcam.bubble.camera_offset_vertical"),
            WidgetSkin.SLIDER,
            true
        );
    }

    private void addPlayersTabWidgets() {
        int innerLeft = this.playersPanelLeft() + 14;
        int innerWidth = this.playersPanelWidth() - 28;
        int searchY = CONTENT_TOP + 8;

        EditBox searchField = new EditBox(this.font, innerLeft + 1, searchY + 1, innerWidth - 2, 18, Component.empty());
        searchField.setMaxLength(48);
        searchField.setBordered(false);
        searchField.setTextColor(0xFFECF0F4);
        searchField.setHint(Component.translatable("screen.bitcam.players.search"));
        searchField.setResponder(query -> {
            if (this.playerCameraList != null) {
                this.playerCameraList.updateSearch(query.trim().toLowerCase());
            }
        });
        this.playerSearchField = this.addRenderableWidget(searchField);

        if (!this.hasOtherPlayers()) {
            this.listRefreshCooldown = 0;
            return;
        }

        int listTop = CONTENT_TOP + 36;
        int listBottom = this.height - 54;
        this.playerCameraList = this.addRenderableWidget(
            new PlayerCameraList(this.client, innerLeft, innerWidth, listBottom, listTop, 26)
        );
        this.playerCameraList.refreshEntries();
        this.listRefreshCooldown = 20;
    }

    private FlatSelectWidget<CameraDeviceInfo> cameraCycleButton(int x, int y, int width) {
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

        FlatSelectWidget<CameraDeviceInfo> button = this.selectWidget(
            x,
            y,
            width,
            values,
            selected,
            camera -> Component.literal(camera.name()),
            value -> {
                if (this.cameras.isEmpty()) {
                    return;
                }

                this.coordinator.selectCamera(value);
                this.init();
            }
        );
        button.active = !this.cameras.isEmpty();
        return button;
    }

    private FlatSelectWidget<CameraCaptureMode> cameraModeCycleButton(int x, int y, int width) {
        CameraCaptureMode selected = this.coordinator.selectedCaptureMode();
        if (!this.cameraModeValues.contains(selected)) {
            selected = CameraCaptureMode.AUTO;
        }

        FlatSelectWidget<CameraCaptureMode> button = this.selectWidget(
            x,
            y,
            width,
            this.cameraModeValues,
            selected,
            this::cameraModeLabel,
            value -> {
                this.coordinator.selectCaptureMode(value);
                if (!this.coordinator.streamingEnabled()) {
                    this.previewSession.restart(this.coordinator.selectedCameraId(), value);
                }
            }
        );
        button.active = !this.cameras.isEmpty();
        return button;
    }

    private AbstractWidget serverQualityCycleButton(int x, int y, int width) {
        List<BitCamStreamQualityProfile> values = this.coordinator.availableQualityProfiles();
        if (values.isEmpty()) {
            FlatSelectWidget<String> button = this.selectWidget(
                x,
                y,
                width,
                List.of("pending"),
                "pending",
                ignored -> Component.translatable("screen.bitcam.camera.server_quality.pending"),
                ignored -> {
                }
            );
            button.active = false;
            return button;
        }

        BitCamStreamQualityProfile selected = values.getFirst();
        String selectedId = this.coordinator.selectedQualityProfileId();
        for (BitCamStreamQualityProfile profile : values) {
            if (profile.id().equalsIgnoreCase(selectedId)) {
                selected = profile;
                break;
            }
        }

        return this.selectWidget(
            x,
            y,
            width,
            values,
            selected,
            this::qualityProfileLabel,
            value -> this.coordinator.selectQualityProfile(value.id())
        );
    }

    private <T> FlatSelectWidget<T> selectWidget(
        int x,
        int y,
        int width,
        List<T> values,
        T initialValue,
        Function<T, Component> formatter,
        Consumer<T> onChange
    ) {
        return new FlatSelectWidget<>(x, y, width, values, initialValue, formatter, onChange);
    }

    private <T extends AbstractWidget> T addStyledWidget(T widget, Component label, WidgetSkin skin, boolean renderWidget) {
        return this.addStyledWidget(widget, label, skin, renderWidget, null, null);
    }

    private <T extends AbstractWidget> T addStyledWidget(T widget, Component label, WidgetSkin skin, boolean renderWidget, ResourceLocation icon) {
        return this.addStyledWidget(widget, label, skin, renderWidget, icon, null);
    }

    private <T extends AbstractWidget> T addStyledWidget(T widget, Component label, WidgetSkin skin, boolean renderWidget, ResourceLocation icon, BooleanSupplier toggleState) {
        if (!renderWidget) {
            widget.setAlpha(0.0F);
        }
        this.styledWidgets.add(new StyledWidget(widget, label, skin, icon, toggleState));
        return this.addRenderableWidget(widget);
    }

    private Component quickActionLabel() {
        return switch (this.activeTab) {
            case CAMERA -> Component.literal("REF");
            case BUBBLE -> Component.literal("VIEW");
            case PLAYERS -> Component.literal("SYNC");
        };
    }

    private void handleQuickAction() {
        switch (this.activeTab) {
            case CAMERA -> {
                this.cameras = this.coordinator.refreshCameras();
                this.init();
            }
            case BUBBLE -> this.refreshPreviewSource();
            case PLAYERS -> {
                if (this.playerCameraList != null) {
                    this.playerCameraList.refreshEntries();
                }
                this.listRefreshCooldown = 20;
            }
        }
    }

    private int contentControlX() {
        return this.contentLabelX() + this.contentLabelWidth() + LABEL_CONTROL_GAP;
    }

    private int contentControlWidth() {
        return Math.max(144, this.settingsColumnRight() - this.contentControlX() - 12);
    }

    private int contentLabelX() {
        return this.settingsColumnLeft() + 12;
    }

    private int contentLabelWidth() {
        return LABEL_COLUMN_WIDTH;
    }

    private int settingsColumnLeft() {
        return SIDE_PADDING + 6;
    }

    private int settingsColumnWidth() {
        int availableWidth = this.width - (SIDE_PADDING * 2) - COLUMN_GAP;
        return Math.min(356, Math.max(308, availableWidth - 260));
    }

    private int settingsColumnRight() {
        return this.settingsColumnLeft() + this.settingsColumnWidth();
    }

    private int previewStageX() {
        return this.settingsColumnRight() + COLUMN_GAP;
    }

    private int previewStageY() {
        return CONTENT_TOP + 18;
    }

    private int previewStageWidth() {
        return Math.max(148, this.width - this.previewStageX() - SIDE_PADDING + 8);
    }

    private int previewStageHeight() {
        return Math.max(188, this.height - this.previewStageY() - STAGE_BOTTOM_PADDING);
    }

    private int playersPanelWidth() {
        return this.width - (SIDE_PADDING * 2) + 16;
    }

    private int playersPanelLeft() {
        return SIDE_PADDING - 8;
    }

    private void refreshPreviewSource() {
        if (this.coordinator.streamingEnabled()) {
            this.previewSession.stop();
            return;
        }

        this.coordinator.previewStore().clear();
        this.previewSession.restart(this.coordinator.selectedCameraId(), this.coordinator.selectedCaptureMode());
    }

    private void toggleStreaming() {
        boolean wasStreaming = this.coordinator.streamingEnabled();
        if (!wasStreaming) {
            this.previewSession.stop();
            this.coordinator.previewStore().clear();
        }

        this.coordinator.toggleStreaming();

        if (wasStreaming) {
            this.refreshPreviewSource();
        }
    }

    private void syncPlayerPreviewFrame(boolean previewStageVisible) {
        if (this.coordinator.streamingEnabled()) {
            return;
        }

        if (!previewStageVisible) {
            this.coordinator.previewStore().clear();
            return;
        }

        LocalPreviewFrame frame = this.previewSession.previewStore().frame();
        if (frame == null) {
            this.coordinator.previewStore().clear();
            return;
        }

        this.coordinator.previewStore().accept(frame);
    }

    private void updateBubbleStyle(BitCamBubbleStyle style) {
        this.coordinator.updateBubbleStyle(style);
    }

    private LocalPreviewFrame currentPreviewFrame() {
        return this.coordinator.previewStore().frame();
    }

    private void renderSceneBackdrop(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(0, 0, this.width, this.height, 0x66050A0E);
        if (this.activeTab == Tab.CAMERA || this.activeTab == Tab.BUBBLE) {
            this.syncPlayerPreviewFrame(true);
            this.renderFloatingPreview(guiGraphics, mouseX, mouseY);
        } else {
            this.syncPlayerPreviewFrame(false);
            this.renderPlayersPanel(guiGraphics);
        }
    }

    private void renderChromeBackdrop(GuiGraphics guiGraphics) {
        int barX = SIDE_PADDING - 8;
        int barWidth = this.width - (SIDE_PADDING * 2) + 16;
        guiGraphics.fill(barX, TOP_BAR_Y, barX + barWidth, TOP_BAR_Y + TOP_BAR_HEIGHT, 0xB51E2228);
        guiGraphics.fill(barX, TOP_BAR_Y + TOP_BAR_HEIGHT, barX + barWidth, TOP_BAR_Y + TOP_BAR_HEIGHT + 1, 0x804A5058);
        guiGraphics.fill(barX, TOP_BAR_Y + TOP_BAR_HEIGHT + 1, barX + barWidth, this.height - 24, this.activeTab == Tab.PLAYERS ? 0x60000000 : 0x18000000);

        if (this.activeTab != Tab.PLAYERS) {
            int columnLeft = this.settingsColumnLeft() - 18;
            int columnRight = this.settingsColumnRight() + 18;
            guiGraphics.fill(columnLeft, CONTENT_TOP - 12, columnRight, this.height - 46, 0x14000000);
        }
    }

    private void renderChromeForeground(GuiGraphics guiGraphics) {
        guiGraphics.drawCenteredString(this.font, this.tabDescription(), this.width / 2, TOP_BAR_Y + TOP_BAR_HEIGHT + 14, 0xB1B8C1);

        switch (this.activeTab) {
            case CAMERA -> {
                this.drawSectionTitle(guiGraphics, Component.translatable("screen.bitcam.section.camera_input"), CONTENT_TOP + 4);
                this.drawSectionTitle(guiGraphics, Component.translatable("screen.bitcam.section.camera_actions"), CONTENT_TOP + 100);
                this.renderInitialSetupPrompt(guiGraphics);
                this.renderCameraStatus(guiGraphics);
            }
            case BUBBLE -> {
                this.drawSectionTitle(guiGraphics, Component.translatable("screen.bitcam.section.bubble_style"), CONTENT_TOP + 4);
                this.drawSectionTitle(guiGraphics, Component.translatable("screen.bitcam.section.bubble_transform"), CONTENT_TOP + 134);
            }
            case PLAYERS -> {
                if (!this.hasOtherPlayers()) {
                    this.renderPlayersEmptyState(guiGraphics);
                } else if (this.playerCameraList != null && this.playerCameraList.children().isEmpty()) {
                    this.renderPlayersNoResults(guiGraphics);
                }
            }
        }
    }

    private void renderStyledWidgets(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (StyledWidget styledWidget : this.styledWidgets) {
            AbstractWidget widget = styledWidget.widget;
            if (!widget.visible) {
                continue;
            }

            if (styledWidget.skin == WidgetSkin.VALUE || styledWidget.skin == WidgetSkin.SLIDER) {
                this.renderWidgetLabel(guiGraphics, styledWidget.label, widget);
            }

            if (styledWidget.skin != WidgetSkin.SLIDER) {
                this.renderFlatWidget(guiGraphics, styledWidget);
            }
        }

        this.renderOpenSelectOverlay(guiGraphics, mouseX, mouseY);
    }

    private void renderWidgetLabel(GuiGraphics guiGraphics, Component label, AbstractWidget widget) {
        if (label.getString().isBlank()) {
            return;
        }

        Component text = this.ellipsize(label, this.contentLabelWidth());
        guiGraphics.drawString(this.font, text, this.contentLabelX(), widget.getY() + 6, 0xFFF1F4F7, true);
    }

    private void renderFlatWidget(GuiGraphics guiGraphics, StyledWidget styledWidget) {
        AbstractWidget widget = styledWidget.widget;
        int x = widget.getX();
        int y = widget.getY();
        int width = widget.getWidth();
        int height = widget.getHeight();
        boolean hovered = widget.isHoveredOrFocused() || (widget instanceof FlatSelectWidget<?> select && select.isOpen());
        boolean selectedTab = styledWidget.skin == WidgetSkin.TAB && !widget.active;

        int borderColor = selectedTab
            ? 0xFF969CA5
            : hovered
                ? 0xFF747C87
                : 0xFF484E57;
        int fillColor = selectedTab
            ? 0xCC4B515B
            : widget.active
                ? 0xB2262A31
                : 0x9C1A1D22;
        int textColor = selectedTab ? 0xFFFFFFFF : (widget.active ? 0xFFD8DDE4 : 0xFF767D86);

        if (styledWidget.skin == WidgetSkin.TOP_ACTION) {
            fillColor = hovered ? 0xC23C424A : 0xB22A2E35;
            textColor = 0xFFF2F4F7;
        } else if (styledWidget.skin == WidgetSkin.ACTION && styledWidget.toggleState != null && widget.active) {
            if (styledWidget.toggleState.getAsBoolean()) {
                borderColor = hovered ? 0xFF8BD69B : 0xFF5DAA72;
                fillColor = 0xB02C4D36;
                textColor = 0xFFE7FFF0;
            } else {
                borderColor = hovered ? 0xFFB48D95 : 0xFF88636B;
                fillColor = 0xB0432B30;
                textColor = 0xFFFFE8EC;
            }
        }

        guiGraphics.fill(x, y, x + width, y + height, borderColor);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fillColor);
        guiGraphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, hovered || selectedTab ? 0x907A818A : 0x5040474F);

        if (styledWidget.icon != null) {
            int iconSize = 16;
            int iconX = x + (width - iconSize) / 2;
            int iconY = y + (height - iconSize) / 2;
            guiGraphics.blit(RenderPipelines.GUI_TEXTURED, styledWidget.icon, iconX, iconY, 0, 0, iconSize, iconSize, 16, 16);
        } else {
            Component text = this.ellipsize(widget.getMessage(), width - (styledWidget.skin == WidgetSkin.VALUE ? 18 : 10));
            guiGraphics.drawCenteredString(this.font, text, x + (width / 2), y + 6, textColor);
        }
        if (styledWidget.skin == WidgetSkin.VALUE) {
            String indicator = widget instanceof FlatSelectWidget<?> select && select.isOpen() ? "^" : "v";
            guiGraphics.drawString(this.font, Component.literal(indicator), x + width - 11, y + 6, textColor, false);
        }
    }

    private void renderOpenSelectOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.openSelect == null || !this.openSelect.visible) {
            return;
        }

        SelectDropdownLayout layout = this.openSelect.dropdownLayout();
        int left = layout.x();
        int top = layout.y();
        int width = layout.width();
        int height = layout.height();
        boolean canScrollUp = layout.scrollOffset() > 0;
        boolean canScrollDown = layout.scrollOffset() < this.openSelect.maxScrollOffset(layout.visibleRows());

        guiGraphics.fill(left - 2, top - 2, left + width + 2, top + height + 2, 0xA0000000);
        guiGraphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0xFF5C646E);
        guiGraphics.fill(left, top, left + width, top + height, 0xE61A1D22);

        for (int row = 0; row < layout.visibleRows(); row++) {
            int valueIndex = layout.scrollOffset() + row;
            if (valueIndex >= this.openSelect.size()) {
                break;
            }

            int rowTop = top + (row * layout.rowHeight());
            Object value = this.openSelect.valueAt(valueIndex);
            boolean selected = this.openSelect.isSelectedValue(value);
            boolean hovered = mouseX >= left && mouseX < left + width && mouseY >= rowTop && mouseY < rowTop + layout.rowHeight();
            int rowFill = selected
                ? 0x9A404854
                : hovered
                    ? 0x70404852
                    : 0x24191D22;
            int rowText = selected ? 0xFFF5F7FA : hovered ? 0xFFE3E8EE : 0xFFC6CDD5;

            guiGraphics.fill(left + 1, rowTop, left + width - 1, rowTop + layout.rowHeight(), rowFill);
            guiGraphics.fill(left + 1, rowTop + layout.rowHeight() - 1, left + width - 1, rowTop + layout.rowHeight(), 0x40474E58);
            guiGraphics.drawString(this.font, this.ellipsize(this.openSelect.labelForIndex(valueIndex), width - 22), left + 8, rowTop + 6, rowText, false);
        }

        if (canScrollUp) {
            guiGraphics.fill(left + width - 14, top + 2, left + width - 2, top + 12, 0x382A2F35);
            guiGraphics.drawCenteredString(this.font, Component.literal("^"), left + width - 8, top + 4, 0xFFC9D0D8);
        }

        if (canScrollDown) {
            guiGraphics.fill(left + width - 14, top + height - 12, left + width - 2, top + height - 2, 0x382A2F35);
            guiGraphics.drawCenteredString(this.font, Component.literal("v"), left + width - 8, top + height - 10, 0xFFC9D0D8);
        }
    }

    private void drawSectionTitle(GuiGraphics guiGraphics, Component title, int y) {
        guiGraphics.drawString(this.font, title, this.contentLabelX(), y, 0xCDD3DA, false);
    }

    private Component ellipsize(Component component, int maxWidth) {
        String text = component.getString();
        if (this.font.width(text) <= maxWidth) {
            return component;
        }

        String ellipsis = "...";
        String clipped = this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width(ellipsis)));
        return Component.literal(clipped + ellipsis);
    }

    private void renderFloatingPreview(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int previewAreaX = this.previewStageX();
        int previewAreaY = this.previewStageY();
        int previewAreaWidth = this.previewStageWidth();
        int previewAreaHeight = this.previewStageHeight();
        AbstractClientPlayer player = this.client.player;
        LocalPreviewFrame frame = this.currentPreviewFrame();
        BitCamBubbleStyle style = this.coordinator.bubbleStyle();
        guiGraphics.fill(previewAreaX, previewAreaY, previewAreaX + previewAreaWidth, previewAreaY + previewAreaHeight, 0x12000000);
        guiGraphics.fill(previewAreaX, previewAreaY, previewAreaX + previewAreaWidth, previewAreaY + Math.round(previewAreaHeight * 0.56F), 0x120B1014);
        guiGraphics.drawString(this.font, Component.translatable("screen.bitcam.preview.title"), previewAreaX + 2, previewAreaY - 12, 0xC6CDD5, false);

        int actorInsetX = 12;
        int actorX = previewAreaX + actorInsetX;
        int actorY = previewAreaY + 8;
        int actorWidth = Math.max(148, previewAreaWidth - (actorInsetX * 2));
        int actorHeight = Math.max(156, previewAreaHeight - 32);
        int actorRight = actorX + actorWidth;
        int actorBottom = actorY + actorHeight;
        int actorCenterX = actorX + (actorWidth / 2);
        int floorY = actorY + Math.round(actorHeight * 0.85F);
        int modelSize = player == null ? 64 : this.previewModelSize(player, actorWidth, actorHeight, style, frame);
        float previewEntityYOffset = this.previewEntityYOffset(style);

        guiGraphics.fill(actorX - 18, floorY, actorX + actorWidth + 18, floorY + 1, 0x54626B78);
        guiGraphics.fill(actorCenterX - 58, floorY + 6, actorCenterX + 58, floorY + 10, 0x16000000);

        if (player != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                guiGraphics,
                actorX,
                actorY,
                actorRight,
                actorBottom,
                modelSize,
                previewEntityYOffset,
                mouseX,
                mouseY,
                player
            );
        } else {
            guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("screen.bitcam.preview.unavailable"),
                actorCenterX,
                previewAreaY + (previewAreaHeight / 2) - 4,
                0xD2D8DF
            );
            return;
        }

        int opacityLabelY = previewAreaY + previewAreaHeight - 12;
        guiGraphics.drawString(
            this.font,
            this.ellipsize(
                Component.translatable(
                    "screen.bitcam.preview.meta",
                    this.coordinator.bubbleStyle().preset().name(),
                    this.coordinator.bubbleStyle().shape().name(),
                    this.coordinator.bubbleStyle().renderMode().name(),
                    this.coordinator.bubbleStyle().opacityPercent() + "%"
                ),
                previewAreaWidth
            ),
            previewAreaX,
            opacityLabelY,
            0x8F9AA7,
            false
        );
    }

    private int previewModelSize(
        AbstractClientPlayer player,
        int actorWidth,
        int actorHeight,
        BitCamBubbleStyle style,
        LocalPreviewFrame frame
    ) {
        int baseSize = Math.max(54, Math.min(94, Math.round(Math.min(actorWidth, actorHeight) * 0.34F)));
        float bubbleScale = BitCamBubblePlacement.scale(style);
        float aspectRatio = this.previewBubbleAspectRatio(style, frame);
        double bubbleHalfWidthWorld = (0.35D * bubbleScale * aspectRatio) + (0.06D * bubbleScale);
        double horizontalReachWorld = Math.abs(BitCamBubblePlacement.horizontalOffset(style, bubbleScale)) + bubbleHalfWidthWorld + 0.18D;
        double bubbleTopWorld = player.getBbHeight() + BitCamBubblePlacement.worldVerticalOffset(style, bubbleScale) + (0.41D * bubbleScale);
        float playerHeight = Math.max(0.1F, player.getBbHeight());
        double availableHalfWidth = Math.max(42.0D, (actorWidth * 0.5D) - 16.0D);
        double availableAboveFeet = Math.max(86.0D, (actorHeight * 0.84D) - 18.0D);
        int widthBound = horizontalReachWorld <= 0.0D
            ? baseSize
            : (int) Math.floor((availableHalfWidth * playerHeight) / (2.0D * horizontalReachWorld));
        int heightBound = bubbleTopWorld <= 0.0D
            ? baseSize
            : (int) Math.floor((availableAboveFeet * playerHeight) / (2.0D * bubbleTopWorld));
        return Math.max(44, Math.min(baseSize, Math.min(widthBound, heightBound)));
    }

    private float previewBubbleAspectRatio(BitCamBubbleStyle style, LocalPreviewFrame frame) {
        if (style.shape() == BitCamBubbleShape.CIRCLE || style.shape() == BitCamBubbleShape.SQUARE) {
            return 1.0F;
        }

        if (frame == null) {
            return 16.0F / 9.0F;
        }

        return BitCamBubbleContentLayout.displayAspectRatio(frame.width(), frame.height(), style);
    }

    private float previewEntityYOffset(BitCamBubbleStyle style) {
        float bubbleScale = BitCamBubblePlacement.scale(style);
        double worldOffset = BitCamBubblePlacement.worldVerticalOffset(style, bubbleScale);
        return (float) Math.max(-0.34D, -0.06D - (worldOffset * 0.18D));
    }

    private void renderPlayersPanel(GuiGraphics guiGraphics) {
        int panelX = this.playersPanelLeft();
        int panelY = CONTENT_TOP - 2;
        int panelWidth = this.playersPanelWidth();
        int panelHeight = this.height - panelY - 46;

        // Panel background + top accent line
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0x75101820);
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x604A5260);

        // List area tint (below separator)
        guiGraphics.fill(panelX, CONTENT_TOP + 33, panelX + panelWidth, panelY + panelHeight, 0x25000000);

        // Search field background
        int searchLeft = panelX + 14;
        int searchWidth = panelWidth - 28;
        int searchY = CONTENT_TOP + 8;
        boolean searchFocused = this.playerSearchField != null && this.playerSearchField.isFocused();
        int searchBorder = searchFocused ? 0xFF747C87 : 0xFF484E57;
        guiGraphics.fill(searchLeft, searchY, searchLeft + searchWidth, searchY + 20, searchBorder);
        guiGraphics.fill(searchLeft + 1, searchY + 1, searchLeft + searchWidth - 1, searchY + 19, 0xB2262A31);

        // Separator
        guiGraphics.fill(panelX + 8, CONTENT_TOP + 32, panelX + panelWidth - 8, CONTENT_TOP + 33, 0x704A5260);
    }

    private void renderInitialSetupPrompt(GuiGraphics guiGraphics) {
        if (!this.coordinator.needsInitialSetup()) {
            return;
        }

        int left = this.contentLabelX();
        int width = (this.contentControlX() + this.contentControlWidth()) - left;
        int top = CONTENT_TOP + 240;
        int bottom = top + 54;
        guiGraphics.fill(left, top, left + width, bottom, 0x72311F19);
        guiGraphics.fill(left, top, left + width, top + 1, 0xFFCB9B56);
        guiGraphics.drawString(this.font, Component.translatable("screen.bitcam.setup.title"), left + 10, top + 8, 0xFFF2D6, true);

        int textY = top + 22;
        for (FormattedCharSequence line : this.font.split(Component.translatable("screen.bitcam.setup.subtitle"), width - 20)) {
            guiGraphics.drawString(this.font, line, left + 10, textY, 0xFFE5CCA0, false);
            textY += 10;
        }
    }

    private void renderCameraStatus(GuiGraphics guiGraphics) {
        int left = this.contentLabelX();
        int top = this.coordinator.needsInitialSetup() ? CONTENT_TOP + 304 : CONTENT_TOP + 246;
        int width = (this.contentControlX() + this.contentControlWidth()) - left;

        if (this.coordinator.isDownloadingCameraLibraries()) {
            int pct = this.coordinator.cameraLibraryDownloadProgress();
            String label = Component.translatable("screen.bitcam.camera.libs_downloading", pct).getString();
            guiGraphics.drawString(this.font, label, left, top, 0x80C8FF, false);
            // Progress bar
            int barY = top + 12;
            int barWidth = width;
            guiGraphics.fill(left, barY, left + barWidth, barY + 4, 0x40FFFFFF);
            guiGraphics.fill(left, barY, left + Math.round(barWidth * pct / 100f), barY + 4, 0xFF80C8FF);
            return;
        }

        String failureMessage = this.coordinator.cameraLibraryDownloadFailure();
        if (!failureMessage.isBlank()) {
            for (FormattedCharSequence line : this.font.split(
                Component.translatable("screen.bitcam.camera.libs_failed", failureMessage), width)) {
                guiGraphics.drawString(this.font, line, left, top, 0xE0A7AB, false);
                top += 10;
            }
            return;
        }

        String statusMessage = this.coordinator.cameraStatusMessage();
        if (statusMessage.isBlank() && !this.cameras.isEmpty()) {
            return;
        }

        if (statusMessage.isBlank()) {
            statusMessage = Component.translatable("screen.bitcam.camera.none").getString();
        }

        int color = this.cameras.isEmpty() ? 0xE0A7AB : 0xA9B2BE;
        for (FormattedCharSequence line : this.font.split(Component.literal(statusMessage), width)) {
            guiGraphics.drawString(this.font, line, left, top, color, false);
            top += 10;
        }
    }

    private void renderPlayersEmptyState(GuiGraphics guiGraphics) {
        int centerX = this.playersPanelLeft() + this.playersPanelWidth() / 2;
        int centerY = (CONTENT_TOP + 36 + this.height - 54) / 2 - 10;
        guiGraphics.drawCenteredString(this.font, Component.translatable("screen.bitcam.players.empty"), centerX, centerY, 0xA9B2BE);
        guiGraphics.drawCenteredString(this.font, Component.translatable("screen.bitcam.players.empty_hint"), centerX, centerY + 14, 0x798494);
    }

    private void renderPlayersNoResults(GuiGraphics guiGraphics) {
        int centerX = this.playersPanelLeft() + this.playersPanelWidth() / 2;
        int centerY = (CONTENT_TOP + 36 + this.height - 54) / 2 - 4;
        guiGraphics.drawCenteredString(this.font, Component.translatable("screen.bitcam.players.no_results"), centerX, centerY, 0x798494);
    }

    private boolean hasOtherPlayers() {
        return !this.otherPlayers().isEmpty();
    }

    private List<AbstractClientPlayer> otherPlayers() {
        ClientLevel level = this.client.level;
        if (level == null || this.client.player == null) {
            return List.of();
        }

        List<AbstractClientPlayer> players = new ArrayList<>(level.players());
        players.removeIf(player -> player.getUUID().equals(this.client.player.getUUID()));
        players.sort(Comparator.comparing(player -> player.getName().getString(), String.CASE_INSENSITIVE_ORDER));
        return players;
    }

    private Component streamingLabel() {
        if (this.coordinator.isCameraStarting()) {
            return Component.translatable("screen.bitcam.camera.streaming_starting");
        }
        return Component.translatable(
            this.coordinator.streamingEnabled() ? "screen.bitcam.camera.streaming_disable" : "screen.bitcam.camera.streaming_enable"
        );
    }

    private Component remotePreviewLabel() {
        return Component.translatable(
            this.coordinator.remotePreviewEnabled() ? "screen.bitcam.camera.remote_preview_disable" : "screen.bitcam.camera.remote_preview_enable"
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

    private Component qualityProfileLabel(BitCamStreamQualityProfile profile) {
        if (profile == null) {
            return Component.translatable("screen.bitcam.camera.server_quality.pending");
        }

        return Component.literal(
            profile.displayName()
                + " • "
                + profile.width()
                + "x"
                + profile.height()
                + " @ "
                + profile.fps()
                + " FPS"
        );
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

    private enum WidgetSkin {
        TAB,
        TOP_ACTION,
        VALUE,
        ACTION,
        SLIDER
    }

    private static final class StyledWidget {
        private final AbstractWidget widget;
        private final Component label;
        private final WidgetSkin skin;
        private final ResourceLocation icon;
        private final BooleanSupplier toggleState;

        private StyledWidget(AbstractWidget widget, Component label, WidgetSkin skin, ResourceLocation icon, BooleanSupplier toggleState) {
            this.widget = widget;
            this.label = label;
            this.skin = skin;
            this.icon = icon;
            this.toggleState = toggleState;
        }
    }

    private interface IntValueConsumer {
        void accept(int value);
    }

    private record SelectDropdownLayout(int x, int y, int width, int rowHeight, int visibleRows, int scrollOffset) {
        private int height() {
            return this.visibleRows * this.rowHeight;
        }
    }

    private final class FlatSelectWidget<T> extends AbstractWidget {
        private static final int MAX_VISIBLE_ROWS = 6;

        private final List<T> values;
        private final Function<T, Component> formatter;
        private final Consumer<T> onChange;
        private T value;
        private int scrollOffset;

        private FlatSelectWidget(
            int x,
            int y,
            int width,
            List<T> values,
            T initialValue,
            Function<T, Component> formatter,
            Consumer<T> onChange
        ) {
            super(x, y, width, 20, Component.empty());
            this.values = List.copyOf(values);
            this.formatter = formatter;
            this.onChange = onChange;
            this.value = initialValue;
            this.setMessage(this.formatter.apply(initialValue));
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            this.defaultButtonNarrationText(narrationElementOutput);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.active || !this.visible || button != 0 || !this.isMouseOver(mouseX, mouseY)) {
                return false;
            }

            if (this.isOpen()) {
                this.closeDropdown();
            } else {
                BitCamSettingsScreen.this.openSelect = this;
                this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScrollOffset(this.visibleRowCount())));
            }
            return true;
        }

        private boolean isOpen() {
            return BitCamSettingsScreen.this.openSelect == this;
        }

        private void closeDropdown() {
            if (BitCamSettingsScreen.this.openSelect == this) {
                BitCamSettingsScreen.this.openSelect = null;
            }
        }

        private boolean handleDropdownClick(double mouseX, double mouseY, int button) {
            if (!this.isOpen() || button != 0) {
                return false;
            }

            SelectDropdownLayout layout = this.dropdownLayout();
            if (mouseX < layout.x() || mouseX >= layout.x() + layout.width() || mouseY < layout.y() || mouseY >= layout.y() + layout.height()) {
                return false;
            }

            int row = (int) ((mouseY - layout.y()) / layout.rowHeight());
            int valueIndex = layout.scrollOffset() + row;
            if (valueIndex >= 0 && valueIndex < this.values.size()) {
                this.setSelectedValue(this.values.get(valueIndex), true);
            }
            this.closeDropdown();
            return true;
        }

        private boolean handleScroll(double mouseX, double mouseY, double verticalAmount) {
            if (!this.isOpen()) {
                return false;
            }

            SelectDropdownLayout layout = this.dropdownLayout();
            boolean overDropdown = mouseX >= layout.x() && mouseX < layout.x() + layout.width() && mouseY >= layout.y() && mouseY < layout.y() + layout.height();
            if (!overDropdown && !this.isMouseOver(mouseX, mouseY)) {
                return false;
            }

            int maxOffset = this.maxScrollOffset(layout.visibleRows());
            if (maxOffset <= 0) {
                return false;
            }

            if (verticalAmount < 0.0D) {
                this.scrollOffset = Math.min(maxOffset, this.scrollOffset + 1);
            } else if (verticalAmount > 0.0D) {
                this.scrollOffset = Math.max(0, this.scrollOffset - 1);
            }
            return true;
        }

        private SelectDropdownLayout dropdownLayout() {
            int rowHeight = 20;
            int visibleRows = this.visibleRowCount();
            int bottomSpace = BitCamSettingsScreen.this.height - (this.getY() + this.getHeight()) - 28;
            int topSpace = this.getY() - CONTENT_TOP + 8;
            int rowsBelow = Math.max(1, bottomSpace / rowHeight);
            int rowsAbove = Math.max(1, topSpace / rowHeight);
            boolean renderBelow = rowsBelow >= visibleRows || rowsBelow >= rowsAbove;
            int renderedRows = Math.min(visibleRows, renderBelow ? rowsBelow : rowsAbove);
            int maxOffset = this.maxScrollOffset(renderedRows);
            this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxOffset));
            int y = renderBelow ? this.getY() + this.getHeight() + 4 : this.getY() - (renderedRows * rowHeight) - 4;
            return new SelectDropdownLayout(this.getX(), y, this.getWidth(), rowHeight, renderedRows, this.scrollOffset);
        }

        private int size() {
            return this.values.size();
        }

        private Object valueAt(int index) {
            return this.values.get(index);
        }

        private Component labelForIndex(int index) {
            return this.formatter.apply(this.values.get(index));
        }

        private boolean isSelectedValue(Object value) {
            return Objects.equals(this.value, value);
        }

        private int visibleRowCount() {
            return Math.max(1, Math.min(MAX_VISIBLE_ROWS, this.values.size()));
        }

        private int maxScrollOffset(int visibleRows) {
            return Math.max(0, this.values.size() - visibleRows);
        }

        private void setSelectedValue(T newValue, boolean notify) {
            this.value = newValue;
            this.setMessage(this.formatter.apply(newValue));
            if (notify) {
                this.onChange.accept(newValue);
            }
        }
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
            this.setMessage(Component.literal(this.currentValue() + "%"));
        }

        @Override
        protected void applyValue() {
            this.consumer.accept(this.currentValue());
            this.updateMessage();
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int width = this.getWidth();
            int height = this.getHeight();
            boolean hovered = this.isHoveredOrFocused();

            guiGraphics.fill(x, y, x + width, y + height, hovered ? 0xFF747C87 : 0xFF484E57);
            guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, this.active ? 0xB2262A31 : 0x9C1A1D22);

            int progressWidth = Math.max(8, (int) Math.round((width - 6) * this.value));
            guiGraphics.fill(x + 3, y + 4, x + 3 + progressWidth, y + height - 4, 0x77485369);
            int knobX = x + 3 + Math.min(width - 8, progressWidth);
            guiGraphics.fill(knobX - 2, y + 3, knobX, y + height - 3, 0xFFD8DDE4);

            guiGraphics.drawCenteredString(Minecraft.getInstance().font, this.getMessage(), x + (width / 2), y + 6, 0xFFE3E7EB);
        }

        private int currentValue() {
            return this.minValue + (int) Math.round(this.value * (this.maxValue - this.minValue));
        }
    }

    private final class PlayerCameraList extends ObjectSelectionList<PlayerCameraList.Entry> {
        private final int leftPos;
        private String searchQuery = "";

        private PlayerCameraList(Minecraft minecraft, int leftPos, int width, int bottom, int top, int itemHeight) {
            super(minecraft, width, bottom, top, itemHeight);
            this.leftPos = leftPos;
        }

        private void updateSearch(String query) {
            this.searchQuery = query == null ? "" : query;
            this.refreshEntries();
        }

        private void refreshEntries() {
            this.clearEntries();
            for (AbstractClientPlayer player : BitCamSettingsScreen.this.otherPlayers()) {
                if (this.searchQuery.isEmpty() || player.getName().getString().toLowerCase().contains(this.searchQuery)) {
                    this.addEntry(new Entry(player));
                }
            }
        }

        @Override
        protected void renderListBackground(GuiGraphics guiGraphics) {}

        @Override
        protected void renderListSeparators(GuiGraphics guiGraphics) {}

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
            private final AbstractClientPlayer player;
            private final UUID playerId;
            private final String playerName;

            private Entry(AbstractClientPlayer player) {
                this.player = player;
                this.playerId = player.getUUID();
                this.playerName = player.getName().getString();
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

                // Row background
                guiGraphics.fill(left, top, left + width, top + height - 1, hovering ? 0x60384450 : 0x35202C38);
                guiGraphics.fill(left, top + height - 1, left + width, top + height, 0x55404E5C);

                // Player head — face layer + hat overlay (8×8 UV → 16×16 display)
                int headSize = 16;
                int headX = left + 6;
                int headY = top + (height - headSize) / 2;
                ResourceLocation skin = this.player.getSkin().texture();
                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, skin, headX, headY, headSize, headSize, 8, 8, 8, 8, 64, 64);
                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, skin, headX, headY, headSize, headSize, 40, 8, 8, 8, 64, 64);

                // Player name
                int nameX = headX + headSize + 8;
                int nameY = top + (height - 8) / 2;
                int btnWidth = 80;
                int maxNameWidth = width - headSize - 14 - btnWidth - 18;
                String displayName = BitCamSettingsScreen.this.ellipsize(Component.literal(this.playerName), maxNameWidth).getString();
                guiGraphics.drawString(BitCamSettingsScreen.this.font, displayName, nameX, nameY, hovering ? 0xFFFFFFFF : 0xFFD8DDE4, false);

                // Toggle button
                int btnHeight = 16;
                int btnX = left + width - btnWidth - 6;
                int btnY = top + (height - btnHeight) / 2;
                boolean btnHovered = mouseX >= btnX && mouseX < btnX + btnWidth && mouseY >= btnY && mouseY < btnY + btnHeight;

                int borderColor = hidden
                    ? (btnHovered ? 0xFFB48D95 : 0xFF88636B)
                    : (btnHovered ? 0xFF8BD69B : 0xFF5DAA72);
                int fillColor = hidden ? 0xB0432B30 : 0xB02C4D36;
                int textColor = hidden ? 0xFFFFE8EC : 0xFFE7FFF0;
                Component btnLabel = Component.translatable(hidden ? "screen.bitcam.players.hidden" : "screen.bitcam.players.visible");

                guiGraphics.fill(btnX, btnY, btnX + btnWidth, btnY + btnHeight, borderColor);
                guiGraphics.fill(btnX + 1, btnY + 1, btnX + btnWidth - 1, btnY + btnHeight - 1, fillColor);
                guiGraphics.fill(btnX + 1, btnY + btnHeight - 2, btnX + btnWidth - 1, btnY + btnHeight - 1, btnHovered ? 0x907A818A : 0x5040474F);
                guiGraphics.drawCenteredString(BitCamSettingsScreen.this.font, btnLabel, btnX + btnWidth / 2, btnY + (btnHeight - 8) / 2, textColor);
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
