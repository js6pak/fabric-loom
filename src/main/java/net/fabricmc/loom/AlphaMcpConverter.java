package net.fabricmc.loom;

import net.fabricmc.loom.providers.mappings.TinyWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AlphaMcpConverter {

    public static void convert(File mcJar, Path mcpZip, Path tinyFile) throws IOException {
        Map<String, String> fieldDescs = new HashMap<>();
        try (JarFile mcJarFile = new JarFile(mcJar)) {
            Enumeration<JarEntry> entries = mcJarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    String className = entry.getName().substring(0, entry.getName().length() - 6);
                    ClassReader reader = new ClassReader(mcJarFile.getInputStream(entry));
                    reader.accept(new ClassVisitor(Opcodes.ASM7) {
                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                            fieldDescs.put(className + "/" + name, descriptor);
                            return super.visitField(access, name, descriptor, signature, value);
                        }
                    }, ClassReader.SKIP_CODE);
                }
            }
        }

        class MemberMapping {
            String notch;
            String desc;
            String srg;
            String named;

            public MemberMapping(String notch, String desc, String srg, String named) {
                this.notch = notch;
                this.desc = desc;
                this.srg = srg;
                this.named = named;
            }
        }

        class ClassMapping {
            String notch;
            String mcp;
            List<MemberMapping> fields = new ArrayList<>();
            List<MemberMapping> methods = new ArrayList<>();

            public ClassMapping(String notch, String mcp) {
                this.notch = notch;
                this.mcp = mcp;
            }
        }

        Map<String, ClassMapping> mappings = new HashMap<>();


        try (ZipFile mcpZipFile = new ZipFile(mcpZip.toFile())) {
            ZipEntry fieldsCsv = mcpZipFile.getEntry("conf/fields.csv");
            Map<String, String> fieldSrgToNamed = new HashMap<>();
            if (fieldsCsv != null) {
                new BufferedReader(new InputStreamReader(mcpZipFile.getInputStream(fieldsCsv)))
                        .lines()
                        .skip(3)
                        .map(line -> line.split(","))
                        .filter(line -> line.length > 6)
                        .forEach(line -> fieldSrgToNamed.put(line[2], line[6]));
            }
            ZipEntry methodsCsv = mcpZipFile.getEntry("conf/methods.csv");
            Map<String, String> methodsSrgToNamed = new HashMap<>();
            if (methodsCsv != null) {
                new BufferedReader(new InputStreamReader(mcpZipFile.getInputStream(methodsCsv)))
                        .lines()
                        .skip(4)
                        .map(line -> line.split(","))
                        .filter(line -> line.length > 4)
                        .forEach(line -> methodsSrgToNamed.put(line[1], line[4]));
            }
            new BufferedReader(new InputStreamReader(mcpZipFile.getInputStream(mcpZipFile.getEntry("conf/minecraft.rgs"))))
                    .lines()
                    .filter(line -> line.startsWith(".class_map") || line.startsWith(".field_map") || line.startsWith("method_map"))
                    .map(line -> line.split(" "))
                    .forEach(line -> {
                        if (".class_map".equals(line[0])) {
                            String className = line[2].contains("/") || line[2].contains(".") ? line[2] : "net/minecraft/src/" + line[2];
                            mappings.put(line[1], new ClassMapping(line[1], className));
                        } else if (".field_map".equals(line[0])) {
                            String key = line[1] + "/" + line[2];
                            String desc = fieldDescs.get(key);
                            if (desc != null) {
                                MemberMapping field = new MemberMapping(line[2], desc, line[3], fieldSrgToNamed.getOrDefault(line[3], line[3]));
                                mappings.computeIfAbsent(line[1], k -> new ClassMapping(k, k)).fields.add(field);
                            }
                        } else {
                            MemberMapping method = new MemberMapping(line[2], line[3], line[4], methodsSrgToNamed.getOrDefault(line[4], line[4]));
                            mappings.computeIfAbsent(line[1], k -> new ClassMapping(k, k)).methods.add(method);
                        }
                    });
        }

        try (TinyWriter writer = new TinyWriter(tinyFile, true, "official", "intermediary", "named")) {
            for (ClassMapping mapping : mappings.values()) {
                writer.acceptClass(mapping.notch, mapping.mcp, mapping.mcp);
                for (MemberMapping field : mapping.fields) {
                    writer.acceptField(mapping.notch, field.desc, field.notch, field.srg, field.named);
                }
                for (MemberMapping method : mapping.methods) {
                    writer.acceptMethod(mapping.notch, method.desc, method.notch, method.srg, method.named);
                }
            }
            writer.flush();
        }

    }

}
