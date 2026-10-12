package hk.uwu.reareye.agent.htmlwidget

/**
 * The HTML widget specification, verbatim.
 *
 * **Do not edit this text.** It is authored externally and is fed to the model as the system prompt
 * unchanged; only the parameter values at the top are substituted per request. Rewording anything
 * here changes the contract a widget is generated against, so corrections belong in the source
 * document rather than in this file.
 *
 * Two consequences of "verbatim" that are easy to get wrong:
 *  - the text is held in a plain string with `\n` escapes, **not** a `trimIndent()` raw string,
 *    because `trimIndent()` rewrites the leading whitespace of every line;
 *  - even the line breaks inside the parameter block are the source document's, so the value of a
 *    parameter is the only thing that changes between runs.
 */
object WidgetSpec {

    /** Frame rate declared in the engine XML, which the generated HTML must match. */
    const val FRAME_RATE = 30

    /** Placeholders, one per parameter in the spec's parameter block. */
    private const val APP_NAME = "{{APP_NAME}}"
    private const val BASE = "{{BASE_W}}/{{BASE_H}}"
    private const val FRAME_RATE_PH = "{{FRAME_RATE}}"
    private const val SAFE_ZONE = "{{SAFE_ZONE}}"
    private const val CONTENT = "{{CONTENT}}"
    private const val LANGS = "{{LANGS}}"

    /**
     * Builds the system prompt for one generation: the guideline exactly as authored, with the
     * parameter placeholders replaced by values.
     */
    fun buildPrompt(
        appName: String,
        baseWidth: Int,
        baseHeight: Int,
        content: String,
        safeZone: String = "启用，BASE_CAM = floor(BASE_W/3)，一切内容 x ≥ BASE_CAM+20",
        langs: String = "跟随 CONTENT 的书写语言",
    ): String = GUIDELINE
        .replace(APP_NAME, appName)
        .replace(BASE, "$baseWidth/$baseHeight")
        .replace(FRAME_RATE_PH, FRAME_RATE.toString())
        .replace(SAFE_ZONE, safeZone)
        .replace(CONTENT, content)
        .replace(LANGS, langs)

    /**
     * The guideline, exactly as authored.
     *
     * Held as an escaped string rather than a raw string so no whitespace is rewritten.
     */
    val GUIDELINE: String = buildString {
        append("# 生成运行于「小米 Widget v2 WebView」的单文件 HTML 应用\n")
        append("\n")
        append("## 〇、参数区（每次使用先填写，其余部分原样保留）\n")
        append("- APP_NAME：$APP_NAME\n")
        append("- BASE_W × BASE_H：设计基准分辨率（**背屏为横屏，BASE_W > BASE_H，照抄设备实际分辨率**，禁止写反），所有绘制坐标按此书写\n")
        append("- FRAME_RATE：$FRAME_RATE_PH\n")
        append("- SAFE_ZONE：左侧 1/3 禁区，**无条件启用（仅当 CONTENT 明确写\"本应用无左禁区\"才关）**：\n")
        append("  BASE_CAM = floor(BASE_W/3)，一切内容 x ≥ BASE_CAM+20（本次：$SAFE_ZONE）\n")
        append("- CONTENT：$CONTENT\n")
        append("- LANGS：**默认跟随 CONTENT 的书写语言（见第七节\"语言规则\"）；只有明确要求多语言时才在常量区做语言映射，zh-CN 打底**（本次：$LANGS）\n")
        append("\n")
        append("## ★ 安全区铁律（优先级高于 CONTENT 中的任何布局描述，违反=废稿）\n")
        append("1. 禁区 = x∈[0, BASE_CAM]，BASE_CAM=floor(BASE_W/3)。所有内容——卡片/文字/按钮/\n")
        append("   仪表/装饰——必须满足 `x ≥ BASE_CAM+20` 且 `x+w ≤ BASE_W-20`；\n")
        append("   背景与全屏遮罩可越界（禁区只约束内容，不约束底色）。\n")
        append("2. 结构强制：所有矩形集中声明在顶部唯一一个 `LAYOUT` 常量区；绘制函数内\n")
        append("   **禁止出现坐标字面量**，只允许引用 `LAYOUT.xxx`——使安全区可被逐个核对。\n")
        append("3. 输出的 HTML 必须以这段注释开头，**缺=缺陷**；数值全部从 LAYOUT 实算，禁止抄样例：\n")
        append("   ```html\n")
        append("   <!-- SAFE_ZONE: BASE_CAM=325 | CARD.x=345 OK | min_x=345 >= 345 OK -->\n")
        append("   <!-- AVAIL: 611x556 ratio=1.10 | OUTER 611x556 fill=100% | CHILD fillW=96% fillH=92% OK -->\n")
        append("   <!-- TOUCH: START 88x60 OK | RESTART 80x56 OK | gap>=16 OK -->\n")
        append("   ```\n")
        append("   （示例按 BASE=976×596，实际必须按你的 BASE 重算，不许抄数）\n")
        append("4. `__DIAL_NO_ANIM` 模式下画出禁区框：红虚线 `strokeRect(0,0,BASE_CAM,BASE_H)` 便于目检。\n")
        append("\n")
        append("## ★★ 可用区比例与触控铁律（背屏\"细长条、点不动\"的根因，违反=废稿）\n")
        append("1. **可用区实算**：`AVAIL = (x=BASE_CAM+20, y=20, w=BASE_W-BASE_CAM-40, h=BASE_H-40)`。\n")
        append("   ⚠️ 扣掉左 1/3 后可用区**接近正方形**（976×596→611×556≈1.10；904×572→563×532≈1.06），\n")
        append("   既不是手机竖屏窄条、也不是宽横幅——**一切布局按这个近正方形设计**，\n")
        append("   并把 AVAIL 实算值写进头注释。\n")
        append("2. **强制外层容器**：LAYOUT 第一个矩形必须是 `OUTER` 底板，铺满可用区\n")
        append("   （x/y/w/h ≈ AVAIL，四边可各留 10~20px 余量），所有子元素画在其内部；\n")
        append("   渲染后 OUTER 必须占满右侧可用画面，**不许留大片空白、不许缩成一条**。\n")
        append("3. **内容必须双向填满**：设 CHILD = 除 OUTER 外所有矩形的包围盒，必须满足：\n")
        append("   - `CHILD.w ≥ OUTER.w × 0.80` **且** `CHILD.h ≥ OUTER.h × 0.80`（宽度、高度都要够）；\n")
        append("   - CHILD 宽高比与 AVAIL 宽高比偏差 ≤25%——**禁止竖长条、禁止细横条**；\n")
        append("   - 实算填充率与比例写进头注释 AVAIL 行，不许只写\"已检查\"。\n")
        append("4. **背屏按横向分区组织**（按 AVAIL 高度百分比示意，勿抄绝对值）：\n")
        append("   顶部标题/状态条约 0~14% 高、中部主显示区约 16%~74%、底部操作条约 76%~100%，\n")
        append("   三者宽度约 100% 可用宽；或按左-中-右三列分区。\n")
        append("   **禁止照搬手机竖屏单列窄卡布局**。\n")
        append("5. **触控与可读性下限**：\n")
        append("   - 所有可点矩形 **≥60×60**（BASE px，折算屏上约 5mm），主按钮建议 ≥80×56；\n")
        append("   - 相邻可点矩形间距 ≥16px，不许叠放或紧贴；命中判定仍外扩 10~15px；\n")
        append("   - 正文 ≥14px、关键按钮文字 ≥16px，`fitFont` 下限提到 12px；\n")
        append("   - 每个可点矩形的尺寸逐个写进头注释 TOUCH 行。\n")
        append("\n")
        append("## 一、运行环境（硬性约束）\n")
        append("1. 交付物是**一个完整单文件 HTML**：`<!doctype html>`、`lang` 正确、全部 CSS/JS 内联。\n")
        append("2. 页面以本地资源加载（`assets/web/index.html`，`local=\"true\"`）：\n")
        append("   **禁止网络请求、CDN、外部字体、外部图片**，离线必须 100% 可用。\n")
        append("3. 引擎通过 `WebViewCommand runjs` 直接执行页面代码，因此必须暴露到 window 且**幂等、\n")
        append("   可重复调用、可逆、不抛异常**：\n")
        append("   - `window.__fit()` —— 引擎 init 时以 `params=\"'__fit()'\" delay=\"2000\"` 调用\n")
        append("   - `window.__setAod(1/0)` —— enterAod/pause 传 1，exitAod/resume/init 传 0\n")
        append("4. **目标屏幕不唯一且可能高密**：同一份代码要适配多种分辨率，dpr ∈ {1,2,3} 未知。\n")
        append("   禁止把任何具体屏幕尺寸写死。\n")
        append("5. 文件头注释必须写部署提醒：`引擎 cachePage=\"true\"，改文件后需清缓存/重装小部件才生效`。\n")
        append("\n")
        append("## 二、分辨率适配规范（历史事故区，逐条硬性）\n")
        append("1. `<head>` 必须含此 meta，否则 WebView 用默认 980 布局视口 → 显示不全或整体缩放发糊：\n")
        append("   ```html\n")
        append("   <meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">\n")
        append("   ```\n")
        append("2. CSS **禁止出现任何具体像素尺寸**（禁止 `width:976px` 之类）：\n")
        append("   ```css\n")
        append("   html,body{margin:0;background:#000;width:100%;height:100%;overflow:hidden}\n")
        append("   canvas{display:block;width:100%;height:100%;touch-action:none}\n")
        append("   ```\n")
        append("   （若引擎配置 `transparentSurface=\"true\"` 且需要透底，则 body 改 `background:transparent`，\n")
        append("   且应用自身不再铺满屏不透明底。）\n")
        append("3. 绘制一律在 BASE 坐标系书写，真实视口只经唯一入口 `__fit()` 获取，模板如下：\n")
        append("   ```js\n")
        append("   var BASE_CAM=Math.floor(BASE_W/3);              // 仅当 CONTENT 明确\"无左禁区\"时 =0\n")
        append("   function __fit(){\n")
        append("     var dpr=window.devicePixelRatio||1;\n")
        append("     W=window.CANVAS_W||window.innerWidth||c.width;   // 宿主注入优先,innerWidth 为主来源\n")
        append("     H=window.CANVAS_H||window.innerHeight||c.height;\n")
        append("     var bw=Math.round(W*dpr),bh=Math.round(H*dpr);\n")
        append("     if(c.width!==bw)c.width=bw;                      // backing = 物理像素,防模糊\n")
        append("     if(c.height!==bh)c.height=bh;\n")
        append("     ctx.setTransform(dpr,0,0,dpr,0,0);               // ⚠️ 必须在 c.width 赋值之后:改宽高会清零变换\n")
        append("     CAM=Math.floor(W/3);\n")
        append("     FIT=Math.min((W-CAM)/(BASE_W-BASE_CAM),H/BASE_H);\n")
        append("     DX=Math.round((CAM+(W-CAM)/2)-((BASE_CAM+BASE_W)/2)*FIT);\n")
        append("     DY=Math.round((H-BASE_H*FIT)/2);\n")
        append("     window.__DIAL_CANVAS_SIZE={width:W,height:H};\n")
        append("   }\n")
        append("   __fit();\n")
        append("   window.__fit=__fit;\n")
        append("   window.addEventListener('resize',__fit);\n")
        append("   ```\n")
        append("   无左禁区时的换算公式：`FIT=Math.min(W/BASE_W,H/BASE_H);`\n")
        append("   `DX=Math.round((W-BASE_W*FIT)/2); DY=Math.round((H-BASE_H*FIT)/2);`\n")
        append("   明令禁止：拿 `c.width` 当视口当主逻辑；假设 dpr==1；改完 canvas 宽高不重设 transform；\n")
        append("   用 `outerWidth/screen.width` 代替 `innerWidth`。\n")
        append("4. 每帧结构固定：背景按 `W×H` 满视口画（处于 dpr 变换下）；\n")
        append("   内容包在 `save(); translate(DX,DY); scale(FIT,FIT); …; restore();` 里；\n")
        append("   全屏覆盖层（暗化/闪屏）必须逆变换回视口坐标\n")
        append("   `fillRect(-DX/FIT,-DY/FIT,W/FIT,H/FIT)`。\n")
        append("5. 触摸命中：`getBoundingClientRect()`（CSS px）→ `×W/rect.width` 归一 → 减 DX/DY → 除 FIT\n")
        append("   进 BASE 坐标再判矩形。**W/H 是 CSS px，canvas 属性是 ×dpr，两者不可混用。**\n")
        append("6. 内容左边界经变换后不得落入视口 `(0..CAM)` 区域（安全区铁律的变换侧验证）。\n")
        append("\n")
        append("## 三、生命周期契约（runjs 必须永远调得通）\n")
        append("1. rAF 用 pending 哨兵管理，杜绝双链/倍速：\n")
        append("   ```js\n")
        append("   var rafPend=false,paused=false;\n")
        append("   function kick(){if(rafPend)return;rafPend=true;requestAnimationFrame(frame);}\n")
        append("   ```\n")
        append("2. `__setAod(v)`：`v=1` → 置 paused，已排队的最后一帧在 frame 入口 return 后断链\n")
        append("   （不 update、不 draw、不发声）；`v=0` → 清 paused、`lastDraw=-1e9` 后 kick；\n")
        append("   同值重复调用直接 return（init 的 `__setAod(0)` 绝不能造成双链）。\n")
        append("3. 时间只做差不补账：dt 取 `Date.now()` 差值并 **clamp ≤200ms**，息屏恢复无跳变。\n")
        append("4. `window.__DIAL_NO_ANIM=true`（引擎定格帧/预览，单向开关）：只执行\n")
        append("   `setupDemo();draw(DEMO_T);` 一帧，不启动循环；此模式与 AOD 暂停期间一律静音。\n")
        append("5. 提供 `window.__debugSize=function(){return [W,H,devicePixelRatio,c.width,c.height,FIT].join(' ')};`\n")
        append("   供 runjs 验证适配结果。\n")
        append("\n")
        append("## 四、渲染与性能\n")
        append("1. rAF 主循环按 `FRAME_MS=1000/FRAME_RATE` 节流；`document.hidden` 时跳过执行但维持链。\n")
        append("2. 如有连续物理：120Hz 定步长子步（`PHYS_DT=1000/120`，`MAX_SUB=8` 防螺旋，超限丢账）。\n")
        append("3. 渐变按参数 key 缓存复用；光晕在局部单位空间（translate+scale 进去，坐标恒 0..1）。\n")
        append("4. 字号只能整数（font 串缓存），自适应缩字下限 12px；圆角半径 `Number.isFinite` 兜底。\n")
        append("5. 一切动画/粒子位置由帧时间 vt 推导，保证定格帧确定性；不保留调试输出（alert/console.log）。\n")
        append("\n")
        append("## 五、输入\n")
        append("仅 Pointer Events；`dragId` 单指语义 + `setPointerCapture`；pointermove/contextmenu\n")
        append("preventDefault；可点矩形命中外扩 10~15px；首次 pointerdown 是创建 AudioContext 的唯一入口。\n")
        append("\n")
        append("## 六、持久化\n")
        append("localStorage：整体 try/catch；读取后逐字段 `Number.isFinite` + clamp + 类型校验；\n")
        append("写入失败静默；任何存储异常不影响主循环。\n")
        append("\n")
        append("## 七、文案与国际化\n")
        append("- **语言规则（优先级最高）：CONTENT 用什么语言书写，生成的 HTML 所有界面文案就用\n")
        append("  什么语言**——标题、按钮、提示、单位、结算文本、常量区全部跟随；禁止混语，\n")
        append("  禁止擅自换成其他语言；CONTENT 是英文则界面全英文，是中文则全中文。\n")
        append("- 全部界面文字抽到顶部 `T_*` 常量区并注释\"四语言只替换这里\"；\n")
        append("  代码主体禁止散落任何语言的字面量；数字单位（km/h、N、m 等）同样入常量。\n")
        append("\n")
        append("## 八、音频\n")
        append("首次手势惰性创建；同 key 55ms 节流；全程 try/catch 静默降级；\n")
        append("节点 start/stop 明确释放不缓存；`__DIAL_NO_ANIM` / AOD 暂停期间静音。\n")
        append("\n")
        append("## 九、定格帧\n")
        append("提供 `setupDemo()`：构造一个\"进行中、有代表性\"的确定性画面（关键数值齐全），\n")
        append("`__DIAL_NO_ANIM` 下只画一帧——结果不得依赖随机数、真实时间或声音。\n")
        append("\n")
        append("## 十、输出要求\n")
        append("只输出完整 HTML 文件本身，不要解释性前后缀；所有章节规范必须真正体现在代码里，\n")
        append("禁止占位符（如 `...`、`// 同上`）。\n")
        append("\n")
        append("## 十一、交付自检清单（逐条勾选后才可交付）\n")
        append("- [ ] **HTML 头部三行注释齐全，数值均为实算：SAFE_ZONE 枚举每个矩形 x ≥BASE_CAM+20；\n")
        append("      AVAIL 行 OUTER≈可用区、CHILD 双向填充 ≥80%、比例偏差 ≤25%；\n")
        append("      TOUCH 行每个可点矩形 ≥60×60、间距 ≥16**\n")
        append("- [ ] **布局为横向分区（顶条/主区/底条或三列），不是手机竖屏单列窄条；\n")
        append("      渲染后右侧可用画面无大片空白、内容不呈细长条**\n")
        append("- [ ] CSS 中 grep 不到任何 `数字px` 尺寸；viewport meta 存在\n")
        append("- [ ] `__fit`/`__setAod` 暴露到 window；`setTransform` 在 canvas 宽高赋值之后；resize 已监听\n")
        append("- [ ] 目标屏 A：`__debugSize()` 输出与实屏一致且 backing=CSS×dpr；四角不裁不胀\n")
        append("- [ ] 目标屏 B（或 BASE 本身）：FIT=1.0 同样通过\n")
        append("- [ ] dpr=2/3 下边缘清晰，无整体放大感\n")
        append("- [ ] runjs `__fit()`、`__setAod(1)`、`__setAod(0)` 各连调 2 次：无异常、无倍速、无双链\n")
        append("- [ ] 息屏 ≥5s 恢复：无位置/物理跳变\n")
        append("- [ ] 完整走一遍主流程：console 无错误、无网络请求\n")
        append("- [ ] **界面文案语言与 CONTENT 书写语言完全一致，无混语**\n")
        append("- [ ] `__DIAL_NO_ANIM`：单帧确定、静音、禁区框可见\n")
        append("- [ ] 文件头含 cachePage 清缓存提醒\n")
        append("- [ ] 输出内容只需要且只能有HTML\n")
        append("\n")
        append("## 十二、禁止事项\n")
        append("写死分辨率/视口；假设 dpr==1；依赖 980 默认视口；外部资源与第三方库；\n")
        append("setTimeout 驱动主循环；dt 不 clamp 的补帧；把 alert/console.log 留在交付版；\n")
        append("一个 Trigger 对应的 runjs 目标函数缺失（调了不存在的函数即视为缺陷）；\n")
        append("界面文案语言与 CONTENT 语言不一致；\n")
        append("内容矩形 x < BASE_CAM+20；HTML 头注释缺 SAFE_ZONE 枚举；绘制函数里出现坐标字面量；\n")
        append("**内容呈竖长条/细横条（CHILD 双向填充 <80% 或比例偏差 >25%）；OUTER 缺失或未铺满可用区；\n")
        append("可点矩形 <60×60；可点矩形间距 <16；BASE 宽高写反（背屏必须宽>高）；照搬手机竖屏单列布局。**")
    }.trimEnd('\n')
}
