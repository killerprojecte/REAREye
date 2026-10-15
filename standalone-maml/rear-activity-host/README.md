# ActivityHost 独立 MAML 样例

本目录沿用 `rear-app-widget` 的独立包布局：ZIP 根目录放置 `manifest.xml`，入口为 `Screen`（
`screenWidth="1080"`、`version="2"`），通过 `RearDisplayBinder`
获取背屏尺寸和安全边距。它不依赖旧样例的小部件选择状态，也不修改旧样例或压缩包。

## 使用与两张卡片

需要宿主已注册 `ActivityHost` 元素且相应运行时能力可用。将生成的 `rear-activity-host.zip` 作为独立
MAML 包加载。

两处 `component="com.android.settings/com.android.settings.Settings"`
仅是配置示例；使用前必须替换为目标设备上实际存在、可供调用方正常启动的 **exported**
Activity。设备厂商可能替换设置入口；此示例不承诺该组件在你的设备可用，也不尝试绕过目标权限、锁屏认证或系统启动规则。

- A 卡仅使用载体 `x/y/w/h`、显式 component 和 `dpi="0"`，不设置 bounds。任务占据左右导航区之间的内容区域。0
  表示不请求固定 density 覆盖。
- B 卡使用 `dpi="320"` 和 `boundsExp="'0,0,'+#demoRight+','+#demoBottom"`。该表达式先生成一个四数值字符串，随后按
  `left,top,right,bottom` 解析；并非四段 bounds 分别由原生 numeric 表达式求值。示例右边界为卡片逻辑宽度的
  40%，下边界为卡片逻辑高度的 80%。

## 坐标、density 与导航

`x/y/w/h` 使用 MAML 逻辑单位。bounds 的原点是**扣除左右导航条后的内容父矩形左上角**
，并非整个背屏或卡片原点；bounds 同样使用 MAML 逻辑单位，经载体实际 MAML 比例换算为像素。固定 dpi
只影响目标任务配置，不是 bounds 的缩放系数。

左右各默认保留 24dp，按宿主 density 转像素并向上取整；这些区域不属于任务内容，可供原有宿主导航手势使用，右侧提供“退出”入口。导航区不得设为
0，也不得侵占全部内容。退出结束本会话新建且拥有的任务，不接管或删除用户已有主屏任务。

若设备 density 较高、可用背屏过窄，或你修改了卡片尺寸，请相应缩小 B 卡 bounds。越界、无可见 clip
或内容过小会拒绝布局，不会静默夹取成合法矩形。样例按背屏安全边距布局，但不保证所有设备尺寸都能容纳两张卡。

## 显式启动与回退

加载包不会自动启动 Activity。每张卡需用户点击宿主生成的“启动 / 按当前 DPI
重建”按钮；两个示例会话相互独立，不保证目标应用支持同时显示两个任务。

运行期间可点击卡片右侧“退出”结束嵌入，再显式点击“主屏打开”。背屏原生全屏交接当前禁用：此 ROM 的入口仅排队
Shell transition，立即释放载体会清空 transition 依赖的任务
token/leash；尚未实现并验证可靠的脱嵌完成确认，不能当作已支持。隐藏、移除、暂停或启动失败后，不会自动循环重启；再次嵌入需用户重新点击。

初始态或未嵌入失败时，用户可显式点击宿主提供的“主屏正常打开”入口，尝试正常的主屏 Activity 启动回退。它不是特权
broker 启动，也不是绕过锁屏认证的机制；是否允许启动、最终显示位置及目标权限仍受系统与设备策略限制。不可用时请回到主屏按正常方式启动该应用。

## 构建与校验范围

在仓库根目录运行：

```sh
python standalone-maml/rear-activity-host/validate_theme.py --build
```

脚本只对本目录 XML、两张卡的属性、说明文件和新 ZIP 根目录结构做静态校验，并生成
`standalone-maml/rear-activity-host.zip`。不执行 Gradle，不修改旧样例或旧 ZIP，不连接设备。

当前没有可用于本任务的具体设备 ADB 连接。XML 解析和 ZIP 校验不是安全验证，不代表系统/ROM
ABI、真实触摸路由、导航手势、锁屏认证、目标 density、生存周期、背屏全屏或主屏回退已通过真机验证；这些必须在目标设备上单独验收。
