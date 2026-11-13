# KltytonUI

用 HTML、CSS 和 JavaScript 编写 Minecraft 界面。

支持屏幕、叠加层、世界内窗口、Canvas/SVG 和音频，提供内嵌 NeonJS 0.1.4 页面脚本运行时及 Vue、McUI 组件。每个页面文档使用独立的 JavaScript Realm，并支持现代 JavaScript；浏览器风格 API 由 Java 提供，KUI 本身不包含 Chromium 或浏览器 DOM 内核。原生渲染接口用于绘制区块网格；地图浏览和玩家指示器由 [XaeroWorldMapEarth](https://github.com/kltyton/XaeroWorldMapEarth) 实现。

1.3.0.1 加入玩家和实体模型预览，并更新背景渐变、DOM 索引、布局和绘制缓存。十六套 CSS 主题继续可用，包含 Create 和 Terminal；页面可添加 `<theme-switcher></theme-switcher>` 切换主题。原有 Ore、AE 和 McUI 样式资源路径继续可用。

NeonJS 0.1.4 的 Java 实现和重定位后的 ASM 随 mod 分发，Intl 使用 Minecraft 自带的 ICU4J。页面运行时及其 JIT 生成的类面向 Java 17，不需要 GraalVM、JVMCI 参数或额外下载。

发行运行包不包含项目文档和 BlueMap 文件。

## 版本

| 加载器 | Minecraft |
| --- | --- |
| Forge | 1.18.2、1.19.2、1.20.1 |
| Fabric | 1.20.1、1.21.1、26.1（含 26.1.2）、26.2 |
| NeoForge | 1.21.1、26.1（含 26.1.2）、26.2 |

## 开发

共享代码位于 `common/`，NeonJS Java 源码与运行时 bundle 位于 `runtime/neonjs/`，各版本独立构建工程位于 `targets/`。目标构建会自动生成并嵌入同一个 Java 17 运行时 bundle。例如构建 NeoForge 26.2（Java 25）：

```powershell
cd targets\neoforge-26.2
.\gradlew.bat assemble
```

产物位于该目标的 `build/libs/`。使用方法见 [文档](docs/intro.md)。

基于 [ApricityUI](https://github.com/Tower-of-Sighs/AUI)，由 kltyton 维护，沿用 [LGPL-2.1](LICENSE) 许可证。
