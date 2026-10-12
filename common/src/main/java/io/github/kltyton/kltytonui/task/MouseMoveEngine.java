package io.github.kltyton.kltytonui.task;

import io.github.kltyton.kltytonui.layout.Position;
import io.github.kltyton.kltytonui.render.Operation;

import java.util.function.Supplier;

/** Samples each host frame; Operation deduplicates unchanged pointer positions. */
public final class MouseMoveEngine {
    private MouseMoveEngine() {
    }

    public static void poll(Supplier<Position> mousePosition) {
        Operation.onMouseMoveFrame(mousePosition.get());
    }
}
