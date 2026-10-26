# NeXtepHomeShim — HyperOS（澎湃）桌面应答、变换与输入法贴合模块（v2.6）

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

本模块是一个独立的 LSPosed 伴生模块（作用域 `com.miui.home` + `android`
系统框架），把 HyperOS 缺失的两侧补齐，不修改 NeXtep 本体：桌面侧的协议
应答与变换（进程 `com.miui.home`），以及输入法贴合（进程 system_server，
见实现第 5 点）。

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
4. **中继只认变化（v2.7）**：协调器执行完每个 `HOST_SET_REQUEST` 都会再发
   一条 `SYSTEMUI_SET_WORKSPACE` 做回告；原版桌面是这条回告的终点消费者
   （只施加变换，不回送）。若垫片把回告也中继回去，就会形成自激广播循环
   ——实测 2026-10-26 曾在约 2 分钟内中继 3,738 次（约 100 Hz），表现为
   桌面在变换状态间疯狂横跳。故中继加值守：与上次中继值相同的
   `SYSTEMUI_SET` 一律按回声丢弃（计数、限频打日志）；真正的请求必然
   翻转取值，不受影响。
5. **状态以真值为准**：`QUERY` 应答的 `WORKSPACE_ACTIVE` 直接读
   `Settings.Global` 中 NeXtep 发布的真实状态，而不是模块自记的缓存，
   避免桌面进程重启后状态不同步导致退出/切换打架。（原第 4 点，顺延）
5. **输入法贴合主窗口（v2.2 引入，v2.6 在澎湃上验证通过）**：NeXtep 本体有
   `WorkspaceImeSurfaceHook` / `WorkspaceImeTargetHook` 两个 system_server
   钩子负责此事，但在澎湃上未见生效。本模块在 system_server 钩住
   `DisplayContent.prepareSurfaces`，按本体的几何公式（同一
   `Settings.Global` 快照）把 IME 容器的变换设为
   `setMatrix(mainScale)` + `setPosition(mainTranslationX, imeTranslationY)`。
   与本体的关键差异有二：
   - **三个 IME 目标逐个评估**：本体只认 `mImeLayeringTarget` 的任务。
     实测澎湃在工作台激活时，layering target 是一个**没有任务归属的系统
     窗口**（打字中的应用不在这一路），本体因此静默跳过。本模块对
     layering / input / control 三个目标分别解析任务，任一命中主画布
     边界即贴合；
   - **诊断不沉默**：本体在条件未命中时不留痕迹。本模块把每次状态变化
     （各目标的任务/边界/窗口标题与包名）经一个 `Settings.Global` 键
     中转，由桌面进程打进其日志——实测 LSPosed 导出日志中 system 进程
     的记录会整段断流，桌面进程的日志则完整可靠。
   `Transaction.setMatrix` 不在公开 SDK 中，与本体一样反射调用。
   尚未移植：本体 `WorkspaceImeTargetHook` 的交换时 IME 焦点交接
   （主窗格与侧栏交换任务时的目标转移），症状不同，留待后续。

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

1. 安装 `NeXtepHomeShim.apk`，在 LSPosed 中启用，作用域勾选
   **「系统桌面 / com.miui.home」和「系统框架 / android」两个**（清单已
   预置推荐作用域；只勾桌面则输入法贴合不加载）；
2. 格窗本体与上游模块**只启用一个**，避免两个工作台互相抢状态；
3. 重启后正常使用格窗的进入手势/磁贴即可。

## 测试情况

- 设备：REDMI Note 12 Turbo（marble），HyperOS OS2.0.214（Android 15）
- 对象：Panes 2.6.2（协议 52）；上游模块协议 39 的应答代码路径相同
- 结果：工作台可正常进入，副窗格渲染真实应用画面（Telegram、视频、音乐等），
  主区域桌面经变换后归位、点击对位（v2.1）；桌面变换自 v2.2 起改用本体
  的 contentScale/contentTranslation 精确公式；**输入法贴合于 v2.6 验证
  通过**：在主窗格应用中弹出的键盘收进主窗口宽度内，不再全屏宽盖住
  侧栏（2026-10-26 截图与日志双验证，贴合命中于 input/layering 目标评估）；
  v2.7 的回声值守经同日实测：广播风暴消失，进出与磁贴开关的中继次数
  回到个位数
- **手势唤醒（v2.8–v2.10，2026-10-26 实测通过）**：新增隐形启动活动
  「格窗开关」（ToggleActivity），被打开时不显示界面、直接请求切换
  工作台，供 MyGesture 等只能"打开应用"的手势工具调用（实测以
  左边缘长按上滑唤醒/关闭）。两个实现要点：协调器接收器
  WorkspaceControlBrokerReceiver 挂有 STATUS_BAR_SERVICE 权限，
  普通应用进程的广播会被静默丢弃，切换请求须由桌面进程中的
  应答器中继（桌面为系统应用，持有该权限）；SYSTEMUI_SET_WORKSPACE
  是状态通知而非请求，垫片只记录不中继，且 QUERY/SET 应答报告
  桌面已施加的状态而非滞后的 Settings 发布值——否则协调器的
  纠错会与发布延迟叠成约 2–4 Hz 的开关振荡（v2.9 实测复现、
  v2.10 断开，~630 次交替 SET 归零）

## 已知问题

- **退出时画面回闪**：已解决（2026-10-26 实测）。早期版本存在退出闪动，
  在桌面变换改用本体精确公式（v2.2）后消失——近似公式下退出还原的比例/
  位置与本体几何对不齐，应是闪动的来源；NeXtep 的
  `workspace_display_transition` 钩子在 MIUI 上装不上（fail-open）与此
  无关，退出过渡在澎湃上可正常完成。
- 桌面变换为第一版参数（等比缩放 + 顶栏高度偏移），横屏与场景恢复
  （scene / HOME 相关附加字段）尚未验证。
- 壁纸窗口不参与变换（保持全屏），为预期行为。

## 许可

本伴生模块为适配贡献，随 NeXtep 项目的 Apache-2.0 许可一同发布。
