package dev.myriadclient.testing.runner;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.InputEvent;
import dev.myriadclient.testing.Key;
import net.minecraft.client.Minecraft;

import java.util.EnumSet;
import java.util.Set;

/** Movement keys a test holds, pressed in each tick's input (as if held on the keyboard) until the tick they're let go. */
public final class Inputs {
	private final TickClock clock;
	private volatile Set<Key> held = EnumSet.noneOf(Key.class);
	private volatile long until;
	/** Whether the sprint key itself is held down by a test (see {@link #onInput}). */
	private boolean sprintKey;

	public Inputs(TickClock clock) {
		this.clock = clock;
	}

	/** Holds {@code keys} for {@code ticks} ticks from now, replacing whatever was held. */
	public void hold(Set<Key> keys, int ticks) {
		held = keys.isEmpty() ? EnumSet.noneOf(Key.class) : EnumSet.copyOf(keys);
		until = clock.now() + ticks;
	}

	/** Lets every key go. */
	public void release() {
		held = EnumSet.noneOf(Key.class);
	}

	/** First, as real keys are there before any handler: modules and core's services see them as pressed. */
	@Subscribe(priority = Priority.HIGHEST)
	private void onInput(InputEvent e) {
		Set<Key> keys = held;
		if (!keys.isEmpty() && clock.now() >= until) {
			release();
			keys = held;
		}
		// The sprint key itself too: ViaFabricPlus, joined as an older version, starts sprinting from the key as those did.
		boolean sprint = keys.contains(Key.SPRINT);
		if (sprint != sprintKey) {
			sprintKey = sprint;
			Minecraft.getInstance().options.keySprint.setDown(sprint);
		}
		if (keys.isEmpty()) return;
		e.forward |= keys.contains(Key.FORWARD);
		e.backward |= keys.contains(Key.BACK);
		e.left |= keys.contains(Key.LEFT);
		e.right |= keys.contains(Key.RIGHT);
		e.jump |= keys.contains(Key.JUMP);
		e.sneak |= keys.contains(Key.SNEAK);
		e.sprint |= keys.contains(Key.SPRINT);
	}
}
