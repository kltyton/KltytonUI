package io.github.kltyton.kltytonui.spi;

import io.github.kltyton.kltytonui.model.ModelSpec;

/** Each element owns a preview; native entities never enter the world. */
@FunctionalInterface
public interface KuiModelRenderService {
    Preview create(ModelSpec source);

    interface Preview extends AutoCloseable {
        void render(KuiModelRenderRequest request);
        default boolean tick() { return false; }
        @Override
        void close();
    }
}
