package hk.uwu.reareye.hook.activityhost

/** Configuration only: no Android, DOM or hook dependencies. */
data class ActivityHostSpec(
    val component: String,
    val dpi: Int = 0,
    val navigationGutterDp: Float = 24f,
    val bounds: String? = null,
) {
    fun validate(): List<String> = buildList {
        if (parseComponent(component) == null) add("component must be an explicit package/class")
        if (!isValidDpi(dpi)) add("dpi must be 0 or in 120..960")
        if (!navigationGutterDp.isFinite() || navigationGutterDp < 0f) {
            add("navigationGutterDp must be finite and nonnegative")
        }
        if (bounds != null) {
            try {
                parseBounds(bounds)
            } catch (_: IllegalArgumentException) {
                add("bounds must contain four finite ordered numbers")
            }
        }
    }

    companion object {
        private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        fun isValidDpi(dpi: Int): Boolean = dpi == 0 || dpi in 120..960

        fun parseComponent(value: String): ActivityHostComponent? {
            if (value != value.trim()) return null
            val parts = value.split('/')
            if (parts.size != 2) return null
            val packageName = parts[0]
            val className = if (parts[1].startsWith('.')) packageName + parts[1] else parts[1]
            if (!isQualifiedName(packageName) || !isQualifiedName(className)) return null
            return ActivityHostComponent(packageName, className)
        }

        private fun isQualifiedName(value: String): Boolean =
            value.isNotEmpty() && value.split('.').all { identifier.matches(it) }

        /** Numeric comma-separated bounds; expression evaluation belongs to the caller. */
        fun parseBounds(value: String): List<Float> {
            val parts = value.split(',')
            require(parts.size == 4) { "bounds requires left,top,right,bottom" }
            val numbers = parts.map {
                requireNotNull(it.trim().toFloatOrNull()) { "bounds must be numeric" }
            }
            require(numbers.all { it.isFinite() }) { "bounds must be finite" }
            require(
                numbers[0] >= 0f && numbers[1] >= 0f &&
                        numbers[2] > numbers[0] && numbers[3] > numbers[1]
            ) {
                "bounds must be nonnegative and ordered"
            }
            return numbers
        }
    }
}

data class ActivityHostComponent(val packageName: String, val className: String)
