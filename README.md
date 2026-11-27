<p align="center">
  <img src="https://reareye-gh.uwu.hk/favicon-32.png" alt="REAREye logo" width="160" />
</p>

<h1 align="center">REAREye</h1>

<p align="center">
  适用于小米 17 Pro / 17 Pro Max 的背屏增强模块。<br>
  基于 LSPosed / Xposed，为系统框架、背屏中心、主题管理和系统界面提供更细粒度的可配置能力。
</p>

<p align="center">
  <a href="./README_EN.md">English</a> · <a href="https://github.com/killerprojecte/REAREye">项目主页</a>
</p>

<p align="center">
  <a href="https://github.com/killerprojecte/REAREye/releases"><img src="https://img.shields.io/github/v/release/killerprojecte/REAREye?display_name=tag" alt="GitHub release"></a>
  <a href="https://github.com/killerprojecte/REAREye/blob/main/LICENSE"><img src="https://img.shields.io/github/license/killerprojecte/REAREye" alt="GitHub license"></a>
  <a href="https://github.com/killerprojecte/REAREye/issues"><img src="https://img.shields.io/github/issues/killerprojecte/REAREye" alt="GitHub issues"></a>
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white" alt="Android"></a>
  <a href="https://github.com/LSPosed/LSPosed"><img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-5C6BC0" alt="Framework"></a>
</p>

## 功能概览

- 放行指定应用在背屏启动，并支持活动白名单控制。
- 管理后台白名单和进程锁定，提升背屏应用驻留稳定性。
- 自定义音乐控件白名单，兼容更多媒体类应用。
- 增强歌词显示，支持原文、翻译、罗马音、不同歌词提供器和内置歌词处理策略。
- 管理背屏组件模板、通知路由、常驻卡片、卡片模板变量和组件额外显示选项。
- 通过背屏组件仓库安装、更新、卸载组件和壁纸资源。
- 管理背屏壁纸、定时轮播、拖拽排序、导入资源、metadata、预览图和模板配置。
- 解除主题管理中的多项限制，包括视频壁纸时长、帧率、静音和模板数量限制。
- 移除国行 GMS 功能限制。
- 提供切换主屏 / 背屏的快捷设置 Tile，以及常用目标应用的快捷终止入口。

## 主要功能

### 模块设置

- 主题样式
  - 支持 Miuix / Monet 两套风格。
  - 支持跟随系统、浅色、深色模式。
- 导航栏样式
  - 支持普通、悬浮、悬浮液态玻璃样式。
- 搜索栏样式
  - 支持默认样式和 Miuix 样式。
- 仓库 API
  - 支持阿里云、Cloudflare 和自定义 API 域名。
  - 自定义域名只需要填写主机名，不需要 `https://` 或路径。
- WebView 硬件加速
  - 默认开启；如果 Markdown 预览在设备上崩溃，可关闭切换到软件渲染。
- 收藏配置
  - 长按配置节点可加入收藏，便于快速访问常用选项。
- 隐藏桌面入口
  - 可选隐藏 REAREye 的 Launcher 图标。
- 更多调试输出
  - 便于排查模块行为与兼容性问题。

### 系统框架增强

- 背屏应用活动白名单
  - 自定义哪些应用允许在背屏中打开。
  - 可单独维护允许的应用列表。
- 允许所有 Activity 在背屏打开
  - 适合自行测试未适配应用。
- 后台白名单
  - 让指定应用在背屏使用时更不容易被系统杀掉。
- 锁定应用进程
  - 将指定应用以更强的保活方式维持在后台。
- 阻止锁屏后背屏返回桌面
  - 保持背屏当前状态，减少锁屏后的自动跳回。
- 禁用背屏保护
  - 主屏进入全屏模式时，不再自动锁定背屏。

### 背屏中心增强

#### 背屏组件仓库

- 浏览、搜索、排序背屏组件和壁纸资源。
- 查看组件详情、README、Release 说明、下载资源和仓库信息。
- 支持组件模式和壁纸模式安装。
- 支持安装进度显示、版本更新提示、最低 / 最高模块版本检查。
- 支持卸载已安装的仓库组件，并清理相关模板、卡片、通知路由或壁纸记录。
- 安装时支持冲突提示，可选择卸载现有组件或强制覆盖。

#### 背屏个性化管理

- 组件模板管理器
  - 管理组件到模板文件的映射关系。
  - 支持导入模板文件并立即更新运行时映射。
  - 仓库管理的组件会显示来源与锁定状态，避免误改。
- 通知路由管理器
  - 根据包名和通知规则，将系统通知映射到指定背屏组件。
  - 支持精确匹配和正则规则。
  - 支持通知发布和移除时实时同步背屏卡片状态。
- 卡片管理器
  - 管理常驻卡片的标题、目标包名、组件名称、优先级和启用状态。
  - 支持关闭常驻显示，仅保存卡片配置。
  - 支持按卡片配置模板变量。
- 卡片模板配置
  - 支持文本、颜色、字号、对齐、图片、日期、开关、下拉、Intent、背景资源、动画和字符串变量。
  - 支持浅色 / 深色预览、图片预览、自定义图片导入和外部编辑器跳转。
- 组件额外设置管理器
  - 手动添加需要额外显示选项的组件。
  - 当前支持为指定组件关闭通知时间显示。
- 允许焦点通知在背屏显示
  - 对已经携带有效焦点 / 背屏参数的通知放行，但对应组件或模板仍需存在。

#### 壁纸管理器

- 查看当前系统可用背屏壁纸、当前壁纸和壁纸状态。
- 设定当前壁纸。
- 将壁纸加入轮播列表。
- 拖拽调整轮播顺序。
- 按毫秒配置每张壁纸的切换间隔。
- 开启或关闭背屏壁纸轮播。
- 标记当前、轮播中、不可用、可编辑、第三方、AON 等状态。
- 导入 MRC / ZIP 壁纸包。
- 选择或生成预览图。
- 编辑壁纸 metadata，包括标题、描述、作者、设计者、分类、资源子类型、可编辑状态、第三方标记和 AON 标记。
- 编辑支持模板配置的壁纸变量。
- 删除由 REAREye 导入或仓库安装的壁纸。

#### 音乐控件增强

- 音乐控件白名单
  - 自定义哪些应用的音乐控件允许显示在背屏中。
  - 对使用标准媒体控件的应用更友好。
- 强制刷新音乐控件状态
  - 解决部分应用在切歌或状态变化后背屏控件更新不及时的问题。

#### 歌词增强

- 歌词显示模式
  - 支持原文、翻译、罗马音。
- 歌词提供器切换
  - 支持 `Lyricon`。
  - 支持 `SuperLyric`。
- 首句前显示歌手名
  - 对静态 LRC 歌词生效。
- 逐行歌词显示模式
  - 可选择显示原文或翻译。
- 移除原生 API 歌词
  - 可移除小米歌词元数据提供的原生歌词信息。
- 跳过仅曲名变化的元数据更新
  - 避免蓝牙等场景只改标题时干扰歌词模块。
- 接管内置歌词处理逻辑
  - 可由 REAREye 接管系统歌词进度与换行处理，也可关闭回退系统逻辑。

#### 视频与媒体行为

- 强制视频壁纸循环播放。
- 视频壁纸音量调节
  - 支持从 0% 到 100% 细粒度调节播放音量。

### 主题管理增强

- 解除视频壁纸限制
  - 移除视频时长限制。
  - 移除默认帧率限制，尽量按素材原始帧率加载。
- 解除背屏模板数量上限
  - 不再受原厂默认模板数量限制。
- 解除视频壁纸静音限制
  - 允许添加带音频的视频壁纸。
- 背屏壁纸同步增强
  - 配合壁纸管理器和导入流程，使主题管理侧资源与背屏中心侧运行时状态保持一致。

### 杂项功能

- 移除国行 GMS 服务限制
  - 用于解除部分国行系统对 Google 服务功能的限制。
  - 例如时间线、位置记录等功能的可用性问题。

### 额外功能

- 快捷设置 Tile
  - 切换当前应用到背屏。
  - 将先前移至背屏的应用切回主屏。
- 首页状态与快捷操作
  - 查看模块工作状态、版本信息、构建渠道、更新状态和 Root 状态。
  - 提供针对背屏中心、主题管理、系统界面的快捷终止入口。
- 关于页
  - 查看项目链接、文档、QQ 群、酷安主页、鸣谢人员和赞助入口。

## 模块作用域

默认作用域包含以下目标进程：

- `android`
- `com.xiaomi.subscreencenter`
- `com.android.thememanager`
- `com.android.systemui`

## 前置要求

- Android 16 / API 36 及以上。
- LSPosed / Xposed 兼容环境。
- Xposed 最低版本 93。
- 适用于带背屏的目标机型，当前仓库主要面向小米 17 Pro / 17 Pro Max。
- 部分功能或快捷操作需要 Root 权限，例如快捷终止目标应用、主屏 / 背屏应用切换等。
- 背屏组件仓库、更新检查、鸣谢人员列表等网络功能需要互联网访问。

## 安装方式

1. 从 [Releases](https://github.com/killerprojecte/REAREye/releases) 下载最新 APK 并安装。
2. 在 LSPosed 或兼容框架中启用 `REAREye`。
3. 确认模块作用域包含：`android`、`com.xiaomi.subscreencenter`、`com.android.thememanager`、
   `com.android.systemui`。
4. 重启设备，或至少重启相关目标应用后再进入模块配置。
5. 打开模块应用，根据自己的需求启用对应功能。

## 使用说明

- 背屏白名单、音乐控件白名单、组件模板、通知路由、卡片、壁纸轮播等配置修改后，建议重启
  `com.xiaomi.subscreencenter`，或使用首页的快捷终止入口后重新打开背屏中心。
- 通知路由依赖 `com.android.systemui` 与 `com.xiaomi.subscreencenter` 的桥接，调整路由后如未立即生效，可重启这两个目标进程。
- 系统框架类修改通常需要重启系统后才能稳定生效。
- 主题管理相关修改建议在变更后重启 `com.android.thememanager`，必要时重启系统。
- 部分快捷操作或系统级能力依赖 Root 权限，请确保 Root 管理器已正确授权。
- 仓库管理的组件、卡片和通知路由会保留来源信息；手动管理时请先确认是否需要卸载或覆盖仓库组件。
- 本模块不内置背屏应用启动器；如果你需要直接在背屏拉起某些应用，可以配合 ADB 或其他启动方式使用。
- 解锁模板数量上限后，极端大数量模板场景仍建议自行测试稳定性。
- 如果仓库详情中的 Markdown 预览导致崩溃，可在模块设置中关闭 WebView 硬件加速。

## 适用场景

- 想让更多应用在背屏上正常启动或驻留。
- 想在背屏使用更多第三方音乐 App 的媒体控件。
- 想更自由地控制歌词来源、显示内容和处理逻辑。
- 想通过仓库安装、更新、卸载背屏组件或壁纸。
- 想自己维护背屏组件模板、通知路由和常驻卡片。
- 想给背屏壁纸做定时轮播、排序、导入、metadata 和预览图管理。
- 想解除原厂主题管理器对视频壁纸和模板数量的限制。
- 想在不刷额外 Magisk 模块的情况下处理国行 GMS 限制。

## 从源码构建

项目使用 Kotlin、Jetpack Compose、Android Gradle Plugin 和 Gradle Wrapper。

- JDK 17。
- Android 编译 SDK 37.2 / Build Tools 37.0.0。
- 最低 SDK 36，目标 SDK 37。
- 如需签名 Release 包，请在 `local.properties`、Gradle 属性或环境变量中配置：
  - `androidStoreFile`
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`
  - `KEY_PASSWORD`

常用命令：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

构建产物位于 `app/build/outputs/`，Release 构建会额外导出重命名后的 APK。

## 获取帮助

- 提交问题反馈: [Issues](https://github.com/killerprojecte/REAREye/issues)
- 查看版本发布: [Releases](https://github.com/killerprojecte/REAREye/releases)
- 项目仓库地址: [killerprojecte/REAREye](https://github.com/killerprojecte/REAREye)

## 免责声明

- 本模块会修改系统与目标应用行为，请自行评估风险。
- 不同系统版本、不同固件版本、不同 Root / Xposed 环境之间可能存在兼容性差异。
- 背屏中心、主题管理和系统界面更新后，部分 Hook 点可能需要适配。
- 使用本模块造成的功能异常、数据问题或设备风险，请自行承担。

## License

See [LICENSE](./LICENSE).
