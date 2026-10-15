package io.github.kltyton.kltytonui.world;

import com.mojang.blaze3d.systems.RenderSystem;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.neoforge.RenderService;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * NeoForge 26.2 world-render hook for {@link WorldWindow}.
 *
 * <p>26.1 splits the old stage enum into per-stage sub-events and no longer
 * hands out the projection matrix / partial tick directly; both are recovered
 * from the level render state and the game's delta tracker. The stored KUI
 * projection is synced to the world projection first so filter passes inside
 * world documents restore the right matrix.</p>
 */
@EventBusSubscriber(modid = KltytonUI.MODID, value = Dist.CLIENT)
public final class WorldWindowRenderer {
    private WorldWindowRenderer() {
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (WorldWindow.windows.isEmpty()) return;

        Matrix4f projectionMatrix = event.getLevelRenderState().cameraRenderState.projectionMatrix;
        Matrix4f viewMatrix = new Matrix4f(event.getModelViewMatrix());
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Matrix4f previousStoredProjection = RenderService.INSTANCE.getProjectionMatrix();
        var previousProjection = RenderSystem.getProjectionMatrixBuffer();
        var previousProjectionType = RenderSystem.getProjectionType();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            modelView.set(viewMatrix);
            RenderService.INSTANCE.setProjectionMatrix(new Matrix4f(projectionMatrix));
            for (WorldWindow window : WorldWindow.windows) {
                window.render(event.getPoseStack(), viewMatrix, projectionMatrix, partialTick);
            }
        } finally {
            try {
                RenderService.INSTANCE.setProjectionMatrix(previousStoredProjection);
            } finally {
                modelView.popMatrix();
                RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);
            }
        }
    }
}
