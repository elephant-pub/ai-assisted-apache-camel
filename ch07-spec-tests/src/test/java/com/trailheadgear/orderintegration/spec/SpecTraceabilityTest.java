package com.trailheadgear.orderintegration.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.trailheadgear.orderintegration.spec.TestCaseDocument.UseCaseDocument;
import com.trailheadgear.orderintegration.spec.support.SpecRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Static traceability check (design 004 §3.9, rules 1 to 5). A surefire unit test: no Docker, no network, nothing is
 * started. The IT classes are discovered by scanning the compiled test class directory, their annotation, display name
 * and static {@code ITEMS} table are read by reflection.
 *
 * <p>Loading an IT class initialises it when {@code ITEMS} is read. Their static initialisers only build constants,
 * a logger and the immutable {@code ITEMS} list (no SpecEnvironment, no container), so this starts nothing.
 */
class SpecTraceabilityTest {

    private static final String APPROVED = "Approved";
    private static final String PACKAGE = SpecTraceabilityTest.class.getPackageName();

    /** What the check reads of one {@code *IT} class; nullable fields mean "absent". */
    public record ItClass(String className, String specTestCaseId, String displayName, int testMethods,
            List<SpecRun.Item> items) {
    }

    // ---------------------------------------------------------------- the real documents and classes

    @Test
    @DisplayName("Every Approved test case has exactly one IT class and the declarations match the documents")
    void realDocumentsAndClassesAreTraceable() {
        List<String> violations = violations(TestCaseDocument.loadAll(), UseCaseDocument.loadUseCases(), discover());
        assertTrue(violations.isEmpty(), () -> "Traceability violations:\n - " + String.join("\n - ", violations));
    }

    @Test
    @DisplayName("Discovery finds the five IT classes of the package")
    void discoveryFindsTheItClasses() {
        List<ItClass> classes = discover();
        assertTrue(classes.size() >= 5, "found only " + classes);
        for (ItClass c : classes) {
            assertTrue(c.className().endsWith("IT"), c.className());
            assertFalse(c.items() == null || c.items().isEmpty(), c.className() + " has no ITEMS");
        }
    }

    // ---------------------------------------------------------------- the check

    /** Rules 1 to 5 of design §3.9. Pure: no I/O. An empty result means the traceability holds. */
    static List<String> violations(List<TestCaseDocument> documents, List<UseCaseDocument> useCases,
            List<ItClass> classes) {
        List<String> out = new ArrayList<>();
        Map<String, TestCaseDocument> byId = new LinkedHashMap<>();
        for (TestCaseDocument d : documents) {
            byId.put(d.id(), d);
        }
        Map<String, List<ItClass>> classesById = new LinkedHashMap<>();
        for (ItClass c : classes) {
            if (c.specTestCaseId() != null) {
                classesById.computeIfAbsent(c.specTestCaseId(), k -> new ArrayList<>()).add(c);
            }
        }

        for (TestCaseDocument d : documents) {
            List<ItClass> carrying = classesById.getOrDefault(d.id(), List.of());
            if (APPROVED.equals(d.status()) && carrying.isEmpty()) {
                out.add("Rule 1: " + d.id() + " (" + d.file() + ") is Approved but no *IT class carries @SpecTestCase(\""
                        + d.id() + "\")");
            }
            if (carrying.size() > 1) {
                out.add("Rule 2: " + d.id() + " (" + d.file() + ") has " + carrying.size() + " classes: "
                        + carrying.stream().map(ItClass::className).toList());
            }
        }

        for (ItClass c : classes) {
            String id = c.specTestCaseId();
            if (id == null) {
                out.add("Rule 3: class " + c.className() + " has no @SpecTestCase");
            } else {
                TestCaseDocument d = byId.get(id);
                if (d == null) {
                    out.add("Rule 3: class " + c.className() + " names " + id + ", which has no document in "
                            + "docs/test_cases/");
                } else {
                    if (!APPROVED.equals(d.status())) {
                        out.add("Rule 3: class " + c.className() + " names " + id + " (" + d.file() + ") whose "
                                + "status is '" + d.status() + "', not Approved");
                    }
                    checkNames(c, d, out);
                }
            }
            checkItems(c, useCases, out);
        }

        for (SpecRun.UseCase uc : SpecRun.UseCase.values()) {
            UseCaseDocument doc = findUseCase(useCases, uc.id());
            if (doc != null && !doc.title().equals(uc.title())) {
                out.add("Rule 5: SpecRun.UseCase." + uc.name() + " has title '" + uc.title() + "' but "
                        + doc.file() + " says '" + doc.title() + "'");
            }
        }
        return out;
    }

    private static void checkNames(ItClass c, TestCaseDocument d, List<String> out) {
        String simple = c.className().substring(c.className().lastIndexOf('.') + 1);
        String digits = d.id().replaceFirst("^TC-", "");
        String prefix = "Tc" + digits;
        if (!simple.startsWith(prefix) || simple.length() > prefix.length()
                && Character.isDigit(simple.charAt(prefix.length()))) {
            out.add("Rule 4: class " + c.className() + " does not start with '" + prefix + "' for " + d.id() + " ("
                    + d.file() + ")");
        }
        if (c.testMethods() != 1) {
            out.add("Rule 4: class " + c.className() + " has " + c.testMethods() + " @Test methods, expected 1 (for "
                    + d.id() + ", " + d.file() + ")");
            return;
        }
        String expected = d.id() + " " + d.title();
        if (!expected.equals(c.displayName())) {
            out.add("Rule 4: class " + c.className() + " has display name '" + c.displayName() + "' but "
                    + d.file() + " requires '" + expected + "'");
        }
    }

    private static void checkItems(ItClass c, List<UseCaseDocument> useCases, List<String> out) {
        if (c.items() == null) {
            out.add("Rule 5: class " + c.className() + " has no static ITEMS field (a List<SpecRun.Item>)");
            return;
        }
        for (SpecRun.Item item : c.items()) {
            if (item.kind() != SpecRun.Kind.NOT_YET_TESTABLE) {
                continue;
            }
            UseCaseDocument uc = findUseCase(useCases, item.useCase().id());
            if (uc == null) {
                out.add("Rule 5: class " + c.className() + " item " + item.id() + " is not yet testable for "
                        + item.useCase().id() + ", which has no document in docs/use_cases/");
            } else if (!APPROVED.equals(uc.status())) {
                out.add("Rule 5: class " + c.className() + " item " + item.id() + " is not yet testable for "
                        + uc.id() + " (" + uc.file() + ") whose status is '" + uc.status() + "', not Approved");
            }
        }
    }

    private static UseCaseDocument findUseCase(List<UseCaseDocument> useCases, String id) {
        for (UseCaseDocument u : useCases) {
            if (u.id().equals(id)) {
                return u;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- discovery

    /** The {@code *IT} classes of the package, found in the compiled test class directory. */
    static List<ItClass> discover() {
        Path dir = packageDirectory();
        List<String> names;
        try (Stream<Path> s = Files.list(dir)) {
            names = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("IT.class"))
                    .map(n -> n.substring(0, n.length() - ".class".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
        List<ItClass> result = new ArrayList<>();
        for (String name : names) {
            result.add(read(PACKAGE + "." + name));
        }
        return result;
    }

    private static Path packageDirectory() {
        try {
            Path root = Path.of(SpecTraceabilityTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return root.resolve(PACKAGE.replace('.', '/'));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate the compiled test classes", e);
        }
    }

    private static ItClass read(String className) {
        Class<?> type;
        try {
            // No initialisation here: annotations and methods are read without running static initialisers
            type = Class.forName(className, false, SpecTraceabilityTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load " + className, e);
        }
        SpecTestCase annotation = type.getAnnotation(SpecTestCase.class);
        List<Method> tests = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.junit.jupiter.api.Test.class)) {
                tests.add(m);
            }
        }
        String displayName = null;
        if (tests.size() == 1) {
            DisplayName dn = tests.get(0).getAnnotation(DisplayName.class);
            displayName = dn == null ? null : dn.value();
        }
        return new ItClass(className, annotation == null ? null : annotation.value(), displayName, tests.size(),
                items(type));
    }

    @SuppressWarnings("unchecked")
    private static List<SpecRun.Item> items(Class<?> type) {
        try {
            Field f = type.getDeclaredField("ITEMS");
            if (!Modifier.isStatic(f.getModifiers())) {
                return null;
            }
            f.setAccessible(true);
            // Reading the field initialises the class: its static initialiser only builds constants and ITEMS
            return (List<SpecRun.Item>) f.get(null);
        } catch (NoSuchFieldException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read ITEMS of " + type.getName(), e);
        }
    }

    // ---------------------------------------------------------------- negative tests on in-memory inputs

    @TempDir
    Path tmp;

    private TestCaseDocument tc(String id, String title, String status) {
        String text = "# Test Case: " + title + "\n\n## Overview\n\n**ID:** " + id + "\n**Status:** " + status
                + "\n\n## Preconditions\n\n- ready\n\n## Flow\n\n"
                + "| Step | Name | Actor | Action | Use Case |\n|---|---|---|---|---|\n"
                + "| 1 | Do it | A | act | - |\n\n## Validation\n\n1. it works\n\n## Postconditions\n\n- done\n";
        Path file = tmp.resolve(id + "-" + status.replace(' ', '-') + ".md");
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return TestCaseDocument.parse(file);
    }

    private static UseCaseDocument uc(String id, String status) {
        return new UseCaseDocument(id, "Title of " + id, status, Path.of("docs/use_cases/" + id + ".md"));
    }

    private static ItClass cls(String name, String id, String display, SpecRun.Item... items) {
        return new ItClass(PACKAGE + "." + name, id, display, 1, List.of(items));
    }

    private static final List<UseCaseDocument> NO_UC = List.of();

    @Test
    @DisplayName("Rule 1: an Approved test case without a class is reported")
    void rule1MissingClass() {
        TestCaseDocument d = tc("TC-001", "First", APPROVED);
        List<String> v = violations(List.of(d), NO_UC, List.of());
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("TC-001") && v.get(0).contains(d.file().toString()), v.get(0));
    }

    @Test
    @DisplayName("Rule 2: two classes for one test case are reported")
    void rule2DuplicateClass() {
        TestCaseDocument d = tc("TC-001", "First", APPROVED);
        List<String> v = violations(List.of(d), NO_UC, List.of(
                cls("Tc001FirstIT", "TC-001", "TC-001 First"),
                cls("Tc001AgainIT", "TC-001", "TC-001 First")));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("Tc001FirstIT") && v.get(0).contains("Tc001AgainIT")
                && v.get(0).contains(d.file().toString()), v.get(0));
    }

    @Test
    @DisplayName("Rule 3: a class without @SpecTestCase, with an unknown ID or with a non-Approved document is reported")
    void rule3AnnotationProblems() {
        TestCaseDocument draft = tc("TC-002", "Second", "Draft");
        List<String> v = violations(List.of(draft), NO_UC, List.of(
                cls("Tc009LostIT", null, null),
                cls("Tc008GhostIT", "TC-008", "TC-008 Ghost"),
                cls("Tc002SecondIT", "TC-002", "TC-002 Second")));
        assertEquals(3, v.size(), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("Tc009LostIT") && s.contains("no @SpecTestCase")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("Tc008GhostIT") && s.contains("TC-008")
                && s.contains("no document")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("Tc002SecondIT") && s.contains(draft.file().toString())
                && s.contains("Draft")), v.toString());
    }

    @Test
    @DisplayName("Rule 4: a wrong class name prefix or display name is reported")
    void rule4NameMismatch() {
        TestCaseDocument d = tc("TC-001", "First", APPROVED);
        List<String> byName = violations(List.of(d), NO_UC, List.of(cls("Tc002FirstIT", "TC-001", "TC-001 First")));
        assertEquals(1, byName.size(), byName.toString());
        assertTrue(byName.get(0).contains("Tc002FirstIT") && byName.get(0).contains("Tc001")
                && byName.get(0).contains(d.file().toString()), byName.get(0));

        List<String> byDisplay = violations(List.of(d), NO_UC, List.of(cls("Tc001FirstIT", "TC-001", "TC-001 Other")));
        assertEquals(1, byDisplay.size(), byDisplay.toString());
        assertTrue(byDisplay.get(0).contains("Tc001FirstIT") && byDisplay.get(0).contains("TC-001 First")
                && byDisplay.get(0).contains(d.file().toString()), byDisplay.get(0));
    }

    @Test
    @DisplayName("Rule 4: a class without exactly one @Test method is reported")
    void rule4TestMethodCount() {
        TestCaseDocument d = tc("TC-001", "First", APPROVED);
        for (int n : new int[] {0, 2}) {
            ItClass c = new ItClass(PACKAGE + ".Tc001FirstIT", "TC-001", null, n, List.of());
            List<String> v = violations(List.of(d), NO_UC, List.of(c));
            assertEquals(1, v.size(), v.toString());
            assertTrue(v.get(0).contains("Tc001FirstIT") && v.get(0).contains(n + " @Test methods, expected 1")
                    && v.get(0).contains(d.file().toString()), v.get(0));
        }
    }

    @Test
    @DisplayName("Rule 5: a not-yet-testable use case that is unknown or not Approved is reported")
    void rule5UseCaseProblems() {
        TestCaseDocument d = tc("TC-001", "First", APPROVED);
        SpecRun.Item unknown = SpecRun.notYetTestable("S1", "needs a missing UC", SpecRun.UseCase.UC_005);
        SpecRun.Item draft = SpecRun.notYetTestable("S2", "needs a draft UC", SpecRun.UseCase.UC_006);
        ItClass c = cls("Tc001FirstIT", "TC-001", "TC-001 First", unknown, draft);
        UseCaseDocument draftDoc = new UseCaseDocument("UC-006", SpecRun.UseCase.UC_006.title(), "Draft",
                Path.of("docs/use_cases/UC-006.md"));
        List<String> v = violations(List.of(d), List.of(draftDoc), List.of(c));
        assertEquals(2, v.size(), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("Tc001FirstIT") && s.contains("UC-005")
                && s.contains("no document")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("Tc001FirstIT") && s.contains("UC-006")
                && s.contains("Draft") && s.contains("UC-006.md")), v.toString());
    }

    @Test
    @DisplayName("Rule 5: a SpecRun.UseCase title that differs from the use case document is reported")
    void rule5TitleMismatch() {
        UseCaseDocument doc = new UseCaseDocument("UC-001", "Something Else", APPROVED, Path.of("docs/use_cases/UC-001.md"));
        List<String> v = violations(List.of(), List.of(doc), List.of());
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("UC_001") && v.get(0).contains("Something Else")
                && v.get(0).contains("UC-001.md"), v.get(0));
    }

    @Test
    @DisplayName("A consistent in-memory set has no violation")
    void consistentSetPasses() {
        TestCaseDocument d = tc("TC-001", "First", APPROVED);
        UseCaseDocument doc = new UseCaseDocument("UC-005", SpecRun.UseCase.UC_005.title(), APPROVED,
                Path.of("docs/use_cases/UC-005.md"));
        ItClass c = cls("Tc001FirstIT", "TC-001", "TC-001 First",
                SpecRun.run("S1", "Do it"), SpecRun.notYetTestable("V1", "needs UC-005", SpecRun.UseCase.UC_005));
        assertEquals(List.of(), violations(List.of(d), List.of(doc), List.of(c)));
    }
}
