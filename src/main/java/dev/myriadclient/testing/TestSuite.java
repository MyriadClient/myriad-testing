package dev.myriadclient.testing;

/**
 * A class of in-game tests. Register it through the {@code myriad-tests} entrypoint in the {@code fabric.mod.json} of
 * the mod holding it (an addon's dev-only test mod), and every public method annotated {@link Test} that takes a
 * {@link TestContext} is a test, named {@code <mod id>:<suite>/<method>} ("myriad-essentials:scaffold/tower").
 * <p>
 * Every test starts the same way: every module off (and put back as it was after), no screen open, the player alive,
 * in survival with an empty inventory, full health and food, standing at the arena's origin facing -x, in a cleared
 * arena; then {@link #beforeEach}, then the test.
 *
 * <pre>{@code
 * public final class ScaffoldTests implements TestSuite {
 *     @Test
 *     public void tower(TestContext t) {
 *         t.player().give(0, "dirt", 64).select(0);
 *         t.module("myriad-essentials:scaffold").set("rotate", "false").enable();
 *         t.hold(Key.JUMP).forTicks(80);
 *         double rose = t.player().serverPosition().y;
 *         t.check(rose >= 5, "rose " + rose + " blocks");
 *     }
 * }
 * }</pre>
 */
public interface TestSuite {
	/**
	 * Run before each of the suite's tests, after the runner's own setup (above) and before Grim's flags start counting:
	 * the setup every test of the suite shares (an addon's usual settings).
	 */
	default void beforeEach(TestContext t) {
	}

	/** The suite's name in test ids; by default its class's simple name without a trailing "Tests", in snake case. */
	default String name() {
		String n = getClass().getSimpleName();
		if (n.endsWith("Tests") && n.length() > 5) n = n.substring(0, n.length() - 5);
		return n.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
	}
}
