# Myriad Testing

In-game tests for Myriad and its addons, written in Java, run in a dev client against a real server (Myriad's Grim test
server), with a command line made for fast iteration: save a file, and the changed classes are swapped into the running
client and the tests run again in seconds. A failure comes with everything needed to see why: the steps the test took
and what the server answered, what the game said, the player tick by tick, the packets and Grim's flags.

Tests live with the addon they test (`src/gametest` in the addon's repository); this repository is the framework, the
runner, the driver the command line talks to, and the command line itself.

## Writing tests

An addon's tests go in a `gametest` source set, which the addon plugin sets up when `src/gametest` exists:

```
my-addon/
  src/gametest/java/com/example/gametest/WaypointTests.java
  src/gametest/resources/fabric.mod.json      a small dev-only mod: "my-addon-tests"
```

```json
{
	"schemaVersion": 1,
	"id": "my-addon-tests",
	"version": "${version}",
	"environment": "client",
	"entrypoints": { "myriad-tests": ["com.example.gametest.WaypointTests"] },
	"depends": { "my-addon": "*", "myriad-testing": "*" }
}
```

A suite is a class implementing `TestSuite`; each public method annotated `@Test` taking a `TestContext` is a test,
named `<mod id>:<suite>/<method in snake case>` (`myriad-essentials-tests:scaffold/tower_rotate`).

```java
public final class ScaffoldTests implements TestSuite {
	@Test(tags = "world")
	public void towerRotate(TestContext t) {
		t.player().give(0, "dirt", 64).select(0);
		t.module("myriad-essentials:scaffold").set("rotate", "true").enable();
		double y0 = t.player().serverPosition().y;
		t.hold(Key.JUMP).forTicks(80);
		double rose = t.player().serverPosition().y - y0;
		t.check(rose >= 4, String.format(Locale.ROOT, "rose %.2f blocks in 80 ticks", rose));
		t.note(String.format(Locale.ROOT, "rose %.2f blocks", rose));
	}
}
```

### What every test starts from

Before each test the runner notes every module's settings and on/off state and turns every module off (all put back
afterwards), closes any screen, respawns the player if dead, empties the inventory, clears effects, sets survival with
full health and food, clears the arena and teleports the player to its origin facing -x (yaw -90). Then the suite's
`beforeEach(t)` runs (an addon's usual settings), and Grim's flags start counting.

Nothing needs undoing at the end of a test: settings and modules are restored, the next test clears the arena. A test
that leaves the player in a strange state (kicked, dead, far away) is fine too.

### The arena

Coordinates in the API are relative to the arena's origin (by default x 30000, y 100, z 0 on the server). y 0 is
standing on the floor. The box from x -24 to 47, z -24 to 23 and y 0 to 23 is cleared to air on a stone floor (y -1)
before every test and is always loaded. A test may build anywhere else as well (a highway a thousand blocks out):
`t.arena().forceload(x1, z1, x2, z2)` first, and the test clears it up itself if later tests would mind.

### Grim

A test that passes its checks still fails if Grim flagged the player during it, for anything but the checks listed in
`@Test(allowFlags = ...)` and, unless the run is `--strict`, the placement checks 2b2t's Grim doesn't act on
(AirLiquidPlace, PositionPlace). `@Test(anyFlags = true)` for tests where Grim's opinion isn't part of the result.
`t.noFlags(...)` checks mid-test. `t.server().likeTwoBTwoT()` lets the player's placements through as 2b2t's Grim does
(air placements, any face, no turn) for the rest of the test; a fresh session follows.

### The API at a glance

| | |
|---|---|
| `t.player()` | `at(x, y, z[, yaw, pitch])` teleport and wait (`teleport(...)` without waiting, for mid-air starts); `look`, `select(0-8)`; `give(slot, "item[components]", n)` (0-8 hotbar, 9-35 inventory, 36-39 armour feet to head, 40 off hand); `equip`; `position()` (client), `serverPosition()` (the truth); `item(slot)`, `count(slot[, item])`, `damage(slot)`, `inventory()` (every slot, `"dirt x64"`), `health()`, `food()`, `data(path)` from the server; `online()`, `rejoin(seconds)` |
| `t.arena()` | `fill`, `set`, `is`, `count` (relative); `at(x, y, z)` / `block(x, y, z)` as absolute text for commands (`"summon husk " + t.arena().at(3.5, 0, 0.5)`); `items(x, y, z, size)` a container's slots (`"dirt x64"`); `data`; `forceload` / `unload` |
| `t.server()` | `run("command")` (in this client's dimension), `passes("if block ...")`, `likeTwoBTwoT()` |
| `t.module(id or name)` | `set(key, value)` as `.set` reads it, `enable()`, `disable()`, `get()` |
| `t.hold(Key...)` | `.forTicks(n)` (waits until let go) or `.forTicksThenContinue(n)` |
| `t.step("...")` | core's dev console steps, for real input and screens: `mouse 1 press`, `key e click`, `cursor 120 80`, `use`, `attack`, `middle`, `tabs`, `tab 1`, `closescreen`, `openitem 14`, `selftest`, `.command`; returns what the step reported |
| waiting | `waitTicks(n)`, `waitUntil("what", () -> condition, maxTicks)` |
| the game | `onGame(() -> ...)` runs on the render thread (read `mc.player` there) |
| results | `check(ok, "what was found")`, `note("result line")`, `skip("reason")`, `hasMod("baritone")`, `chat()` (lines since the test began), `flags()` (`GrimFlag`s), `noFlags(...)`, `flagsFromHere()` |

`@Test` takes `name`, `tags`, `timeoutTicks` (1200 by default), `allowFlags`, `anyFlags`.

The server cuts a long `data get` answer short (about 100 characters, ending "..."), so `items`, `inventory`, `item`
and `count` read one field at a time; with `data(path)`, ask for the field you want (`Items[0].id`), not a whole list.

## Running

Start the server kit (Myriad Essentials' `tools/grim-test/start.sh` for now) and a test client from the addon:

```
./gradlew runTestClient -PcoreDir=../myriad -PtestingDir=../myriad-testing -Photswap \
    -PquickPlayServer=localhost:25565 -Pusername=GrimTester
```

`-PcoreDir` and `-PtestingDir` use checkouts next to the addon instead of released jars (Myriad Testing's class folders
once `./gradlew classes` has built them there, so it hot-swaps like the addon; its jar otherwise); `-Photswap` runs the
client on the JetBrains Runtime with a debug port, for hot-swapping. The test client joins through ViaFabricPlus set to
its own version (26.3, so nothing is translated), with the `client` run's settings; `-Pvia=1.20.4` joins as 1.20.4
instead (worth a run before a
release, as most 2b2t players join that way). Then:

```
cli/myriad-test list [filter]           the tests the client has
cli/myriad-test run scaffold            every test whose id contains "scaffold"
cli/myriad-test run scaffold/tower      just that one (a filter with a / that ends an id picks only it)
cli/myriad-test run --tag 2b2t
cli/myriad-test run scaffold --reload   rebuild what changed, swap it into the client, run
cli/myriad-test watch scaffold          the same on every save
cli/myriad-test rerun --failed
cli/myriad-test report [id-part]        the last run's full report for a failure
cli/myriad-test state                   the game right now, as JSON (player, inventory, screen, modules on)
cli/myriad-test reload                  rebuild and swap only
```

A failure prints its last steps and the path of its report (under the client's `run/myriad-testing/reports/`). The exit
code is 0 when everything passed, 1 when something failed, 2 when the client couldn't be reached or a build failed;
`--junit file.xml` writes a report for CI, `--json` the raw result lines.

Hot-swapping takes changed method bodies, new methods and new fields in the addon, its tests, core and this framework
(each when the client runs it from a checkout); only classes whose bytes changed are redefined (redefining hundreds at
once has crashed the runtime, so more than 150 asks for a restart). A new setting or changed default, a static
initialiser, a mixin, a new test suite (an entrypoint) or a new class in core (loaded from its jar) needs a restart.

## The driver

The client listens on localhost:25610 (the next free port for another client), one JSON object per line each way:
`ping`, `list`, `run` (a `result` line per test as it finishes, then `done`), `stop`, `state`, `info`. The command line
is a thin client of it; agents and tools can talk to it directly.

## Layout

```
src/main/java/dev/myriadclient/testing/   the API: TestSuite, Test, TestContext, Key, TestFailure, TestSkipped
  runner/                                 Runner, Recorder (the record a report is made of), Journal (settings), GrimLog,
                                          Rcon, TickClock, Inputs, Config
  driver/                                 the socket and its state/info answers
cli/myriad-test                           the command line (Python 3, no dependencies)
```
