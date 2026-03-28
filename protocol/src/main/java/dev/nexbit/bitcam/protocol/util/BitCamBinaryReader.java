package dev.nexbit.bitcam.protocol.util;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class BitCamBinaryReader {
    private final DataInputStream input;

    public BitCamBinaryReader(byte[] bytes) {
        this.input = new DataInputStream(new ByteArrayInputStream(bytes));
    }

    public int readInt() throws IOException {
        return this.input.readInt();
    }

    public long readLong() throws IOException {
        return this.input.readLong();
    }

    public boolean readBoolean() throws IOException {
        return this.input.readBoolean();
    }

    public UUID readUuid() throws IOException {
        return new UUID(this.input.readLong(), this.input.readLong());
    }

    public String readString() throws IOException {
        byte[] bytes = this.readByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public byte[] readByteArray() throws IOException {
        int length = this.input.readInt();
        byte[] bytes = new byte[length];
        this.input.readFully(bytes);
        return bytes;
    }
}
