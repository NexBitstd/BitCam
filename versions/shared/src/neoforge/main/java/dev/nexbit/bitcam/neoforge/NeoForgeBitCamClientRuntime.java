//#if NEOFORGE
//$$ package dev.nexbit.bitcam.neoforge;
//$$
//$$ import com.mojang.brigadier.arguments.IntegerArgumentType;
//$$ import com.mojang.blaze3d.platform.InputConstants;
//$$ import dev.nexbit.bitcam.client.render.BitCamBillboardRenderer;
//$$ import dev.nexbit.bitcam.client.ui.BitCamSettingsScreen;
//$$ import dev.nexbit.bitcam.clientcommon.BitCamClientCoordinator;
//$$ import dev.nexbit.bitcam.clientcommon.CameraCatalog;
//$$ import dev.nexbit.bitcam.clientcommon.runtime.BitCamClientSessionController;
//$$ import dev.nexbit.bitcam.clientcommon.runtime.BitCamClientUiHost;
//$$ import dev.nexbit.bitcam.neoforge.network.NeoForgeBitCamControlPayload;
//$$ import dev.nexbit.bitcam.neoforge.platform.NeoForgePlatformAccess;
//#if MC<12107
//$$ import dev.nexbit.bitcam.protocol.BitCamProtocol;
//#endif
//$$ import dev.nexbit.bitcam.protocol.signal.ServerWelcomeSignalPacket;
//$$ import net.minecraft.client.KeyMapping;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.player.AbstractClientPlayer;
//$$ import net.minecraft.commands.Commands;
//$$ import net.minecraft.network.chat.Component;
//#if MC>=12102
//$$ import net.minecraft.world.entity.Entity;
//#endif
//$$ import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
//$$ import net.neoforged.neoforge.client.event.ClientTickEvent;
//$$ import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
//$$ import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
//$$ import net.neoforged.neoforge.client.event.RenderPlayerEvent;
//#if MC>=12107
//$$ import net.neoforged.neoforge.client.network.ClientPacketDistributor;
//$$ import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
//#else
//$$ import net.neoforged.neoforge.network.PacketDistributor;
//$$ import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
//#endif
//$$ import net.neoforged.neoforge.network.handling.IPayloadContext;
//$$ import org.lwjgl.glfw.GLFW;
//$$
//$$ public final class NeoForgeBitCamClientRuntime {
//$$     private static final String KEY_CATEGORY = "category.bitcam";
//$$     private static final String KEY_TOGGLE = "key.bitcam.toggle_stream";
//$$     private static final String KEY_SETTINGS = "key.bitcam.open_settings";
//$$
//$$     private final NeoForgePlatformAccess platform;
//$$     private final Minecraft client = Minecraft.getInstance();
//$$     private final KeyMapping toggleKey = createKeyMapping(KEY_TOGGLE, GLFW.GLFW_KEY_V);
//$$     private final KeyMapping settingsKey = createKeyMapping(KEY_SETTINGS, GLFW.GLFW_KEY_B);
//$$
//$$     private static KeyMapping createKeyMapping(String translationKey, int glfwKey) {
        //#if MC>=12109
//$$         return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, glfwKey,
//$$             net.minecraft.client.KeyMapping.Category.register(
            //#if MC>=12111
//$$                 net.minecraft.resources.Identifier.fromNamespaceAndPath("bitcam", "main")));
            //#else
//$$                 net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bitcam", "main")));
            //#endif
        //#else
//$$         return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, glfwKey, KEY_CATEGORY);
        //#endif
//$$     }
//$$     private final BitCamClientSessionController sessionController;
//$$     private final BitCamBillboardRenderer billboardRenderer;
//$$
//$$     public NeoForgeBitCamClientRuntime(String minecraftVersion) {
//$$         this.platform = NeoForgePlatformAccess.createClient(minecraftVersion);
//$$         // Start downloading platform camera natives immediately on game launch (background).
//$$         CameraCatalog.prewarm(this.platform.configDirectory());
//$$         this.sessionController = new BitCamClientSessionController(
//$$             this.platform,
//$$             () -> this.client.player.getUUID(),
//$$             new BitCamClientUiHost() {
//$$                 @Override
//$$                 public void showMessage(String message) {
//$$                     NeoForgeBitCamClientRuntime.this.sendMessage(message);
//$$                 }
//$$
//$$                 @Override
//$$                 public void openSettings(BitCamClientCoordinator coordinator) {
//$$                     NeoForgeBitCamClientRuntime.this.client.setScreen(new BitCamSettingsScreen(NeoForgeBitCamClientRuntime.this.client.screen, coordinator));
//$$                 }
//$$             },
//$$             this::sendHello
//$$         );
//$$         this.billboardRenderer = new BitCamBillboardRenderer(this.client, this.sessionController::coordinator);
//$$     }
//$$
    //#if MC>=12107
//$$     public void registerClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
//$$         event.register(NeoForgeBitCamControlPayload.TYPE, this::handleControlPacket);
//$$     }
    //#else
    //$$ public void registerClientPayloadHandlers(RegisterPayloadHandlersEvent event) {
    //$$     event.registrar(Integer.toString(BitCamProtocol.PROTOCOL_VERSION))
    //$$         .optional()
    //$$         .playToClient(NeoForgeBitCamControlPayload.TYPE, NeoForgeBitCamControlPayload.CODEC, this::handleControlPacket);
    //$$ }
    //#endif
//$$
//$$     public void registerKeyMappings(RegisterKeyMappingsEvent event) {
//$$         event.register(this.toggleKey);
//$$         event.register(this.settingsKey);
//$$     }
//$$
//$$     public void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
//$$         this.sessionController.onJoin();
//$$         this.sendHello();
//$$     }
//$$
//$$     public void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
//$$         this.sessionController.onDisconnect();
//$$         // Logout can fire off the render thread, but close() releases GL textures and must run there.
//$$         this.client.execute(this.billboardRenderer::close);
//$$     }
//$$
//$$     public void onClientTick(ClientTickEvent.Post event) {
//$$         while (this.toggleKey.consumeClick()) {
//$$             this.sessionController.toggleStreaming();
//$$         }
//$$         while (this.settingsKey.consumeClick()) {
//$$             this.sessionController.openSettings();
//$$         }
//$$         if (this.sessionController.shouldRetryHello(this.client.getConnection() != null)) {
//$$             this.sendHello();
//$$         }
//$$         this.sessionController.pruneRemoteFrames(10_000L);
//$$         this.billboardRenderer.tick();
//$$     }
//$$
        //#if MC>=12109
//$$     public void onRenderPlayer(RenderPlayerEvent.Post<?> event) {
//$$         if (this.client.level == null) {
//$$             return;
//$$         }
//$$         net.minecraft.client.renderer.entity.state.AvatarRenderState renderState = event.getRenderState();
//$$         Entity entity = this.client.level.getEntity(renderState.id);
//$$         if (!(entity instanceof AbstractClientPlayer player)) {
//$$             return;
//$$         }
//$$         this.billboardRenderer.renderPlayerBubble(
//$$             event.getPoseStack(), player,
//$$             player.getBbHeight(),
//$$             renderState.bodyRot,
//$$             renderState.yRot,
//$$             renderState.xRot,
//$$             ((net.minecraft.client.renderer.entity.player.AvatarRenderer<?>) event.getRenderer()).getModel().head
//$$         );
        //#elseif MC>=12102
//$$     public void onRenderPlayer(RenderPlayerEvent.Post event) {
//$$         if (this.client.level == null) {
//$$             return;
//$$         }
//$$         Entity entity = this.client.level.getEntity(event.getRenderState().id);
//$$         if (!(entity instanceof AbstractClientPlayer player)) {
//$$             return;
//$$         }
//$$         this.billboardRenderer.renderPlayerBubble(
//$$             event.getPoseStack(), player,
//$$             player.getBbHeight(),
//$$             event.getRenderState().bodyRot,
//$$             event.getRenderState().yRot,
//$$             event.getRenderState().xRot,
//$$             event.getRenderer().getModel().head
//$$         );
        //#else
//$$     public void onRenderPlayer(RenderPlayerEvent.Post event) {
//$$         if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
//$$             return;
//$$         }
//$$         this.billboardRenderer.renderPlayerBubble(
//$$             event.getPoseStack(), player,
//$$             player.getBbHeight(),
//$$             player.yBodyRot,
//$$             player.getYRot(),
//$$             player.getXRot(),
//$$             event.getRenderer().getModel().head
//$$         );
        //#endif
//$$     }
//$$
//$$     public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
//$$         event.getDispatcher().register(
//$$             Commands.literal("bitcam")
//$$                 .then(Commands.literal("toggle").executes(context -> {
//$$                     this.sessionController.toggleStreaming();
//$$                     return 1;
//$$                 }))
//$$                 .then(Commands.literal("cameras").executes(context -> {
//$$                     this.sessionController.listCameras();
//$$                     return 1;
//$$                 }))
//$$                 .then(Commands.literal("settings").executes(context -> {
//$$                     this.sessionController.openSettings();
//$$                     return 1;
//$$                 }))
//$$                 .then(Commands.literal("camera")
//$$                     .then(Commands.argument("index", IntegerArgumentType.integer(0)).executes(context -> {
//$$                         this.sessionController.selectCamera(IntegerArgumentType.getInteger(context, "index"));
//$$                         return 1;
//$$                     })))
//$$         );
//$$     }
//$$
//$$     private void handleControlPacket(NeoForgeBitCamControlPayload payload, IPayloadContext context) {
//$$         if (payload.decodeSignalPacket() instanceof ServerWelcomeSignalPacket welcome) {
//$$             this.client.execute(() -> this.sessionController.handleWelcome(welcome));
//$$         }
//$$     }
//$$
//$$     private void sendHello() {
//$$         BitCamClientCoordinator coordinator = this.sessionController.coordinator();
//$$         if (coordinator != null) {
            //#if MC>=12107
//$$             ClientPacketDistributor.sendToServer(NeoForgeBitCamControlPayload.fromSignalPacket(coordinator.createHelloPacket()));
            //#else
            //$$ PacketDistributor.sendToServer(NeoForgeBitCamControlPayload.fromSignalPacket(coordinator.createHelloPacket()));
            //#endif
//$$         }
//$$     }
//$$
//$$     private void sendMessage(String message) {
//$$         if (this.client.player != null) {
            //#if MC>=260100
//$$             this.client.player.sendSystemMessage(Component.literal(message));
            //#else
//$$             this.client.player.displayClientMessage(Component.literal(message), false);
            //#endif
//$$         } else {
//$$             this.platform.logger().info(message);
//$$         }
//$$     }
//$$ }
//#endif
