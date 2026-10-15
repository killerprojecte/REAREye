package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

/** Numeric XML attributes are evaluated only on the native MAML tick thread. */
fun interface MamlNumericExpressionCompiler {
    fun compileNumber(expression: String): () -> Double?
}
