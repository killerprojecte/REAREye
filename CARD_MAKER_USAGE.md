# 卡片制作器配合 REAREye 使用说明

[REAREye 卡片制作器](https://guocheng1378.github.io/rear-eye-card-maker/) 是一个在线工具，可以为小米背屏制作 MAML 卡片模板。通过 REAREye 模块，可以将制作好的卡片导入到背屏上显示。

## 前提条件

- 小米 17 Pro / Pro Max（带背屏）
- 已安装 LSPosed 并启用 REAREye 模块
- REAREye 作用域包含 `com.xiaomi.subscreencenter`

## 流程

### 1. 制作并导出卡片

1. 打开 [卡片制作器](https://guocheng1378.github.io/rear-eye-card-maker/)
2. 选择模板（时钟、天气、音乐等 17 个预设），或从「自定义」开始
3. 编辑元素、调整样式、选择字体
4. 点击 **📦 导出 ZIP** 下载 `{卡片名}.zip`

ZIP 包结构：
```
卡片名.zip
├── manifest.xml       ← MAML 模板（核心文件）
├── var_config.xml     ← 变量配置（非自定义模板才有）
├── images/            ← 图片资源
└── videos/            ← 视频资源
```

> 也可以点「📋 复制 XML」直接复制 MAML 代码手动粘贴。

### 2. 在 REAREye 中导入模板

1. 打开 REAREye 模块应用
2. 进入 **组件模板管理器**
3. 点击「导入模板」，选择 ZIP 文件或粘贴 XML
4. 为模板指定一个 **business 名称**（例如 `my_clock`、`my_weather`）
   - business 是模板的唯一标识符

模板会部署到：
```
/data/system/theme_magic/users/{用户ID}/subscreencenter/smart_assistant/{business}/
```

### 3. 绑定卡片到背屏

1. 进入 REAREye **卡片管理器**
2. 添加新卡片：
   - **标题**：自定义名称
   - **目标包名**：`com.xiaomi.subscreencenter`（默认）
   - **business**：与第二步设置的名称一致
   - **优先级**：数字越大越靠前，默认 500
   - **常驻**：勾选后卡片一直显示
3. 保存

### 4. 生效

- 修改配置后，重启 `com.xiaomi.subscreencenter` 或重启系统

## 模板类型说明

| 模板类型 | 浏览器预览 | 背屏真机 |
|---------|-----------|---------|
| 普通模板（时钟、名言等） | 可正常预览 | 正常显示 |
| 真实设备模板（天气/音乐） | 显示占位数据 | 绑定系统数据，显示真实内容 |

使用天气/音乐真实模板时，需确保 REAREye 相关 hook 功能已启用。

## 设备分辨率

| 设备 | 分辨率 | 摄像头区域 |
|------|--------|-----------|
| Pro Max | 976×596 | 30% |
| Pro | 904×572 | 30% |
| 标准版 | 840×520 | 28% |
| Ultra | 1020×620 | 32% |

## 常见问题

**Q：导入后背屏没显示？**
- 检查 business 名称是否一致
- 重启背屏中心或重启系统
- 确认 REAREye 已启用且作用域包含 `com.xiaomi.subscreencenter`

**Q：修改已导入的模板？**
- 重新编辑 → 导出 → 用同一 business 名称重新导入覆盖

**Q：多个卡片？**
- 每个模板用不同 business 名称导入，分别绑定即可

## 相关链接

- 卡片制作器：<https://guocheng1378.github.io/rear-eye-card-maker/>
- 卡片制作器仓库：<https://github.com/guocheng1378/rear-eye-card-maker>
