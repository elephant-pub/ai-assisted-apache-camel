package com.trailheadgear.orderintegration.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pure parser of a test case document ({@code docs/test_cases/TC-*.md}). No Citrus, no Docker, no Spring.
 * Any unexpected layout throws {@link IllegalStateException} naming the file and the line.
 */
public final class TestCaseDocument {

    public record Step(int number, String name, String useCaseId) {
    }

    public record UseCaseDocument(String id, String title, String status, Path file) {

        public static UseCaseDocument parseUseCase(Path file) {
            List<String> lines = readLines(file);
            String title = null;
            String id = null;
            String status = null;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (title == null && line.startsWith("# ")) {
                    title = stripPrefix(line.substring(2).strip(), "Use Case:");
                } else if (id == null && line.startsWith("**Use Case ID:**")) {
                    id = line.substring("**Use Case ID:**".length()).strip();
                } else if (status == null && line.startsWith("**Status:**")) {
                    status = line.substring("**Status:**".length()).strip();
                }
            }
            if (title == null || title.isEmpty()) {
                throw fail(file, 1, "missing H1 title");
            }
            if (id == null || id.isEmpty()) {
                throw fail(file, 1, "missing '**Use Case ID:**'");
            }
            if (status == null || status.isEmpty()) {
                throw fail(file, 1, "missing '**Status:**'");
            }
            return new UseCaseDocument(id, title, status, file);
        }

        public static List<UseCaseDocument> loadUseCases() {
            return loadUseCases(docsDir());
        }

        public static List<UseCaseDocument> loadUseCases(Path docsDir) {
            List<UseCaseDocument> result = new ArrayList<>();
            for (Path p : listFiles(docsDir.resolve("use_cases"), "UC-")) {
                result.add(parseUseCase(p));
            }
            result.sort((a, b) -> a.id().compareTo(b.id()));
            return List.copyOf(result);
        }
    }

    private static final Pattern UC_ID = Pattern.compile("UC-\\d+");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)\\.\\s+(.+)$");
    private static final String CLEANUP_PREFIX = "Cleanup order";

    private final String id;
    private final String title;
    private final String status;
    private final Path file;
    private final List<Step> steps;
    private final List<String> validations;
    private final List<String> postconditions;
    private final String cleanup;
    private final List<String> preconditions;

    private TestCaseDocument(String id, String title, String status, Path file, List<Step> steps,
            List<String> validations, List<String> postconditions, String cleanup, List<String> preconditions) {
        this.id = id;
        this.title = title;
        this.status = status;
        this.file = file;
        this.steps = List.copyOf(steps);
        this.validations = List.copyOf(validations);
        this.postconditions = List.copyOf(postconditions);
        this.cleanup = cleanup;
        this.preconditions = List.copyOf(preconditions);
    }

    public static Path docsDir() {
        String prop = System.getProperty("spec-test.docs-dir");
        return prop != null && !prop.isBlank() ? Path.of(prop) : Path.of("docs");
    }

    public static List<TestCaseDocument> loadAll() {
        return loadAll(docsDir());
    }

    public static List<TestCaseDocument> loadAll(Path docsDir) {
        List<TestCaseDocument> result = new ArrayList<>();
        for (Path p : listFiles(docsDir.resolve("test_cases"), "TC-")) {
            result.add(parse(p));
        }
        result.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(result);
    }

    public static TestCaseDocument parse(Path file) {
        List<String> lines = readLines(file);
        String title = null;
        String id = null;
        String status = null;
        String section = "";
        int sectionLine = 1;
        List<Step> steps = new ArrayList<>();
        List<String> validations = new ArrayList<>();
        List<String> postconditions = new ArrayList<>();
        List<String> preconditions = new ArrayList<>();
        String cleanup = null;
        boolean sawFlowHeader = false;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = lines.get(i);
            String trimmed = line.strip();
            if (line.startsWith("## ")) {
                section = line.substring(3).strip();
                sectionLine = lineNo;
                continue;
            }
            if (line.startsWith("# ")) {
                if (title == null) {
                    title = stripPrefix(line.substring(2).strip(), "Test Case:");
                }
                continue;
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            switch (section) {
                case "Overview" -> {
                    if (line.startsWith("**ID:**")) {
                        id = line.substring("**ID:**".length()).strip();
                    } else if (line.startsWith("**Status:**")) {
                        status = line.substring("**Status:**".length()).strip();
                    }
                }
                case "Preconditions" -> preconditions.add(bullet(file, lineNo, trimmed));
                case "Flow" -> {
                    if (!trimmed.startsWith("|")) {
                        throw fail(file, lineNo, "expected a table row in Flow but found: " + trimmed);
                    }
                    List<String> cells = cells(file, lineNo, trimmed);
                    if (cells.size() != 5) {
                        throw fail(file, lineNo, "Flow row must have 5 columns but has " + cells.size());
                    }
                    if (!sawFlowHeader) {
                        if (!cells.get(0).equals("Step") || !cells.get(1).equals("Name")
                                || !cells.get(4).equals("Use Case")) {
                            throw fail(file, lineNo, "unexpected Flow table header: " + trimmed);
                        }
                        sawFlowHeader = true;
                    } else if (cells.stream().allMatch(c -> c.matches("-{3,}"))) {
                        // separator row of the Markdown table
                    } else {
                        steps.add(step(file, lineNo, cells, steps.size() + 1));
                    }
                }
                case "Validation" -> {
                    Matcher m = NUMBERED.matcher(trimmed);
                    if (!m.matches()) {
                        throw fail(file, lineNo, "expected a numbered Validation item but found: " + trimmed);
                    }
                    int n = Integer.parseInt(m.group(1));
                    if (n != validations.size() + 1) {
                        throw fail(file, lineNo, "Validation item numbered " + n + " but expected "
                                + (validations.size() + 1));
                    }
                    validations.add(m.group(2).strip());
                }
                case "Postconditions" -> {
                    String text = bullet(file, lineNo, trimmed);
                    if (text.startsWith(CLEANUP_PREFIX)) {
                        if (cleanup != null) {
                            throw fail(file, lineNo, "more than one 'Cleanup order' bullet");
                        }
                        cleanup = text;
                    } else {
                        postconditions.add(text);
                    }
                }
                default -> {
                    // Roles and any other sections are not parsed.
                }
            }
        }

        if (title == null || title.isEmpty()) {
            throw fail(file, 1, "missing H1 title");
        }
        if (id == null || id.isEmpty()) {
            throw fail(file, 1, "missing '**ID:**' in Overview");
        }
        if (status == null || status.isEmpty()) {
            throw fail(file, 1, "missing '**Status:**' in Overview");
        }
        if (steps.isEmpty()) {
            throw fail(file, 1, "Flow table has no steps");
        }
        if (validations.isEmpty()) {
            throw fail(file, 1, "Validation section has no numbered items");
        }
        if (postconditions.isEmpty()) {
            throw fail(file, 1, "Postconditions section has no bullets");
        }
        if (preconditions.isEmpty()) {
            throw fail(file, 1, "Preconditions section has no bullets");
        }
        return new TestCaseDocument(id, title, status, file, steps, validations, postconditions, cleanup,
                preconditions);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String status() {
        return status;
    }

    public Path file() {
        return file;
    }

    public List<Step> steps() {
        return steps;
    }

    public List<String> validations() {
        return validations;
    }

    /** Postcondition bullets in document order, excluding the "Cleanup order" bullet. */
    public List<String> postconditions() {
        return postconditions;
    }

    /** Text of the "Cleanup order" bullet, or null if the document has none. */
    public String cleanup() {
        return cleanup;
    }

    public List<String> preconditions() {
        return preconditions;
    }

    /** S1..Sn, V1..Vn, P1..Pn, then P-cleanup when a cleanup bullet exists. */
    public List<String> itemIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= steps.size(); i++) {
            ids.add("S" + i);
        }
        for (int i = 1; i <= validations.size(); i++) {
            ids.add("V" + i);
        }
        for (int i = 1; i <= postconditions.size(); i++) {
            ids.add("P" + i);
        }
        if (cleanup != null) {
            ids.add("P-cleanup");
        }
        return List.copyOf(ids);
    }

    private static Step step(Path file, int lineNo, List<String> cells, int expectedNumber) {
        int number;
        try {
            number = Integer.parseInt(cells.get(0));
        } catch (NumberFormatException e) {
            throw fail(file, lineNo, "Flow step number is not an integer: '" + cells.get(0) + "'");
        }
        if (number != expectedNumber) {
            throw fail(file, lineNo, "Flow step numbered " + number + " but expected " + expectedNumber);
        }
        String name = cells.get(1);
        if (name.isEmpty()) {
            throw fail(file, lineNo, "Flow step " + number + " has no name");
        }
        String uc = cells.get(4);
        String useCaseId = null;
        if (!uc.equals("-")) {
            Matcher m = UC_ID.matcher(uc);
            if (!m.find()) {
                throw fail(file, lineNo, "Flow step " + number + " use case cell is neither '-' nor a UC link: '"
                        + uc + "'");
            }
            useCaseId = m.group();
        }
        return new Step(number, name, useCaseId);
    }

    private static List<String> cells(Path file, int lineNo, String row) {
        if (!row.endsWith("|") || row.length() < 2) {
            throw fail(file, lineNo, "table row does not end with '|': " + row);
        }
        String inner = row.substring(1, row.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String c : inner.split("\\|", -1)) {
            cells.add(c.strip());
        }
        return cells;
    }

    private static String bullet(Path file, int lineNo, String trimmed) {
        if (!trimmed.startsWith("- ")) {
            throw fail(file, lineNo, "expected a '- ' bullet but found: " + trimmed);
        }
        return trimmed.substring(2).strip();
    }

    private static String stripPrefix(String text, String prefix) {
        return text.startsWith(prefix) ? text.substring(prefix.length()).strip() : text;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static List<Path> listFiles(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Directory not found: " + dir.toAbsolutePath());
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".md");
            }).sorted().toList();
            if (files.isEmpty()) {
                throw new IllegalStateException("No " + prefix + "*.md documents in " + dir.toAbsolutePath());
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
    }

    private static IllegalStateException fail(Path file, int line, String message) {
        return new IllegalStateException(file + ":" + line + ": " + message);
    }
}
