//#if FABRIC
package dev.nexbit.bitcam.fabric.network;

import dev.nexbit.bitcam.common.BitCamMetadata;
import dev.nexbit.bitcam.protocol.signal.BitCamSignalCodec;
import dev.nexbit.bitcam.protocol.signal.BitCamSignalPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record FabricBitCamControlPayload(byte[] payload) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "control");
    public static final Type<FabricBitCamControlPayload> TYPE = new Type<>(CHANNEL);
    public static final StreamCodec<RegistryFriendlyByteBuf, FabricBitCamControlPayload> CODEC = CustomPacketPayload.codec(
        (value, buffer) -> buffer.writeByteArray(value.payload),
        buffer -> new FabricBitCamControlPayload(buffer.readByteArray())
    );

    public static FabricBitCamControlPayload fromSignalPacket(BitCamSignalPacket packet) {
        return new FabricBitCamControlPayload(BitCamSignalCodec.encode(packet));
    }

    public BitCamSignalPacket decodeSignalPacket() {
        return BitCamSignalCodec.decode(this.payload);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//#endif
