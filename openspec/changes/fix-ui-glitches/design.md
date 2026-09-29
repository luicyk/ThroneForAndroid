# Design

## Context

动机见 proposal.md - Why；行为要求见 specs/ui-layout/spec.md。此处只记录影响方案的现状事实：

- **空状态块**（`app/src/main/res/layout/layout_profile_list.xml`）：`ScrollView`（`@id/profiles_empty`，`fillViewport="true"`）内唯一子 `LinearLayout` 设了 `android:layout_gravity="center"` + `android:gravity="center_horizontal"` + 不对称 padding（top 24dp / bottom 104dp）。`ScrollView` 忽略子 view 的 `layout_gravity`；`fillViewport` 又把子高拉到视口高度，内容便顶对齐贴在上部（即用户看到的 top middle）。`ProfileListFragment` 对该 ScrollView 调 `applyListInsets()`（bottom + horizontal inset，`clipToPadding=false`）。
- **配置切换栏**（`app/src/main/res/layout/layout_group_list.xml` 的 `@id/group_tab`）：`TabLayout`，`layout_height="wrap_content"`，文字样式来自全局 `tabStyle` → [`Widget.SagerNet.TabLayout`](app/src/main/res/values/themes.xml:157)（`tabTextAppearance` = [`TextAppearance.SagerNet.Button`](app/src/main/res/values/themes.xml:150)，14sp）。`TextAppearance.SagerNet.Button` 同时被 MaterialButton、对话框按钮、snackbar 动作共用，不能整体放大。
- **legacy 界面**：`layout_apps.xml`（`AppManagerActivity` 分应用代理）、`layout_app_list.xml`（`AppListActivity`）、`layout_rule_set_picker.xml`（`RuleSetPickerActivity`）共享同一套 T4A v1.x 折叠头部模式：root `CoordinatorLayout` / `AppBarLayout` / `CollapsingToolbarLayout` 三层 `fitsSystemWindows="true"`，`AppBarLayout` 带 `app:statusBarForeground="?attr/colorPrimary"`，header 硬编码 `paddingTop="56dp"` 顶开状态栏；三个 Activity 都以注释 "the app bar fits system windows (status bar foreground); the list pads the navigation bar" 配 `binding.list.applyListInsets(ime = true, horizontal = false)` + toolbar/header 的 `applyInsetPadding(horizontal = true)`。
- **已知上游 bug**：material-components-android [#3404](https://github.com/material-components/material-components-android/issues/3404)（"Status bar foreground detaches when flinging"）——`statusBarForeground` 在 fling 时与 AppBarLayout 脱开下移，与用户截图一致；该 issue 截至 2026-05 仍 Open，本仓库 `com.google.android.material:material:1.8.0` 受影响。
- **仓库既有模式**：非折叠界面统一用 [`AppBarLayout.applyTopInset()`](app/src/main/java/io/nekohasekai/sagernet/widget/WindowInsetsListeners.kt:137)（top + horizontal inset padding，insets 不消费），由 [`ThemedActivity.onContentChanged`](app/src/main/java/io/nekohasekai/sagernet/ui/ThemedActivity.kt:66) 对 `R.id.appbar` 自动安装。三个 legacy 布局的 AppBarLayout 同为 `@id/appbar`，改掉 XML 后自动走此路径，无需 Activity 级 inset 代码。

## Goals / Non-Goals

**Goals:**
- 三个问题各有一个最小、可独立审查与回退的修复；行为契约落在 specs/ui-layout。
- 问题 3 的修复采用仓库已有 inset 模式（`applyTopInset`），让 legacy 界面与其余界面的状态栏处理同构，而不是引入新的 hack。
- 全部改动限于 Android UI 资源/布局与少量 Activity inset 调用；不动核心、构建链、工具布局。

**Non-Goals:**
- 不升级 material 库、不为 #3404 引入 fork/补丁。
- 不重构 legacy 界面的折叠头部视觉设计（布局结构、卡片、chips 保持原样）。
- 不统一全应用的 inset 处理风格（只迁移出问题的三个界面）。
- 不处理与本次三个问题无关的 UI 细节（如横屏 stats bar）。

## Decisions

1. **空状态居中：修正 `ScrollView` 子 view 的 gravity，而不是换容器。**
   把 `LinearLayout` 的 `android:gravity` 从 `center_horizontal` 改为 `center`（水平 + 垂直），删除无效的 `android:layout_gravity="center"`；`fillViewport="true"` 保留——内容不足视口时子高被拉伸、内部 `gravity="center"` 垂直居中，内容超高时 `wrap_content` 生效可滚动（满足 spec 的滚动场景）。不对称 padding（top 24dp / bottom 104dp）改为对称（上下 24dp 量级），否则居中仍被推高。
   - 备选：ConstraintLayout/FrameLayout 包裹居中——为一个纯静态提示块换容器并新增依赖层级，收益为零；`layout_gravity` 改动即根因修复。
   - 注：`applyListInsets()` 给 ScrollView 加的 bottom inset（导航栏高度）使居中区域是"屏幕减导航栏"，视觉中心高半个导航栏高度，属可接受偏差（Risks 记录）。

2. **配置切换栏：只调 `group_tab` 局部属性，不共用按钮文字样式。**
   在 `group_tab` 上设 `android:layout_height="40dp"`（48dp 默认高 → 稍矮）并新增专用文字样式 `TextAppearance.SagerNet.Tab`（parent `TextAppearance.SagerNet.Button`，`android:textSize="16sp"`）经 `app:tabTextAppearance` 引用。改动仅落在首页这一处 TabLayout。
   - 备选：直接改 `Widget.SagerNet.TabLayout` 全局样式——该样式经主题 `tabStyle` 作用于所有 TabLayout，与 spec "不影响其他标签栏"冲突；被否。
   - 备选：放大 `TextAppearance.SagerNet.Button`——按钮/对话框共用，波及面失控；被否。
   - 40dp/16sp 是"稍微小一点/稍大一点"的量化解析，真机观感可在验证阶段微调（Risks 记录）。

3. **legacy 界面：状态栏 inset 放在不滚动的根容器，修复真机回归。**
   首次迁移把 `AppBarLayout` 的 `fitsSystemWindows`/`statusBarForeground` 移除、改用 `ThemedActivity.onContentChanged` 的 `applyTopInset()`，并删掉 header 的 `paddingTop="56dp"`。真机发现两处回归：第一排模式控件被置顶工具栏遮挡；下拉后折叠头部白色卡片绘制进透明状态栏。上游 material-components-android [#4867](https://github.com/material-components/material-components-android/issues/4867) 确认给可折叠 AppBarLayout 加顶部 padding 不能阻止头部绘制进状态栏；先前假设不成立。
   三个布局的 root `CoordinatorLayout` 增加主题色背景和 `clipToPadding="true"`；三个 Activity 对 `binding.root` 调用 `applyInsetPadding(top = true)`，由固定根容器预留状态栏空间并裁剪超出顶部边界的折叠内容；对 `binding.appbar` 调用 `applyInsetPadding(horizontal = true)`，覆盖 `ThemedActivity.onContentChanged` 自动注册的顶部 inset 监听器（仍保留侧边导航栏的安全间距），避免双份顶部 inset。恢复三个 header 的顶部占位，采用 `?attr/actionBarSize` 与其置顶 Toolbar 的高度一致（取代原本固定的 56dp）；列表仍用 `applyListInsets(ime = true, horizontal = true)` 处理底部与横向 inset。根容器不消费 insets，列表照常接收。
   - 备选：升级 material 到修复版——#3404 至今 Open，无修复版本可升；被否。
   - 备选：列表 `overScrollMode="never"`——#3404 是 fling settle 时 foreground 绘制位置问题，与 over-scroll 效果开关无关，且损失 over-scroll 反馈；被否。
   - 备选：保留 AppBarLayout 顶部 padding，仅给根容器加背景色——只能遮盖窗口背景，无法防止头部文字压在状态栏图标上；被真机证据否定。
   - 备选：把固定 Toolbar 和滚动头部拆成两个 AppBarLayout——上游 #4867 推荐，可从结构上分离；但三处界面需重排布局/滚动行为，先采用根容器裁剪的最小自洽修复。
   - 代价：根容器裁剪禁止列表在状态栏背后绘制，这三个旧界面不再有状态栏区域的 edge-to-edge 内容效果；符合固定空间要求。

## Risks / Trade-offs

- [空状态居中区域含 bottom inset，中心比纯屏幕中心略高（约半个导航栏高度）] → 视觉差异在数 dp 量级，真机确认可接受；若要求严格屏幕中心，可在验证阶段把 `applyListInsets()` 换成对称 inset 处理，spec 场景不变。
- [40dp / 16sp 的"稍微"量级因屏幕密度/字体缩放观感不同] → 真机截图核对，不符即微调数值；spec 以"约"表述，改动不越出行为边界。
- [根容器裁剪是否在所有 Android 版本及机型都能隔离折叠头部] → 批次 3 真机覆盖初始/下拉/折叠/横屏；不通过则停留本批次，考虑上游推荐的固定工具栏与可折叠头部分离方案，不继续收尾。
- [工具栏高度随屏幕方向和设备变动] → header 顶部占位引用 `?attr/actionBarSize`，确保与实际 Toolbar 配套，而不是硬编码 56dp。
- [三个 Activity 的 inset 调用调整若漏改会出双倍 padding 或列表贴边] → tasks 中列为同批次必改项，验证场景含横竖屏侧边/底部导航栏检查。

## Migration Plan

无数据/接口迁移。三个修复分为三个独立批次提交（见 tasks.md），每批次可单独 `git revert` 回退，互不依赖；回退时对应 spec 场景随之失去实现，需在回退说明中标注。

## Open Questions

（无。数值微调与居中精度属验证阶段的观感校准，不改变 specs、方案或任务拆分。）
