package io.github.kltyton.kltytonui.network.fabric;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.network.NetworkPlatform;
import io.github.kltyton.kltytonui.network.api.INetworkPacket;
import io.github.kltyton.kltytonui.network.api.NetworkAutoRegistration;
import io.github.kltyton.kltytonui.network.api.NetworkPacket;
import io.github.kltyton.kltytonui.network.api.NetworkPacketTypes;
import io.github.kltyton.kltytonui.network.api.Side;
import io.github.kltyton.kltytonui.network.serialization.NetworkSerialization;
import io.github.kltyton.kltytonui.network.spi.INetworkManager;
import io.github.kltyton.kltytonui.network.util.NetworkUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NetworkManagerImpl implements INetworkManager {

    private static final Map<CustomPacketPayload.Type<?>, NetworkUtil.PacketInfo<?>> REGISTERED_PACKETS =
            new ConcurrentHashMap<>();
    private static final Set<CustomPacketPayload.Type<?>> SERVERBOUND_REGISTERED = ConcurrentHashMap.newKeySet();
    private static final Set<CustomPacketPayload.Type<?>> CLIENTBOUND_REGISTERED = ConcurrentHashMap.newKeySet();
    private static final Set<CustomPacketPayload.Type<?>> COMMON_RECEIVERS = ConcurrentHashMap.newKeySet();
    private static final Set<CustomPacketPayload.Type<?>> CLIENT_RECEIVERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean autoRegistrationHookInstalled;
    private static volatile boolean commonInitialized;
    private static volatile boolean clientInitialized;

    public NetworkManagerImpl() {
    }

    public static void initialize() {
        commonInitialized = true;
        installAutoRegistrationHook();
        processRegistration(RegistrationPhase.COMMON);
    }

    public static void initializeClient() {
        clientInitialized = true;
        installAutoRegistrationHook();
        processRegistration(RegistrationPhase.CLIENT);
    }

    private static void installAutoRegistrationHook() {
        if (autoRegistrationHookInstalled) {
            return;
        }
        autoRegistrationHookInstalled = true;
        NetworkAutoRegistration.addPacketRegistrationListener(packetClass -> {
            NetworkManagerImpl impl = new NetworkManagerImpl();
            if (commonInitialized) {
                impl.registerAnnotated(packetClass, RegistrationPhase.COMMON);
            }
            if (clientInitialized) {
                impl.registerAnnotated(packetClass, RegistrationPhase.CLIENT);
            }
        });
    }

    private static void processRegistration(RegistrationPhase phase) {
        NetworkManagerImpl impl = new NetworkManagerImpl();
        for (Class<? extends INetworkPacket<?>> packetClass : NetworkAutoRegistration.findAllAnnotatedPackets()) {
            impl.registerAnnotated(packetClass, phase);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends INetworkPacket<T> & CustomPacketPayload> void registerAnnotated(
            Class<? extends INetworkPacket<?>> rawClass,
            RegistrationPhase phase
    ) {
        Class<T> clazz = (Class<T>) rawClass;
        NetworkPacket meta = clazz.getAnnotation(NetworkPacket.class);
        if (meta == null || !clazz.isRecord()) {
            return;
        }

        CustomPacketPayload.Type<T> type = NetworkPacketTypes.typeOf(clazz);
        StreamCodec<RegistryFriendlyByteBuf, T> codec = NetworkSerialization.autoCodec(clazz);
        REGISTERED_PACKETS.putIfAbsent(type, new NetworkUtil.PacketInfo<>(type, codec));

        Side side = meta.side();
        if (side == Side.SERVER || side == Side.BOTH) {
            if (SERVERBOUND_REGISTERED.add(type)) {
                PayloadTypeRegistry.playC2S().register(type, codec);
            }
            if (phase == RegistrationPhase.COMMON && COMMON_RECEIVERS.add(type)) {
                ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                        context.server().execute(() -> payload.handle(
                                new FabricServerNetworkContext(context.server(), context.player()))));
                KltytonUI.LOGGER.debug("[KUI Network] Common receiver registered: {} ({})", clazz.getSimpleName(), type.id());
            }
        }
        if (side == Side.CLIENT || side == Side.BOTH) {
            if (CLIENTBOUND_REGISTERED.add(type)) {
                PayloadTypeRegistry.playS2C().register(type, codec);
            }
            if (phase == RegistrationPhase.CLIENT && CLIENT_RECEIVERS.add(type)) {
                ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                        context.client().execute(() -> payload.handle(
                                new FabricClientNetworkContext(context.client()))));
                KltytonUI.LOGGER.debug("[KUI Network] Client receiver registered: {} ({})", clazz.getSimpleName(), type.id());
            }
        }
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToServer(T packet) {
        int threshold = NetworkAutoRegistration.getChunkThreshold(packet.getClass());
        if (threshold <= 0) {
            ClientPlayNetworking.send(packet);
            return;
        }
        NetworkUtil.PacketInfo<T> info = packetInfo(packet);
        RegistryFriendlyByteBuf buf = NetworkUtil.createClientBuffer();
        NetworkUtil.sendWithChunking(
                packet,
                info,
                buf,
                threshold,
                () -> ClientPlayNetworking.send(packet),
                data -> NetworkUtil.sendChunkedPacketToServer(data, packet.type().id(), threshold)
        );
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToPlayer(T packet, ServerPlayer player) {
        if (player == null) {
            return;
        }
        int threshold = NetworkAutoRegistration.getChunkThreshold(packet.getClass());
        if (threshold > 0) {
            sendWithChunking(packet, java.util.Collections.singletonList(player), threshold);
        } else {
            ServerPlayNetworking.send(player, packet);
        }
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToAll(T packet) {
        var server = NetworkPlatform.currentServer();
        if (server == null) {
            throw new IllegalStateException("Cannot sendToAll without a running server");
        }
        Collection<ServerPlayer> players = PlayerLookup.all(server);
        if (players.isEmpty()) {
            return;
        }
        int threshold = NetworkAutoRegistration.getChunkThreshold(packet.getClass());
        if (threshold > 0) {
            NetworkUtil.PacketInfo<T> info = packetInfo(packet);
            RegistryFriendlyByteBuf buf = NetworkUtil.createBufferFromFirstPlayer(players);
            try {
                byte[] data = NetworkUtil.encodePacket(packet, info, buf);
                NetworkUtil.sendChunkedPacketToAll(data, packet.type().id(), threshold);
            } finally {
                buf.release();
            }
        } else {
            for (ServerPlayer player : players) {
                ServerPlayNetworking.send(player, packet);
            }
        }
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToWorld(T packet, ServerLevel level) {
        sendToPlayers(packet, PlayerLookup.world(level), NetworkAutoRegistration.getChunkThreshold(packet.getClass()));
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToNear(
            T packet, ServerLevel level, Vec3 pos, double radius) {
        sendToPlayers(packet, PlayerLookup.around(level, pos, radius),
                NetworkAutoRegistration.getChunkThreshold(packet.getClass()));
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToNearExcept(
            T packet, ServerLevel level, Vec3 pos, double radius, ServerPlayer excluded) {
        Collection<ServerPlayer> players = PlayerLookup.around(level, pos, radius).stream()
                .filter(player -> player != excluded)
                .toList();
        sendToPlayers(packet, players, NetworkAutoRegistration.getChunkThreshold(packet.getClass()));
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToTrackingEntity(T packet, Entity entity) {
        sendToPlayers(packet, PlayerLookup.tracking(entity),
                NetworkAutoRegistration.getChunkThreshold(packet.getClass()));
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToTrackingEntityAndSelf(
            T packet, Entity entity) {
        ArrayList<ServerPlayer> players = new ArrayList<>(PlayerLookup.tracking(entity));
        if (entity instanceof ServerPlayer self && !players.contains(self)) {
            players.add(self);
        }
        sendToPlayers(packet, players, NetworkAutoRegistration.getChunkThreshold(packet.getClass()));
    }

    @Override
    public <T extends INetworkPacket<T> & CustomPacketPayload> void sendToTrackingChunk(
            T packet, ServerLevel level, ChunkPos chunkPos) {
        sendToPlayers(packet, PlayerLookup.tracking(level, chunkPos),
                NetworkAutoRegistration.getChunkThreshold(packet.getClass()));
    }

    private <T extends INetworkPacket<T> & CustomPacketPayload> void sendToPlayers(
            T packet, Collection<ServerPlayer> players, int threshold) {
        if (players.isEmpty()) {
            return;
        }
        if (threshold > 0) {
            sendWithChunking(packet, players, threshold);
        } else {
            for (ServerPlayer player : players) {
                ServerPlayNetworking.send(player, packet);
            }
        }
    }

    private <T extends INetworkPacket<T> & CustomPacketPayload> void sendWithChunking(
            T packet, Collection<ServerPlayer> players, int threshold) {
        if (players.isEmpty()) {
            return;
        }
        NetworkUtil.PacketInfo<T> info = packetInfo(packet);
        RegistryFriendlyByteBuf buf = NetworkUtil.createBufferFromFirstPlayer(players);
        NetworkUtil.sendWithChunking(
                packet,
                info,
                buf,
                threshold,
                () -> {
                    for (ServerPlayer player : players) {
                        ServerPlayNetworking.send(player, packet);
                    }
                },
                data -> NetworkUtil.sendChunkedPacket(data, packet.type().id(), players, threshold)
        );
    }

    @SuppressWarnings("unchecked")
    private static <T extends INetworkPacket<T> & CustomPacketPayload> NetworkUtil.PacketInfo<T> packetInfo(T packet) {
        NetworkUtil.PacketInfo<T> info =
                (NetworkUtil.PacketInfo<T>) REGISTERED_PACKETS.get(packet.type());
        if (info == null) {
            throw new IllegalStateException("Packet is not registered for chunking: " + packet.getClass().getName());
        }
        return info;
    }

    private enum RegistrationPhase {
        COMMON,
        CLIENT
    }
}
