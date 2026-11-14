package dev.myriadclient.testing.runner;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;

/** Counts client ticks, so a test's thread can wait for the game: a wait parks it until the tick it's waiting for ends. */
public final class TickClock {
	private long tick;

	@Subscribe
	private void onTick(TickEvent.Post e) {
		synchronized (this) {
			tick++;
			notifyAll();
		}
	}

	/** The ticks counted since the game started. */
	public synchronized long now() {
		return tick;
	}

	/** Waits until tick {@code target} has ended; false if {@code timeoutMs} went by first (the game froze or closed). */
	public synchronized boolean await(long target, long timeoutMs) throws InterruptedException {
		long until = System.currentTimeMillis() + timeoutMs;
		while (tick < target) {
			long left = until - System.currentTimeMillis();
			if (left <= 0) return false;
			wait(left);
		}
		return true;
	}
}
