package dev.nexbit.bitcam.protocol.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class BitCamBinaryWriter {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final DataOutputStream data = new DataOutputStream(this.output);

    public void writeInt(int value) throws IOException {
        this.data.writeInt(value);
    }

    public void writeLong(long value) throws IOException {
        this.data.writeLong(value);
    }

    public void writeBoolean(boolean value) throws IOException {
        this.data.writeBoolean(value);
    }

    public void writeUuid(UUID uuid) throws IOException {
        this.data.writeLong(uuid.getMostSignificantBits());
        this.data.writeLong(uuid.getLeastSignificantBits());
    }

    public void writeString(String value) throws IOException {
        this.writeByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    public void writeByteArray(byte[] bytes) throws IOException {
        this.data.writeInt(bytes.length);
        this.data.write(bytes);
    }

    public byte[] toByteArray() {
        return this.output.toByteArray();
    }
}
