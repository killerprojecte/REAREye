# REAREye Lua 脚本

脚本入口统一是 main.lua（单文件脚本例外：入口可以是任意一个 .lua 文件）。入口返回一个表，函数可以在
return 外定义，再作为 provider 的 handler 传入：

~~~lua
local function hello(params, config, context)
    context:log("hello")
    return { message = "Hello " .. tostring(params.name or "REAREye") }
end

return {
    id = "hello",
    name = "Hello",
    providers = {
        hello = { handler = hello, mimeType = "application/json" }
    }
}
~~~

Provider handler 的参数依次是 params、规范化后的 config 和 context。context 提供 now()、log(text)
；项目脚本还可以调用 context:readText("lua/helper.lua")，或使用 require("lua.helper") 加载项目目录内的
Lua 文件。路径不能离开项目根目录。单文件脚本不能读取文件，也不能使用 require。

脚本项目存放在应用私有目录 files/script-projects：standalone 保存单文件脚本，projects 保存可包含多个
Lua 文件的项目，components 保存组件释放的独立项目。组件重新安装会先清空同一组件的脚本目录再完整释放；组件卸载会删除该目录，组件包本身不会被编辑器修改。

编辑器使用 Sora Editor 的 Monarch Lua 语言包，提供语法高亮、增量标识符补全和 REAREye API
关键词。运行时位于独立的 :rear-script-runtime Android library，当前宿主通过 ScriptExecutor
调用，后续可以直接替换为进程或 IPC 宿主。
