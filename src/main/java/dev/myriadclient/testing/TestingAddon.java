package dev.myriadclient.testing;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.MyriadAddon;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.testing.driver.Driver;
import dev.myriadclient.testing.runner.Config;
import dev.myriadclient.testing.runner.GameLog;
import dev.myriadclient.testing.runner.Inputs;
import dev.myriadclient.testing.runner.Recorder;
import dev.myriadclient.testing.runner.Runner;
import dev.myriadclient.testing.runner.TickClock;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;

/**
 * Myriad Testing's entrypoint: the tick clock, held keys and recorder the runner needs, the driver socket for the
 * command line, and {@code .test list|run [filter]} for running tests from chat.
 */
public final class TestingAddon implements MyriadAddon {
	@Override
	public void initialize(AddonContext ctx) {
		TickClock clock = new TickClock();
		Inputs inputs = new Inputs(clock);
		Recorder recorder = new Recorder(clock);
		ctx.events().subscribe(clock);
		ctx.events().subscribe(inputs);
		ctx.events().subscribe(recorder);
		GameLog gameLog = new GameLog(clock);
		gameLog.install();
		Runner runner = new Runner(clock, inputs, recorder, gameLog);
		new Driver(runner).start(Integer.getInteger("myriad.test.port", 25610));
		ctx.registerCommand(new TestCommand(runner));
	}

	/** {@code .test list [filter]}, {@code .test run [filter]}, {@code .test stop}. */
	private static final class TestCommand extends Command {
		private final Runner runner;

		TestCommand(Runner runner) {
			super("test", "Runs in-game tests: .test list [filter], .test run [filter], .test stop.");
			this.runner = runner;
		}

		@Override
		public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
			b.then(literal("list").executes(c -> list("")).then(argument("filter", StringArgumentType.greedyString())
				.executes(c -> list(StringArgumentType.getString(c, "filter")))));
			b.then(literal("run").executes(c -> run("")).then(argument("filter", StringArgumentType.greedyString())
				.executes(c -> run(StringArgumentType.getString(c, "filter")))));
			b.then(literal("stop").executes(c -> {
				runner.stop();
				info("Stopping after this test");
				return SINGLE_SUCCESS;
			}));
		}

		private int list(String filter) {
			List<Runner.Case> cases = runner.select(filter.isBlank() ? List.of() : List.of(filter.split(" ")), List.of());
			info(cases.size() + " test" + (cases.size() == 1 ? "" : "s"));
			for (Runner.Case c : cases) info(c.id());
			return SINGLE_SUCCESS;
		}

		private int run(String filter) {
			List<Runner.Case> cases = runner.select(filter.isBlank() ? List.of() : List.of(filter.split(" ")), List.of());
			boolean started = runner.start(cases, Config.defaults(), r -> mc.execute(() -> {
				if (r.ok()) info("PASS " + r.id() + ": " + r.detail());
				else error("FAIL " + r.id() + ": " + r.detail());
			}), results -> mc.execute(() -> info(results.stream().filter(Runner.Result::ok).count() + "/" + results.size() + " passed")));
			if (!started) error("A run is going already");
			return SINGLE_SUCCESS;
		}
	}
}
