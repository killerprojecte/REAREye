package hk.uwu.build;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;
import org.objectweb.asm.*;
import static org.junit.Assert.*;

public class MaterialKolorNamespaceTest {
    @Test public void relocatesCallableSignaturesArrayDescriptorsAndClassReferences() throws Exception {
        String old = "com/materialkolor/";
        String isolated = "hk/uwu/mixui/shaded/materialkolor4/";
        List<String> strings = List.of(
            "highestSurface(L" + old + "scheme/DynamicScheme;)L" + old + "dynamiccolor/DynamicColor;",
            "primary()L" + old + "dynamiccolor/DynamicColor;",
            "[L" + old + "quantize/QuantizerWu$Box;",
            "L" + old + "scheme/DynamicScheme;",
            "com.materialkolor.scheme.DynamicScheme",
            "unrelated user text"
        );
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, old + "Fixture", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "references", "()V", null, null);
        method.visitCode();
        for (String value : strings) { method.visitLdcInsn(value); method.visitInsn(Opcodes.POP); }
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd();
        File dir = Files.createTempDirectory("materialkolor-namespace-test").toFile();
        File input = new File(dir, "input.jar"), output = new File(dir, "output.jar");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(input))) {
                zip.putNextEntry(new ZipEntry(old + "Fixture.class")); zip.write(writer.toByteArray()); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("META-INF/NOTICE.txt")); zip.write("license notice".getBytes()); zip.closeEntry();
            }
            MaterialKolorNamespace.materialJar(input, output);
            try (ZipFile zip = new ZipFile(output)) {
                assertNull(zip.getEntry(old + "Fixture.class"));
                assertEquals("license notice", new String(zip.getInputStream(zip.getEntry("META-INF/NOTICE.txt")).readAllBytes()));
                List<String> actual = new ArrayList<>();
                new ClassReader(zip.getInputStream(zip.getEntry(isolated + "Fixture.class")).readAllBytes())
                    .accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitLdcInsn(Object value) { if (value instanceof String) actual.add((String)value); }
                            };
                        }
                    }, 0);
                assertEquals(strings.stream().map(s -> s.replace(old, isolated).replace("com.materialkolor.", "hk.uwu.mixui.shaded.materialkolor4.")).toList(), actual);
            }
        } finally { input.delete(); output.delete(); dir.delete(); }
    }
}
