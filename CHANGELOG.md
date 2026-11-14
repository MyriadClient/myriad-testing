# Changelog

Myriad Testing is versioned on its own. Before 1.0 its API (`dev.myriadclient.testing`) may change in any minor
release; each release says which Myriad it needs.

## 0.1.0

Needs Myriad 2.0.0, for Minecraft 26.3. The first release.

- **Tests in Java, in the game.** An addon keeps its suites in a `gametest` source set (the addon plugin sets it up when
  `src/gametest` exists) and registers them under the `myriad-tests` entrypoint. A test sets up an arena on the server,
  holds keys, runs the dev console's steps, turns modules on and checks what happened, on the client and on the server.
- **A run says why a test failed:** the steps it took and what the server answered, what the game said, Grim's flags
  (a test fails on any it didn't allow) and a report for each failure.
- **`cli/myriad-test`** (Python 3, no dependencies) lists and runs tests in a running test client, rebuilds and swaps
  changed classes into it (`--reload`, `watch`), and shows the game's state.
