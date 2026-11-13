# Java Mod API

The shared Java entry point is `io.github.kltyton.kltytonui.KltytonUI`. For in-page DOM, events, fetch, and Canvas, see the [Web API](web-api).

Use the Java API on the thread that owns the page or menu.

```java
// Document / Overlay
Document doc = KltytonUI.createDocument("overlays/status.html");
KltytonUI.getDocument(path);  KltytonUI.removeDocument(path);
KltytonUI.getDocumentByUUID(uuid);  KltytonUI.getAllDocument();

// Screen / container
KltytonUI.screen("screens/settings.html");                    // client request, UI-only
KltytonUI.menu(serverPlayer, "screens/machine.html")          // server side, real container
        .bind(binding -> binding.blockEntity(pos).player());
KltytonUI.closeScreen();

// WorldWindow
WorldWindow win = KltytonUI.createWorldWindow("world/notice.html", position, 64);
KltytonUI.removeWorldWindow(win);
```

The details are spread across the topic docs — don't look for them here:

- Document lifecycle, refresh generation, DOM operations → [Overlay doc](overlay-document) and [Web API doc](web-api)
- `new KltytonScreen(path)`, pause/background/scale → [KltytonScreen doc](kltytonui-screen)
- Container declarations, advanced `KltytonScreenNetworkHandler.openScreen(...)` → [Container doc](container)
- WorldWindow rotation, Follow/Facing, LOD, coordinate conversion → [WorldWindow doc](world-window)
- Loader / ClientLoader / HTML resource loading → [Resource Management doc](resource-manager)
- Custom element registration (`@ElementRegister`, scan packages) → [Secondary Development doc](secondary-development)
- Built-in components such as DialogWindow, ContextMenu, ToastManager, ColorPicker → [Built-in UI Library](ui-library)

## Threads, Null Values, and Refresh

**Threads**: Creating Documents, modifying the DOM, opening/closing Screens, and operating on WorldWindows must all happen on the client thread; in network callbacks and Futures, wrap UI work in `Minecraft.getInstance().execute(...)` first. The server-side `menu` is called on the server thread.

**Null values**: These APIs express failure with null — don't use try-catch instead of null checks: `createDocument` (missing template), `getElementById` (element does not exist or the reference is stale), `getCurrentScreenDocument` (wrong Screen type), WorldWindow projection/hit tests (invisible, occluded, out of range).

**Refresh**: `refresh()` is a full page rebuild, not an update mechanism. For high-frequency data, change the textContent/attributes of existing elements. After a refresh, all old Elements, listeners, and Observers become invalid; in async callbacks, store `getRefreshGeneration()` first, then verify with `isCurrentGeneration(gen)` before writing.

When updating the DOM from Java in a way that may trigger script helper logic, wrap it in `Document.runWithContext(document, () -> ...)` to establish the current Document context.

## Client Config Keys

Config file `config/kltytonui-client.toml`, read from `KltytonUIConfig.CLIENT` on the Java side:

| Key | Purpose |
| --- | --- |
| `debug.autoReload` | Auto-reload when the development directory changes |
| `debug.frameTimingHud` | Frame timing HUD |
| `debug.remoteDebug` | Local external debugger |
| `input.viewportZoomPassThrough` | Ctrl+scroll zoom passes through unintercepted Overlays |
| `worldWindow.maxDisplayDistance` | Default display distance for world windows |
| `worldWindow.lodEnabled` / `fullDetailDistance` / `reducedDetailDistance` | World window LOD |
| `worldWindow.depthOffsetScale` | World window depth offset scale |
