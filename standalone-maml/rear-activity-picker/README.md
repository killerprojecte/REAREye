# 独立背屏 Activity 选择卡片

本目录是一个独立 MAML 模板，不覆盖 AppWidget/旧 ActivityHost 样例。ZIP 根目录仅包含 `manifest.xml`
；卡片根标志为 `rearActivityPicker="true"`，不是伪装的小部件。

## 交互

1. 应用菜单只显示上一项、当前项、下一项（三行图标和名称）。
2. 点击应用进入三行 Activity 菜单，显示标签、全限定 Activity 类名（不含组件的 `包名/`
   前缀）及应用图标；上次记录显示在标题区。
3. 点击 Activity 进入 DPI 草稿页，竖向提供 `-10 / -1 / +1 / +10`、继承密度 `0`、返回、确认。无记录采用真实背屏
   density，限定正值 120..960；继承模式首次步进以真实 density 加减，仅接受落在合法范围内的结果。越界步进按钮不出现。
4. 确认原子保存成功才由原生桥发布一次用户启动请求。保存失败保留菜单及草稿，并显示 `ra_error`
   。恢复选择不是自动启动：没有新的启动请求时显示选择菜单，必须重新确认。
5. 应用画面使用完整右侧可显示矩形、纯黑背景，没有模板或原生启动/退出/主屏打开/重新选择按钮。
   会话关闭或暂停撤销许可后回到菜单；保存记录保留，返回及拖动均不保存。

两级菜单仅竖向分页，一次上下拖动一项，不横向翻页。点击与移动严格分开；移动超过阈值后即使返回原点也不算点击，cancel
不触发选择或保存。上下辅助按钮仍使用相同的防误触规则。当前页加载未完成、旧
revision/offset/package、未知行均不可选择，图标失效显示占位而不是沿用旧包图标。

## 宿主 ABI 与安全矩形

- `RearActivityAppsBinder name="apps"`：`appNames/packageNames/icons`，
  `total/offset/hasMore/requestLimit/requestIconSize`，以及通用 `count/loading/error/revision`。
- `RearActivitiesBinder name="activities" packageExp="@draftPackage"`：
  `activityNames/activityClasses/components/icons/packageName` 及同样的分页/通用状态。
  `activityClasses` 仅供显示；保存与启动仍使用完整 `components`。两个查询固定
  limit=3、iconSize=64（原生上限分别 7、96）。图标是可空 Bitmap 数组，通过对象别名及 `BitmapVar`
  获取，不是资源路径。
- `RearDisplayBinder` 发布真实背屏 `densityDpi/width/height` 和物理 `safeLeft/Top/Right/Bottom`
  ，优先使用系统 cutout。
  296px fallback 仅用于已核实的 Pro Max 型号（popsicle/2509FPN0BC）、976×596、rotation=0；不推测 Pro
  的遮挡宽度。
  本机读取到右侧 `Rect(296,0,976,596)`，内容 680×596；这是左侧不可显示区，不是四侧边框。
  模板逻辑 scale 为 1080/物理宽度，只用于坐标，不当作 density。
- `pickerX/Y/W/H` 是选择菜单的交互矩形，可保留菜单内部 padding；应用单独使用完整 `safeX/Y/W/H`，不扣额外边距。
  `ActivityHost showControls="false" navigationGutterDp="0"` 不创建按钮、不预留导航条。
  身份未就绪、density 未知或安全矩形不足时不启动。

原生桥独占 `ra_ready/ra_instance/ra_selected/ra_package/ra_component/ra_dpi/ra_error` 和
`ra_launchRequest`；XML 不声明、修改或持久化这些字段。草稿只存在于 MAML 内存。唯一命令入口是字符串
`ra_action`：

```text
instance|load|package||0|sequence
instance|save|package|fullComponent|dpi|sequence
instance|reset|||0|sequence
```

sequence 每个 root 为严格递增正整数，每次使用 `max(#sequence,#ra_actionSequence)+1`。
`ra_actionSequence` 是只读原生 nonce 下界，不声明或持久化：同一 root 原生 reinit 即使重置本地
sequence，也不能绕过防重放门槛。`load` 通过
`ra_savedComponent/ra_savedActivityClass/ra_savedDpi/ra_savedFound/ra_loadedPackage/ra_loadRevision`
回调；匹配 package 且
revision 更新后才接受恢复 DPI。宿主 saveVar Hook 已要求命令 `VariableCommand persist="true"`
：该标志仅用于进入可信命令 Hook，原生必须取消原 saveVar/ConfigFile 写入并消费命令，**动作不会落盘**
。未安装此原生 Hook 的普通 MAML runtime 不支持该模板，不能将 persist 标志误当配置持久化实现。

`ActivityHost` 读取 `componentExp="@ra_component"`、`dpiExp="#ra_dpi"`、`instanceExp="@ra_instance"` 和
`userRequestExp="@ra_launchRequest"`；未自设 boundsExp，采用安全矩形内 carrier 默认 bounds。模板不直接生成
launchRequest，也不启用背屏原生全屏交接。启动还需要原生桥在保存成功后授予的一次性 permit，并绑定当前
root、可信实例、component、DPI 与 sequence；后端原子消费 permit，重复请求失效。permit
使用动作受理时预留的全局顺序，而不是落盘完成时的顺序；隐藏、暂停、detach 和 finish
推进失效边界，因此迟到的持久化完成或快照不能在重新显示后自动启动。客户端在同步 `setSurface`
返回后再次检查会话代际，关闭期间阻塞的布局不会继续消费启动请求。仅伪造或复制 launchRequest
的裸字符串不可触发启动，恢复选择也不产生 permit。

## 原生安全职责与设备限制

目录须限定宿主当前用户和 PackageManager 可见应用，只允许 enabled、exported、无受限 permission 的入口，首版排除
alias；目录不是权限或应用登录绕过工具。点击、保存和实际启动仍必须重新验证组件，应用卸载/禁用/替换的竞态不能仅靠模板解决。

每张卡片、每个应用的记录需要原生私有 AtomicFile 存储和可信 owner 身份隔离，模板不能指定路径、其他 card
身份或任意 Intent。真正删除定义必须撤销实例和会话并清除这张卡的全部记录；隐藏、暂停、暂时移除显示及进程重启保留记录，不能当作删除。reset
只清当前 active 选择，不擦掉该卡各应用历史。

ActivityHost 的跨应用任务、输入洞穿/路由、焦点、IME、左右宿主导航带、摄像头遮挡和卡片切换属于原生安全边界。背屏全屏继续禁用；支付、鉴权及受保护应用可能拒绝嵌入。
**本阶段没有设备运行验证**：不能声称真实 Activity 启动、触摸、遮挡、重启持久化或支付兼容已通过。

## 校验及重复打包

在仓库根目录运行：

```sh
python standalone-maml/rear-activity-picker/validate_theme.py --package
```

校验器检查 XML schema、函数引用、BitmapVar/按钮动作 ABI、只读原生状态和单 ActivityHost，随后解释执行实际
XML 中的 VariableCommand/FunctionCommand/IfCommand/BinderCommand。27 个行为模型覆盖有限分页及过时结果、未知行/旧
package/loading、点击/单步拖动/cancel/触摸中 revision 改变、DPI
恢复/继承/边界、readiness/无有效安全区、保存失败重试和提交顺序、未授权恢复显示可操作菜单但不产生
launch、
应用状态无按钮/纯黑/完整安全矩形、返回不保存、防拖动误确认、物理 inset，以及 MAML tick 缓存旧值下互斥选择、
连续 DPI 步进和实时 live-selection 守卫、reinit 后本地 sequence=0/原生 nonce=10 的下一动作使用 11。
事件命令直接重新求值关键 guard，不依赖上一帧的派生 Good/loadReady/stepBase/liveSelection。

模拟目录回调和原生存储不是 Android runtime。Python 模型不证明原生 AtomicFile/任务启动/触摸 Hook
的正确性；这些还需要 Kotlin 测试和设备验收。

`--package` 生成本目录的 `rear-activity-picker.zip`，检查 ZIP 根结构和 CRC，并以固定时间戳、权限和条目顺序生成两次验证字节一致。
`--write-template` 可从脚本内的模板构造器重新生成 manifest；它用于维护同目录文件，不修改
production、旧样例或 Git。没有运行 Gradle。
