package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.runtime.concurrent.LazyValue;
import java.util.LinkedHashSet;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves platform code definers and their capabilities; derived from NeonJS and adapted to Java. */
public final class CodeDefiners {
    private static final System.Logger LOGGER = System.getLogger(CodeDefiners.class.getName());
    private static final LazyValue<CodeDefiner> DEFAULT = new LazyValue<>(CodeDefiners::findDefault);
    private static final ConcurrentHashMap<CodeDefiner, Boolean> USABLE = new ConcurrentHashMap<>();

    private CodeDefiners() {
    }

    public static CodeDefiner getDefault() {
        return DEFAULT.get();
    }

    private static CodeDefiner findDefault() {
        String configured = System.getProperty("neonjs.codeDefiner");
        if (configured != null && !configured.isEmpty()) {
            if (configured.equals("isolated")) return new IsolatedCodeDefiner();
            if (configured.equals("jvm")) return new JvmCodeDefiner();
            try {
                return (CodeDefiner) Class.forName(configured).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException error) {
                return CodeDefiners.<RuntimeException, CodeDefiner>rethrow(error);
            }
        }
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader own = CodeDefiner.class.getClassLoader();
        if (context != null) loaders.add(context);
        if (own != null) loaders.add(own);
        for (ClassLoader loader : loaders) {
            try {
                for (CodeDefiner definer : ServiceLoader.load(CodeDefiner.class, loader)) {
                    if (definer.isSupported()) return definer;
                }
            } catch (ServiceConfigurationError | RuntimeException | LinkageError error) {
                LOGGER.log(System.Logger.Level.WARNING, "A platform code-definer provider failed", error);
            }
        }
        return new JvmCodeDefiner();
    }

    public static boolean isUsable(CodeDefiner definer) {
        return USABLE.computeIfAbsent(definer, candidate -> {
            try {
                byte[] bytes = JvmCompiler.INSTANCE.probeClass(candidate.getClassFileVersion());
                Class<?> generated = candidate.define(JvmCompiler.PROBE_CLASS, bytes, CodeDefiner.class.getClassLoader());
                return generated.getDeclaredConstructor().newInstance() instanceof CompiledCode;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                if (System.getProperty("neonjs.jit.debug") != null) error.printStackTrace();
                return false;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable error) throws E {
        throw (E) error;
    }
}
