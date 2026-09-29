# Proposal

## Why

真机使用中发现三个 UI 观感问题：首页无配置时的空状态提示块偏在屏幕上部（top middle）而非屏幕居中；首页的配置切换栏（分组 Tab 栏）高度偏大而文字偏小；分应用代理等沿用 T4A v1.x 布局的界面在用力滑动（fling）时，顶部预留的状态栏区域会随之滑下、露出窗口背景（对应 material-components-android 已知未修复 bug [#3404](https://github.com/material-components/material-components-android/issues/3404) "Status bar foreground detaches when flinging"，本仓库使用 material 1.8.0 同样受影响）。三者都是用户直接可见的观感缺陷，宜在日常使用前修掉。

## What Changes

- 首页空状态提示块（`layout_profile_list.xml` 的 `profiles_empty` 区域）改为在整个屏幕（视口）内几何居中，不再顶偏；底部为 FAB/状态栏预留的不对称 padding 不再把内容推高。
- 首页配置切换栏（`layout_group_list.xml` 的 `group_tab` TabLayout）高度略减（48dp → 40dp 量级），tab 文字略增大（14sp → 16sp 量级）；不影响其他 TabLayout 用法与按钮文字样式。
- 三个 T4A v1.x 布局（`layout_apps.xml` 分应用代理、`layout_app_list.xml` 应用列表选择、`layout_rule_set_picker.xml` 规则集选择）从 `fitsSystemWindows` + `statusBarForeground` 的状态栏处理模式迁移到不滚动的根容器承担顶部 inset 并裁剪折叠内容的模式；保留头部为置顶工具栏预留的空间，避免控件遮挡或滑入状态栏，规避上游 #3404 和 #4867。

## Capabilities

### New Capabilities
- `ui-layout`: 界面布局与系统栏处理的可验证行为——空状态内容的居中规则、配置切换栏的尺寸规则、legacy 界面顶部状态栏空间在滚动/滑动中保持固定的规则。

### Modified Capabilities

（无。现有 12 个能力规范均不覆盖 UI 视觉/布局行为；`profile-management` 等只约束数据与流程行为，本次不改动其 requirement。）

## Impact

- **受影响能力规范**：新增 `openspec/specs/ui-layout/spec.md`；不修改既有主规范。
- **涉及范围**：仅 Android 端 UI 资源/布局与少量 Activity inset 处理；不涉及 throne 核心、构建链（`buildScript/`、`.github/`）与开发工具布局（`tools/`）。
- **受影响代码**：
  - `app/src/main/res/layout/layout_profile_list.xml`（空状态居中）
  - `app/src/main/res/layout/layout_group_list.xml` 与 `app/src/main/res/values/themes.xml`（配置切换栏高度/字号；新增 tab 文字样式，不改 `TextAppearance.SagerNet.Button` 以免影响按钮）
  - `app/src/main/res/layout/layout_apps.xml`、`layout_app_list.xml`、`layout_rule_set_picker.xml`（去掉 `fitsSystemWindows`/`statusBarForeground`，恢复 header 的工具栏高度占位，在根容器固定状态栏底色并裁剪滚动内容）
  - 对应 Activity：`AppManagerActivity`、`AppListActivity`、`RuleSetPickerActivity`（根容器承担顶部 inset，应用栏仅处理横向 inset，覆盖通用的应用栏顶部 inset 监听器）
- **外部来源**（按 rules 记录）：
  - material-components-android issue [#3404](https://github.com/material-components/material-components-android/issues/3404)（"Status bar foreground detaches when flinging"，截至 2026-05 仍 Open 未修复）；本仓库 `com.google.android.material:material:1.8.0`（`app/build.gradle.kts`）。不依赖本地外部源码。
  - material-components-android issue [#4867](https://github.com/material-components/material-components-android/issues/4867)（"Content scrolls behind statusbar"，说明对可折叠 AppBarLayout 添加顶部 padding 时内容仍可绘制进状态栏；2025-07-28 关闭并提供替代布局建议）。
  - Android 12+ over-scroll 效果行为（developer.android.com "Android 12 overscroll"），`overScrollMode` 语义仅作辅助参考。
- **验证方式**：本地仅静态校验（`openspec validate`、布局/资源 lint 级检查）；构建与真机 fling/居中/尺寸验证交由 GitHub Actions 与真机回传截图。
