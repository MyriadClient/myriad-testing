package dev.myriadclient.testing.runner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.testing.GrimFlag;
import dev.myriadclient.testing.Test;
import dev.myriadclient.testing.TestContext;
import dev.myriadclient.testing.TestFailure;
import dev.myriadclient.testing.TestSkipped;
import dev.myriadclient.testing.TestSuite;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Finds the test suites every loaded mod registers ({@code myriad-tests} entrypoints) and runs tests one after another
 * on a thread of their own. Around each: every module's settings and state noted and every module turned off (all put
 * back after), the arena reset and the player put in it with nothing, the suite's {@code beforeEach}, Grim's flags read, and when one fails, a report (its steps, what the game said,
 * the player tick by tick, the packets, Grim's flags, a screenshot) under {@code <game dir>/myriad-testing/reports}.
 */
public final class Runner {
	static final Logger LOG = LoggerFactory.getLogger("Myriad/Testing");
	private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	/** One test: its id, the suite instance and method, and its annotation. */
	public record Case(String id, TestSuite suite, Method method, Test test) {
	}

	/**
	 * How one test went: passed or not (a skipped test counts as passed), the result line or failure, how long, Grim's
	 * flags, the report written.
	 */
	public record Result(String id, boolean ok, boolean skipped, String detail, long millis, long ticks, List<String> flags,
	                     List<String> lastSteps, @Nullable String report) {
		public JsonObject json() {
			JsonObject o = new JsonObject();
			o.addProperty("type", "result");
			o.addProperty("id", id);
			o.addProperty("ok", ok);
			o.addProperty("skipped", skipped);
			o.addProperty("detail", detail);
			o.addProperty("millis", millis);
			o.addProperty("ticks", ticks);
			JsonArray f = new JsonArray();
			flags.forEach(f::add);
			o.add("flags", f);
			JsonArray s = new JsonArray();
			lastSteps.forEach(s::add);
			o.add("lastSteps", s);
			if (report != null) o.addProperty("report", report);
			return o;
		}
	}

	/** Checks 2b2t's Grim flags but doesn't act on (measured there): placing in the air, and against any face. */
	private static final Set<String> TWO_B2T_IGNORES = Set.of("AirLiquidPlace", "PositionPlace");

	private final Minecraft mc = Minecraft.getInstance();
	private final TickClock clock;
	private final Inputs inputs;
	private final Recorder recorder;
	private final GameLog gameLog;
	private final Journal journal = new Journal();
	private volatile boolean running;
	private volatile @Nullable Thread thread;

	public Runner(TickClock clock, Inputs inputs, Recorder recorder, GameLog gameLog) {
		this.clock = clock;
		this.inputs = inputs;
		this.recorder = recorder;
		this.gameLog = gameLog;
	}

	/** Every test of every loaded suite, by id. */
	public List<Case> tests() {
		List<Case> cases = new ArrayList<>();
		for (EntrypointContainer<TestSuite> c : FabricLoader.getInstance().getEntrypointContainers("myriad-tests", TestSuite.class)) {
			String mod = c.getProvider().getMetadata().getId();
			TestSuite suite;
			try {
				suite = c.getEntrypoint();
			} catch (RuntimeException e) {
				LOG.error("Test suite of {} couldn't be made", mod, e);
				continue;
			}
			for (Method m : suite.getClass().getMethods()) {
				Test t = m.getAnnotation(Test.class);
				if (t == null || m.getParameterCount() != 1 || m.getParameterTypes()[0] != TestContext.class || Modifier.isStatic(m.getModifiers())) continue;
				String name = t.name().isEmpty() ? m.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toLowerCase(Locale.ROOT) : t.name();
				cases.add(new Case(mod + ":" + suite.name() + "/" + name, suite, m, t));
			}
		}
		cases.sort(Comparator.comparing(Case::id));
		return cases;
	}

	/**
	 * Tests named by any of {@code filters} (all with none) that carry every tag in {@code tags}. A filter with a
	 * {@code /} that is the end of some test's id ("scaffold/tower") names just those; any other names every test whose id
	 * contains it ("scaffold", "tower" for tower_2b2t and tower_rotate too).
	 */
	public List<Case> select(List<String> filters, List<String> tags) {
		List<Case> all = tests(), out = new ArrayList<>();
		List<String> exact = filters.stream().filter(f -> f.contains("/") && all.stream().anyMatch(c -> endsAt(c.id(), f))).toList();
		for (Case c : all) {
			boolean named = filters.isEmpty() || filters.stream().anyMatch(f -> exact.contains(f) ? endsAt(c.id(), f) : c.id().contains(f));
			boolean tagged = tags.stream().allMatch(t -> List.of(c.test().tags()).contains(t));
			if (named && tagged) out.add(c);
		}
		return out;
	}

	/** Whether {@code id} ends with {@code name} from the start of one of its parts. */
	private static boolean endsAt(String id, String name) {
		if (!id.endsWith(name)) return false;
		int at = id.length() - name.length();
		return at == 0 || id.charAt(at - 1) == ':' || id.charAt(at - 1) == '/';
	}

	public boolean isRunning() {
		return running;
	}

	/** Stops the run after the current test (which is interrupted). */
	public void stop() {
		running = false;
		Thread t = thread;
		if (t != null) t.interrupt();
	}

	/**
	 * Runs {@code cases} on a thread of their own, telling {@code each} as each finishes and {@code done} at the end. False
	 * (nothing started) if a run is going already.
	 */
	public synchronized boolean start(List<Case> cases, Config config, Consumer<Result> each, Consumer<List<Result>> done) {
		if (running) return false;
		running = true;
		Thread t = new Thread(() -> {
			List<Result> results = new ArrayList<>();
			Path reports = mc.gameDirectory.toPath().resolve("myriad-testing").resolve("reports").resolve(LocalDateTime.now().format(RUN_ID));
			try (Rcon rcon = new Rcon(config.rconHost(), config.rconPort(), config.rconPassword())) {
				GrimLog grim = new GrimLog(config.serverLog(), config.strict());
				boolean prepared = false;
				for (Case c : cases) {
					if (!running) break;
					if (!prepared) {
						prepare(rcon, config);
						prepared = true;
					}
					Result r = runOne(c, config, rcon, grim, reports);
					results.add(r);
					each.accept(r);
				}
			} catch (RuntimeException e) {
				LOG.error("Test run failed", e);
			} finally {
				running = false;
				thread = null;
				done.accept(results);
			}
		}, "Myriad test runner");
		t.setDaemon(true);
		thread = t;
		t.start();
		return true;
	}

	/** Keeps the arena's chunks loaded on the server (once a run) and waits until they are. */
	private void prepare(Rcon rcon, Config config) {
		String in = config.dimension() == null ? "" : "execute in " + config.dimension() + " run ";
		int x1 = config.arenaX() + TestContext.Arena.MIN_X - 16, z1 = config.arenaZ() + TestContext.Arena.MIN_Z - 16;
		int x2 = config.arenaX() + TestContext.Arena.MAX_X + 16, z2 = config.arenaZ() + TestContext.Arena.MAX_Z + 16;
		rcon.run(in + "forceload add " + x1 + " " + z1 + " " + x2 + " " + z2);
		String check = (config.dimension() == null ? "execute " : "execute in " + config.dimension() + " ") + "if loaded " + config.arenaX() + " " + config.arenaY() + " "
			+ config.arenaZ();
		for (int i = 0; i < 60 && !rcon.run(check).contains("passed"); i++) sleep(250);
		// Myriad's server kit answers inventory clicks as 2b2t does when asked (its AnswerClicks plugin); off unless the run says.
		String answers = rcon.run("answerclicks " + config.answerClicks());
		if (answers.contains("Unknown")) LOG.info("The server has no answerclicks command (not Myriad's server kit)");
	}

	private Result runOne(Case c, Config config, Rcon rcon, GrimLog grim, Path reports) {
		long started = System.currentTimeMillis(), startTick = clock.now();
		TestContext t = new TestContext(c.id(), config, rcon, clock, inputs, recorder, grim);
		String failure = null, skipped = null;
		recorder.begin();
		gameLog.begin();
		try {
			t.onGame(() -> {
				if (mc.player != null && mc.player.isDeadOrDying()) mc.player.respawn();
			});
			// A world still loading (after a rejoin or a respawn) is left to finish: closing its screen early means the
			// client never tells the server it has loaded, and the server then ignores its clicks and sets every move back.
			t.waitUntil("the world has loaded", () -> !(mc.gui.screen() instanceof LevelLoadingScreen) && !(mc.gui.screen() instanceof DeathScreen), 600);
			t.onGame(() -> {
				journal.snapshot();
				for (Module m : List.copyOf(Myriad.modules().enabled())) m.setEnabled(false);
				if (mc.gui.screen() != null) mc.gui.setScreen(null);
			});
			// In creative for the way back: a fall the last test left you in (an elytra flight) would kill you on landing.
			t.server().run("gamemode creative " + t.player().name());
			t.arena().reset();
			t.player().at(0.5, 0, 0.5);
			t.player().fresh();
			c.suite().beforeEach(t);
			grim.mark();
			failure = invoke(c, t);
		} catch (TestSkipped e) {
			skipped = e.getMessage();
		} catch (TestFailure e) {
			failure = "setting up: " + e.getMessage();
		} catch (RuntimeException e) {
			failure = "setting up: " + e;
		}
		inputs.release();
		try {
			t.onGame(journal::restore);
		} catch (RuntimeException e) {
			LOG.error("Couldn't put the modules back after {}", c.id(), e);
		}
		recorder.end();
		gameLog.end();
		// Myriad or an addon logged an error: a handler threw (and may now be switched off) or a service failed. That's
		// the failure to see, whatever the test's checks made of what followed.
		if (skipped == null && gameLog.firstError() != null) {
			failure = "the game logged an error: " + gameLog.firstError() + (failure != null ? " (then: " + failure + ")" : "");
		}
		String player = t.player().name();
		List<GrimFlag> flagged = grim.flags(player);
		List<String> flags = flagged.stream().map(GrimFlag::toString).toList();
		if (failure == null && skipped == null && !c.test().anyFlags()) {
			Set<String> allowed = new HashSet<>(List.of(c.test().allowFlags()));
			if (!config.strict()) allowed.addAll(TWO_B2T_IGNORES);
			List<String> bad = flagged.stream().map(GrimFlag::check).filter(f -> !allowed.contains(f)).distinct().toList();
			if (!bad.isEmpty()) failure = "Grim flagged " + String.join(", ", bad) + (t.detail() != null ? " (" + t.detail() + ")" : "");
		}
		Recorder.Record record = recorder.record();
		String report = null;
		if (failure != null) report = writeReport(c, t, failure, flags, record, reports);
		if (t.lenient()) {
			rcon.run("op " + player);
			rcon.run("grim reload");
		}
		if (t.lenient() || !flags.isEmpty() && config.freshSessionAfterFlags() && config.serverLog() != null) freshSession(rcon, player);
		List<String> steps = record.steps();
		List<String> last = steps.subList(Math.max(0, steps.size() - 12), steps.size());
		String detail = failure != null ? failure : skipped != null ? "skipped: " + skipped : t.detail() != null ? t.detail() : "passed";
		return new Result(c.id(), failure == null, skipped != null, detail, System.currentTimeMillis() - started, clock.now() - startTick, flags,
			last, report);
	}

	/** Runs the test, interrupting it past its time; null if it passed, else why not. */
	private @Nullable String invoke(Case c, TestContext t) {
		Thread self = Thread.currentThread();
		long deadline = clock.now() + c.test().timeoutTicks();
		boolean[] timedOut = {false};
		Thread watchdog = new Thread(() -> {
			try {
				clock.await(deadline, c.test().timeoutTicks() * 200L + 10000);
				timedOut[0] = true;
				self.interrupt();
			} catch (InterruptedException ignored) {
				// The test finished first.
			}
		}, "Myriad test watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		try {
			c.method().invoke(c.suite(), t);
			return null;
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (timedOut[0]) return "timed out after " + c.test().timeoutTicks() + " ticks";
			if (cause instanceof TestSkipped s) throw s;
			if (cause instanceof TestFailure f) return f.getMessage();
			LOG.error("Test {} threw", c.id(), cause);
			return cause.toString() + where(cause);
		} catch (IllegalAccessException e) {
			return "can't call it: " + e;
		} finally {
			watchdog.interrupt();
			Thread.interrupted();
		}
	}

	/** " at Suite.java:42": where in the test code it threw. */
	private static String where(Throwable t) {
		for (StackTraceElement e : t.getStackTrace()) {
			if (!e.getClassName().startsWith("java.") && !e.getClassName().startsWith("jdk.")) return " at " + e.getFileName() + ":" + e.getLineNumber();
		}
		return "";
	}

	/**
	 * A new Grim session for the next test: Grim's state after flags (a rotation check left suspicious, a setback
	 * pending) would judge the next test by this one.
	 */
	private void freshSession(Rcon rcon, String player) {
		recorder.step("fresh Grim session");
		rcon.run("kick " + player + " fresh Grim session");
		for (int i = 0; i < 40 && mc.player != null; i++) sleep(100);
		// Joined again as soon as the server lets you: a server with a connection throttle (Bukkit's 4 s by default)
		// turns you away until it's over, and you're tried again.
		long until = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < until) {
			mc.execute(() -> Myriad.server().reconnect());
			sleep(300);
			while (System.currentTimeMillis() < until && mc.player == null && !(mc.gui.screen() instanceof DisconnectedScreen)) sleep(100);
			if (mc.player != null && mc.level != null) {
				// Done loading (the server takes moves and clicks only then), with the chunks around you.
				for (int i = 0; i < 300 && mc.gui.screen() instanceof LevelLoadingScreen; i++) sleep(100);
				return;
			}
			sleep(500);
		}
		LOG.warn("Couldn't rejoin after a fresh Grim session");
	}

	private @Nullable String writeReport(Case c, TestContext t, String failure, List<String> flags, Recorder.Record r, Path dir) {
		try {
			Files.createDirectories(dir);
			String file = c.id().replaceAll("[^A-Za-z0-9._-]", "_");
			StringBuilder b = new StringBuilder();
			b.append("# ").append(c.id()).append("\n\nFAILED: ").append(failure).append("\n\n");
			b.append("## Grim flags\n\n").append(flags.isEmpty() ? "none\n" : String.join("\n", flags) + "\n");
			b.append("\n## Steps\n\n").append(String.join("\n", r.steps())).append("\n");
			b.append("\n## Warnings and errors in the game log\n\n").append(gameLog.lines().isEmpty() ? "none\n" : String.join("\n", gameLog.lines()) + "\n");
			b.append("\n## What the game said\n\n").append(r.events().isEmpty() ? "nothing\n" : String.join("\n", r.events()) + "\n");
			b.append("\n## The player, tick by tick\n\n").append(String.join("\n", r.ticks())).append("\n");
			b.append("\n## Packets\n\n").append(String.join("\n", r.packets())).append("\n");
			Path report = dir.resolve(file + ".md");
			Files.writeString(report, b.toString());
			try {
				t.onGame(() -> Screenshot.grab(dir.toFile(), file + ".png", mc.gameRenderer.mainRenderTarget(), 1, m -> {
				}));
			} catch (RuntimeException e) {
				LOG.warn("No screenshot for {}", c.id(), e);
			}
			return report.toString();
		} catch (IOException e) {
			LOG.error("Couldn't write the report for {}", c.id(), e);
			return null;
		}
	}

	private static void sleep(long ms) {
		try {
			TimeUnit.MILLISECONDS.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
