# MoreBubbleButton

Xposed 模块，为 Pixel Launcher 最近任务界面和 SystemUI 通知中心添加「消息气泡」能力。

## 功能

- 在 Pixel Launcher 多任务界面底部操作栏添加「🫧 消息气泡」按钮
- 在任务卡片菜单（点击 app 左上角图标）中添加「🫧 消息气泡」选项
- 点击后将当前选中的应用变为 app bubble
- 在 SystemUI 通知中心为可打开的普通通知显示消息气泡按钮
- 点击通知气泡按钮后：
  - 创建并展开 app bubble
  - 优先跳转通知 `contentIntent` 对应界面，而不是只打开 app 首页
  - 自动收起通知中心
  - 对 `FLAG_AUTO_CANCEL` 通知执行移除，使其表现为已读/已处理
- 自动过滤无启动入口或特殊用户通知，避免 `android` / `user=-1` 等无效场景导致 SystemUI 崩溃
- 设置界面支持 X/Y 轴位置调节，滑杆旁提供 `+` / `−` 精细调节按钮
- Android 17 气泡小窗支持宽度、高度百分比调节（50%–150%）

## 要求

- Android 16+ (API 36+)
- 已安装 LSPosed / KernelSU + Zygisk
- Pixel Launcher (NexusLauncher)
- SystemUI 通知气泡功能需要把作用域同时勾选到 `com.android.systemui`

## 安装

1. 下载 [最新 Release](https://github.com/TYOPXN360/MoreBubbleButton/releases) APK
2. 在 LSPosed 中安装并启用模块
3. 设置作用域：
   - `com.google.android.apps.nexuslauncher`
   - `com.android.launcher3`（如设备使用该包名）
   - `com.android.systemui`（通知中心气泡按钮必需）
4. 强制停止 Pixel Launcher / SystemUI，或重启设备

## 使用

### 方式一：最近任务底部操作栏

进入多任务界面 → 点击底部「🫧 消息气泡」按钮。

### 方式二：任务卡片菜单

进入多任务界面 → 点击任务卡片左上角 app 图标 → 点击「🫧 消息气泡」。

### 方式三：通知中心气泡按钮

下拉通知中心 → 对支持打开的普通通知点击气泡图标 → 自动打开对应通知界面的 app bubble。

### 方式四：设置界面

打开 MoreBubbleButton 应用 → 调整开关和按钮位置。

## 设置项

| 设置 | 说明 | 默认值 |
|------|------|--------|
| 任务卡片菜单 | 在菜单中显示消息气泡选项 | 开 |
| 底部操作栏 | 在底部显示消息气泡按钮 | 开 |
| 通知横幅气泡 | 在 SystemUI 通知中心显示气泡按钮 | 开 |
| 按钮位置 | 跟随原按钮 / 第二行 | 跟随原按钮 |
| X 轴 | 水平位置，支持滑杆和 `+` / `−` 精调 | 50% |
| Y 轴 | 垂直位置，支持滑杆和 `+` / `−` 精调 | 50% |
| 气泡宽度 | Android 17 应用气泡窗口宽度比例（50%–150%） | 100% |
| 气泡高度 | Android 17 应用气泡窗口高度比例（50%–150%） | 100% |
| 内容缩放 | 把气泡里 app 的画面整体缩小（50%–100%，100% 为关闭） | 100% |
| 重启启动器 + 系统界面 | 通过 root 重启 Pixel Launcher / SystemUI 使设置生效 | - |

## 技术实现

基于 [libxposed API 102](https://github.com/libxposed/api)，Hook Pixel Launcher 与 SystemUI。

### Pixel Launcher

| Hook 目标 | 方法 | 作用 |
|-----------|------|------|
| `OverviewActionsView` | `onFinishInflate` | 注入底部操作栏按钮 |
| `OverviewActionsView` | `onClick` | 处理按钮点击 |
| `TaskMenuView` | `addMenuOptions` | 注入任务卡片菜单项 |

最近任务气泡触发通过 `SystemUiProxy.showAppBubble()` 调用 WMShell Bubble 服务。

### SystemUI

| Hook 目标 | 作用 |
|-----------|------|
| `NotificationContentView.shouldShowBubbleButton` | 为符合条件的通知显示气泡按钮 |
| `BubblesManager.onUserChangedBubble` / `expandStackAndSelectBubble` | 拦截通知气泡点击，改走稳定的 app bubble 路径 |
| `BubbleController.expandStackAndSelectBubble(Intent, UserHandle, EntryPoint, null)` | 使用通知 `contentIntent` 创建并展开 app bubble |
| `BubbleCoordinator.removeNotification` | 在气泡成功展开后移除 auto-cancel 通知 |
| `BubblePositioner` 尺寸源头（Android 17 气泡小窗大小） | 统一缩放气泡浮窗的宽度与高度，见下 |

### Android 17 气泡小窗大小

气泡浮窗由两部分组成，尺寸来源不同：

- **容器视图**：`BubbleExpandedView`（浮动气泡）/ `BubbleBarExpandedView`（气泡栏），负责圆角轮廓、阴影、把手；
- **任务窗口**：承载 app 内容，bounds 由 `BubblePositioner.getTaskViewRestBounds()` 计算，气泡栏模式下它直接复用 `getBubbleBarExpandedViewBounds()` 的结果。

如果只修改 `getTaskViewRestBounds()`，会出现「内容变小、轮廓不变」的空白区域，拖动贴边时容器重新布局还会把内容尺寸改回原值。因此模块改为在两者共同的尺寸源头缩放：

| Hook 目标 | 作用 |
|-----------|------|
| `BubblePositioner.getTaskViewContentWidth(boolean)` | 浮动气泡宽度（容器宽度 + 任务窗口宽度） |
| `BubblePositioner.getMaxExpandedViewHeight(boolean)` | 浮动气泡高度上限（容器高度上限 + 任务窗口高度） |
| `BubblePositioner.getExpandedViewHeight(BubbleViewProvider)` | 浮动气泡资源高度与 Y 轴定位 |
| `BubblePositioner.getExpandedViewContainerPadding(boolean, boolean)` | 横向气泡行布局下的水平居中：把缩小后的余量一半补到左侧内边距 |
| `BubblePositioner.getPointerPosition(float)` | 指针跟随居中：指针坐标按同样的偏移量回退，小突出继续对准上方图标 |
| `BubblePositioner.getBubbleBarExpandedViewBounds(boolean, boolean, Rect)` | 气泡栏模式下容器与任务窗口共用的 Rect，按底边锚点缩放并水平居中 |

缩放结果会限制在屏幕范围内；调整为 100% 时不生效。窗口高度保持底部锚点（贴着气泡栏/气泡堆），宽度缩小后会自动水平居中，指向气泡图标的小突出会同步跟随。

居中和指针补偿只在**横向气泡行**布局（手机竖屏，图标行居中于屏幕）下启用；横屏/大屏的侧边竖向气泡列保持系统默认的贴列摆放——那种布局下窗口居中会让指针够不到图标列。

> `getTaskViewContentWidth()` 内部会减去容器左内边距，而居中逻辑刚好加宽了它，因此宽度换算时需要把该偏移补回，否则实际比例会小于设置值。

百分比变化时模块会输出 `MBDBG bubble size apply` 日志（含 `source`、`axis`、`percent`、`before -> after`）；同时每个尺寸源头在每个进程里会输出一次 `MBDBG bubble size probe` 日志，用来确认设备实际走的是浮动气泡还是气泡栏布局，以及模块读到的百分比。

### 内容缩放（把 app 画面整体缩小）

气泡窗口是**真实窗口**：窗口变小后 app 只是重新排版去适配，文字与控件的物理尺寸由显示密度决定，所以「窗口小了」并不等于「内容小了」。

平台没有向 SystemUI 暴露按任务改密度的接口（`ActivityOptions` 只有 `setLaunchBounds` / `setLaunchDisplayId`，没有密度或 display configuration 覆盖），因此模块改用**排版放大 + 画面缩小**达到同样的观感：

| Hook 目标 | 作用 |
|-----------|------|
| `BubblePositioner.getTaskViewContentWidth(boolean)` | 宽度：窗口宽度 ÷ scale |
| `BubblePositioner.getMaxExpandedViewHeight(boolean)` | 高度上限：÷ scale |
| `BubblePositioner.getExpandedViewHeight(BubbleViewProvider)` | 资源高度：÷ scale |
| `BubbleExpandedView.mTaskView`（布局后处理） | 把画面按 scale 缩小、关掉 Surface 裁剪，画面正好铺满窗口 |

关键点：任务窗口的 bounds 由 `BubbleStackView.updateExpandedView()` 用 `（容器位置）+（getContentWidth() × min(getExpandedViewHeight, getMaxExpandedViewHeight)）` 直接算出来，**宽和高必须一起放大**——只放大宽会让 app 的排版框变成「宽够高不够」，缩放后画面比窗口矮一圈（表现为「窗口框比画面大一圈」）。

缩放后画面仍铺满窗口，窗口本身（轮廓、指针、居中）尺寸不变；因此等同于给该窗口更高的显示密度，文字与控件一起变小。

限制：内容缩放只作用于**手机竖屏的浮动气泡布局**（横向气泡行，非气泡栏、非侧边竖列），其它布局保持系统默认；另外这是画面级缩放而非真正的 density 改变，输入法与部分自绘 app 可能不完全贴合。


## 构建

```bash
cd source/BubbleButtonModule
source /mnt/TY/android/android-project/classapp/set-env.sh
export GRADLE_USER_HOME=$PWD/../.gradle
./gradlew assembleDebug
./gradlew assembleRelease
```

APK 输出：

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Release

Release 构建启用 R8 minify 与 resource shrink，并使用 release keystore 签名。

## AI 声明

本项目部分代码、调试与发布说明由 AI 辅助完成，最终行为以真实设备验证为准。

## License

Apache License 2.0
