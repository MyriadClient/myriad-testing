package dev.myriadclient.testing;

/** Thrown by {@link TestContext#skip}: the test can't run here (a mod it needs isn't loaded), neither passed nor failed. */
public final class TestSkipped extends RuntimeException {
	public TestSkipped(String reason) {
		super(reason);
	}
}
