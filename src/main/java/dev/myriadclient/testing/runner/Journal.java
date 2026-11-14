package dev.myriadclient.testing.runner;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Every module as it was before a test, put back after it whatever the test did or wherever it failed: its settings and
 * whether it was on. Tests change settings freely and never clean up after themselves. Render thread only.
 */
public final class Journal {
	private record State(boolean enabled, JsonObject settings) {
	}

	private final Map<Module, State> before = new IdentityHashMap<>();

	/** Remembers every module as it is now. */
	public void snapshot() {
		before.clear();
		for (Module m : Myriad.modules()) before.put(m, new State(m.isEnabled(), m.settings.toJson()));
	}

	/** Puts every module back as it was at the snapshot: settings first, then on or off (silently). */
	public void restore() {
		for (var e : before.entrySet()) {
			Module m = e.getKey();
			State s = e.getValue();
			if (m.isEnabled() && !s.enabled()) m.setEnabledSilently(false);
			m.settings.fromJson(s.settings());
			if (!m.isEnabled() && s.enabled()) m.setEnabledSilently(true);
		}
		before.clear();
	}
}
