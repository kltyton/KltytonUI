package io.github.kltyton.kltytonui.network.chunk;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.network.api.INetworkContext;
import io.github.kltyton.kltytonui.network.api.INetworkPacket;
import io.github.kltyton.kltytonui.network.api.NetworkPacket;
import io.github.kltyton.kltytonui.network.api.NetworkPacketTypes;
import io.github.kltyton.kltytonui.network.api.Side;
import io.github.kltyton.kltytonui.network.serialization.NetworkSerialization;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Reassembles packets within the sending connection and releases each reservation once. */
public final class GenericChunkAssembler {
    private static final Map<SessionKey, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong BYTES_IN_ASSEMBLY = new AtomicLong();
    private static final long QUOTA_BYTES = 64L * 1024L * 1024L;
    private static final long EXPIRE_MS = 30_000L;
    private static final ScheduledExecutorService CLEANUP = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GenericChunkAssembler-Cleanup");
        thread.setDaemon(true);
        return thread;
    });

    static {
        CLEANUP.scheduleAtFixedRate(() -> {
            for (var entry : SESSIONS.entrySet()) {
                Session session = entry.getValue();
                if (session.expired() && SESSIONS.remove(entry.getKey(), session)) {
                    session.releaseAll();
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private GenericChunkAssembler() {
    }

    public static void receiveChunk(UUID sessionId, int totalSize, short chunkIndex, short totalChunks,
                                    ResourceLocation typeId, byte[] chunkData, INetworkContext context) {
        if (totalSize <= 0 || totalSize > QUOTA_BYTES || totalChunks <= 0 || totalChunks > totalSize
                || chunkIndex < 0 || chunkIndex >= totalChunks || chunkData.length == 0
                || chunkData.length > totalSize) {
            throw new IllegalArgumentException("Invalid chunk dimensions for " + typeId);
        }
        var packetClass = NetworkPacketTypes.classOf(typeId);
        NetworkPacket metadata = packetClass == null ? null : packetClass.getAnnotation(NetworkPacket.class);
        if (metadata == null || !CustomPacketPayload.class.isAssignableFrom(packetClass)
                || (context.isClientSide() ? metadata.side() == Side.SERVER : metadata.side() == Side.CLIENT)) {
            throw new IllegalArgumentException("Chunk target is not registered for this direction: " + typeId);
        }
        var sender = context.sender();
        if (context.isServerSide() && sender == null) {
            throw new IllegalStateException("Server chunk context has no sender");
        }
        SessionKey key = new SessionKey(sessionId, sender == null ? null : sender.getUUID(), context.isClientSide());
        Session session = SESSIONS.computeIfAbsent(key, ignored -> {
            long reserved = BYTES_IN_ASSEMBLY.addAndGet(totalSize);
            if (reserved > QUOTA_BYTES) {
                BYTES_IN_ASSEMBLY.addAndGet(-totalSize);
                KltytonUI.LOGGER.warn("Chunk assembly quota exceeded for {}", typeId);
                return null;
            }
            return new Session(totalChunks, totalSize, typeId);
        });
        if (session == null) return;
        try {
            if (session.total != totalChunks || session.totalSize != totalSize || !session.typeId.equals(typeId)) {
                throw new IllegalArgumentException("Chunk metadata changed for " + sessionId);
            }
            if (!session.add(chunkIndex, chunkData)) return;
        } catch (RuntimeException failure) {
            if (SESSIONS.remove(key, session)) session.releaseAll();
            throw failure;
        }
        if (!SESSIONS.remove(key, session)) return;
        CompositeByteBuf composite = null;
        try {
            composite = session.assembleComposite();
            @SuppressWarnings("unchecked")
            Class<CustomPacketPayload> payloadClass = (Class<CustomPacketPayload>) (Class<?>) packetClass;
            var codec = NetworkSerialization.autoCodec(payloadClass);
            var payload = codec.decode(new RegistryFriendlyByteBuf(composite, context.registryAccess()));
            if (!(payload instanceof INetworkPacket<?> packet)) {
                throw new IllegalStateException("Chunk target has no packet handler: " + typeId);
            }
            context.enqueueWork(() -> packet.handle(context));
        } finally {
            ReferenceCountUtil.release(composite);
            session.releaseAll();
        }
    }

    private record SessionKey(UUID sessionId, UUID sender, boolean clientSide) {
    }

    private static final class Session {
        final int total;
        final int totalSize;
        final ResourceLocation typeId;
        final BitSet received;
        final ByteBuf[] parts;
        final long startMs = System.currentTimeMillis();
        int count;
        int receivedBytes;
        boolean released;

        Session(int total, int totalSize, ResourceLocation typeId) {
            this.total = total;
            this.totalSize = totalSize;
            this.typeId = typeId;
            received = new BitSet(total);
            parts = new ByteBuf[total];
        }

        synchronized boolean add(int index, byte[] data) {
            if (released) return false;
            if (!received.get(index)) {
                if (receivedBytes + data.length > totalSize) {
                    throw new IllegalArgumentException("Chunk data exceeds declared size for " + typeId);
                }
                parts[index] = Unpooled.wrappedBuffer(data);
                received.set(index);
                receivedBytes += data.length;
                count++;
            }
            if (count == total && receivedBytes != totalSize) {
                throw new IllegalArgumentException("Chunk data does not match declared size for " + typeId);
            }
            return count == total;
        }

        CompositeByteBuf assembleComposite() {
            CompositeByteBuf composite = Unpooled.compositeBuffer(total);
            try {
                for (int i = 0; i < total; i++) {
                    composite.addComponent(true, parts[i]);
                    parts[i] = null;
                }
                return composite;
            } catch (RuntimeException failure) {
                composite.release();
                throw failure;
            }
        }

        boolean expired() {
            return System.currentTimeMillis() - startMs > EXPIRE_MS;
        }

        synchronized void releaseAll() {
            if (released) return;
            released = true;
            for (int i = 0; i < total; i++) {
                ReferenceCountUtil.release(parts[i]);
                parts[i] = null;
            }
            BYTES_IN_ASSEMBLY.addAndGet(-totalSize);
        }
    }
}
