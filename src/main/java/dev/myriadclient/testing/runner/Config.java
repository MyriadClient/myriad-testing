package dev.myriadclient.testing.runner;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * Where tests run: the server's RCON and console log, the dimension this client's tests use, where the arena goes, and
 * whether the server's Grim is the strict one ({@code strict}: current Grim as shipped, judging held keys under clicks;
 * otherwise 2b2t's, where a click over held movement keys alone isn't flagged, so Grim's MultiActions flags for just
 * that are left out). Defaults suit Myriad's Grim test server; the command line sends its own with each run, and system
 * properties ({@code -Dmyriad.test.rcon=host:port}, {@code .rconPassword}, {@code .serverLog}, {@code .dimension},
 * {@code .strict}, {@code .answerClicks}) set a client's.
 */
public record Config(String rconHost, int rconPort, String rconPassword, @Nullable Path serverLog, @Nullable String dimension,
                     int arenaX, int arenaY, int arenaZ, boolean freshSessionAfterFlags, boolean strict, String answerClicks) {
	/** The defaults, with any system properties. */
	public static Config defaults() {
		String rcon = System.getProperty("myriad.test.rcon", "localhost:25575");
		int colon = rcon.lastIndexOf(':');
		String log = System.getProperty("myriad.test.serverLog");
		return new Config(rcon.substring(0, colon), Integer.parseInt(rcon.substring(colon + 1)), System.getProperty("myriad.test.rconPassword", "grimtest"),
			log == null || log.isBlank() ? null : Path.of(log), System.getProperty("myriad.test.dimension"), 30000, 100, 0, true,
			Boolean.getBoolean("myriad.test.strict"), System.getProperty("myriad.test.answerClicks", "off"));
	}

	/**
	 * These, with whatever {@code json} sets: rcon ("host:port"), rconPassword, serverLog, dimension, arena [x, y, z],
	 * freshSessionAfterFlags, strict, answerClicks ("off", "on": the server answers inventory clicks as 2b2t does,
	 * "pre": harsher, "slots": 2b2t's own one-slot answers).
	 */
	public Config with(@Nullable JsonObject json) {
		if (json == null) return this;
		String host = rconHost;
		int port = rconPort;
		if (json.has("rcon")) {
			String r = json.get("rcon").getAsString();
			int c = r.lastIndexOf(':');
			host = r.substring(0, c);
			port = Integer.parseInt(r.substring(c + 1));
		}
		int[] arena = {arenaX, arenaY, arenaZ};
		if (json.has("arena")) for (int i = 0; i < 3; i++) arena[i] = json.getAsJsonArray("arena").get(i).getAsInt();
		return new Config(host, port, json.has("rconPassword") ? json.get("rconPassword").getAsString() : rconPassword,
			json.has("serverLog") ? Path.of(json.get("serverLog").getAsString()) : serverLog,
			json.has("dimension") ? json.get("dimension").getAsString() : dimension, arena[0], arena[1], arena[2],
			json.has("freshSessionAfterFlags") ? json.get("freshSessionAfterFlags").getAsBoolean() : freshSessionAfterFlags,
			json.has("strict") ? json.get("strict").getAsBoolean() : strict,
			json.has("answerClicks") ? json.get("answerClicks").getAsString() : answerClicks);
	}
}
