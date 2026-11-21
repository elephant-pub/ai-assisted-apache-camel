package com.trailheadgear.orderintegration.spec.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Design 005 §3.10 row 2: {@code P-cleanup} of TC-001 and TC-005 names the right cause when the partner's registration
 * is not what it was. A partner that was not registered at {@code C-setup} was not touched by the cleanup.
 */
class SpecRunCleanupMessageTest {

    @Test
    void aPartnerNotRegisteredAtSetupIsNamedAsSuch() {
        assertThat(SpecRun.cleanupMessage("summit-outfitters", false))
            .isEqualTo("Partner summit-outfitters was not registered at the start (see C-setup)");
    }

    @Test
    void aPartnerRegisteredAtSetupWasTouchedByTheCleanup() {
        assertThat(SpecRun.cleanupMessage("summit-outfitters", true))
            .isEqualTo("Partner summit-outfitters was touched by the cleanup");
    }
}
