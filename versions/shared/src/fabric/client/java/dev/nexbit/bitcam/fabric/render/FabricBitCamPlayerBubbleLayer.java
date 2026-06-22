//#if FABRIC
package dev.nexbit.bitcam.fabric.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.nexbit.bitcam.client.render.BitCamBillboardRenderer;
import net.minecraft.client.model.PlayerModel;
//#if MC<12102
//$$ import net.minecraft.client.player.AbstractClientPlayer;
//#endif
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
//#if MC>=12109
//$$ import net.minecraft.client.renderer.SubmitNodeCollector;
//$$ import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//#elseif MC>=12102
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//#endif

//#if MC>=12109
//$$ public final class FabricBitCamPlayerBubbleLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
//#elseif MC>=12102
public final class FabricBitCamPlayerBubbleLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
//#else
//$$ public final class FabricBitCamPlayerBubbleLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
//#endif
    private final BitCamBillboardRenderer renderer;

    //#if MC>=12109
    //$$ public FabricBitCamPlayerBubbleLayer(
    //$$     RenderLayerParent<AvatarRenderState, PlayerModel> parent,
    //$$     BitCamBillboardRenderer renderer
    //$$ ) {
    //$$     super(parent);
    //$$     this.renderer = renderer;
    //$$ }
    //#elseif MC>=12102
    public FabricBitCamPlayerBubbleLayer(
        RenderLayerParent<PlayerRenderState, PlayerModel> parent,
        BitCamBillboardRenderer renderer
    ) {
        super(parent);
        this.renderer = renderer;
    }
    //#else
    //$$ public FabricBitCamPlayerBubbleLayer(
    //$$     RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent,
    //$$     BitCamBillboardRenderer renderer
    //$$ ) {
    //$$     super(parent);
    //$$     this.renderer = renderer;
    //$$ }
    //#endif

    @Override
    //#if MC>=12109
    //$$ public void submit(
    //$$     PoseStack poseStack,
    //$$     SubmitNodeCollector collector,
    //$$     int packedLight,
    //$$     AvatarRenderState renderState,
    //$$     float limbSwing,
    //$$     float limbSwingAmount
    //$$ ) {
    //$$     net.minecraft.world.entity.Entity entity = net.minecraft.client.Minecraft.getInstance().level == null
    //$$         ? null
    //$$         : net.minecraft.client.Minecraft.getInstance().level.getEntity(renderState.id);
    //$$     if (!(entity instanceof net.minecraft.client.player.AbstractClientPlayer player)) {
    //$$         return;
    //$$     }
    //$$     this.renderer.renderPlayerBubble(
    //$$         poseStack, player,
    //$$         (float) renderState.boundingBoxHeight,
    //$$         renderState.bodyRot, renderState.yRot, renderState.xRot,
    //$$         this.getParentModel().head
    //$$     );
    //$$ }
    //#elseif MC>=12102
    public void render(
        PoseStack poseStack,
        MultiBufferSource multiBufferSource,
        int packedLight,
        PlayerRenderState renderState,
        float limbSwing,
        float limbSwingAmount
    ) {
        net.minecraft.world.entity.Entity entity = net.minecraft.client.Minecraft.getInstance().level == null
            ? null
            : net.minecraft.client.Minecraft.getInstance().level.getEntity(renderState.id);
        if (!(entity instanceof net.minecraft.client.player.AbstractClientPlayer player)) {
            return;
        }
        this.renderer.renderPlayerBubble(
            poseStack, player,
            (float) renderState.boundingBoxHeight,
            renderState.bodyRot, renderState.yRot, renderState.xRot,
            this.getParentModel().head
        );
    }
    //#else
    //$$ public void render(
    //$$     PoseStack poseStack,
    //$$     MultiBufferSource multiBufferSource,
    //$$     int packedLight,
    //$$     AbstractClientPlayer player,
    //$$     float limbSwing,
    //$$     float limbSwingAmount,
    //$$     float partialTick,
    //$$     float ageInTicks,
    //$$     float netHeadYaw,
    //$$     float headPitch
    //$$ ) {
    //$$     this.renderer.renderPlayerBubble(
    //$$         poseStack, player,
    //$$         player.getBbHeight(),
    //$$         player.yBodyRot, player.getYRot(), player.getXRot(),
    //$$         this.getParentModel().head
    //$$     );
    //$$ }
    //#endif
}
//#endif
