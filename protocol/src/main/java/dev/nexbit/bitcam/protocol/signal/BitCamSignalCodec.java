package dev.nexbit.bitcam.protocol.signal;

import dev.nexbit.bitcam.protocol.util.BitCamBinaryReader;
import dev.nexbit.bitcam.protocol.util.BitCamBinaryWriter;
import java.io.IOException;

public final class BitCamSignalCodec {
    private BitCamSignalCodec() {
    }

    public static byte[] encode(BitCamSignalPacket packet) {
        try {
            BitCamBinaryWriter writer = new BitCamBinaryWriter();
            writer.writeInt(packet.type().ordinal());

            switch (packet.type()) {
                case CLIENT_HELLO -> encodeClientHello(writer, (ClientHelloSignalPacket) packet);
                case SERVER_WELCOME -> encodeServerWelcome(writer, (ServerWelcomeSignalPacket) packet);
            }

            return writer.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode signal packet " + packet.type(), exception);
        }
    }

    public static BitCamSignalPacket decode(byte[] bytes) {
        try {
            BitCamBinaryReader reader = new BitCamBinaryReader(bytes);
            int typeOrdinal = reader.readInt();
            if (typeOrdinal < 0 || typeOrdinal >= BitCamSignalPacketType.values().length) {
                throw new IllegalStateException("Unsupported BitCam signal packet type ordinal: " + typeOrdinal);
            }

            BitCamSignalPacketType type = BitCamSignalPacketType.values()[typeOrdinal];

            return switch (type) {
                case CLIENT_HELLO -> decodeClientHello(reader);
                case SERVER_WELCOME -> decodeServerWelcome(reader);
            };
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to decode signal packet", exception);
        }
    }

    private static void encodeClientHello(BitCamBinaryWriter writer, ClientHelloSignalPacket packet) throws IOException {
        writer.writeInt(packet.protocolVersion());
        writer.writeString(packet.requestedQualityProfileId());
    }

    private static ClientHelloSignalPacket decodeClientHello(BitCamBinaryReader reader) throws IOException {
        return new ClientHelloSignalPacket(reader.readInt(), reader.readString());
    }

    private static void encodeServerWelcome(BitCamBinaryWriter writer, ServerWelcomeSignalPacket packet) throws IOException {
        writer.writeInt(packet.protocolVersion());
        writer.writeString(packet.udpHost());
        writer.writeInt(packet.udpPort());
        writer.writeUuid(packet.sessionId());
        writer.writeByteArray(packet.secret());
        writer.writeInt(packet.mtu());
        writer.writeInt(packet.width());
        writer.writeInt(packet.height());
        writer.writeInt(packet.fps());
        writer.writeInt(Float.floatToIntBits(packet.quality()));
        writer.writeString(packet.selectedQualityProfileId());
        writer.writeInt(packet.availableQualityProfiles().size());
        for (BitCamStreamQualityProfile profile : packet.availableQualityProfiles()) {
            writer.writeString(profile.id());
            writer.writeString(profile.displayName());
            writer.writeInt(profile.width());
            writer.writeInt(profile.height());
            writer.writeInt(profile.fps());
            writer.writeInt(Float.floatToIntBits(profile.quality()));
        }
        writer.writeInt(packet.radius());
    }

    private static ServerWelcomeSignalPacket decodeServerWelcome(BitCamBinaryReader reader) throws IOException {
        int protocolVersion = reader.readInt();
        String udpHost = reader.readString();
        int udpPort = reader.readInt();
        var sessionId = reader.readUuid();
        byte[] secret = reader.readByteArray();
        int mtu = reader.readInt();
        int width = reader.readInt();
        int height = reader.readInt();
        int fps = reader.readInt();
        float quality = Float.intBitsToFloat(reader.readInt());
        String selectedQualityProfileId = reader.readString();
        int profileCount = reader.readInt();
        java.util.ArrayList<BitCamStreamQualityProfile> profiles = new java.util.ArrayList<>(Math.max(0, profileCount));
        for (int index = 0; index < profileCount; index++) {
            profiles.add(new BitCamStreamQualityProfile(
                reader.readString(),
                reader.readString(),
                reader.readInt(),
                reader.readInt(),
                reader.readInt(),
                Float.intBitsToFloat(reader.readInt())
            ));
        }
        return new ServerWelcomeSignalPacket(
            protocolVersion,
            udpHost,
            udpPort,
            sessionId,
            secret,
            mtu,
            width,
            height,
            fps,
            quality,
            selectedQualityProfileId,
            profiles,
            reader.readInt()
        );
    }
}
