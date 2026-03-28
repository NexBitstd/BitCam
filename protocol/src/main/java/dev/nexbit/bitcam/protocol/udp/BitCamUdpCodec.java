package dev.nexbit.bitcam.protocol.udp;

import dev.nexbit.bitcam.protocol.util.BitCamBinaryReader;
import dev.nexbit.bitcam.protocol.util.BitCamBinaryWriter;
import java.io.IOException;
import java.io.InvalidObjectException;

public final class BitCamUdpCodec {
    private BitCamUdpCodec() {
    }

    public static byte[] encode(BitCamUdpPacket packet) {
        try {
            BitCamBinaryWriter writer = new BitCamBinaryWriter();
            writer.writeInt(packet.type().ordinal());

            switch (packet.type()) {
                case SESSION_REQUEST -> encodeSessionRequest(writer, (SessionRequestUdpPacket) packet);
                case SESSION_ACCEPTED -> encodeSessionAccepted(writer, (SessionAcceptedUdpPacket) packet);
                case KEEP_ALIVE -> encodeKeepAlive(writer, (KeepAliveUdpPacket) packet);
                case VIDEO_FRAME -> encodeVideoFrame(writer, (VideoFrameUdpPacket) packet);
                case STREAM_STOPPED -> encodeStreamStopped(writer, (StreamStoppedUdpPacket) packet);
            }

            return writer.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode UDP packet " + packet.type(), exception);
        }
    }

    public static BitCamUdpPacket decode(byte[] bytes) {
        try {
            if (bytes.length < Integer.BYTES) {
                throw new InvalidObjectException("packet too short: " + bytes.length + " bytes");
            }

            BitCamBinaryReader reader = new BitCamBinaryReader(bytes);
            UdpPacketType type = decodeEnum(reader.readInt(), UdpPacketType.values(), "udp packet type");

            return switch (type) {
                case SESSION_REQUEST -> decodeSessionRequest(reader);
                case SESSION_ACCEPTED -> decodeSessionAccepted(reader);
                case KEEP_ALIVE -> decodeKeepAlive(reader);
                case VIDEO_FRAME -> decodeVideoFrame(reader);
                case STREAM_STOPPED -> decodeStreamStopped(reader);
            };
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to decode UDP packet (" + bytes.length + " bytes)", exception);
        }
    }

    private static void encodeSessionRequest(BitCamBinaryWriter writer, SessionRequestUdpPacket packet) throws IOException {
        writer.writeInt(packet.protocolVersion());
        writer.writeUuid(packet.sessionId());
        writer.writeByteArray(packet.secret());
        writer.writeBoolean(packet.sendEnabled());
        writer.writeBoolean(packet.receiveEnabled());
    }

    private static SessionRequestUdpPacket decodeSessionRequest(BitCamBinaryReader reader) throws IOException {
        return new SessionRequestUdpPacket(
            reader.readInt(),
            reader.readUuid(),
            reader.readByteArray(),
            reader.readBoolean(),
            reader.readBoolean()
        );
    }

    private static void encodeSessionAccepted(BitCamBinaryWriter writer, SessionAcceptedUdpPacket packet) throws IOException {
        writer.writeUuid(packet.sessionId());
    }

    private static SessionAcceptedUdpPacket decodeSessionAccepted(BitCamBinaryReader reader) throws IOException {
        return new SessionAcceptedUdpPacket(reader.readUuid());
    }

    private static void encodeKeepAlive(BitCamBinaryWriter writer, KeepAliveUdpPacket packet) throws IOException {
        writer.writeUuid(packet.sessionId());
    }

    private static KeepAliveUdpPacket decodeKeepAlive(BitCamBinaryReader reader) throws IOException {
        return new KeepAliveUdpPacket(reader.readUuid());
    }

    private static void encodeVideoFrame(BitCamBinaryWriter writer, VideoFrameUdpPacket packet) throws IOException {
        writer.writeUuid(packet.streamerId());
        writer.writeInt(packet.frameId());
        writer.writeInt(packet.fragmentIndex());
        writer.writeInt(packet.fragmentCount());
        writer.writeInt(packet.width());
        writer.writeInt(packet.height());
        writer.writeLong(packet.captureTimeMillis());
        writer.writeInt(packet.bubbleStyle().preset().ordinal());
        writer.writeInt(packet.bubbleStyle().shape().ordinal());
        writer.writeInt(packet.bubbleStyle().renderMode().ordinal());
        writer.writeInt(packet.bubbleStyle().scalePercent());
        writer.writeInt(packet.bubbleStyle().xOffsetPercent());
        writer.writeInt(packet.bubbleStyle().yOffsetPercent());
        writer.writeInt(packet.bubbleStyle().opacityPercent());
        writer.writeInt(packet.bubbleStyle().contentMode().ordinal());
        writer.writeInt(packet.bubbleStyle().contentZoomPercent());
        writer.writeInt(packet.bubbleStyle().contentXOffsetPercent());
        writer.writeInt(packet.bubbleStyle().contentYOffsetPercent());
        writer.writeBoolean(packet.keyFrame());
        writer.writeByteArray(packet.payload());
    }

    private static VideoFrameUdpPacket decodeVideoFrame(BitCamBinaryReader reader) throws IOException {
        return new VideoFrameUdpPacket(
            reader.readUuid(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readLong(),
            new BitCamBubbleStyle(
                decodeEnum(reader.readInt(), BitCamBubblePreset.values(), "bubble preset"),
                decodeEnum(reader.readInt(), BitCamBubbleShape.values(), "bubble shape"),
                decodeEnum(reader.readInt(), BitCamBubbleRenderMode.values(), "bubble render mode"),
                reader.readInt(),
                reader.readInt(),
                reader.readInt(),
                reader.readInt(),
                decodeEnum(reader.readInt(), BitCamBubbleContentMode.values(), "bubble content mode"),
                reader.readInt(),
                reader.readInt(),
                reader.readInt()
            ),
            reader.readBoolean(),
            reader.readByteArray()
        );
    }

    private static void encodeStreamStopped(BitCamBinaryWriter writer, StreamStoppedUdpPacket packet) throws IOException {
        writer.writeUuid(packet.streamerId());
    }

    private static StreamStoppedUdpPacket decodeStreamStopped(BitCamBinaryReader reader) throws IOException {
        return new StreamStoppedUdpPacket(reader.readUuid());
    }

    private static <E extends Enum<E>> E decodeEnum(int ordinal, E[] values, String label) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new InvalidObjectException("Invalid " + label + " ordinal: " + ordinal);
        }

        return values[ordinal];
    }
}
