package dev.nexbit.bitcam.fabric.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;

public final class FabricBitCamPlayerBubbleLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
    private final BitCamBillboardRenderer renderer;

    public FabricBitCamPlayerBubbleLayer(
        RenderLayerParent<PlayerRenderState, PlayerModel> parent,
        BitCamBillboardRenderer renderer
    ) {
        super(parent);
        this.renderer = renderer;
    }

    @Override
    public void render(
        PoseStack poseStack,
        MultiBufferSource multiBufferSource,
        int packedLight,
        PlayerRenderState renderState,
        float limbSwing,
        float limbSwingAmount
    ) {
        this.renderer.renderPlayerLayer(poseStack, multiBufferSource, packedLight, renderState);
    }
}
