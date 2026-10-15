package io.github.kltyton.kltytonui.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.kltyton.kltytonui.init.Window;
import io.github.kltyton.kltytonui.render.FrameTimingHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Advances page frame callbacks before extraction and measures deferred rasterisation. */
@Mixin(GameRenderer.class)
public abstract class GameRendererFrameTimingMixin {
    @WrapMethod(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V")
    private void kltytonui$measureFrame(DeltaTracker deltaTracker, boolean advanceGameTime, Operation<Void> original) {
        FrameTimingHud.beginFrame();
        try {
            Window.window.fireAnimationFrame();
            original.call(deltaTracker, advanceGameTime);
        } finally {
            FrameTimingHud.endFrame();
        }
    }
}
