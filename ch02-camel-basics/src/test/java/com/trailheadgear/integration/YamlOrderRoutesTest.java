package com.trailheadgear.integration;

import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("yaml")
class YamlOrderRoutesTest extends OrderRoutesTest {

    @org.junit.jupiter.api.Test
    void theYamlRoutesAreTheOnesRunning() {
        org.assertj.core.api.Assertions.assertThat(camel.getRoute("order-intake").getSourceLocation())
                .contains("order-routes.camel.yaml");
    }
}
