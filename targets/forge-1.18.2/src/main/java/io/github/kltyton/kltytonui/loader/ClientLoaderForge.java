package io.github.kltyton.kltytonui.loader;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.dev.debug.ExternalDebugServer;
import io.github.kltyton.kltytonui.forge.ResourceService;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.List;

/**
 * Forge client-setup hook for {@link ClientLoader}.
 *
 * <p>The shared client-resource reload logic lives in {@code common}; only the
 * event wiring (registration, enqueue work) is loader-specific and lives here.</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, modid = KltytonUI.MODID, value = Dist.CLIENT)
public final class ClientLoaderForge {
    private static boolean initialResourceReload = true;

    private ClientLoaderForge() {
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ExternalDebugServer.startIfEnabled();
            ClientLoader.reloadResources();
        });
    }

    @SubscribeEvent
    public static void registerResourceReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<ClientLoader.ThemeResourceSnapshot>() {
            @Override
            protected ClientLoader.ThemeResourceSnapshot prepare(ResourceManager manager, ProfilerFiller profiler) {
                return ClientLoader.prepareThemeSnapshot(ResourceService.listThemeResourcePaths(manager));
            }

            @Override
            protected void apply(ClientLoader.ThemeResourceSnapshot snapshot, ResourceManager manager, ProfilerFiller profiler) {
                if (initialResourceReload) {
                    initialResourceReload = false;
                    ClientLoader.publishThemeSnapshot(snapshot);
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    io.github.kltyton.kltytonui.client.model.ModelRenderService.invalidate();
                    ClientLoader.reloadResources(snapshot);
                });
            }
        });
    }
}
