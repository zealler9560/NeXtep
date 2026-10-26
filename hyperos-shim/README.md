# NeXtepHomeShim — HyperOS（澎湃）桌面应答与变换模块

> TL;DR: A companion LSPosed module that makes NeXtep / 格窗 Panes usable on
> Xiaomi HyperOS: it answers the workspace QUERY/SET broadcast protocol inside
> MIUI Home (which has no ColorOS-style responder), and applies the launcher
> transform + touch remapping driven by NeXtep's own published workspace
> geometry. Tested on Redmi Note 12 Turbo, HyperOS 2.0, Panes 2.6.2.

## 背景：为什么 HyperOS 上「进不去」

NeXtep 的 `TriggerCoordinator.requestToggle` 在打开工作台前，会先向 **HOME 包**
发送一个有序广播 `QUERY_WORKSPACE`，必须收到 `Ready(active, homePackage,
runtimeProtocolVersion)` 应答才会继续。这套桌面侧应答在 ColorOS 上由其桌面
钩子实现；MIUI 桌面（`com.miui.home`）没有应答者，于是切换在第一步就中止
（诊断日志中表现为 `launcher: no answer`），表现为格窗和上游模块都「进不去」。

本模块是一个独立的 LSPosed 伴生模块（作用域仅 `com.miui.home`），把缺失的
桌面侧补齐，不修改 NeXtep 本体。

## 实现（v2.1）

1. **协议应答**：在 MIUI 桌面进程内动态注册导出广播接收器，同时覆盖两套
   命名空间（fork 协议 52：`io.github.yishisanren.panes`；上游协议 39：
   `io.github.lujinxin.nextep`）：
   - `QUERY_WORKSPACE` → `resultCode = 0x4E58`，回带
     `WORKSPACE_ACTIVE / HOME_PACKAGE / RUNTIME_PROTOCOL_VERSION`；
   - `SET_WORKSPACE` → 记录状态并同样应答；
   - `SYSTEMUI_SET_WORKSPACE` → 中继为 `HOST_SET_REQUEST` 发给模块的
     `WorkspaceControlBrokerReceiver`。
   协议本身不要求签名权限（`sendOrderedBroadcast(intent, null, ...)`），
   进程内应答即可满足契约。
2. **桌面变换（主窗口显示）**：NeXtep 的 SystemUI 侧会把工作台几何发布到
   `Settings.Global`（`nextep_workspace_active_v1`、
   `nextep_workspace_geometry_v1`，CSV 字段顺序：
   screenWidth, screenHeight, topHeight, sidebarWidth, side, 导航内边距×4,
   rotation, controlAtBottom, statusInset, mainCanvasHeight, bottomGap）。
   本模块用 `ContentObserver` 订阅这两个键，对桌面 Launcher Activity 的
   decor view 施加变换：pivot (0,0)，等比缩放
   `scale = (screenW - sidebarW) / screenW`，按侧栏方向平移
   `tx = side==LEFT ? sidebarW : 0`，`ty = topHeight`，200ms 动画进出。
3. **触控映射（主窗口点击对位）**：只做视觉变换会导致点击位置系统性偏移
   （decor 的变换不参与窗口事件坐标换算）。本模块钩住桌面 Activity 的
   `dispatchTouchEvent`，把事件坐标按逆变换 `(p - t) / scale` 映射回桌面
   坐标系后再分发，与原版 `TouchCoordinateMapper` 的思路一致。
4. **状态以真值为准**：`QUERY` 应答的 `WORKSPACE_ACTIVE` 直接读
   `Settings.Global` 中 NeXtep 发布的真实状态，而不是模块自记的缓存，
   避免桌面进程重启后状态不同步导致退出/切换打架。

## 构建

无 Gradle，JDK 17 + Android SDK（platform 35、build-tools 35.0.0）即可：

```bash
./build.sh   # 产物 NeXtepHomeShim.apk，自备 keystore（见脚本内变量）
```

**踩过的坑（构建时务必注意）**：`stubs/` 下的 Xposed API 类只是编译期占位，
**绝不能被 dex 进 APK**——LSPosed 会直接拒绝加载并在日志中写明
"The Xposed API classes are compiled into the module's APK"。
`build.sh` 因此分两步编译，只把模块自身类打进 dex。

## 安装

1. 安装 `NeXtepHomeShim.apk`，在 LSPosed 中启用，作用域勾选「系统桌面 /
   com.miui.home」（清单已预置推荐作用域）；
2. 格窗本体与上游模块**只启用一个**，避免两个工作台互相抢状态；
3. 重启后正常使用格窗的进入手势/磁贴即可。

## 测试情况

- 设备：REDMI Note 12 Turbo（marble），HyperOS OS2.0.214（Android 15）
- 对象：Panes 2.6.2（协议 52）；上游模块协议 39 的应答代码路径相同
- 结果：工作台可正常进入，副窗格渲染真实应用画面（Telegram、视频、音乐等），
  主区域桌面经变换后归位、点击对位（v2.1）

## 已知问题

- **退出时画面回闪**：正在定位。已知 NeXtep 的
  `workspace_display_transition` 钩子在 MIUI 的 `DefaultTransitionHandler`
  上装不上（其不存在 `isAnimationsDisabledForAnyDisplay(TransitionInfo)`，
  源模块中该钩子为 fail-open），退出闪动可能与此有关，也可能与桌面变换
  还原时序有关；有诊断日志结论后会更新本节。
- 桌面变换为第一版参数（等比缩放 + 顶栏高度偏移），横屏与场景恢复
  （scene / HOME 相关附加字段）尚未验证。
- 壁纸窗口不参与变换（保持全屏），为预期行为。

## 许可

本伴生模块为适配贡献，随 NeXtep 项目的 Apache-2.0 许可一同发布。
