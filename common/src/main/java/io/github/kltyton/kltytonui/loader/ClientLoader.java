package io.github.kltyton.kltytonui.loader;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.dev.DevTools;
import io.github.kltyton.kltytonui.element.ThemeSwitcher;
import io.github.kltyton.kltytonui.ui.ToastManager;
import io.github.kltyton.kltytonui.task.AbstractAsyncHandler;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.task.FrameTaskScheduler;
import io.github.kltyton.kltytonui.render.FontDrawer;
import io.github.kltyton.kltytonui.render.ImageDrawer;
import io.github.kltyton.kltytonui.resource.Font;
import io.github.kltyton.kltytonui.parser.HTML;
import io.github.kltyton.kltytonui.parser.CSS;
import io.github.kltyton.kltytonui.parser.Selector;
import io.github.kltyton.kltytonui.resource.async.image.ImageAsyncHandler;
import io.github.kltyton.kltytonui.resource.async.network.NetworkAsyncHandler;
import io.github.kltyton.kltytonui.resource.async.style.StyleAsyncHandler;
import io.github.kltyton.kltytonui.spi.KuiServices;
import io.github.kltyton.kltytonui.style.Style;
import io.github.kltyton.kltytonui.style.Text;
import io.github.kltyton.kltytonui.layout.Size;
import io.github.kltyton.kltytonui.viewport.KltytonViewport;
import io.github.kltyton.kltytonui.dom.DocumentRegistry;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.function.BiConsumer;
import io.github.kltyton.kltytonui.world.WorldWindow;

public class ClientLoader extends Loader {
    public static final String THEME_RESOURCE_ROOT = "kltytonui/theme/";
    public static final String THEME_RESOURCE_MANAGER_ROOT = "kltytonui/kltytonui/theme";
    private static final Object STATIC_RESOURCE_CACHE_LOCK = new Object();
    private static List<StaticResourceEntry> cachedFinalStaticResources = null;
    private static final Object THEME_SNAPSHOT_LOCK = new Object();
    private static final AtomicLong THEME_RESOURCE_GENERATION = new AtomicLong();
    private static final AtomicLong THEME_FILESYSTEM_GENERATION = new AtomicLong();
    private static final Object THEME_RESCAN_LOCK = new Object();
    private static boolean filesystemThemeRescanInFlight;
    private static long pendingFilesystemThemeRescanGeneration = -1;
    private static List<String> managerThemeIds = List.of();
    private static List<String> filesystemThemeIds = List.of();
    private static long publishedManagerThemeGeneration;
    private static long publishedFilesystemThemeGeneration;
    private static volatile List<String> discoveredThemeIds = List.of();
    private static boolean reloadQueued;
    private static boolean reloadRequested;
    public ClientLoader(String extension) {
        super(extension);
    }

    public static void reload() {
        requestFilesystemThemeRescan();
        reloadRequested = true;
        if (reloadQueued) return;
        reloadQueued = true;
        String progressToast = ToastManager.show(
                "Reloading...",
                new ToastManager.ToastOptions(0, false, "", "", "", "")
        );

        // Leave one complete UI frame between the progress toast and the synchronous reload.
        FrameTaskScheduler.scheduleAfterFrames(2, deadlineNs -> {
            try {
                do {
                    reloadRequested = false;
                    long beginNs = System.nanoTime();
                    KuiServices.script().reload();
                    reloadResourcesInternal(beginNs);
                } while (reloadRequested);
            } finally {
                ToastManager.dismiss(progressToast);
                reloadQueued = false;
            }
            return true;
        });
    }

    /** Reloads all client resources after the loader's resource manager is ready. */
    public static void reloadResources() {
        reloadResourcesInternal(System.nanoTime());
    }

    /** Publishes a two-source theme snapshot prepared for the current resource reload. */
    public static void publishThemeSnapshot(ThemeResourceSnapshot snapshot) {
        synchronized (THEME_SNAPSHOT_LOCK) {
            if (snapshot.managerGeneration() == THEME_RESOURCE_GENERATION.get()
                    && snapshot.managerGeneration() > publishedManagerThemeGeneration) {
                managerThemeIds = snapshot.managerThemeIds();
                publishedManagerThemeGeneration = snapshot.managerGeneration();
            }
            if (snapshot.filesystemGeneration() == THEME_FILESYSTEM_GENERATION.get()
                    && snapshot.filesystemGeneration() > publishedFilesystemThemeGeneration) {
                filesystemThemeIds = snapshot.filesystemThemeIds();
                publishedFilesystemThemeGeneration = snapshot.filesystemGeneration();
            }
            discoveredThemeIds = mergeThemeIds(managerThemeIds, filesystemThemeIds);
        }
    }

    /** Reloads client resources and publishes their prepared theme snapshot. */
    public static void reloadResources(ThemeResourceSnapshot snapshot) {
        publishThemeSnapshot(snapshot);
        reloadResources();
    }

    /** Runs on the reload preparation executor; manager and filesystem IDs remain separate until apply. */
    public static ThemeResourceSnapshot prepareThemeSnapshot(Collection<String> resourceManagerPaths) {
        long managerGeneration = THEME_RESOURCE_GENERATION.incrementAndGet();
        long filesystemGeneration = THEME_FILESYSTEM_GENERATION.incrementAndGet();
        List<String> resourceThemeIds = extractThemeIds(resourceManagerPaths);
        List<String> filesystemIds = scanFilesystemThemeIds();
        return new ThemeResourceSnapshot(managerGeneration, filesystemGeneration, resourceThemeIds, filesystemIds);
    }

    /** Starts a filesystem-only rescan without blocking the caller or retaining a resource manager. */
    public static void requestFilesystemThemeRescan() {
        long generation = THEME_FILESYSTEM_GENERATION.incrementAndGet();
        synchronized (THEME_RESCAN_LOCK) {
            if (filesystemThemeRescanInFlight) {
                pendingFilesystemThemeRescanGeneration = generation;
                return;
            }
            filesystemThemeRescanInFlight = true;
        }
        startFilesystemThemeRescan(generation);
    }

    private static void startFilesystemThemeRescan(long generation) {
        try {
            CompletableFuture.supplyAsync(ClientLoader::scanFilesystemThemeIds)
                    .thenAccept(themeIds -> Minecraft.getInstance().execute(() -> {
                        synchronized (THEME_SNAPSHOT_LOCK) {
                            if (generation != THEME_FILESYSTEM_GENERATION.get()
                                    || generation <= publishedFilesystemThemeGeneration) return;
                            filesystemThemeIds = themeIds;
                            publishedFilesystemThemeGeneration = generation;
                            discoveredThemeIds = mergeThemeIds(managerThemeIds, filesystemThemeIds);
                        }
                    }))
                    .whenComplete((ignored, failure) -> finishFilesystemThemeRescan(failure));
        } catch (RuntimeException failure) {
            finishFilesystemThemeRescan(failure);
        }
    }

    private static void finishFilesystemThemeRescan(Throwable failure) {
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            KltytonUI.LOGGER.warn("[KUI Resource] asynchronous theme rescan failed; keeping the last filesystem snapshot", cause);
        }

        long nextGeneration;
        synchronized (THEME_RESCAN_LOCK) {
            nextGeneration = pendingFilesystemThemeRescanGeneration;
            if (nextGeneration < 0) {
                filesystemThemeRescanInFlight = false;
                return;
            }
            pendingFilesystemThemeRescanGeneration = -1;
        }
        startFilesystemThemeRescan(nextGeneration);
    }

    public static List<String> themeIdsSnapshot() {
        return discoveredThemeIds;
    }

    private static List<String> extractThemeIds(Collection<String> resourceManagerPaths) {
        TreeSet<String> themeIds = new TreeSet<>();
        for (String path : resourceManagerPaths) addThemeId(path, themeIds);
        return List.copyOf(themeIds);
    }

    private static List<String> scanFilesystemThemeIds() {
        TreeSet<String> themeIds = new TreeSet<>();
        for (Path root : Loader.getWatchRoots()) {
            Path themeRoot = root.resolve(THEME_RESOURCE_ROOT).normalize();
            if (!Files.isDirectory(themeRoot)) continue;
            try (Stream<Path> paths = Files.walk(themeRoot)) {
                paths.filter(Files::isRegularFile)
                        .forEach(path -> addThemeId(
                                THEME_RESOURCE_ROOT + themeRoot.relativize(path).toString().replace('\\', '/'),
                                themeIds));
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to scan theme root " + themeRoot, exception);
            }
        }
        for (Path projectRoot : Loader.getDevProjectRoots()) {
            for (String id : ThemeSwitcher.fallbackThemeIds()) {
                String stylesheet = THEME_RESOURCE_ROOT + id + "/" + id + ".css";
                for (Path candidate : Loader.buildProjectRootCandidates(projectRoot, stylesheet)) {
                    if (Files.isRegularFile(candidate)) addThemeId(stylesheet, themeIds);
                }
            }
        }
        return List.copyOf(themeIds);
    }

    private static List<String> mergeThemeIds(Collection<String> managerIds, Collection<String> filesystemIds) {
        TreeSet<String> merged = new TreeSet<>(managerIds);
        merged.addAll(filesystemIds);
        return List.copyOf(merged);
    }

    private static void addThemeId(String path, Set<String> themeIds) {
        if (path == null || path.isBlank()) return;
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith(THEME_RESOURCE_MANAGER_ROOT + "/")) {
            normalized = normalized.substring("kltytonui/".length());
        }
        if (!normalized.startsWith(THEME_RESOURCE_ROOT)) return;

        String relative = normalized.substring(THEME_RESOURCE_ROOT.length());
        int slash = relative.indexOf('/');
        if (slash <= 0) return;
        String id = relative.substring(0, slash);
        if (!relative.substring(slash + 1).equals(id + ".css")) return;
        themeIds.add(id);
    }

    public record ThemeResourceSnapshot(
            long managerGeneration,
            long filesystemGeneration,
            List<String> managerThemeIds,
            List<String> filesystemThemeIds
    ) {
        public ThemeResourceSnapshot {
            managerThemeIds = List.copyOf(managerThemeIds);
            filesystemThemeIds = List.copyOf(filesystemThemeIds);
        }
    }

    private static void reloadResourcesInternal(long beginNs) {
        invalidateStaticResourceCache();
        invalidateGlobalJSSourceCache();
        ensureAsyncHandlersInitialized();
        AbstractAsyncHandler.clearAllAndBumpGeneration();
        CSS.clearCompiledStylesheets();
        Selector.clearCompiledCache();
        DocumentRegistry.resetCreateTimingState();
        ImageDrawer.clearRenderTypeCache();
        io.github.kltyton.kltytonui.render.BackgroundPainter.invalidate();
        FontDrawer.clearCache();
        Font.prepareReload();
        warmUpDocumentInfrastructure();

        long scanStartNs = System.nanoTime();
        HTML.scan();
        long scanCostMs = (System.nanoTime() - scanStartNs) / 1_000_000L;

        warmUpScannedTemplates();

        long refreshStartNs = System.nanoTime();
        Document.refreshAll();
        WorldWindow.windows.forEach(worldWindow -> worldWindow.document.refresh());
        DevTools.refresh();
        io.github.kltyton.kltytonui.dev.ResourceManager.refresh();
        long refreshCostMs = (System.nanoTime() - refreshStartNs) / 1_000_000L;

        long totalCostMs = (System.nanoTime() - beginNs) / 1_000_000L;
        ToastManager.show(
                "Reload complete: " + totalCostMs + "ms (scan " + scanCostMs + "ms, refresh " + refreshCostMs + "ms)",
                new ToastManager.ToastOptions(4200, true, "", "", "", "")
        );
    }

    /** Prepares only templates which opted into resource-load warm-up. */
    public static void warmUpScannedTemplates() {
        long firstCreateWarmStartNs = System.nanoTime();
        int preparedTemplates = HTML.prepareTemplates();
        warmUpPageScripts();
        ImageAsyncHandler.INSTANCE.prefetch(HTML.preparedImageSources());
        int preparedStylesheets = 0;
        for (HTML.TemplateResources template : HTML.preparedTemplateResources()) {
            preparedStylesheets += StyleAsyncHandler.INSTANCE.warmUpTemplateStyles(
                    template.path(),
                    template.externalStyleSrcs(),
                    template.inlineStyles(),
                    resolveWarmupViewport(template.path())
            );
        }
        long firstCreateWarmCostMs = (System.nanoTime() - firstCreateWarmStartNs) / 1_000_000L;
        KltytonUI.LOGGER.debug(
                "[KUI Resource] first-create warm-up templates={} stylesheets={} cost={}ms",
                preparedTemplates,
                preparedStylesheets,
                firstCreateWarmCostMs
        );
    }

    private static Size resolveWarmupViewport(String path) {
        try {
            KltytonViewport viewport = KltytonViewport.spec(path)
                    .createState(path)
                    .resolve(Minecraft.getInstance().getWindow());
            return new Size(viewport.layoutWidth(), viewport.layoutHeight());
        } catch (RuntimeException | LinkageError exception) {
            return new Size(1024, 768);
        }
    }

    private static void ensureAsyncHandlersInitialized() {
        ImageAsyncHandler.INSTANCE.id();
        StyleAsyncHandler.INSTANCE.id();
        NetworkAsyncHandler.INSTANCE.id();
    }

    private static void warmUpDocumentInfrastructure() {
        try {
            Style.warmUpMetadata();
        } catch (RuntimeException | LinkageError exception) {
            KltytonUI.LOGGER.warn("[KUI Resource] style metadata warm-up failed", exception);
        }
        try {
            Text.warmUpFontMetrics();
        } catch (RuntimeException | LinkageError exception) {
            KltytonUI.LOGGER.warn("[KUI Resource] font metrics warm-up failed", exception);
        }
        try {
            StyleAsyncHandler.INSTANCE.warmUpGlobalCss();
        } catch (RuntimeException | LinkageError exception) {
            KltytonUI.LOGGER.warn("[KUI Resource] global stylesheet warm-up failed", exception);
        }
        readGlobalJS();
    }

    private static void warmUpPageScripts() {
        try {
            KuiServices.script().warmUp();
        } catch (RuntimeException | LinkageError exception) {
            KltytonUI.LOGGER.warn("[KUI Resource] script engine warm-up failed", exception);
        }
    }

    public static void invalidateGlobalJSSourceCache() {
        Loader.invalidateGlobalJSSourceCache();
    }

    public static InputStream getResourceStream(String path) {
        InputStream filesystemStream = Loader.getResourceStream(path);
        if (filesystemStream != null) {
            return filesystemStream;
        }
        if (path == null || path.isEmpty()) return null;
        try {
            Optional<InputStream> resource = KuiServices.resources().openResource("kltytonui/" + path);
            return resource.orElse(null);
        } catch (RuntimeException | LinkageError exception) {
            KltytonUI.LOGGER.warn("[KUI Resource] failed to open resource-pack resource path={}", path, exception);
        }
        return null;
    }

    public static List<StaticResourceEntry> listFinalStaticResources() {
        synchronized (STATIC_RESOURCE_CACHE_LOCK) {
            if (cachedFinalStaticResources != null) return cachedFinalStaticResources;
        }

        LinkedHashMap<String, StaticResourceEntry> merged = new LinkedHashMap<>();
        loadResourcePackEntries(merged);
        loadFilesystemStaticResources(merged);
        List<StaticResourceEntry> entries = merged.values().stream()
                .sorted(Comparator.comparing(StaticResourceEntry::path))
                .toList();
        synchronized (STATIC_RESOURCE_CACHE_LOCK) {
            cachedFinalStaticResources = entries;
        }
        return entries;
    }

    public static void invalidateStaticResourceCache() {
        synchronized (STATIC_RESOURCE_CACHE_LOCK) {
            cachedFinalStaticResources = null;
        }
    }

    public static String readGlobalCSS() {
        try (InputStream stream = getResourceStream("global.css")) {
            if (stream != null) return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            KltytonUI.LOGGER.warn("[KUI Resource] failed to read global.css", exception);
        }
        return null;
    }

    private static void loadResourcePackEntries(Map<String, StaticResourceEntry> merged) {
        Map<String, String> resources = KuiServices.resources().listResourcePaths("kltytonui", "");
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            String path = entry.getKey();
            if (path.isBlank()) continue;
            String sourcePack = Loader.safe(entry.getValue());
            merged.put(path, new StaticResourceEntry(
                    path,
                    Loader.extensionOf(path),
                    ResourceLayer.RESOURCE_PACK,
                    "resource-pack",
                    sourcePack,
                    -1L
            ));
        }
    }

    public void loadResources(BiConsumer<String, String> handler) {
        this.handler = handler;
        loadedResourceCount = 0;
        loadFromResourcePack();
        loadFromLocalFolder();
        loadFromDevFolders();
        KltytonUI.LOGGER.debug("[KUI Resource] scanned extension={} loaded={}", extension, loadedResourceCount);
    }

    private void loadFromResourcePack() {
        Map<String, String> paths = KuiServices.resources().listResourcePaths("kltytonui", "." + extension);

        for (String path : paths.keySet()) {
            try (InputStream stream = KuiServices.resources().openResource("kltytonui/" + path).orElse(null)) {
                if (stream == null) continue;
                handler.accept(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                loadedResourceCount++;
            } catch (IOException exception) {
                KltytonUI.LOGGER.error(
                        "[KUI Resource] failed to read resource-pack {} path={}",
                        extension,
                        path,
                        exception
                );
            }
        }
    }
}
