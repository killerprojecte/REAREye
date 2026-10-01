# REAREye Scripting API

本文档面向 REAREye 脚本、组件和 Provider 开发者。当前脚本语言为 LuaJIT，脚本入口返回一个描述表，用于注册
Provider 和配置项。

## 最小脚本

`lua
local function hello(params, config, context)
return "Hello " .. tostring(params.name or "REAREye")
end

return {
id = "hello_script",
name = "Hello Script",
description = "A small example provider",
group = "examples",
providers = { hello = hello },
}
`

Provider 处理函数签名：

`lua
function(params, config, context) -> any
`

- params：调用方传入的参数表。
- config：根据 configs 声明并归一化后的配置表。
- context：REAREye 注入的运行时工具对象。
- 返回值：字符串、数字、布尔值、数组或键为字符串的表。

## 脚本描述表

入口必须返回一个 Lua 表。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | string | 否 | 默认使用主文件名；只允许字母、数字、点、下划线和连字符。 |
| name | string | 否 | 显示名称，默认使用项目名称。 |
| description | string | 否 | 脚本说明。 |
| group | string | 否 | 分组名称。 |
| providers | table | 是 | Provider 映射。 |
| configs | array | 否 | 配置项声明列表。 |

## Provider

Provider 可以直接写成函数：

`lua
return {
    providers = {
        uptime = function(_, _, context)
            return { now = context:now() }
        end,
    },
}
`

也可以使用完整定义：

`lua
return {
    providers = {
        weather = {
            name = "Weather",
            description = "Fetch weather data",
            mimeType = "application/json",
            mode = "REFRESH",
            ttlMillis = 300000,
            handler = function(params, config, context)
                return { city = params.city or config.city }
            end,
        },
    },
}
`

完整字段：

- handler：必填，处理函数。
- name：显示名称，默认使用 Provider ID。
- description：显示说明。
- mimeType：结果 MIME 类型，默认 text/plain。
- mode：IMMEDIATE、CACHE_FIRST、CACHE_ONLY 或 REFRESH。
- ttlMillis：缓存时长，范围 0 到 86400000 毫秒。

## 配置项

配置项写在 configs 数组中：

`lua
return {
    configs = {
        { id = "city", type = "STRING", default = "Shanghai", note = "查询城市" },
        { id = "enabled", type = "BOOLEAN", default = true },
        { id = "count", type = "NUMBER", default = 3, min = 1, max = 10, step = 1 },
        {
            id = "unit",
            type = "OPTIONS",
            default = "celsius",
            options = {
                { id = "celsius", name = "摄氏度" },
                { id = "fahrenheit", name = "华氏度" },
            },
        },
    },
}
`

支持的配置类型：

- STRING：转为字符串，缺省为空字符串。
- NUMBER：按 min、max、step 归一化，缺省为 0。
- BOOLEAN：只有 true 或字符串 true 视为真。
- OPTIONS：值必须存在于 options，否则使用第一个选项。

## context 通用方法

### context:now()

返回宿主当前时间戳，单位为毫秒。

### context:log(message)

写入脚本调试日志，也可以使用全局 print。单条日志最多保留 4096 个字符，每次执行最多保留 200 条日志。

`lua
context:log("request started")
print("value", params.value)
`

### context:readText(path)

读取当前项目根目录内的文本文件。路径必须是相对路径，不能使用绝对路径、点、点点、反斜杠或盘符。

`lua
local template = context:readText("assets/template.txt")
`

独立 Lua 脚本不能读取项目文件；项目脚本和组件脚本可以使用该方法。

## Root 命令

### context.root:exec(command, options)

通过 su -c 执行 Root 命令，并返回标准输出、标准错误和退出状态。

`lua
local result = context.root:exec("id")
if result.exitCode ~= 0 then
    error(result.stderr)
end
return {
    stdout = result.stdout,
    stderr = result.stderr,
    exitCode = result.exitCode,
}
`

可选参数：

`lua
local result = context.root:exec("cat", {
    stdin = "hello from script\\n",
    timeoutMillis = 5000,
})
`

返回字段：

- exitCode：命令退出码，成功通常为 0。
- stdout：标准输出字符串。
- stderr：标准错误字符串。
- timedOut：是否因为超时被终止。
- outputLimitExceeded：输出是否超过宿主限制。

Root 能力依赖用户在 Root 管理器中授予 REAREye 权限。默认超时为 10 秒，可设置范围为 100 到 120000
毫秒。标准输出和标准错误分别有大小上限。

## HTTP 客户端

LuaJIT 本身不带 HTTP 客户端。REAREye 在 Android 宿主中使用 OkHttp 提供
context.http，支持请求方法、查询参数、请求头、请求体、超时、重定向和完整响应信息。

### context.http:request(request)

`lua
local response = context.http:request({
url = "https://api.example.com/data",
method = "GET",
query = {
city = "Shanghai",
lang = "zh-CN",
},
headers = {
["Accept"] = "application/json",
["User-Agent"] = "REAREye-Script/1.0",
},
timeoutMillis = 15000,
followRedirects = true,
followSslRedirects = true,
})

if not response.ok then
error("HTTP " .. tostring(response.statusCode) .. ": " .. response.body)
end
return response.body
`

请求字段：

- url：必填，HTTP 或 HTTPS URL。
- method：默认 GET，支持 GET、HEAD、POST、PUT、PATCH、DELETE。
- query：字符串键值表，自动进行 URL 查询参数编码。
- headers：字符串键值表，同名请求头会被覆盖。
- body：字符串请求体。
- timeoutMillis：连接、读、写和整个请求的超时，默认 15 秒，范围 100 到 120000 毫秒。
- followRedirects：是否跟随普通重定向，默认 true。
- followSslRedirects：是否跟随 HTTP/HTTPS 重定向，默认 true。

响应字段：

- statusCode：HTTP 状态码。
- ok：状态码为 2xx 时为 true。
- headers：响应头表，同名多值会合并为逗号分隔字符串。
- body：响应正文字符串。
- contentType：响应的 Content-Type。
- url：最终响应 URL，包含重定向后的地址。

### 快捷方法

`lua
local a = context.http:get(url, options)
local b = context.http:post(url, body, options)
local c = context.http:put(url, body, options)
local d = context.http:patch(url, body, options)
local e = context.http:delete(url, options)
`

快捷方法的 options 与 request 相同，可以包含 query、headers、timeoutMillis 和重定向设置。

发送 JSON 时手动设置请求头和请求体：

`lua
local response = context.http:post(
    "https://api.example.com/items",
    '{"name":"REAREye"}',
    {
        headers = {
            ["Content-Type"] = "application/json",
            ["Accept"] = "application/json",
        },
    }
)
`

HTTP 客户端返回正文，不自动解析 JSON。需要解析 JSON 时，建议在项目中加入 Lua 模块，或让调用方根据
mimeType 解析结果。

## 项目文件与 require

项目脚本可以加载项目根目录下的 Lua 模块：

`lua
local json = require("lib.json")
local helper = require("lib.helper")
`

require("a.b") 会依次查找 a/b.lua 和 a/b/init.lua。模块名只允许 Lua 标识符和点号，不能包含绝对路径或路径穿越内容。

## 外部调用

应用内调试页会直接执行 Provider。外部组件也可以通过 ContentProvider 查询：

`text
content://hk.uwu.reareye.script/{projectId}/{providerId}?name=REAREye
`

也可以使用查询参数指定项目和 Provider：

`text
content://hk.uwu.reareye.script/run?project=my_project&provider=hello&name=REAREye
`

返回列：project_id、provider_id、mime_type、data、error、elapsed_millis。

## 运行限制

- 默认脚本执行超时为 5 秒；运行时本身最多接受 30 秒。
- 单个项目文件最大 2 MiB。
- 返回值最大嵌套深度为 24，最多 10000 个元素。
- 返回字符串最大 262144 个字符。
- Lua 字节码不允许加载，只接受 UTF-8 文本源码。
- io、package、debug、ffi、jit、java、luajava 默认关闭。
- os 仅保留 time、date、difftime 和 clock。
- Root 和网络能力由 Android 宿主注入；纯 rear-script-runtime 测试环境没有真实 Root 或网络。

## 错误处理

`lua
local ok, result = pcall(function()
return context.http:get("https://example.com")
end)

if not ok then
return { ok = false, error = tostring(result) }
end
return { ok = result.ok, status = result.statusCode }
`

Root 命令失败不会自动把 stderr 当作异常，请检查 exitCode、timedOut 和 stderr。HTTP 网络错误会抛出 Lua
错误；HTTP 4xx/5xx 仍然返回响应表，需要脚本检查 response.ok。

## 兼容建议

脚本应优先使用本文档中的 context API，不要依赖 Android 内部类、LuaJIT 私有实现或宿主 Java
反射。这样脚本可以在调试页、组件 Provider 和外部 ContentProvider 调用中保持一致。
