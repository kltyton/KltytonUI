# 二次开发：自定义元素与帧耗时 HUD

模组可注册自定义 DOM 元素，并使用帧耗时 HUD。页面脚本见 [Web API](web-api)，内置标签见[扩展元素](extension-elements)。

## 先守住的两条边界

**客户端线程**：Document、Element、布局、Screen、WorldWindow 全是客户端 UI 状态。从网络回调、Future、异步任务回来时先切线程：

```java
Minecraft.getInstance().execute(() -> {
    Document document = KltytonUI.createDocument("overlays/status.html");
    if (document != null && document.body != null) {
        document.body.setTextContent("ready");
    }
});
```

资源解码可以异步，DOM 提交和纹理上传必须在客户端/渲染线程。

**刷新代次**：`refresh()` 重建整棵树，你存的 Element 引用、监听器全部失效。异步回调存代次、回来验证：

```java
long generation = document.getRefreshGeneration();
Minecraft.getInstance().execute(() -> {
    if (!document.isCurrentGeneration(generation)) return;
    Element element = document.getElementById("status");
    if (element != null) element.setTextContent("loaded");
});
```

## 注册自定义元素

继承 `Element`，加 `@ElementRegister`，提供 `public (Document)` 构造器：

```java
@ElementRegister(MyPanel.TAG_NAME)
public final class MyPanel extends Element {
    public static final String TAG_NAME = "MY-PANEL";

    public MyPanel(Document document) {
        super(document, TAG_NAME);
    }

    @Override
    protected void onInitFromDom(Element origin) {
        // 到这里属性、子节点、监听器才迁移完成；构造函数里读不到初始属性
        String mode = getAttribute("mode");
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        // 自定义绘制；阶段分 SHADOW / BODY / BORDER
        super.drawPhase(poseStack, phase);
    }
}
```

然后在模组初始化阶段（构造器或更早）登记扫描包：

```java
KltytonUIRegistry.scanPackage("com.example.mod.ui");
// 或 scanPackages("com.example.mod.ui", "com.example.mod.client.element");
```

要点和坑：

- 标签名按大写注册、大小写不敏感，**带模组前缀**（`EXAMPLE-PANEL`），避免撞车；同标签重复注册后者覆盖前者，扫描顺序不是稳定优先级；
- 注解扫描由 loader target 提供：Forge/NeoForge 使用各自的 mod 扫描元数据，Fabric 扫描 mod class 文件。`scanPackage` 只收集指定包及其子包；必须在 KUI 元素注册前调用——第一个 Document 创建后再登记，已解析的页面不会追溯转换；
- 构造器里别读属性，初始化放 `onInitFromDom`；实例化失败会退回普通 Element（页面还在，扩展行为没了），但 `onInitFromDom` 和绘制里的异常没有这种兜底；
- 元素注册不是热重载，改注册逻辑要重启客户端；「重载资源」操作只重扫资源；
- 不需要自定义绘制的元素不用覆写 `drawPhase`，CSS 照常生效；
- 自定义绘制时：尺寸从 `Box.of(this)` / `getBoundingClientRect()` 取；先处理零尺寸和资源未就绪；别每帧创建 DynamicTexture、解析字符串、触发布局；资源异步就绪后更新内部状态并 `document.markDirty(this, ...)`。

## frameTimingHud：帧耗时 HUD

`config/kltytonui-client.toml`：

```toml
[debug]
frameTimingHud = true
```

左上角显示最近 120 个 KUI 帧样本：

```text
max 2.31 ms  min 0.42 ms  avg 0.88 ms  g 12 img 3 sb 7  ly 0 tf 1  item 26 x0.08/0.31 ms
```

`max/min/avg` 是 KUI 文档渲染耗时；`g`/`img`/`sb` 是最近一帧 Graph 批次、图片批次、共享 `BufferSource` 的 flush 次数；`ly`/`tf` 是全量布局提交与定向变换提交的次数；`item` 段只在有 `<item>` 等物品节点时出现，依次是物品绘制次数、平均耗时、单次最大耗时。它只统计 KUI 绘制段，不等于总帧时间或 FPS，也不含脚本执行成本。

想让同一份帧数据进日志而不只是显示在屏幕上，加 `-Dkltytonui.fontStats.interval=900`（每 N 次 KUI 帧边界输出一行 `[KUI FontStats]`，含 `imageFlushes`/`graphFlushes` 等与 HUD 同源的字段）。

用法：保持页面稳定等窗口填满 → 记 `avg`/`max` 和批次计数 → 只改一个变量 → 再比。`g/img` 偏高说明批次被打断或没合批，`sb` 偏高说明共享缓冲被频繁冲刷，`item` 的均值和峰值用来判断物品节点是不是热点——都是定位线索不是结论。**`ly` 是最该盯的一个数**：全量布局提交会重建整个文档的几何并重跑布局测量，常见页面应该长期为 0；如果它每帧都大于 0，说明持续有东西把整篇文档标成需要重排——优先查 `:hover` 之类规则里是否改了 `width`/`padding`/`font-size` 等影响布局的属性（只改颜色/背景/`opacity` 不会；只改 `transform` 会走 `tf` 而不是 `ly`）。扩展元素要缓存不变的几何/纹理状态，别在 `drawPhase` 里做重活。

## 常见故障

**自定义标签还是普通 Element**：类在扫描包及其子包里吗？`public (Document)` 构造器有吗？scanPackage 调得够早吗？改完重启客户端验证。

**注册后页面初始化报错**：看带路径和标签名的 KUI 错误日志。属性初始化是不是写构造器里了——挪到 `onInitFromDom`。绘制时检查尺寸、资源句柄、document 有效性。


**HUD 数值忽高忽低**：DevTools、资源管理器这类内置页面本身会改变批次。用连续 120 帧的 avg 看趋势、max 找尖峰，别拿单帧波动当回归。
