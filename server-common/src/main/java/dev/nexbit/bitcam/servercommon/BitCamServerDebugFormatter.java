package dev.nexbit.bitcam.servercommon;

import dev.nexbit.bitcam.protocol.udp.BitCamBubbleStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

public final class BitCamServerDebugFormatter {
    private BitCamServerDebugFormatter() {
    }

    public static List<String> summaryLines(BitCamServerDebugSnapshot snapshot) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add(
            "BitCam UDP "
                + snapshot.udpHost()
                + ":"
                + snapshot.udpPort()
                + " | mtu="
                + snapshot.mtu()
                + " | frame="
                + snapshot.width()
                + "x"
                + snapshot.height()
                + "@"
                + snapshot.fps()
                + " | quality="
                + String.format(Locale.ROOT, "%.2f", snapshot.quality())
                + " | radius="
                + snapshot.radius()
        );
        lines.add(
            "Sessions="
                + snapshot.sessionCount()
                + " | activeReceivers="
                + snapshot.activeReceiverCount()
                + " | activeStreams="
                + snapshot.activeStreamCount()
        );
        return lines;
    }

    public static List<String> clientLines(BitCamServerDebugSnapshot snapshot, Function<UUID, String> nameResolver) {
        ArrayList<String> lines = new ArrayList<>(summaryLines(snapshot));
        if (snapshot.clients().isEmpty()) {
            lines.add("No BitCam sessions are currently registered.");
            return lines;
        }

        for (BitCamClientDebugSnapshot client : snapshot.clients()) {
            String name = nameResolver == null ? shortId(client.playerId()) : nameResolver.apply(client.playerId());
            if (name == null || name.isBlank()) {
                name = shortId(client.playerId());
            }

            lines.add(
                name
                    + " | player="
                    + shortId(client.playerId())
                    + " | session="
                    + shortId(client.sessionId())
                    + " | udp="
                    + client.address()
                    + " | send="
                    + client.sendEnabled()
                    + " | recv="
                    + client.receiveEnabled()
                    + " | lastSeen="
                    + formatAge(client.lastSeenAgeMillis())
            );

            BitCamStreamDebugSnapshot stream = client.stream();
            if (stream == null) {
                lines.add("  stream=idle");
                continue;
            }

            BitCamBubbleStyle style = stream.bubbleStyle();
            lines.add(
                "  stream="
                    + stream.width()
                    + "x"
                    + stream.height()
                    + " frameId="
                    + stream.frameId()
                    + " fragments="
                    + stream.fragmentCount()
                    + " codec="
                    + stream.codec()
                    + " keyFrame="
                    + stream.keyFrame()
                    + " viewers="
                    + stream.viewerCount()
                    + " captureAge="
                    + formatAge(stream.captureAgeMillis())
                    + " packetAge="
                    + formatAge(stream.lastPacketAgeMillis())
            );
            lines.add(
                "  bubble="
                    + style.preset()
                    + "/"
                    + style.shape()
                    + "/"
                    + style.renderMode()
                    + "/"
                    + style.contentMode()
                    + " scale="
                    + style.scalePercent()
                    + "% pos="
                    + style.xOffsetPercent()
                    + ","
                    + style.yOffsetPercent()
                    + " opacity="
                    + style.opacityPercent()
                    + "% camera="
                    + style.contentZoomPercent()
                    + "%@"
                    + style.contentXOffsetPercent()
                    + ","
                    + style.contentYOffsetPercent()
            );
        }
        return lines;
    }

    private static String formatAge(long millis) {
        if (millis < 1_000L) {
            return millis + "ms";
        }

        if (millis < 60_000L) {
            return String.format(Locale.ROOT, "%.1fs", millis / 1_000.0D);
        }

        return String.format(Locale.ROOT, "%.1fm", millis / 60_000.0D);
    }

    private static String shortId(UUID id) {
        String value = id == null ? "unknown" : id.toString();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }
}
