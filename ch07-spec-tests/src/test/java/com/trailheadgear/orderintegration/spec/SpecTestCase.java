package com.trailheadgear.orderintegration.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a test class to one test case document under {@code docs/test_cases} (for example {@code TC-001}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SpecTestCase {

    /** The test case ID, for example {@code TC-001}. */
    String value();
}
