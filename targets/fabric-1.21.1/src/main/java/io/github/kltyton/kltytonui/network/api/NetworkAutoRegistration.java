package io.github.kltyton.kltytonui.network.api;

import io.github.kltyton.kltytonui.util.AnnotationScanUtil;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class NetworkAutoRegistration {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Set<Class<? extends INetworkPacket<?>>> REGISTERED_PACKET_CLASSES = ConcurrentHashMap.newKeySet();
    private static final CopyOnWriteArrayList<Consumer<Class<? extends INetworkPacket<?>>>> PACKET_LISTENERS =
            new CopyOnWriteArrayList<>();

    private NetworkAutoRegistration() {
    }

    public static void addPacketRegistrationListener(Consumer<Class<? extends INetworkPacket<?>>> listener) {
        if (listener != null) {
            PACKET_LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removePacketRegistrationListener(Consumer<Class<? extends INetworkPacket<?>>> listener) {
        if (listener != null) {
            PACKET_LISTENERS.remove(listener);
        }
    }

    public static Set<Class<? extends INetworkPacket<?>>> findAllAnnotatedPackets() {
        Predicate<Class<?>> filter = AnnotationScanUtil.nonAbstractNonInterface()
                .and(INetworkPacket.class::isAssignableFrom)
                .and(CustomPacketPayload.class::isAssignableFrom);
        Set<Class<?>> classes = AnnotationScanUtil.findAnnotatedClasses(NetworkPacket.class, filter);

        int added = 0;
        int skipped = 0;

        for (Class<?> clazz : classes) {
            @SuppressWarnings("unchecked")
            Class<? extends INetworkPacket<?>> packetClass =
                    (Class<? extends INetworkPacket<?>>) clazz;

            if (!REGISTERED_PACKET_CLASSES.add(packetClass)) {
                skipped++;
                continue;
            }

            try {
                notifyPacketDiscovered(packetClass);
            } catch (RuntimeException exception) {
                REGISTERED_PACKET_CLASSES.remove(packetClass);
                throw exception;
            }
            added++;

            LOGGER.debug("[NetworkAutoReg] Found packet: {} (chunkThreshold={})",
                    packetClass.getName(),
                    getChunkThreshold(packetClass));
        }

        LOGGER.info("[NetworkAutoReg] Metadata scan found: {} | added: {} | skipped: {}",
                classes.size(), added, skipped);
        return Set.copyOf(REGISTERED_PACKET_CLASSES);
    }

    private static void notifyPacketDiscovered(Class<? extends INetworkPacket<?>> packetClass) {
        for (var listener : PACKET_LISTENERS) {
            listener.accept(packetClass);
        }
    }

    public static int getChunkThreshold(Class<?> clazz) {
        NetworkPacket annotation = clazz.getAnnotation(NetworkPacket.class);
        return annotation != null ? annotation.chunkThreshold() : 0;
    }
}
