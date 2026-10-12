package hk.uwu.reareye.agent.htmlwidget

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles the widget package that REAREye registers as a component.
 *
 * A package is a plain zip holding exactly two things:
 *
 * ```
 * manifest.xml          <- constant, never generated
 * web/index.html        <- the model's output
 * ```
 *
 * Because the manifest is fixed, "generate a component" reduces to "generate one HTML file", and
 * the engine-side MAML/template format never has to be produced by a model. That is the whole reason
 * this path is tractable.
 */
object HtmlWidgetPackage {

    /** Fixed engine manifest. Only `index.html` varies between widgets. */
    val MANIFEST_XML: String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<Widget version=\"2\"\n")
        append("         frameRate=\"${WidgetSpec.FRAME_RATE}\"\n")
        append("         screenWidth=\"904\"\n")
        append("         scaleByDensity=\"false\"\n")
        append("         transparentSurface=\"false\">\n")
        append("\n")
        append("  <WebView\n")
        append("      name=\"wv\"\n")
        append("      x=\"0\" y=\"0\"\n")
        append("      w=\"#view_width\"\n")
        append("      h=\"#view_height\"\n")
        append("      local=\"true\"\n")
        append("      cachePage=\"true\"\n")
        append("      uri=\"web/index.html\" />\n")
        append("\n")
        append("  <ExternalCommands>\n")
        append("    <Trigger action=\"init\">\n")
        append("      <WebViewCommand target=\"wv\"\n")
        append("                      command=\"runjs\"\n")
        append("                      params=\"'__fit()'\"\n")
        append("                      delay=\"2000\"/>\n")
        append("    </Trigger>\n")
        append("  </ExternalCommands>\n")
        append("\n")
        append("</Widget>\n")
    }

    /** Entry name of the generated page inside the zip. Must match `uri` in the manifest. */
    private const val PAGE_ENTRY = "web/index.html"
    private const val MANIFEST_ENTRY = "manifest.xml"

    /**
     * Writes [html] and the fixed manifest into a new zip at [target], creating parent directories.
     *
     * @return the file written.
     */
    fun writeZip(target: File, html: String): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            // Timestamps are fixed so two identical widgets produce identical bytes, which makes the
            // output reproducible and diffable.
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = 0L })
            zip.write(MANIFEST_XML.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(PAGE_ENTRY).apply { time = 0L })
            zip.write(html.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return target
    }

    /**
     * Turns a display title into a component business identifier.
     *
     * Only ASCII letters, digits and underscores survive, because the id is embedded in file names
     * and in the app-card id. A purely non-ASCII title (the common case for Chinese requests) leaves
     * nothing behind, so the caller supplies a fallback.
     */
    fun deriveBusiness(title: String, fallback: String = "widget"): String {
        val ascii = title.lowercase()
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }
            .joinToString("")
            .trim('_')
            .replace(Regex("_+"), "_")
            .take(32)
        return ascii.ifBlank { fallback }
    }

    /**
     * Picks a business id that is not already taken.
     *
     * Never reuses or overwrites an existing one: a collision would otherwise point two components
     * at the same name, and every card referencing it would silently change meaning.
     */
    fun uniqueBusiness(desired: String, taken: Set<String>): String {
        if (desired !in taken) return desired
        var index = 2
        while ("${desired}_$index" in taken) index++
        return "${desired}_$index"
    }
}
