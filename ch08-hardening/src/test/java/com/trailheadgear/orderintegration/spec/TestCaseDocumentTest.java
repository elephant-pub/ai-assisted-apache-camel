package com.trailheadgear.orderintegration.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trailheadgear.orderintegration.spec.TestCaseDocument.Step;
import com.trailheadgear.orderintegration.spec.TestCaseDocument.UseCaseDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCaseDocumentTest {

    private static TestCaseDocument load(String id) {
        return TestCaseDocument.loadAll().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertDocument(String id, String title, int steps, int validations, int postconditions) {
        TestCaseDocument d = load(id);
        assertThat(d.title()).isEqualTo(title);
        assertThat(d.status()).isEqualTo("Approved");
        assertThat(d.steps()).hasSize(steps);
        assertThat(d.validations()).hasSize(validations);
        assertThat(d.postconditions()).hasSize(postconditions);
        assertThat(d.cleanup()).startsWith("Cleanup order");
        assertThat(d.preconditions()).isNotEmpty();
        assertThat(d.itemIds()).hasSize(steps + validations + postconditions + 1);
        assertThat(d.itemIds()).endsWith("P-cleanup");
    }

    @Test
    void loadsAllTestCasesSortedById() {
        // Design 005 §3.9: TC-006 and TC-007 are Proposed (not required by the matrix), but loaded like the others
        assertThat(TestCaseDocument.loadAll()).extracting(TestCaseDocument::id)
                .containsExactly("TC-001", "TC-002", "TC-003", "TC-004", "TC-005", "TC-006", "TC-007");
    }

    // TC-001: 7 Flow steps, 4 Validation items, 3 Postcondition bullets plus the Cleanup order bullet.
    @Test
    void tc001() {
        assertDocument("TC-001", "Partner Order to Warehouse", 7, 4, 3);
    }

    // TC-002: 8 Flow steps, 3 Validation items, 4 Postcondition bullets plus the Cleanup order bullet.
    @Test
    void tc002() {
        assertDocument("TC-002", "Peakmart Redelivery Shipped Once", 8, 3, 4);
    }

    // TC-003: 5 Flow steps, 4 Validation items, 2 Postcondition bullets plus the Cleanup order bullet.
    @Test
    void tc003() {
        assertDocument("TC-003", "Peakmart Order Rejected", 5, 4, 2);
    }

    // TC-004: 8 Flow steps, 4 Validation items, 4 Postcondition bullets plus the Cleanup order bullet.
    @Test
    void tc004() {
        assertDocument("TC-004", "Set-Aside Order Event Reaches Warehouse", 8, 4, 4);
    }

    // TC-005: 9 Flow steps, 3 Validation items, 4 Postcondition bullets plus the Cleanup order bullet.
    @Test
    void tc005() {
        assertDocument("TC-005", "Pick List Once After Restart", 9, 3, 4);
    }

    @Test
    void tc001StepsCarryTheirUseCaseLinks() {
        TestCaseDocument d = load("TC-001");
        assertThat(d.steps()).containsExactly(
                new Step(1, "Submit partner order", "UC-001"),
                new Step(2, "Check order", "UC-003"),
                new Step(3, "Verify order accepted", null),
                new Step(4, "Deliver pick list", "UC-004"),
                new Step(5, "Verify pick list", null),
                new Step(6, "Look up order", "UC-005"),
                new Step(7, "Verify order history", null));
        assertThat(d.itemIds()).containsExactly("S1", "S2", "S3", "S4", "S5", "S6", "S7", "V1", "V2", "V3", "V4",
                "P1", "P2", "P3", "P-cleanup");
        assertThat(d.validations().get(0)).startsWith("**One Pick List**");
    }

    @Test
    void useCasesParseWithIdTitleAndStatus() {
        Map<String, String> expected = Map.of(
                "UC-001", "Submit Partner Order",
                "UC-002", "Take In Peakmart Order",
                "UC-003", "Check Order",
                "UC-004", "Deliver Pick List",
                "UC-005", "Look Up Order",
                "UC-006", "Alert on Stuck Order",
                "UC-007", "Register Partner",
                "UC-008", "Resubmit Set-Aside Order Event");
        List<UseCaseDocument> all = UseCaseDocument.loadUseCases();
        assertThat(all).hasSize(8);
        for (UseCaseDocument uc : all) {
            assertThat(uc.title()).isEqualTo(expected.get(uc.id()));
            assertThat(uc.status()).isEqualTo("Approved");
        }
        assertThat(all).extracting(UseCaseDocument::id).containsExactlyInAnyOrderElementsOf(expected.keySet());
    }

    @Test
    void malformedDocumentFailsNamingTheFile(@TempDir Path tmp) throws IOException {
        Path bad = tmp.resolve("TC-999-bad.md");
        Files.writeString(bad, """
                # Test Case: Broken

                ## Overview

                **ID:** TC-999
                **Status:** Approved

                ## Flow

                | Step | Name | Description | Test Data | Use Case |
                |------|------|-------------|-----------|----------|
                | x | Oops | d | - | - |
                """);
        assertThatThrownBy(() -> TestCaseDocument.parse(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TC-999-bad.md")
                .hasMessageContaining(":12:");
    }

    @Test
    void missingIdFailsNamingTheFile(@TempDir Path tmp) throws IOException {
        Path bad = tmp.resolve("TC-998-noid.md");
        Files.writeString(bad, "# Test Case: No Id\n\n## Overview\n\n**Status:** Approved\n");
        assertThatThrownBy(() -> TestCaseDocument.parse(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TC-998-noid.md")
                .hasMessageContaining("**ID:**");
    }
}
