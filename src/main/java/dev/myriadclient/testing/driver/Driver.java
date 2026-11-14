package dev.myriadclient.testing.driver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.myriadclient.testing.runner.Config;
import dev.myriadclient.testing.runner.Runner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * The runner's socket, on localhost only: one JSON object per line each way, for the {@code myriad-test} command line
 * and agents. Requests: {@code {"cmd":"ping"}}, {@code {"cmd":"list","filter":[...]}}, {@code {"cmd":"run","filter":[...],
 * "tags":[...],"config":{...}}} (answered with a {@code result} line per test as it finishes, then {@code done}),
 * {@code {"cmd":"stop"}}, {@code {"cmd":"state"}} (the player, the screen and the modules on, as data) and
 * {@code {"cmd":"info"}} (what the command line needs to hot-reload this client: its class folders and debug port). The port is {@code -Dmyriad.test.port} (25610 by default; another client takes the next one free).
 */
public final class Driver {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Testing");

	private final Runner runner;
	private int port;

	public Driver(Runner runner) {
		this.runner = runner;
	}

	/** Listens from the first free port from {@code first} on, in the background. */
	public void start(int first) {
		Thread t = new Thread(() -> {
			for (int p = first; p < first + 10; p++) {
				try (ServerSocket server = new ServerSocket(p, 8, InetAddress.getLoopbackAddress())) {
					port = p;
					LOG.info("Test driver listening on localhost:{}", p);
					while (!server.isClosed()) {
						Socket s = server.accept();
						Thread c = new Thread(() -> serve(s), "Myriad test driver client");
						c.setDaemon(true);
						c.start();
					}
					return;
				} catch (IOException e) {
					// Taken by another client: the next port.
				}
			}
			LOG.warn("Test driver found no free port from {}", first);
		}, "Myriad test driver");
		t.setDaemon(true);
		t.start();
	}

	public int port() {
		return port;
	}

	private void serve(Socket socket) {
		try (socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		     PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) continue;
				JsonObject req;
				try {
					req = JsonParser.parseString(line).getAsJsonObject();
				} catch (RuntimeException e) {
					out.println(error("not JSON: " + e.getMessage()));
					continue;
				}
				handle(req, out);
			}
		} catch (IOException e) {
			// The other side went away.
		}
	}

	private void handle(JsonObject req, PrintWriter out) {
		String cmd = req.has("cmd") ? req.get("cmd").getAsString() : "";
		switch (cmd) {
			case "ping" -> {
				JsonObject o = new JsonObject();
				o.addProperty("type", "pong");
				o.addProperty("running", runner.isRunning());
				out.println(o);
			}
			case "list" -> {
				JsonObject o = new JsonObject();
				o.addProperty("type", "tests");
				JsonArray tests = new JsonArray();
				for (Runner.Case c : runner.select(strings(req, "filter"), strings(req, "tags"))) {
					JsonObject t = new JsonObject();
					t.addProperty("id", c.id());
					JsonArray tags = new JsonArray();
					for (String tag : c.test().tags()) tags.add(tag);
					t.add("tags", tags);
					tests.add(t);
				}
				o.add("tests", tests);
				out.println(o);
			}
			case "run" -> run(req, out);
			case "state" -> out.println(State.now());
			case "info" -> out.println(State.info(port));
			case "stop" -> {
				runner.stop();
				JsonObject o = new JsonObject();
				o.addProperty("type", "stopping");
				out.println(o);
			}
			default -> out.println(error("unknown cmd '" + cmd + "' (ping, list, run, stop, state, info)"));
		}
	}

	private void run(JsonObject req, PrintWriter out) {
		List<Runner.Case> cases = runner.select(strings(req, "filter"), strings(req, "tags"));
		Config config = Config.defaults().with(req.has("config") && req.get("config").isJsonObject() ? req.getAsJsonObject("config") : null);
		CountDownLatch finished = new CountDownLatch(1);
		long started = System.currentTimeMillis();
		boolean ok = runner.start(cases, config, r -> {
			synchronized (out) {
				out.println(r.json());
			}
		}, results -> {
			JsonObject o = new JsonObject();
			o.addProperty("type", "done");
			o.addProperty("passed", results.stream().filter(Runner.Result::ok).count());
			o.addProperty("failed", results.stream().filter(r -> !r.ok()).count());
			o.addProperty("millis", System.currentTimeMillis() - started);
			synchronized (out) {
				out.println(o);
			}
			finished.countDown();
		});
		if (!ok) {
			out.println(error("a run is going already"));
			return;
		}
		try {
			finished.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static List<String> strings(JsonObject req, String key) {
		List<String> out = new ArrayList<>();
		JsonElement e = req.get(key);
		if (e == null) return out;
		if (e.isJsonArray()) for (JsonElement s : e.getAsJsonArray()) out.add(s.getAsString());
		else out.add(e.getAsString());
		return out;
	}

	private static String error(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "error");
		o.addProperty("message", message);
		return o.toString();
	}
}
