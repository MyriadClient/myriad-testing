package dev.myriadclient.testing;

/** A test's check that didn't hold: thrown by {@link TestContext#check} and its kin, reported as the test's failure. */
public final class TestFailure extends RuntimeException {
	public TestFailure(String message) {
		super(message);
	}
}
