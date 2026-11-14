package dev.myriadclient.testing.runner;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Warnings and errors the game logs while a test runs, for its report; and whether Myriad or an addon logged an error
 * (a handler that threw, a service that failed), which fails the test: it would otherwise carry on with part of the
 * game switched off, and fail later for a reason that hides the real one, or hang.
 */
public final class GameLog extends AbstractAppender {
	/** Errors from these loggers aren't the game's: the runner's own (a test that threw is its failure already). */
	private static final String OWN = "Myriad/Testing";

	private final TickClock clock;
	private final List<String> lines = new ArrayList<>();
	private String firstError;
	private long startTick;
	private boolean recording;

	public GameLog(TickClock clock) {
		super("MyriadTesting", null, null, true, Property.EMPTY_ARRAY);
		this.clock = clock;
	}

	/** Listens to the root logger from now on. */
	public void install() {
		start();
		((Logger) LogManager.getRootLogger()).addAppender(this);
	}

	/** A test starts: nothing seen yet. */
	public synchronized void begin() {
		lines.clear();
		firstError = null;
		startTick = clock.now();
		recording = true;
	}

	/** The test is over. */
	public synchronized void end() {
		recording = false;
	}

	/** What was logged at WARN or above during the test, a line each (an error's stack trace in short). */
	public synchronized List<String> lines() {
		return List.copyOf(lines);
	}

	/** The first error Myriad or an addon logged during the test ("Myriad/EventBus: Handler from ... threw: ..."), or null. */
	public synchronized String firstError() {
		return firstError;
	}

	@Override
	public void append(LogEvent event) {
		if (!event.getLevel().isMoreSpecificThan(Level.WARN)) return;
		String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
		String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
		Throwable thrown = event.getThrown();
		StringBuilder line = new StringBuilder(String.format(java.util.Locale.ROOT, "t%-5d %s [%s] %s", clock.now() - startTick, event.getLevel(),
			logger, message));
		if (thrown != null) {
			line.append("\n        ").append(thrown);
			StackTraceElement[] trace = thrown.getStackTrace();
			for (int i = 0; i < Math.min(6, trace.length); i++) line.append("\n          at ").append(trace[i]);
		}
		synchronized (this) {
			if (!recording) return;
			if (lines.size() < 500) lines.add(line.toString());
			if (firstError == null && event.getLevel().isMoreSpecificThan(Level.ERROR) && logger.startsWith("Myriad") && !logger.equals(OWN)) {
				firstError = logger + ": " + message + (thrown != null ? ": " + thrown : "");
			}
		}
	}
}
