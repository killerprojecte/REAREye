package hk.uwu.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Narrow resource-only adapter for the published Nothing appearance, not vendored library code. */
public final class NothingFontCompatibility {
    public static final String ARTIFACT_NAME = "mochi-nothing-0.2.0.aar";
    static final String NOTICE_PATH = "assets/nothing/REAREYE_COMPATIBILITY.txt";
    private static final List<String> FIRMWARE_FONTS = List.of("nothing_display", "nothing_dot");
    private static final byte[] INTER_ALIAS = ("""
        <?xml version="1.0" encoding="utf-8"?>
        <font-family xmlns:android="http://schemas.android.com/apk/res/android">
            <font android:font="@font/nothing_inter" />
        </font-family>
        """).getBytes(StandardCharsets.UTF_8);
    static final String NOTICE = """
        REAREye compatibility adaptation of Maven mochi-nothing 0.2.0

        The upstream ASSET_NOTICES.md says redistribution permission has not been
        established for its firmware-sourced NType82 and Ndot57 fonts. REAREye does
        not distribute those font files. The nothing_display and nothing_dot
        resource names are retained as XML font families referencing the original
        SIL Open Font License Inter font (nothing_inter.ttf) already in this AAR.

        Default Nothing appearance is unchanged: Inter body text and platform
        system-serif display/headline/large/nav titles. Optional Nothing dot or
        display font requests fall back to Inter; this is not the original dot font.

        All upstream code, R symbols, the Inter font, its Inter-OFL.txt license,
        ASSET_NOTICES.md and font-provenance.json are retained unchanged. Original
        provenance lists describe the upstream artifact, not fonts in this adapted
        package. This notice describes the resource-only build-time adaptation.
        """;

    public static void sanitizeArtifact(File input, File output) throws IOException {
        if (!ARTIFACT_NAME.equals(input.getName())) {
            throw new IOException("Nothing font compatibility is scoped to " + ARTIFACT_NAME);
        }
        if (input.getCanonicalFile().equals(output.getCanonicalFile())) {
            throw new IOException("Do not rewrite the original Maven artifact in place");
        }
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipFile aar = new ZipFile(input)) {
            var iterator = aar.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory()) continue;
                if (entries.putIfAbsent(entry.getName(), aar.getInputStream(entry).readAllBytes()) != null) {
                    throw new IOException("Duplicate AAR entry: " + entry.getName());
                }
            }
        }
        for (String required : List.of(
            "AndroidManifest.xml", "classes.jar", "R.txt", "res/font/nothing_inter.ttf",
            "assets/licenses/Inter-OFL.txt", "assets/nothing/ASSET_NOTICES.md",
            "assets/nothing/font-provenance.json"
        )) {
            requireEntry(entries, required);
        }
        String symbols = new String(entries.get("R.txt"), StandardCharsets.UTF_8);
        for (String name : List.of("nothing_display", "nothing_dot", "nothing_inter")) {
            if (!symbols.matches("(?s).*\\bint font " + name + " 0x[0-9a-fA-F]+(?:\\s|$).*")) {
                throw new IOException("Missing expected Nothing font R symbol: " + name);
            }
        }
        for (String name : FIRMWARE_FONTS) {
            requireEntry(entries, "res/font/" + name + ".otf");
            if (entries.containsKey("res/font/" + name + ".xml")) {
                throw new IOException("Unexpected pre-existing font alias: " + name);
            }
            entries.remove("res/font/" + name + ".otf");
            entries.put("res/font/" + name + ".xml", INTER_ALIAS);
        }
        if (entries.putIfAbsent(NOTICE_PATH, NOTICE.getBytes(StandardCharsets.UTF_8)) != null) {
            throw new IOException("Unexpected pre-existing REAREye compatibility notice");
        }
        Files.createDirectories(output.toPath().toAbsolutePath().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output.toPath()))) {
            for (var entry : entries.entrySet()) {
                ZipEntry target = new ZipEntry(entry.getKey());
                target.setTime(0L);
                zip.putNextEntry(target);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static void requireEntry(Map<String, byte[]> entries, String name) throws IOException {
        if (!entries.containsKey(name) || entries.get(name).length == 0) {
            throw new IOException("Missing expected Maven Nothing entry: " + name);
        }
    }

    private NothingFontCompatibility() {}
}
