package com.trailheadgear.orderintegration;

import org.springframework.boot.SpringApplication;

/**
 * Test main class for src/test/scripts/kill-test.sh (design 002, Flow kill-test support). It starts the production
 * application with {@link KillTestHook} added; the hook does nothing unless the profile {@code kill-test} is active.
 * Lives on the test classpath only, so the packaged jar has no hook.
 */
public class KillTestApplication {

    public static void main(String[] args) {
        SpringApplication.from(CamelApplication::main).with(KillTestHook.class).run(args);
    }
}
