package dev.myriadclient.testing;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.dev.DevSteps;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.testing.runner.Config;
import dev.myriadclient.testing.runner.GrimLog;
import dev.myriadclient.testing.runner.Inputs;
import dev.myriadclient.testing.runner.Rcon;
import dev.myriadclient.testing.runner.Recorder;
import dev.myriadclient.testing.runner.TickClock;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything a test does, from its own thread: set the scene on the server, put the player somewhere with something in
 * hand, switch modules and their settings, hold keys, wait for the game, and check the outcome against the server's
 * truth. Calls that touch the game go to the render thread and wait for it; waits park the test until the tick they
 * wait for. Every step is written into the test's record, which its report shows if it fails.
 * <p>
 * Positions are relative to the test's {@link #arena()} unless a method says otherwise, so tests never collide and can
 * run anywhere. Settings and modules are put back after every test, so nothing needs undoing at the end.
 */
public final class TestContext {
	private static final Pattern POS = Pattern.compile("\\[(-?[\\d.E-]+)d, (-?[\\d.E-]+)d, (-?[\\d.E-]+)d]");

	private final String name;
	private final Config config;
	private final Rcon rcon;
	private final TickClock clock;
	private final Inputs inputs;
	private final Recorder recorder;
	private final GrimLog grim;
	private final Minecraft mc = Minecraft.getInstance();
	private final Server server = new Server();
	private final Arena arena = new Arena();
	private final PlayerControl player = new PlayerControl();
	private @Nullable String detail;

	/** The runner's: a context for the test named {@code name}. */
	public TestContext(String name, Config config, Rcon rcon, TickClock clock, Inputs inputs, Recorder recorder, GrimLog grim) {
		this.name = name;
		this.config = config;
		this.rcon = rcon;
		this.clock = clock;
		this.inputs = inputs;
		this.recorder = recorder;
		this.grim = grim;
	}

	/** The test's id ({@code mod:suite/test}). */
	public String name() {
		return name;
	}

	/** The test server: commands, and checks on what it holds. */
	public Server server() {
		return server;
	}

	/** The test's own space on the server: a floor at y 0, air above, cleared before every test. */
	public Arena arena() {
		return arena;
	}

	/** The local player: where they stand, what they hold. */
	public PlayerControl player() {
		return player;
	}

	/** A module by id ({@code "myriad-essentials:scaffold"}) or name ("Scaffold"). */
	public ModuleControl module(String idOrName) {
		Module m = onGame(() -> find(idOrName).orElse(null));
		if (m == null) throw new TestFailure("no module " + idOrName);
		return new ModuleControl(m);
	}

	/** The module of exactly {@code type}. */
	public <M extends Module> ModuleControl module(Class<M> type) {
		M m = onGame(() -> Myriad.modules().getOrNull(type));
		if (m == null) throw new TestFailure("no module " + type.getSimpleName() + " registered");
		return new ModuleControl(m);
	}

	private static Optional<Module> find(String idOrName) {
		if (idOrName.contains(":")) {
			Optional<Module> byId = Myriad.modules().get(MyriadId.parse(idOrName, "myriad"));
			if (byId.isPresent()) return byId;
		}
		return Myriad.modules().byName(idOrName);
	}

	/** Holds {@code keys} (as if pressed on the keyboard); say how long with {@link Hold#forTicks}. */
	public Hold hold(Key... keys) {
		return new Hold(keys);
	}

	/**
	 * Runs core's dev console steps in order, each after the last is done ({@code "mouse 0 click"}, {@code "key e click"},
	 * {@code "cursor 120 80"}, {@code "tab 1"}; see {@code DevSteps} in core), and returns what the last one reported
	 * ({@code "tabs"} answers the shelf's tabs). For what the test API has no method of its own for: screens, the UI,
	 * real mouse and key input.
	 */
	public List<String> step(String... steps) {
		DevSteps dev = Myriad.devSteps().orElseThrow(() -> new TestFailure("core's dev steps aren't available (not a dev run)"));
		List<String> said = List.of();
		for (String step : steps) {
			try {
				// A step that never finishes is core's console not running (its handler failed): a failure, not a hang.
				// Long steps (wait, selftest, frametime) get the rest of the test's time.
				boolean longStep = step.startsWith("wait ") || step.startsWith("selftest") || step.startsWith("frametime");
				said = longStep ? dev.run(step).get() : dev.run(step).get(30, TimeUnit.SECONDS);
			} catch (TimeoutException e) {
				recorder.step("step " + step + "  -> never finished");
				throw new TestFailure("step '" + step + "' never finished in 30 s: is core's dev console still running? (see the game log)");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new TestFailure("interrupted at step '" + step + "'");
			} catch (ExecutionException e) {
				recorder.step("step " + step + "  -> FAILED: " + e.getCause().getMessage());
				throw new TestFailure("step '" + step + "' failed: " + e.getCause().getMessage());
			}
			recorder.step("step " + step + (said.isEmpty() ? "" : "  -> " + String.join(" | ", said)));
		}
		return said;
	}

	/** Waits {@code ticks} client ticks. */
	public void waitTicks(int ticks) {
		recorder.step("wait " + ticks + " ticks");
		awaitTick(clock.now() + ticks);
	}

	/**
	 * Waits until {@code condition} (checked on the render thread after each tick) holds, at most {@code maxTicks}; whether
	 * it did.
	 */
	public boolean waitUntil(String what, BooleanSupplier condition, int maxTicks) {
		long until = clock.now() + maxTicks;
		while (clock.now() < until) {
			if (onGame(condition::getAsBoolean)) {
				recorder.step("waited until " + what);
				return true;
			}
			awaitTick(clock.now() + 1);
		}
		recorder.step("gave up waiting until " + what + " after " + maxTicks + " ticks");
		return false;
	}

	private void awaitTick(long target) {
		try {
			if (!clock.await(target, Math.max(5000, (target - clock.now()) * 200))) throw new TestFailure("the game stopped ticking");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TestFailure("stopped");
		}
	}

	/** {@code task} on the render thread, waiting for its result. */
	public <T> T onGame(Supplier<T> task) {
		if (mc.isSameThread()) return task.get();
		try {
			return mc.submit(task).get(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TestFailure("stopped");
		} catch (Exception e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof TestFailure f) throw f;
			throw new TestFailure("on the game's thread: " + cause);
		}
	}

	/** {@code task} on the render thread, waiting for it. */
	public void onGame(Runnable task) {
		onGame(() -> {
			task.run();
			return null;
		});
	}

	/**
	 * Fails the test unless {@code ok}. {@code message} says what was found, worded to read right either way ("6/6
	 * blocks under the path"); a failure shows it as "check failed: ...".
	 */
	public void check(boolean ok, String message) {
		recorder.step((ok ? "ok: " : "FAILED: ") + message);
		if (!ok) throw new TestFailure("check failed: " + message);
	}

	/** Ends the test as skipped, with {@code reason} as its result line: it can't run in this client. */
	public void skip(String reason) {
		recorder.step("skipped: " + reason);
		throw new TestSkipped(reason);
	}

	/** Whether the mod {@code id} ("baritone", "litematica") is loaded in this client. */
	public boolean hasMod(String id) {
		return FabricLoader.getInstance().isModLoaded(id);
	}

	/** Notes {@code what} in the test's record (and, last one, as its result line when it passes). */
	public void note(String what) {
		recorder.step("note: " + what);
		detail = what;
	}

	/** The last {@link #note}, the result line shown for a test that passed. */
	public @Nullable String detail() {
		return detail;
	}

	/**
	 * The chat lines the client got since the test began, oldest first, as plain text: the server's and Myriad's own
	 * ({@code "[Myriad] Scaffold enabled"}, a command's answer).
	 */
	public List<String> chat() {
		return recorder.chat();
	}

	/** Grim's flags for this player since the test started (or since {@link #flagsFromHere}). */
	public List<GrimFlag> flags() {
		return grim.flags(player.name());
	}

	/**
	 * Grim's flags count from here, for {@link #flags}, {@link #noFlags} and the check after the test: what the setup
	 * so far did (teleports, scenes) isn't the test's.
	 */
	public void flagsFromHere() {
		recorder.step("Grim's flags count from here");
		grim.mark();
	}

	/** Fails if Grim flagged anything since the test started, apart from the checks named in {@code allowed}. */
	public void noFlags(String... allowed) {
		List<String> ok = Arrays.asList(allowed);
		List<GrimFlag> bad = new ArrayList<>();
		for (GrimFlag f : flags()) if (!ok.contains(f.check())) bad.add(f);
		check(bad.isEmpty(), bad.isEmpty() ? "no Grim flags" + (allowed.length > 0 ? " but " + ok : "") : "Grim flagged " + bad);
	}

	/** Commands to the test server, and checks on what it holds. */
	public final class Server {
		/** Runs {@code command} (no slash) on the server, in this client's dimension, and returns its answer. */
		public String run(String command) {
			String c = config.dimension() == null || command.startsWith("execute in ") || !inDimension(command) ? command
				: command.startsWith("execute ") ? "execute in " + config.dimension() + " " + command.substring(8)
				: "execute in " + config.dimension() + " run " + command;
			String answer = rcon.run(c);
			recorder.step("/" + command + (answer.isBlank() ? "" : "  -> " + abbreviate(answer)));
			return answer;
		}

		/** Commands about places, which run in this client's dimension (op, gamerule and the like don't need one). */
		private static boolean inDimension(String command) {
			return !command.startsWith("op ") && !command.startsWith("deop ") && !command.startsWith("gamerule ") && !command.startsWith("grim ")
				&& !command.startsWith("kick ") && !command.startsWith("say ");
		}

		/** Whether an {@code execute if|unless ...} test passes on the server ({@code "if block 0 0 0 stone"}). */
		public boolean passes(String condition) {
			return run("execute " + condition).contains("passed");
		}

		/**
		 * Lets this player's placements through as 2b2t's Grim does (air placements, faces you can't see, no turn to
		 * the block), flagged but not cancelled, until the test ends: deopped, so the server's {@code myriad.lenientplace}
		 * group applies. The session that follows is fresh, as those flags leave Grim checking later placements oddly.
		 */
		public void likeTwoBTwoT() {
			run("deop " + player.name());
			run("grim reload");
			lenient = true;
		}
	}

	private volatile boolean lenient;

	/** The runner's: whether the test asked for 2b2t's placement leniency, to be undone after it. */
	public boolean lenient() {
		return lenient;
	}

	/**
	 * The test's place on the server, in this client's dimension. Coordinates are relative to its origin, where every
	 * test starts (standing at 0.5 0 0.5, facing -x): y 0 is standing on the floor. Before every test the box from
	 * {@link #MIN_X} to {@link #MAX_X}, {@link #MIN_Z} to {@link #MAX_Z} and 0 to {@link #TOP} is cleared to air on a
	 * stone floor (y -1), its mobs and items gone, and kept loaded. A test may build anywhere else as well (a highway a
	 * thousand blocks out): {@link #forceload} that first, and clear it up itself.
	 */
	public final class Arena {
		/** The box cleared before every test, relative to the origin (inclusive). */
		public static final int MIN_X = -24, MAX_X = 47, MIN_Z = -24, MAX_Z = 23, TOP = 23;

		/** The absolute x of {@code dx} in the arena. */
		public int x(double dx) {
			return config.arenaX() + (int) Math.floor(dx);
		}

		/** The absolute y of {@code dy} in the arena (0: standing on its floor). */
		public int y(double dy) {
			return config.arenaY() + (int) Math.floor(dy);
		}

		/** The absolute z of {@code dz} in the arena. */
		public int z(double dz) {
			return config.arenaZ() + (int) Math.floor(dz);
		}

		/** A relative position as absolute coordinates for a command ({@code "30003.5 100.0 0.5"}), to {@code summon} at. */
		public String at(double x, double y, double z) {
			return coord(config.arenaX() + x) + " " + coord(config.arenaY() + y) + " " + coord(config.arenaZ() + z);
		}

		/** A relative block position as absolute coordinates for a command ({@code "30003 99 0"}). */
		public String block(int x, int y, int z) {
			return x(x) + " " + y(y) + " " + z(z);
		}

		/** Its floor (the layer under y 0, across the cleared box) made of {@code block} ("stone"). */
		public Arena floor(String block) {
			fillAbsolute(x(MIN_X), y(-1), z(MIN_Z), x(MAX_X), y(-1), z(MAX_Z), block);
			return this;
		}

		/** Fills the box between two corners (relative, inclusive, any size: it's split for the server) with {@code block}. */
		public Arena fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
			fillAbsolute(x(x1), y(y1), z(z1), x(x2), y(y2), z(z2), block);
			return this;
		}

		/** Sets the block at a relative position ({@code "chest[facing=north]"}, {@code "chest{Items:[...]}"}). */
		public Arena set(int x, int y, int z, String block) {
			server.run("setblock " + block(x, y, z) + " " + block);
			return this;
		}

		/** Whether the block at a relative position is {@code block} on the server ({@code "dirt"}, {@code "#minecraft:logs"}). */
		public boolean is(int x, int y, int z, String block) {
			return server.passes("if block " + block(x, y, z) + " " + block);
		}

		/** How many of the positions between two corners (relative, inclusive) hold {@code block} on the server. */
		public int count(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
			int n = 0;
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
				for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
					for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) if (is(x, y, z, block)) n++;
			return n;
		}

		/**
		 * A block entity's data at {@code path} ({@code "Items[0].id"}), as the server prints it ("" if there's none). The
		 * server cuts a long answer short (about 100 characters, ending "..."): ask for the field wanted.
		 */
		public String data(int x, int y, int z, String path) {
			return entityData(server.run("data get block " + block(x, y, z) + " " + path));
		}

		/**
		 * What the container at a relative position holds, slot by slot: {@code "dirt x64"} ("" for an empty slot). Read a
		 * field at a time (the server cuts a long answer short, a chest's whole list or a box's contents).
		 */
		public List<String> items(int x, int y, int z, int size) {
			String target = "block " + block(x, y, z);
			String[] slots = new String[size];
			Arrays.fill(slots, "");
			for (int i = 0; i < 64; i++) {
				String id = field(target, "Items[" + i + "].id");
				if (id.isEmpty()) break;
				String slot = field(target, "Items[" + i + "].Slot");
				// Changed between the two reads (something moving items now): the entry is read as gone.
				if (slot.isEmpty()) continue;
				int at = Integer.parseInt(slot.replace("b", ""));
				String count = field(target, "Items[" + i + "].count");
				if (at >= 0 && at < size) slots[at] = id.replace("minecraft:", "") + " x" + (count.isEmpty() ? "1" : count);
			}
			recorder.step("items at " + block(x, y, z) + ": " + String.join(", ", Arrays.stream(slots).filter(t -> !t.isEmpty()).toList()));
			return List.of(slots);
		}

		/**
		 * Keeps the chunks over a relative area loaded and waits until the server has them all (a new world generates
		 * them first, and a fill that reaches one that isn't in yet fails). The arena's own box always is.
		 */
		public Arena forceload(int x1, int z1, int x2, int z2) {
			int ax1 = x(Math.min(x1, x2)) >> 4, ax2 = x(Math.max(x1, x2)) >> 4, az1 = z(Math.min(z1, z2)) >> 4, az2 = z(Math.max(z1, z2)) >> 4;
			for (int cx = ax1; cx <= ax2; cx++)
				for (int cz = az1; cz <= az2; cz++) server.run("forceload add " + (cx << 4) + " " + (cz << 4));
			for (int cx = ax1; cx <= ax2; cx++) {
				for (int cz = az1; cz <= az2; cz++) {
					String check = "if loaded " + (cx << 4) + " " + config.arenaY() + " " + (cz << 4);
					long until = System.currentTimeMillis() + 30_000;
					while (!server.passes(check)) {
						if (System.currentTimeMillis() > until) throw new TestFailure("the server never loaded the chunk at " + (cx << 4) + " " + (cz << 4));
						waitTicks(2);
					}
				}
			}
			return this;
		}

		/** Lets the chunks over a relative area go again ({@link #forceload}'s); the arena's own stay loaded. */
		public Arena unload(int x1, int z1, int x2, int z2) {
			int ax1 = x(Math.min(x1, x2)) >> 4, ax2 = x(Math.max(x1, x2)) >> 4, az1 = z(Math.min(z1, z2)) >> 4, az2 = z(Math.max(z1, z2)) >> 4;
			int bx1 = x(MIN_X - 16) >> 4, bx2 = x(MAX_X + 16) >> 4, bz1 = z(MIN_Z - 16) >> 4, bz2 = z(MAX_Z + 16) >> 4;
			for (int cx = ax1; cx <= ax2; cx++)
				for (int cz = az1; cz <= az2; cz++)
					if (cx < bx1 || cx > bx2 || cz < bz1 || cz > bz2) server.run("forceload remove " + (cx << 4) + " " + (cz << 4));
			return this;
		}

		/** Air everywhere in the box and a stone floor, its items and mobs gone: as every test finds it. */
		public void reset() {
			fillAbsolute(x(MIN_X), y(0), z(MIN_Z), x(MAX_X), y(TOP), z(MAX_Z), "air");
			floor("stone");
			server.run(String.format(Locale.ROOT, "kill @e[type=!player,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]", x(MIN_X - 8), y(-8), z(MIN_Z - 8),
				MAX_X - MIN_X + 16, TOP + 16, MAX_Z - MIN_Z + 16));
		}

		/** Fills in slabs small enough for the server's limit on one fill. */
		private void fillAbsolute(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
			int xs = Math.abs(x2 - x1) + 1, zs = Math.abs(z2 - z1) + 1;
			if (xs * zs > 32768) {
				// Too wide for one layer: strips along x.
				int step = Math.max(1, 32768 / zs);
				for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x += step)
					fillAbsolute(x, y1, z1, Math.min(Math.max(x1, x2), x + step - 1), y2, z2, block);
				return;
			}
			int layers = Math.max(1, 32768 / (xs * zs));
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y += layers) {
				int top = Math.min(Math.max(y1, y2), y + layers - 1);
				server.run("fill " + x1 + " " + y + " " + z1 + " " + x2 + " " + top + " " + z2 + " " + block);
			}
		}
	}

	/**
	 * A coordinate for a command, always with a decimal point: commands take a whole number for a block's middle
	 * ({@code tp ... 1} lands at 1.5), a decimal as it is.
	 */
	private static String coord(double v) {
		return String.format(Locale.ROOT, "%.3f", v).replaceAll("(\\.\\d*?)0+$", "$1").replaceAll("\\.$", ".0");
	}

	/**
	 * One value from {@code data get <target> <path>}, unquoted ("" if there's none), read without a step in the record:
	 * callers note what they found.
	 */
	private String field(String target, String path) {
		String command = "data get " + target + " " + path;
		String answer = rcon.run(config.dimension() == null || target.startsWith("entity ") ? command : "execute in " + config.dimension() + " run " + command);
		String value = entityData(answer);
		return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
	}

	/** What {@code data get} printed after "has the following entity data:" ("" if nothing). */
	private static String entityData(String answer) {
		int at = answer.indexOf("data: ");
		return at < 0 ? "" : answer.substring(at + 6).trim();
	}

	/** The local player. */
	public final class PlayerControl {
		/** The player's name. */
		public String name() {
			return onGame(() -> mc.player == null ? "" : mc.player.getGameProfile().name());
		}

		/** Teleports to a relative position facing -x (yaw -90), level, and waits until the client stands there. */
		public PlayerControl at(double x, double y, double z) {
			return at(x, y, z, -90, 0);
		}

		/** Teleports to a relative position facing {@code yaw}/{@code pitch}, and waits until the client stands there. */
		public PlayerControl at(double x, double y, double z, float yaw, float pitch) {
			double ax = config.arenaX() + x, ay = config.arenaY() + y, az = config.arenaZ() + z;
			server.run(String.format(Locale.ROOT, "tp %s %s %s %s %s %s", name(), coord(ax), coord(ay), coord(az), coord(yaw), coord(pitch)));
			Vec3 target = new Vec3(ax, ay, az);
			if (!waitUntil("standing there", () -> mc.player != null && mc.player.position().distanceTo(target) < 0.2, 100)) {
				throw new TestFailure("the client never got to where it was teleported");
			}
			// Grim's answer to the teleport.
			waitTicks(5);
			return this;
		}

		/**
		 * Teleports to a relative position facing {@code yaw}/{@code pitch} and waits only until the client has taken the
		 * teleport (within 3 blocks of it), not until it stands there: for a start in mid-air (an elytra flight), where
		 * the player is falling again before {@link #at} would see them still.
		 */
		public PlayerControl teleport(double x, double y, double z, float yaw, float pitch) {
			double ax = config.arenaX() + x, ay = config.arenaY() + y, az = config.arenaZ() + z;
			server.run(String.format(Locale.ROOT, "tp %s %s %s %s %s %s", name(), coord(ax), coord(ay), coord(az), coord(yaw), coord(pitch)));
			Vec3 target = new Vec3(ax, ay, az);
			if (!waitUntil("the client took the teleport", () -> mc.player != null && mc.player.position().distanceTo(target) < 3, 100)) {
				throw new TestFailure("the client never got to where it was teleported");
			}
			return this;
		}

		/** Turns the camera (and so the player) to {@code yaw}/{@code pitch}. */
		public PlayerControl look(float yaw, float pitch) {
			recorder.step("look " + yaw + " " + pitch);
			onGame(() -> {
				mc.player.setYRot(yaw);
				mc.player.setXRot(pitch);
			});
			return this;
		}

		/**
		 * Puts {@code count} of {@code item} ("dirt", {@code "elytra[damage=330]"}) in slot {@code slot} on the server:
		 * 0-8 the hotbar, 9-35 the inventory, 36-39 armour (feet to head), 40 the off hand.
		 */
		public PlayerControl give(int slot, String item, int count) {
			server.run("item replace entity " + name() + " " + slotName(slot) + " with " + item + " " + count);
			return this;
		}

		/** One {@code item} in slot {@code slot} (see {@link #give(int, String, int)}). */
		public PlayerControl give(int slot, String item) {
			return give(slot, item, 1);
		}

		/** Puts {@code item} in an equipment slot ("armor.chest", "weapon.offhand") on the server. */
		public PlayerControl equip(String slot, String item) {
			server.run("item replace entity " + name() + " " + slot + " with " + item);
			return this;
		}

		/** Selects hotbar slot {@code slot} (0-8). */
		public PlayerControl select(int slot) {
			recorder.step("select hotbar " + slot);
			onGame(() -> mc.player.getInventory().setSelectedSlot(slot));
			return this;
		}

		/** Empties the inventory, clears effects, sets survival and full health and food, on the server. */
		public PlayerControl fresh() {
			String n = name();
			server.run("clear " + n);
			server.run("effect clear " + n);
			server.run("gamemode survival " + n);
			server.run("effect give " + n + " instant_health 1 10 true");
			server.run("effect give " + n + " saturation 1 20 true");
			return this;
		}

		/** Where the client has the player, relative to the arena. */
		public Vec3 position() {
			Vec3 p = onGame(() -> mc.player.position());
			return p.subtract(config.arenaX(), config.arenaY(), config.arenaZ());
		}

		/** The client's y, relative to the arena. */
		public double y() {
			return position().y;
		}

		/** Where the server has the player, relative to the arena (the truth a check should use). */
		public Vec3 serverPosition() {
			String answer = rcon.run("data get entity " + name() + " Pos");
			Matcher m = POS.matcher(answer);
			if (!m.find()) throw new TestFailure("couldn't read the server's position: " + answer);
			return new Vec3(Double.parseDouble(m.group(1)) - config.arenaX(), Double.parseDouble(m.group(2)) - config.arenaY(),
				Double.parseDouble(m.group(3)) - config.arenaZ());
		}

		/**
		 * The player's data at {@code path} on the server ({@code "Health"}, {@code "Inventory[{Slot:3b}].id"}), as printed.
		 * The server cuts a long answer short (about 100 characters, ending "..."): ask for the field wanted.
		 */
		public String data(String path) {
			return entityData(server.run("data get entity " + name() + " " + path));
		}

		/** The item id in slot {@code slot} on the server ({@code "minecraft:dirt"}; "" if empty). Slots as {@link #give}. */
		public String item(int slot) {
			String id = slotField(slot, "id");
			recorder.step("slot " + slot + ": " + (id.isEmpty() ? "empty" : id));
			return id;
		}

		/** How many items slot {@code slot} holds on the server (0 if empty). Slots as {@link #give}. */
		public int count(int slot) {
			String count = slotField(slot, "count");
			if (count.isEmpty()) count = slotField(slot, "id").isEmpty() ? "0" : "1";
			recorder.step("slot " + slot + ": " + count + " items");
			return Integer.parseInt(count);
		}

		/**
		 * The whole inventory on the server, by slot as {@link #give} numbers them (0-8 hotbar, 9-35 inventory, 36-39
		 * armour, 40 off hand): {@code "dirt x64"}, "" for an empty slot.
		 */
		public List<String> inventory() {
			String target = "entity " + name();
			String[] slots = new String[41];
			Arrays.fill(slots, "");
			for (int i = 0; i < 41; i++) {
				String id = field(target, "Inventory[" + i + "].id");
				if (id.isEmpty()) break;
				String slot = field(target, "Inventory[" + i + "].Slot");
				if (slot.isEmpty()) continue;
				int nbt = Integer.parseInt(slot.replace("b", ""));
				int at = nbt == -106 ? 40 : nbt >= 100 ? 36 + nbt - 100 : nbt;
				String count = field(target, "Inventory[" + i + "].count");
				if (at >= 0 && at < 41) slots[at] = id.replace("minecraft:", "") + " x" + (count.isEmpty() ? "1" : count);
			}
			List<String> held = new ArrayList<>();
			for (int i = 0; i < 41; i++) if (!slots[i].isEmpty()) held.add(i + ": " + slots[i]);
			recorder.step("inventory: " + (held.isEmpty() ? "empty" : String.join(", ", held)));
			return List.of(slots);
		}

		/** How many of {@code item} slot {@code slot} holds on the server (0 if empty or something else). */
		public int count(int slot, String item) {
			return item(slot).equals("minecraft:" + item.replace("minecraft:", "")) ? count(slot) : 0;
		}

		/** The damage on the item in slot {@code slot} on the server (0 if none or empty). */
		public int damage(int slot) {
			String damage = slotField(slot, "components.\"minecraft:damage\"");
			recorder.step("slot " + slot + ": damage " + (damage.isEmpty() ? "0" : damage));
			return damage.isEmpty() ? 0 : Integer.parseInt(damage);
		}

		/** The player's health on the server. */
		public float health() {
			String h = data("Health").replace("f", "");
			return h.isEmpty() ? 0 : Float.parseFloat(h);
		}

		/** The player's food level on the server. */
		public int food() {
			String f = data("foodLevel");
			return f.isEmpty() ? 0 : Integer.parseInt(f);
		}

		/** Whether the client is in a world on a server, and done loading it (the server takes its moves and clicks). */
		public boolean online() {
			return onGame(() -> mc.player != null && mc.level != null && mc.getConnection() != null && !(mc.gui.screen() instanceof LevelLoadingScreen));
		}

		/**
		 * Joins the last server again if not in a world (after a kick or disconnect), waiting until in one, at most
		 * {@code seconds}; whether it got back.
		 */
		public boolean rejoin(int seconds) {
			recorder.step("rejoin");
			long until = System.currentTimeMillis() + seconds * 1000L;
			while (System.currentTimeMillis() < until) {
				if (online()) return true;
				onGame(() -> {
					if (mc.player == null) Myriad.server().reconnect();
				});
				for (int i = 0; i < 30 && !online() && System.currentTimeMillis() < until; i++) sleep(200);
			}
			return online();
		}

		private String slotField(int slot, String path) {
			return field("entity " + name(), "Inventory[{Slot:" + nbtSlot(slot) + "b}]." + path);
		}

		private static int nbtSlot(int slot) {
			if (slot == 40) return -106;
			if (slot >= 36 && slot <= 39) return 100 + slot - 36;
			return slot;
		}

		private static String slotName(int slot) {
			if (slot < 9) return "hotbar." + slot;
			if (slot < 36) return "inventory." + (slot - 9);
			if (slot == 40) return "weapon.offhand";
			return switch (slot) {
				case 36 -> "armor.feet";
				case 37 -> "armor.legs";
				case 38 -> "armor.chest";
				case 39 -> "armor.head";
				default -> throw new TestFailure("no slot " + slot);
			};
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TestFailure("stopped");
		}
	}

	/** One module, switched and set up for the test (all put back after it). */
	public final class ModuleControl {
		private final Module module;

		ModuleControl(Module module) {
			this.module = module;
		}

		/** Sets setting {@code key} ({@code "rotate"}, or {@code "group.setting"}) from text as the {@code .set} command reads it. */
		public ModuleControl set(String key, String value) {
			recorder.step("set " + module.name() + " " + key + " = " + value);
			String error = onGame(() -> {
				Optional<Setting<?>> s = module.settings.get(key.replace(' ', '_').toLowerCase(Locale.ROOT));
				if (s.isEmpty()) return "no setting " + key + " in " + module.name();
				return s.get().parse(value) ? null : "couldn't set " + key + " to " + value;
			});
			if (error != null) throw new TestFailure(error);
			return this;
		}

		/** Turns it on. */
		public ModuleControl enable() {
			recorder.step(module.name() + " on");
			onGame(() -> module.setEnabled(true));
			return this;
		}

		/** Turns it off. */
		public ModuleControl disable() {
			recorder.step(module.name() + " off");
			onGame(() -> module.setEnabled(false));
			return this;
		}

		/** The module itself, for reading its state (on the render thread: wrap reads in {@link #onGame}). */
		public Module get() {
			return module;
		}
	}

	/** Keys about to be held. */
	public final class Hold {
		private final EnumSet<Key> keys;

		Hold(Key[] keys) {
			this.keys = keys.length == 0 ? EnumSet.noneOf(Key.class) : EnumSet.copyOf(Arrays.asList(keys));
		}

		/** Holds the keys for {@code ticks} ticks and waits until they're let go. */
		public void forTicks(int ticks) {
			recorder.step("hold " + keys.toString().toLowerCase(Locale.ROOT) + " for " + ticks + " ticks");
			inputs.hold(keys, ticks);
			awaitTick(clock.now() + ticks + 1);
		}

		/** Holds the keys for {@code ticks} ticks without waiting (for checks while they're held). */
		public void forTicksThenContinue(int ticks) {
			recorder.step("hold " + keys.toString().toLowerCase(Locale.ROOT) + " for " + ticks + " ticks (not waiting)");
			inputs.hold(keys, ticks);
		}
	}

	private static String abbreviate(String s) {
		String one = s.replace('\n', ' ').trim();
		return one.length() > 160 ? one.substring(0, 160) + "..." : one;
	}
}
