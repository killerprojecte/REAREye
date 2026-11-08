package hk.uwu.build;

import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Isolates the Maven MaterialKolor 4 binary and its Mochi references from MIUIX's v6. */
public final class MaterialKolorNamespace {
    private static final String OLD = "com/materialkolor/";
    private static final String NEW = "hk/uwu/mixui/shaded/materialkolor4/";
    private static final Remapper NAMES = new Remapper() {
        @Override public String map(String name) {
            return name.startsWith(OLD) ? NEW + name.substring(OLD.length()) : name;
        }
        @Override public Object mapValue(Object value) {
            if (value instanceof String) {
                String s = (String) value;
                // Kotlin Metadata d2/xs/pn and JVM reflection names. Leave the encoded d1
                // protobuf untouched: its type names reference the separate d2 string table.
                if (s.startsWith("com/materialkolor/") || s.startsWith("Lcom/materialkolor/") || s.startsWith("("))
                    return s.replace(OLD, NEW);
                if (s.startsWith("com.materialkolor."))
                    return s.replace("com.materialkolor.", "hk.uwu.mixui.shaded.materialkolor4.");
            }
            return super.mapValue(value);
        }
    };

    private static byte[] relocateClass(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, new ClassRemapper(writer, NAMES)) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals("com/kyant/backdrop/effects/RenderEffectKt") && name.equals("effect") &&
                            descriptor.equals("(Lcom/kyant/backdrop/BackdropEffectScope;Landroid/graphics/RenderEffect;)V")) {
                            // Backdrop 2 replaced only this Android overload with Compose's wrapper.
                            // Preserve the same Android effect via the supported conversion, not a no-op.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "androidx/compose/ui/graphics/AndroidRenderEffect_androidKt", "asComposeRenderEffect",
                                "(Landroid/graphics/RenderEffect;)Landroidx/compose/ui/graphics/RenderEffect;", false);
                            descriptor = "(Lcom/kyant/backdrop/BackdropEffectScope;Landroidx/compose/ui/graphics/RenderEffect;)V";
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] jar(byte[] input, boolean privateLibrary) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(input));
             ZipOutputStream out = new ZipOutputStream(result)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                // Private math classes are called through Mochi bytecode, not imported as
                // Kotlin top-level APIs; do not advertise their old package module index.
                if (privateLibrary && name.endsWith(".kotlin_module")) continue;
                if (name.matches("META-INF/[^/]+\\.(SF|RSA|DSA)")) continue;
                byte[] bytes = zip.readAllBytes();
                if (name.endsWith(".class")) {
                    bytes = relocateClass(bytes);
                    name = NAMES.map(name.substring(0, name.length() - 6)) + ".class";
                }
                ZipEntry target = new ZipEntry(name);
                target.setTime(0L);
                out.putNextEntry(target); out.write(bytes); out.closeEntry();
            }
        }
        return result.toByteArray();
    }

    public static void materialJar(File input, File output) throws IOException {
        byte[] bytes = Files.readAllBytes(input.toPath());
        if (input.getName().endsWith(".aar")) {
            try (ZipFile aar = new ZipFile(input)) {
                ZipEntry classes = aar.getEntry("classes.jar");
                if (classes == null) throw new IOException("MaterialKolor AAR has no classes.jar");
                bytes = aar.getInputStream(classes).readAllBytes();
            }
        }
        Files.createDirectories(output.toPath().getParent());
        Files.write(output.toPath(), jar(bytes, true));
    }

    public static void mochiArtifact(File input, File output) throws IOException {
        if (!input.getName().endsWith(".aar")) {
            Files.write(output.toPath(), jar(Files.readAllBytes(input.toPath()), false));
            return;
        }
        try (ZipFile aar = new ZipFile(input); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            var entries = aar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                byte[] bytes = aar.getInputStream(entry).readAllBytes();
                if (entry.getName().equals("classes.jar") || entry.getName().startsWith("libs/") && entry.getName().endsWith(".jar"))
                    bytes = jar(bytes, false);
                ZipEntry target = new ZipEntry(entry.getName()); target.setTime(0L);
                out.putNextEntry(target); out.write(bytes); out.closeEntry();
            }
        }
    }
    private MaterialKolorNamespace() {}
}
