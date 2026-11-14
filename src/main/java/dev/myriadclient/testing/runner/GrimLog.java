package dev.myriadclient.testing.runner;

import dev.myriadclient.testing.GrimFlag;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grim's flags for one player, read from the test server's console log since a mark: every alert line, so a test can
 * fail on them and its report shows Grim's own details. Without a log (no {@code serverLog} configured) there are none.
 */
public final class GrimLog {
	private static final Pattern COLOUR = Pattern.compile("\u001B\\[[0-9;]*m");

	private final @Nullable Path log;
	private final boolean strict;
	private long mark;

	/** {@code strict}: see {@link Config#strict()}. */
	public GrimLog(@Nullable Path log, boolean strict) {
		this.log = log;
		this.strict = strict;
	}

	/** Flags count from here. */
	public void mark() {
		mark = size();
	}

	private long size() {
		try {
			return log == null || !Files.exists(log) ? 0 : Files.size(log);
		} catch (IOException e) {
			return 0;
		}
	}

	/** Every line since the mark in which Grim flagged {@code player}: "Simulation (x3) .0023 /gl 12". */
	public List<GrimFlag> flags(String player) {
		List<GrimFlag> flags = new ArrayList<>();
		if (log == null) return flags;
		Pattern line = Pattern.compile("(?<![\\w])" + Pattern.quote(player) + " failed (\\w+)(.*)");
		try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
			long end = f.length();
			if (end < mark) mark = 0;
			byte[] bytes = new byte[(int) Math.min(end - mark, 16 << 20)];
			f.seek(mark);
			f.readFully(bytes);
			for (String text : COLOUR.matcher(new String(bytes, StandardCharsets.UTF_8)).replaceAll("").split("\n")) {
				Matcher m = line.matcher(text);
				if (!m.find()) continue;
				// Like 2b2t: a click over held movement keys alone isn't flagged there (over sprinting or sneaking it is).
				if (!strict && (m.group(1).equals("MultiActionsC") || m.group(1).equals("MultiActionsD"))
					&& m.group(2).contains("sprinting=false, sneaking=false, input=true")) continue;
				flags.add(new GrimFlag(m.group(1), m.group(2).trim()));
			}
		} catch (IOException e) {
			// No log, nothing to read.
		}
		return flags;
	}

}
