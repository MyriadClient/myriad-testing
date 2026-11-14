package dev.myriadclient.testing.runner;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.ChatReceiveEvent;
import dev.myriadclient.api.event.events.ModuleToggleEvent;
import dev.myriadclient.api.event.events.NotificationEvent;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TeleportEvent;
import dev.myriadclient.api.event.events.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What happened while a test ran, tick by tick, for its report when it fails: the steps it took, the player each tick
 * (where, how fast, which way, on the ground, sprinting), the packets both ways, and what the game said (toasts,
 * chat, modules switched, the server moving you). Records only while a test runs; the noisiest packets (other
 * entities moving, chunks, light) are left out.
 */
public final class Recorder {
	/** Packets that say nothing about what a test does, and would bury what does. */
	private static final Set<String> NOISE = Set.of("ClientboundMoveEntityPacket", "ClientboundMoveEntityPacket$Pos", "ClientboundMoveEntityPacket$Rot",
		"ClientboundMoveEntityPacket$PosRot", "ClientboundRotateHeadPacket", "ClientboundSetEntityMotionPacket", "ClientboundLevelChunkWithLightPacket",
		"ClientboundLightUpdatePacket", "ClientboundForgetLevelChunkPacket", "ClientboundKeepAlivePacket", "ServerboundKeepAlivePacket",
		"ClientboundSetTimePacket", "ClientboundBundlePacket", "ClientboundEntityPositionSyncPacket", "ClientboundSetEntityDataPacket",
		"ClientboundUpdateAttributesPacket", "ClientboundChunkBatchStartPacket", "ClientboundChunkBatchFinishedPacket",
		"ServerboundChunkBatchReceivedPacket", "ClientboundSoundPacket", "ClientboundLevelParticlesPacket", "ServerboundClientTickEndPacket",
		"ClientboundPingPacket", "ServerboundPongPacket", "ClientboundTickingStatePacket", "ClientboundTickingStepPacket");
	private static final int MAX_LINES = 20000;

	private final Minecraft mc = Minecraft.getInstance();
	private final TickClock clock;
	private volatile boolean recording;
	private long start;
	private final List<String> steps = new ArrayList<>(), ticks = new ArrayList<>(), packets = new ArrayList<>(), events = new ArrayList<>(),
		chat = new ArrayList<>();

	public Recorder(TickClock clock) {
		this.clock = clock;
	}

	/** Starts a fresh record (for the test starting now). */
	public synchronized void begin() {
		steps.clear();
		ticks.clear();
		packets.clear();
		events.clear();
		chat.clear();
		start = clock.now();
		recording = true;
	}

	/** Stops recording; what was recorded stays for {@link #report}. */
	public synchronized void end() {
		recording = false;
	}

	/** The tick of the test, counted from its start. */
	public long tickOfTest() {
		return clock.now() - start;
	}

	/** Notes a step the test took ("hold forward, sprint for 30 ticks"). */
	public synchronized void step(String what) {
		if (steps.size() < MAX_LINES) steps.add(String.format(Locale.ROOT, "t%-5d %s", tickOfTest(), what));
	}

	/** The steps taken so far, for a failure message. */
	public synchronized List<String> steps() {
		return List.copyOf(steps);
	}

	@Subscribe(priority = Priority.LOWEST)
	private void onTick(TickEvent.Post e) {
		if (!recording) return;
		var p = mc.player;
		if (p == null) {
			add(ticks, String.format(Locale.ROOT, "t%-5d (no player) screen=%s", tickOfTest(), screen()));
			return;
		}
		var v = p.getDeltaMovement();
		add(ticks, String.format(Locale.ROOT, "t%-5d pos %.3f %.3f %.3f  motion %.3f %.3f %.3f  rot %.1f %.1f  %s%s%s slot=%d hp=%.1f%s",
			tickOfTest(), p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.getYRot(), p.getXRot(), p.onGround() ? "ground " : "air ",
			p.isSprinting() ? "sprint " : "", p.isShiftKeyDown() ? "sneak " : "", p.getInventory().getSelectedSlot(), p.getHealth(),
			mc.gui.screen() != null ? " screen=" + screen() : ""));
	}

	private String screen() {
		return mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName();
	}

	@Subscribe(priority = Priority.LOWEST)
	private void onSend(PacketEvent.Send e) {
		if (recording) packet(">", e.packet(), e.isCancelled());
	}

	@Subscribe(priority = Priority.LOWEST)
	private void onReceive(PacketEvent.Receive e) {
		if (recording) packet("<", e.packet(), e.isCancelled());
	}

	private void packet(String way, Packet<?> packet, boolean cancelled) {
		String name = packet.getClass().getSimpleName();
		if (NOISE.contains(name) || name.isEmpty()) return;
		String text = describe(packet);
		if (text.length() > 300) text = text.substring(0, 300) + "...";
		add(packets, String.format(Locale.ROOT, "t%-5d %s %s%s", tickOfTest(), way, cancelled ? "(cancelled) " : "", text));
	}

	@Subscribe
	private void onNotification(NotificationEvent e) {
		if (recording) add(events, String.format(Locale.ROOT, "t%-5d toast %s: %s: %s", tickOfTest(), e.level(), e.title(), e.message()));
	}

	@Subscribe(priority = Priority.LOWEST)
	private void onChat(ChatReceiveEvent e) {
		if (!recording) return;
		String text = e.message().getString();
		add(events, String.format(Locale.ROOT, "t%-5d chat %s", tickOfTest(), text));
		add(chat, text);
	}

	@Subscribe
	private void onToggle(ModuleToggleEvent e) {
		if (recording) add(events, String.format(Locale.ROOT, "t%-5d module %s %s", tickOfTest(), e.module().name(), e.enabled() ? "on" : "off"));
	}

	@Subscribe
	private void onTeleport(TeleportEvent e) {
		if (recording) add(events, String.format(Locale.ROOT, "t%-5d server moved you %.2f blocks", tickOfTest(), e.distance()));
	}

	private synchronized void add(List<String> list, String line) {
		if (list.size() < MAX_LINES) list.add(line);
	}

	/** The chat lines received since the test began, oldest first, as text. */
	public synchronized List<String> chat() {
		return List.copyOf(chat);
	}

	/**
	 * A packet as text: its own {@code toString} (records print their fields), or for one without (it prints
	 * {@code Class@1a2b3c}), its fields by reflection: {@code ClientboundContainerSetSlotPacket[containerId=0, slot=36, ...]}.
	 */
	static String describe(Object packet) {
		String text = String.valueOf(packet);
		if (!DEFAULT_TO_STRING.matcher(text).matches()) return text;
		StringBuilder b = new StringBuilder(packet.getClass().getSimpleName()).append('[');
		boolean first = true;
		for (Class<?> c = packet.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					b.append(first ? "" : ", ").append(f.getName()).append('=').append(f.get(packet));
					first = false;
				} catch (ReflectiveOperationException | RuntimeException e) {
					// Not readable: left out.
				}
			}
		}
		return b.append(']').toString();
	}

	private static final Pattern DEFAULT_TO_STRING = Pattern.compile("[\\w.$]+@[0-9a-f]+");

	/** What was recorded, as sections of lines: steps, game events, ticks and packets. */
	public synchronized Record record() {
		return new Record(List.copyOf(steps), List.copyOf(events), List.copyOf(ticks), List.copyOf(packets));
	}

	/** One test's record. */
	public record Record(List<String> steps, List<String> events, List<String> ticks, List<String> packets) {
	}
}
