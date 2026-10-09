# LuaJava's native bindings resolve Java classes and callbacks by their original
# names (including JuaAPI in LuaJitNatives.initBindings). Keep the binding API even
# when its only caller is JNI, which R8 cannot see.
-keep class party.iroiro.luajava.** { *; }
