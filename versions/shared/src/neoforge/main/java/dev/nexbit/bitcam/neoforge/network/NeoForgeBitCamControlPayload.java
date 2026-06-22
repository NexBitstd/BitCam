//#if NEOFORGE
//$$ package dev.nexbit.bitcam.neoforge.network;
//$$
//$$ import dev.nexbit.bitcam.common.BitCamMetadata;
//$$ import dev.nexbit.bitcam.protocol.signal.BitCamSignalCodec;
//$$ import dev.nexbit.bitcam.protocol.signal.BitCamSignalPacket;
//$$ import net.minecraft.network.RegistryFriendlyByteBuf;
//$$ import net.minecraft.network.codec.StreamCodec;
//$$ import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
        //#if MC>=12111
//$$ import net.minecraft.resources.Identifier;
        //#else
//$$ import net.minecraft.resources.ResourceLocation;
        //#endif
//$$
//$$ public record NeoForgeBitCamControlPayload(byte[] payload) implements CustomPacketPayload {
        //#if MC>=12111
//$$     public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "control");
        //#else
//$$     public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath(BitCamMetadata.MOD_ID, "control");
        //#endif
//$$     public static final Type<NeoForgeBitCamControlPayload> TYPE = new Type<>(CHANNEL);
//$$     public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeBitCamControlPayload> CODEC = CustomPacketPayload.codec(
//$$         (value, buffer) -> buffer.writeByteArray(value.payload),
//$$         buffer -> new NeoForgeBitCamControlPayload(buffer.readByteArray())
//$$     );
//$$
//$$     public static NeoForgeBitCamControlPayload fromSignalPacket(BitCamSignalPacket packet) {
//$$         return new NeoForgeBitCamControlPayload(BitCamSignalCodec.encode(packet));
//$$     }
//$$
//$$     public BitCamSignalPacket decodeSignalPacket() {
//$$         return BitCamSignalCodec.decode(this.payload);
//$$     }
//$$
//$$     @Override
//$$     public Type<? extends CustomPacketPayload> type() {
//$$         return TYPE;
//$$     }
//$$ }
//#endif
