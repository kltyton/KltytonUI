package io.github.kltyton.kltytonui.fabric;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.client.Client;
import io.github.kltyton.kltytonui.client.DebugAIScreenshotTicker;
import io.github.kltyton.kltytonui.client.DebugReloadWatcher;
import io.github.kltyton.kltytonui.client.ClientRuntimeSelfTest;
import io.github.kltyton.kltytonui.client.ClientWptSnapshotRunner;
import io.github.kltyton.kltytonui.client.InitEvent;
import io.github.kltyton.kltytonui.client.KltytonUIClientCommands;
import io.github.kltyton.kltytonui.dom.expander.RecipeExpander;
import io.github.kltyton.kltytonui.loader.ClientLoader;
import io.github.kltyton.kltytonui.network.api.NetworkAutoRegistration;
import io.github.kltyton.kltytonui.network.fabric.NetworkManagerImpl;
import io.github.kltyton.kltytonui.registry.KltytonUIRegistry;
import io.github.kltyton.kltytonui.registry.ClientMenuScreens;
import io.github.kltyton.kltytonui.registry.Keybindings;
import io.github.kltyton.kltytonui.world.WorldWindow;
import io.github.kltyton.kltytonui.world.WorldWindowTestSpawner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

public final class KltytonUIFabricClient implements ClientModInitializer {
    private static final boolean DEVELOPMENT_TESTS_PRESENT = KltytonUI.class.getResource(
            "/io/github/kltyton/kltytonui/client/ClientRuntimeSelfTest.class") != null;
    public void onInitializeClient() {
        FabricServicesBootstrap.initClient();
        NetworkManagerImpl.initializeClient();
        NetworkAutoRegistration.findAllAnnotatedPackets();
        // The common entrypoint runs before the client service exists. Register
        // the scan scope after installing the real Fabric client service.
        KltytonUIRegistry.scanPackages("io.github.kltyton.kltytonui.element");
        InitEvent.init();
        FabricShaderRegistry.register();
        registerKeys();
        KltytonUIRegistry.register();
        ClientMenuScreens.register();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                KltytonUIClientCommands.register(dispatcher));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> RecipeExpander.clearCache());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RecipeExpander.clearCache());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Client.tick();
            InitEvent.tick();
            DebugReloadWatcher.tick();
            DebugAIScreenshotTicker.tick();
            if (DEVELOPMENT_TESTS_PRESENT && !io.github.kltyton.kltytonui.spi.KuiServices.client().isProduction()) {
                ClientRuntimeSelfTest.tick();
                ClientWptSnapshotRunner.tick();
                WorldWindowTestSpawner.tick();
            }
        });
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            if (Minecraft.getInstance().screen != null) return;
            io.github.kltyton.kltytonui.init.Window.window.fireAnimationFrame();
            Client.drawOverlayLike(graphics);
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (WorldWindow.windows.isEmpty()) return;
            float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
            // 1.21.1 的 matrixStack 只是局部变换栈（Fabric 抓的是 render 里 new 出来的那个），
            // 相机视图矩阵走 positionMatrix()，漏掉它剔除和命中测试都会错。
            for (WorldWindow window : WorldWindow.windows) {
                window.render(context.matrixStack(), context.positionMatrix(), context.projectionMatrix(), partialTick);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register((Screen target, GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) -> {
                    io.github.kltyton.kltytonui.init.Window.window.fireAnimationFrame();
                    Client.drawScreenLike(graphics);
                }));
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleResourceReloadListener<ClientLoader.ThemeResourceSnapshot>() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(KltytonUI.MODID, "client_resources");
            }

            @Override
            public CompletableFuture<ClientLoader.ThemeResourceSnapshot> load(ResourceManager resourceManager, ProfilerFiller profiler, Executor executor) {
                return CompletableFuture.supplyAsync(() -> {
                    var paths = resourceManager.listResources("kltytonui/kltytonui/theme",
                                    location -> KltytonUI.MODID.equals(location.getNamespace()))
                            .keySet().stream()
                            .map(location -> location.getPath())
                            .toList();
                    return ClientLoader.prepareThemeSnapshot(paths);
                }, executor);
            }

            @Override
            public CompletableFuture<Void> apply(ClientLoader.ThemeResourceSnapshot themeSnapshot, ResourceManager resourceManager, ProfilerFiller profiler, Executor executor) {
                return CompletableFuture.runAsync(() -> {
                    RecipeExpander.clearCache();
                    Minecraft.getInstance().execute(() -> {
                        io.github.kltyton.kltytonui.client.model.ModelRenderService.invalidate();
                        ClientLoader.reloadResources(themeSnapshot);
                    });
                }, executor);
            }
        });
    }

    private static void registerKeys() {
        KeyBindingHelper.registerKeyBinding(Keybindings.RELEASE_MOUSE);
        KeyBindingHelper.registerKeyBinding(Keybindings.RELOAD);
        KeyBindingHelper.registerKeyBinding(Keybindings.DEV_TOOLS);
        KeyBindingHelper.registerKeyBinding(Keybindings.RESOURCE_MANAGER);
    }
}
