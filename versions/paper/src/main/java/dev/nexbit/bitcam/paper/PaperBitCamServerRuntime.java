package dev.nexbit.bitcam.paper;

import dev.nexbit.bitcam.common.BitCamBootstrap;
import dev.nexbit.bitcam.common.BitCamPermissionExpressions;
import dev.nexbit.bitcam.paper.platform.PaperPlatformAccess;
import dev.nexbit.bitcam.protocol.BitCamProtocol;
import dev.nexbit.bitcam.protocol.signal.BitCamSignalCodec;
import dev.nexbit.bitcam.protocol.signal.ClientHelloSignalPacket;
import dev.nexbit.bitcam.servercommon.BitCamServerCoordinator;
import dev.nexbit.bitcam.servercommon.BitCamServerDebugFormatter;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class PaperBitCamServerRuntime implements Listener, PluginMessageListener, AutoCloseable {
    private final BitCamPaperPlugin plugin;
    private final PaperPlatformAccess platform;
    private final BitCamServerCoordinator coordinator;

    public PaperBitCamServerRuntime(BitCamPaperPlugin plugin, String minecraftVersion) {
        this.plugin = plugin;
        this.platform = new PaperPlatformAccess(plugin, minecraftVersion);
        this.coordinator = new BitCamServerCoordinator(this.platform, this::resolveViewers, this::hasPermission);
    }

    public void initialize() {
        BitCamBootstrap.bootstrapServer(this.platform);
        this.coordinator.start();

        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        Bukkit.getMessenger().registerIncomingPluginChannel(this.plugin, BitCamProtocol.CONTROL_CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this.plugin, BitCamProtocol.CONTROL_CHANNEL);
    }

    private List<UUID> resolveViewers(UUID streamerId, int radius) {
        Player streamer = Bukkit.getPlayer(streamerId);
        if (streamer == null) {
            return List.of();
        }

        double radiusSquared = (double) radius * radius;
        List<UUID> viewers = new ArrayList<>();

        for (Player viewer : streamer.getWorld().getPlayers()) {
            if (viewer.getUniqueId().equals(streamerId)) {
                continue;
            }

            if (viewer.getLocation().distanceSquared(streamer.getLocation()) > radiusSquared) {
                continue;
            }

            viewers.add(viewer.getUniqueId());
        }

        return viewers;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BitCamProtocol.CONTROL_CHANNEL.equals(channel)) {
            return;
        }

        if (this.coordinator == null) {
            return;
        }

        byte[] payload;
        try {
            payload = unwrapCustomPayloadBytes(message);
        } catch (IllegalArgumentException exception) {
            this.platform.logger().warn("Ignoring malformed BitCam plugin message from " + player.getName() + ": " + exception.getMessage());
            return;
        }

        if (!(BitCamSignalCodec.decode(payload) instanceof ClientHelloSignalPacket hello)) {
            return;
        }

        this.coordinator
            .createWelcomePacket(player.getUniqueId(), hello)
            .ifPresent(welcome -> player.sendPluginMessage(this.plugin, BitCamProtocol.CONTROL_CHANNEL, wrapCustomPayloadBytes(BitCamSignalCodec.encode(welcome))));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.coordinator.removeSession(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this.plugin, BitCamProtocol.CONTROL_CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this.plugin, BitCamProtocol.CONTROL_CHANNEL);
        this.coordinator.close();
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bitcam.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use BitCam debug commands.");
            return true;
        }

        if (args.length == 0 || !"debug".equalsIgnoreCase(args[0])) {
            sender.sendMessage(ChatColor.RED + "Usage: /bitcam debug [clients]");
            return true;
        }

        boolean verbose = args.length > 1 && "clients".equalsIgnoreCase(args[1]);
        List<String> lines = verbose
            ? BitCamServerDebugFormatter.clientLines(this.coordinator.debugSnapshot(), playerId -> {
                Player player = Bukkit.getPlayer(playerId);
                return player == null ? null : player.getName();
            })
            : BitCamServerDebugFormatter.summaryLines(this.coordinator.debugSnapshot());

        for (String line : lines) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        return true;
    }

    private boolean hasPermission(UUID playerId, String permissionExpression) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return false;
        }

        return BitCamPermissionExpressions.allows(
            permissionExpression,
            level -> level <= 0 || player.isOp(),
            player::hasPermission
        );
    }

    private static byte[] unwrapCustomPayloadBytes(byte[] message) {
        if (message == null || message.length == 0) {
            throw new IllegalArgumentException("Empty plugin payload");
        }

        int[] decode = readVarInt(message, 0);
        int payloadLength = decode[0];
        int offset = decode[1];

        if (payloadLength < 0) {
            throw new IllegalArgumentException("Negative payload length");
        }

        if (offset + payloadLength > message.length) {
            throw new IllegalArgumentException("Payload length " + payloadLength + " exceeds message size " + message.length);
        }

        byte[] payload = new byte[payloadLength];
        System.arraycopy(message, offset, payload, 0, payloadLength);
        return payload;
    }

    private static byte[] wrapCustomPayloadBytes(byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + 5);
        writeVarInt(output, payload.length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    private static int[] readVarInt(byte[] bytes, int offset) {
        int value = 0;
        int position = 0;
        int cursor = offset;

        while (cursor < bytes.length) {
            int currentByte = bytes[cursor++] & 0xFF;
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) {
                return new int[] {value, cursor};
            }

            position += 7;
            if (position >= 35) {
                throw new IllegalArgumentException("VarInt is too large");
            }
        }

        throw new IllegalArgumentException("Incomplete VarInt");
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.write(remaining);
    }
}
