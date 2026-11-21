package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Design 006 T1 (D1, C-003 amended, review 3): the application is compiled for Java 25. Reads the class file the build
 * produced, not the JVM that runs the test, so a Java 25 JDK compiling with release 21 still fails.
 */
class JavaRuntimeTest {

    private static final int MAGIC = 0xCAFEBABE;
    private static final int JAVA_25 = 69;

    @Test
    void theApplicationClassesAreCompiledForJava25() throws Exception {
        Path classes = Path.of(CamelApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path classFile = classes.resolve(CamelApplication.class.getName().replace('.', '/') + ".class");
        try (InputStream in = Files.newInputStream(classFile); DataInputStream data = new DataInputStream(in)) {
            int magic = data.readInt();
            data.readUnsignedShort(); // minor version
            int major = data.readUnsignedShort();
            assertEquals(MAGIC, magic, () -> classFile + " is not a class file");
            assertEquals(JAVA_25, major,
                    () -> "compiled for class file major " + major + ", expected " + JAVA_25 + " (Java 25, C-003)");
        }
    }
}
