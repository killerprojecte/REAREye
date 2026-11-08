package hk.uwu.build;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class NothingFontCompatibilityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static Map<String, byte[]> fixture() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("AndroidManifest.xml", bytes("manifest fixture"));
        files.put("classes.jar", bytes("unchanged upstream classes"));
        files.put("R.txt", bytes("int font nothing_display 0x0\nint font nothing_dot 0x0\nint font nothing_inter 0x0\n"));
        files.put("res/font/nothing_display.otf", bytes("NType fixture to remove"));
        files.put("res/font/nothing_dot.otf", bytes("Ndot fixture to remove"));
        files.put("res/font/nothing_inter.ttf", bytes("original OFL Inter fixture"));
        files.put("assets/licenses/Inter-OFL.txt", bytes("original Inter license fixture"));
        files.put("assets/nothing/ASSET_NOTICES.md", bytes("original asset notice fixture"));
        files.put("assets/nothing/font-provenance.json", bytes("original provenance fixture"));
        files.put("META-INF/com/android/build/gradle/aar-metadata.properties", bytes("unchanged metadata"));
        return files;
    }

    @Test public void replacesOnlyFirmwareFontBytesAndPreservesCodeSymbolsAndNotices() throws Exception {
        Map<String, byte[]> original = fixture();
        File input = archive(NothingFontCompatibility.ARTIFACT_NAME, original);
        File output = new File(temporary.getRoot(), "adapted.aar");
        NothingFontCompatibility.sanitizeArtifact(input, output);
        Map<String, byte[]> actual = contents(output);
        assertFalse(actual.containsKey("res/font/nothing_display.otf"));
        assertFalse(actual.containsKey("res/font/nothing_dot.otf"));
        for (var entry : original.entrySet()) {
            if (entry.getKey().equals("res/font/nothing_display.otf") ||
                entry.getKey().equals("res/font/nothing_dot.otf")) continue;
            assertArrayEquals(entry.getKey(), entry.getValue(), actual.get(entry.getKey()));
        }
        assertEquals(original.size() + 1, actual.size());
        assertEquals(NothingFontCompatibility.NOTICE,
            new String(actual.get(NothingFontCompatibility.NOTICE_PATH), StandardCharsets.UTF_8));
        assertTrue(NothingFontCompatibility.NOTICE.contains("not the original dot font"));
        assertTrue(NothingFontCompatibility.NOTICE.contains("Default Nothing appearance is unchanged"));
        // Also prove the original Maven cache artifact was never modified.
        Map<String, byte[]> preservedInput = contents(input);
        for (var entry : original.entrySet()) {
            assertArrayEquals(entry.getKey(), entry.getValue(), preservedInput.get(entry.getKey()));
        }
    }

    @Test public void aliasesAreAndroidFontFamiliesPointingToThePreservedInterResource() throws Exception {
        File input = archive(NothingFontCompatibility.ARTIFACT_NAME, fixture());
        File output = new File(temporary.getRoot(), "adapted.aar");
        NothingFontCompatibility.sanitizeArtifact(input, output);
        Map<String, byte[]> actual = contents(output);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        for (String name : new String[] {"nothing_display", "nothing_dot"}) {
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(actual.get("res/font/" + name + ".xml")));
            var root = document.getDocumentElement();
            assertEquals("font-family", root.getTagName());
            var fonts = root.getElementsByTagName("font");
            assertEquals(1, fonts.getLength());
            assertEquals("@font/nothing_inter", fonts.item(0).getAttributes()
                .getNamedItemNS("http://schemas.android.com/apk/res/android", "font").getNodeValue());
        }
        assertNotNull(actual.get("res/font/nothing_inter.ttf"));
        assertArrayEquals(fixture().get("R.txt"), actual.get("R.txt"));
    }

    @Test public void archiveOutputIsDeterministic() throws Exception {
        File input = archive(NothingFontCompatibility.ARTIFACT_NAME, fixture());
        File first = new File(temporary.getRoot(), "first.aar");
        File second = new File(temporary.getRoot(), "second.aar");
        NothingFontCompatibility.sanitizeArtifact(input, first);
        NothingFontCompatibility.sanitizeArtifact(input, second);
        assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()));
    }

    @Test public void refusesOtherArtifactVersionsAndInPlaceRewrites() throws Exception {
        File other = archive("mochi-nothing-0.2.1.aar", fixture());
        File output = new File(temporary.getRoot(), "adapted.aar");
        assertThrows(IOException.class, () -> NothingFontCompatibility.sanitizeArtifact(other, output));
        assertFalse(output.exists());
        File input = archive(NothingFontCompatibility.ARTIFACT_NAME, fixture());
        byte[] original = Files.readAllBytes(input.toPath());
        assertThrows(IOException.class, () -> NothingFontCompatibility.sanitizeArtifact(input, input));
        assertArrayEquals(original, Files.readAllBytes(input.toPath()));
    }

    @Test public void failsClosedWhenExpectedFontsNoticesOrSymbolsChange() throws Exception {
        for (String missing : new String[] {"res/font/nothing_dot.otf", "res/font/nothing_display.otf",
            "res/font/nothing_inter.ttf", "assets/licenses/Inter-OFL.txt", "assets/nothing/ASSET_NOTICES.md",
            "assets/nothing/font-provenance.json"}) {
            Map<String, byte[]> files = fixture(); files.remove(missing);
            File input = archive(NothingFontCompatibility.ARTIFACT_NAME, files);
            File output = new File(temporary.getRoot(), "adapted.aar");
            assertThrows(IOException.class, () -> NothingFontCompatibility.sanitizeArtifact(input, output));
            assertFalse(output.exists());
        }
        Map<String, byte[]> files = fixture(); files.put("R.txt", bytes("int font nothing_inter 0x0\n"));
        File input = archive(NothingFontCompatibility.ARTIFACT_NAME, files);
        File output = new File(temporary.getRoot(), "adapted.aar");
        assertThrows(IOException.class, () -> NothingFontCompatibility.sanitizeArtifact(input, output));
        assertFalse(output.exists());
    }

    private File archive(String name, Map<String, byte[]> files) throws IOException {
        File file = new File(temporary.getRoot(), name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, byte[]> contents(File file) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) files.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        return files;
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
