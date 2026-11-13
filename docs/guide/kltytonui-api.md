# Java 模组 API

各目标共用 Java 入口 `io.github.kltyton.kltytonui.KltytonUI`。页面内的 DOM、事件、fetch 与 Canvas 见 [Web API](web-api)。

在页面或菜单所属线程调用 Java API。

```java
// Document / Overlay
Document doc = KltytonUI.createDocument("overlays/status.html");
KltytonUI.getDocument(path);  KltytonUI.removeDocument(path);
KltytonUI.getDocumentByUUID(uuid);  KltytonUI.getAllDocument();

// Screen / 容器
KltytonUI.screen("screens/settings.html");                    // 客户端请求，UI-only
KltytonUI.menu(serverPlayer, "screens/machine.html")          // 服务端，真实容器
        .bind(binding -> binding.blockEntity(pos).player());
KltytonUI.closeScreen();

// WorldWindow
WorldWindow win = KltytonUI.createWorldWindow("world/notice.html", position, 64);
KltytonUI.removeWorldWindow(win);
```

细节分散在各专题文档里，别在这篇里找：

- Document 的生命周期、刷新代次、DOM 操作 → [Overlay 文档](overlay-document)和 [Web API 文档](web-api)
- `new KltytonScreen(path)`、pause/背景/缩放 → [KltytonScreen 文档](kltytonui-screen)
- 容器声明、高级 `KltytonScreenNetworkHandler.openScreen(...)` → [容器文档](container)
- WorldWindow 的旋转、Follow/Facing、LOD、坐标转换 → [WorldWindow 文档](world-window)
- Loader / ClientLoader / HTML 的资源读取 → [资源管理文档](resource-manager)
- 自定义元素注册（`@ElementRegister`、扫描包）→ [二次开发文档](secondary-development)
- DialogWindow、ContextMenu、ToastManager、ColorPicker 等内置组件 → [内置 UI 库](ui-library)

## 线程、空值、刷新

**线程**：创建 Document、改 DOM、开关 Screen、操作 WorldWindow 都得在客户端线程；网络回调和 Future 里先 `Minecraft.getInstance().execute(...)`。服务端 `menu` 在服务端线程调。

**空值**：这些 API 都用 null 表达失败，别拿 try-catch 代替判空——`createDocument`（模板缺失）、`getElementById`（元素不存在或引用已失效）、`getCurrentScreenDocument`（Screen 类型不对）、WorldWindow 的投影/命中（不可见、被挡、超距）。

**刷新**：`refresh()` 是整页重建，不是更新手段。高频数据改现有元素的 textContent/属性。刷新后旧 Element、监听器、Observer 全部失效；异步回调先存 `getRefreshGeneration()`，回来用 `isCurrentGeneration(gen)` 验证再写。

Java 侧更新 DOM 时如果会触发脚本辅助逻辑，包一层 `Document.runWithContext(document, () -> ...)` 建立当前 Document 上下文。

## 客户端配置键

配置文件 `config/kltytonui-client.toml`，Java 侧从 `KltytonUIConfig.CLIENT` 读：

| 键 | 作用 |
| --- | --- |
| `debug.autoReload` | 开发目录变化时自动重载 |
| `debug.frameTimingHud` | 帧耗时 HUD |
| `debug.remoteDebug` | 本地外部调试器 |
| `input.viewportZoomPassThrough` | Ctrl+滚轮缩放穿透未拦截的 Overlay |
| `worldWindow.maxDisplayDistance` | 世界窗口默认显示距离 |
| `worldWindow.lodEnabled` / `fullDetailDistance` / `reducedDetailDistance` | 世界窗口 LOD |
| `worldWindow.depthOffsetScale` | 世界窗口深度偏移比例 |
