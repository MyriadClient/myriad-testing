package dev.myriadclient.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public method of a {@link TestSuite} taking a {@link TestContext} as a test.
 * <p>
 * A test that passes its checks still fails if Grim flagged the player during it, for anything but the checks in
 * {@link #allowFlags} and, unless the server is the strict one, the placement checks 2b2t's Grim doesn't act on
 * (AirLiquidPlace, PositionPlace).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
	/** The test's name in its id; the method's name in snake case if empty. */
	String name() default "";

	/** Ticks it may take before it fails as timed out (a minute by default). */
	int timeoutTicks() default 1200;

	/** Tags to pick tests by ({@code myriad-test run --tag movement}). */
	String[] tags() default {};

	/** Grim checks this test may be flagged for without failing ("RotationPlace" for 2b2t-style placing). */
	String[] allowFlags() default {};

	/** Whether Grim's flags don't count for this test at all (one that turns every module on at once). */
	boolean anyFlags() default false;
}
