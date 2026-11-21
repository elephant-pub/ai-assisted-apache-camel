package com.trailheadgear.orderintegration.spec.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.TestReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the items of one test case in table order and records their outcomes (design 004 §3.2, A-3, A-7).
 *
 * <p>Each IT class declares a static {@code ITEMS} list. Run items are executed through {@link #execute}; a
 * not-yet-testable item is never executed: it is reported (TestReporter and log) and recorded with the use case it
 * waits for. After the first failing run item every later run item is {@code NOT_RUN}, and the failure is rethrown so
 * that the {@code @Test} fails. {@link #finish()} writes {@code <spec-test.work-dir>/results/<TC-id>.json}.
 *
 * <p>One instance per test run; there is no static mutable state.
 */
public final class SpecRun {

    private static final Logger LOG = LoggerFactory.getLogger(SpecRun.class);

    /** The use cases of {@code docs/use_cases/}, with ID and title as the documents state them. */
    public enum UseCase {
        UC_001("UC-001", "Submit Partner Order"),
        UC_002("UC-002", "Take In Peakmart Order"),
        UC_003("UC-003", "Check Order"),
        UC_004("UC-004", "Deliver Pick List"),
        UC_005("UC-005", "Look Up Order"),
        UC_006("UC-006", "Alert on Stuck Order"),
        UC_007("UC-007", "Register Partner"),
        UC_008("UC-008", "Resubmit Set-Aside Order Event");

        private final String id;
        private final String title;

        UseCase(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }
    }

    /** Whether an item is executed or declared as waiting for an unbuilt use case. */
    public enum Kind {
        RUN, NOT_YET_TESTABLE
    }

    /** The outcome of an item. */
    public enum Outcome {
        PASSED, FAILED, NOT_YET_TESTABLE, NOT_RUN
    }

    /** One row of an IT class's {@code ITEMS} table. */
    public record Item(String id, String name, Kind kind, UseCase useCase) {
        public Item {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            if ((kind == Kind.NOT_YET_TESTABLE) != (useCase != null)) {
                throw new IllegalArgumentException("Item " + id + ": a use case is given exactly for not-yet-testable items");
            }
        }
    }

    /** The work of a run item. */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    /** A run item: executed and asserted. */
    public static Item run(String id, String name) {
        return new Item(id, name, Kind.RUN, null);
    }

    /** A not-yet-testable item: never executed, reported with the use case it needs. */
    public static Item notYetTestable(String id, String name, UseCase useCase) {
        return new Item(id, name, Kind.NOT_YET_TESTABLE, Objects.requireNonNull(useCase, "useCase"));
    }

    /** The directory of the result files: {@code <spec-test.work-dir>/results}. */
    public static Path resultsDir() {
        return workDir().resolve("results");
    }

    /** {@code spec-test.work-dir} (set by failsafe), falling back to {@code target/spec-tests}. */
    public static Path workDir() {
        return Path.of(System.getProperty("spec-test.work-dir", "target/spec-tests")).toAbsolutePath();
    }

    private final String testCaseId;
    private final Class<?> testClass;
    private final String displayName;
    private final List<Item> items;
    private final TestReporter reporter;
    private final Map<String, Outcome> outcomes = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Set<String> reported = new LinkedHashSet<>();
    private int next;
    private Throwable firstFailure;
    private boolean finished;
    private boolean bodyCompleted;

    public SpecRun(String testCaseId, Class<?> testClass, String displayName, List<Item> items, TestReporter reporter) {
        this.testCaseId = Objects.requireNonNull(testCaseId, "testCaseId");
        this.testClass = Objects.requireNonNull(testClass, "testClass");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.items = List.copyOf(items);
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        Set<String> ids = new LinkedHashSet<>();
        for (Item item : this.items) {
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException(testCaseId + ": duplicate item " + item.id());
            }
        }
    }

    /**
     * Executes the run item {@code itemId}. Items must be executed in table order; the not-yet-testable items passed
     * on the way are reported. After an earlier failure the item is recorded as {@code NOT_RUN} and not executed. A
     * failure of this item is recorded and rethrown.
     */
    public void execute(String itemId, Action action) throws Exception {
        int index = advanceTo(itemId, firstFailure != null);
        Item item = items.get(index);
        if (firstFailure != null) {
            outcomes.put(item.id(), Outcome.NOT_RUN);
            LOG.info("{} {} {}: not run (after an earlier failure)", testCaseId, item.id(), item.name());
            return;
        }
        runAndRecord(item, action);
    }

    /**
     * Executes the run item {@code itemId} even after an earlier failure (for {@code P-cleanup} in the test's
     * {@code finally}). A failure is recorded; it is rethrown only when no earlier failure is being propagated.
     */
    public void executeAlways(String itemId, Action action) throws Exception {
        // Without bodyCompleted() an exception from outside the items may be propagating: skip ahead instead of
        // failing on the order check, and never replace that exception
        boolean propagating = firstFailure != null || !bodyCompleted;
        int index = advanceTo(itemId, propagating);
        Item item = items.get(index);
        if (propagating) {
            try {
                action.run();
                outcomes.put(item.id(), Outcome.PASSED);
                LOG.info("{} {} {}: passed", testCaseId, item.id(), item.name());
            } catch (Exception | AssertionError e) {
                outcomes.put(item.id(), Outcome.FAILED);
                failures.put(item.id(), message(e));
                LOG.warn("{} {} {}: failed: {}", testCaseId, item.id(), item.name(), message(e));
                if (firstFailure != null) {
                    firstFailure.addSuppressed(e);
                } else {
                    firstFailure = e;
                }
            }
            return;
        }
        runAndRecord(item, action);
    }

    /** True once a run item has failed. */
    public boolean failed() {
        return firstFailure != null;
    }

    /** The outcomes recorded so far, in table order of execution. */
    public Map<String, Outcome> outcomes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
    }

    /**
     * Marks the end of the test body (its last statement inside {@code try}). Only then does {@link #finish()} treat a
     * run item that was never executed as an error; otherwise an exception is already propagating and must not be
     * replaced.
     */
    public void bodyCompleted() {
        bodyCompleted = true;
    }

    /**
     * Reports the remaining not-yet-testable items, records the remaining run items as {@code NOT_RUN}, and writes the
     * result file. Called from the test's {@code finally}. When the body completed, no run item failed, and a run item
     * was never executed, the file is written and an {@link AssertionError} is thrown: the test did not execute its
     * table. While a failure is propagating, a write error is attached to it instead of replacing it.
     */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        List<String> skipped = new ArrayList<>();
        for (int i = next; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.kind() == Kind.NOT_YET_TESTABLE) {
                report(item);
            } else if (!outcomes.containsKey(item.id())) {
                outcomes.put(item.id(), Outcome.NOT_RUN);
                skipped.add(item.id());
            }
        }
        next = items.size();
        try {
            write();
        } catch (IOException e) {
            if (firstFailure != null) {
                firstFailure.addSuppressed(e);
                return;
            }
            throw e;
        }
        if (!bodyCompleted && firstFailure == null) {
            LOG.warn("{}: the test body did not reach bodyCompleted()", testCaseId);
        }
        if (bodyCompleted && firstFailure == null && !skipped.isEmpty()) {
            throw new AssertionError(testCaseId + ": run items never executed: " + skipped);
        }
    }

    private int advanceTo(String itemId, boolean skipAhead) {
        if (finished) {
            throw new IllegalStateException(testCaseId + ": item " + itemId + " after finish()");
        }
        int index = indexOf(itemId);
        if (index < next) {
            throw new IllegalStateException(testCaseId + ": item " + itemId + " is out of table order or ran twice");
        }
        Item item = items.get(index);
        if (item.kind() != Kind.RUN) {
            throw new IllegalStateException(testCaseId + ": item " + itemId + " is declared not yet testable ("
                + item.useCase().id() + ") and is never executed");
        }
        for (int i = next; i < index; i++) {
            Item passed = items.get(i);
            if (passed.kind() == Kind.NOT_YET_TESTABLE) {
                report(passed);
            } else if (!outcomes.containsKey(passed.id())) {
                if (!skipAhead) {
                    throw new IllegalStateException(testCaseId + ": item " + itemId + " executed before run item "
                        + passed.id());
                }
                // After a failure the test jumps to its finally block: the run items in between were not run
                outcomes.put(passed.id(), Outcome.NOT_RUN);
            }
        }
        next = index + 1;
        return index;
    }

    private int indexOf(String itemId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(itemId)) {
                return i;
            }
        }
        throw new IllegalArgumentException(testCaseId + ": item " + itemId + " is not in the ITEMS table");
    }

    private void runAndRecord(Item item, Action action) throws Exception {
        try {
            action.run();
            outcomes.put(item.id(), Outcome.PASSED);
            LOG.info("{} {} {}: passed", testCaseId, item.id(), item.name());
        } catch (Exception | AssertionError e) {
            outcomes.put(item.id(), Outcome.FAILED);
            failures.put(item.id(), message(e));
            firstFailure = e;
            LOG.warn("{} {} {}: failed: {}", testCaseId, item.id(), item.name(), message(e));
            throw e;
        }
    }

    private void report(Item item) {
        if (!reported.add(item.id())) {
            return;
        }
        outcomes.put(item.id(), Outcome.NOT_YET_TESTABLE);
        String line = testCaseId + " " + item.id() + " " + item.name() + ": not yet testable, needs "
            + item.useCase().id() + " " + item.useCase().title() + " (not built)";
        LOG.info(line);
        reporter.publishEntry(testCaseId + " " + item.id(), line);
    }

    private static String message(Throwable e) {
        String text = e.getMessage();
        return e.getClass().getSimpleName() + (text == null ? "" : ": " + text);
    }

    private void write() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Item item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.id());
            row.put("name", item.name());
            row.put("kind", item.kind().name());
            row.put("outcome", outcomes.getOrDefault(item.id(), Outcome.NOT_RUN).name());
            row.put("useCase", item.useCase() == null ? null : item.useCase().id());
            row.put("useCaseTitle", item.useCase() == null ? null : item.useCase().title());
            row.put("failure", failures.get(item.id()));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testCaseId", testCaseId);
        result.put("testClass", testClass.getName());
        result.put("displayName", displayName);
        result.put("items", rows);

        Path dir = resultsDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(testCaseId + ".json");
        Path temp = Files.createTempFile(dir, testCaseId, ".json.tmp");
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(temp.toFile(), result);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        LOG.info("{}: result written to {}", testCaseId, target);
    }
}
