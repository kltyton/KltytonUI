package io.github.kltyton.kltytonui.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.kltyton.kltytonui.init.Window;
import io.github.kltyton.kltytonui.render.FrameTimingHud;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/** Advances page callbacks before frame extraction and measures the complete render frame. */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameTimingMixin {
    @WrapMethod(method = "renderFrame(Z)V")
    private void kltytonui$measureFrame(boolean advanceGameTime, Operation<Void> original) {
        FrameTimingHud.beginFrame();
        try {
            Window.window.fireAnimationFrame();
            original.call(advanceGameTime);
        } finally {
            FrameTimingHud.endFrame();
        }
    }
}
