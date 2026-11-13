package io.github.kltyton.kltytonui.network.fabric;

import io.github.kltyton.kltytonui.network.api.INetworkContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric client implementation of {@link INetworkContext}.
 */
public record FabricClientNetworkContext(Minecraft client) implements INetworkContext {

    @Override
    public boolean isClientSide() {
        return true;
    }

    @Override
    public boolean isServerSide() {
        return false;
    }

    @Override
    public ServerPlayer sender() {
        return null;
    }

    @Override
    public Minecraft client() {
        return client;
    }

    @Override
    public void enqueueWork(Runnable task) {
        client.execute(task);
    }

    @Override
    public RegistryAccess registryAccess() {
        return client.level == null ? RegistryAccess.EMPTY : client.level.registryAccess();
    }
}
