package dev.myriadclient.testing.driver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the driver's {@code state} and {@code info} answer. */
final class State {
	private static final Pattern DEBUG_PORT = Pattern.compile("-agentlib:jdwp=.*address=(?:[^:,]*:)?(\\d+)");

	private State() {
	}

	/** The game as it is now, read on the render thread. */
	static JsonObject now() {
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture<JsonObject> f = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				f.complete(read(mc));
			} catch (RuntimeException e) {
				f.completeExceptionally(e);
			}
		});
		try {
			return f.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			JsonObject o = new JsonObject();
			o.addProperty("type", "error");
			o.addProperty("message", "couldn't read the game's state: " + e);
			return o;
		}
	}

	private static JsonObject read(Minecraft mc) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "state");
		Screen screen = mc.gui.screen();
		o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		o.addProperty("server", Myriad.server().address());
		LocalPlayer p = mc.player;
		if (p != null) {
			JsonObject player = new JsonObject();
			player.addProperty("name", p.getGameProfile().name());
			player.add("position", vec(p.position()));
			player.add("motion", vec(p.getDeltaMovement()));
			player.addProperty("yaw", p.getYRot());
			player.addProperty("pitch", p.getXRot());
			player.addProperty("serverYaw", Myriad.rotations().serverYaw());
			player.addProperty("serverPitch", Myriad.rotations().serverPitch());
			player.addProperty("onGround", p.onGround());
			player.addProperty("gliding", p.isFallFlying());
			player.addProperty("health", p.getHealth());
			player.addProperty("food", p.getFoodData().getFoodLevel());
			player.addProperty("selected", p.getInventory().getSelectedSlot());
			player.addProperty("dimension", p.level().dimension().identifier().toString());
			JsonArray items = new JsonArray();
			for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
				ItemStack s = p.getInventory().getItem(i);
				if (s.isEmpty()) continue;
				JsonObject item = new JsonObject();
				item.addProperty("slot", i);
				item.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
				item.addProperty("count", s.getCount());
				if (s.isDamageableItem()) item.addProperty("damage", s.getDamageValue() + "/" + s.getMaxDamage());
				items.add(item);
			}
			player.add("inventory", items);
			o.add("player", player);
			o.addProperty("ping", Myriad.server().ping());
		}
		JsonArray modules = new JsonArray();
		for (Module m : Myriad.modules().enabled()) modules.add(m.id().toString());
		o.add("modulesOn", modules);
		return o;
	}

	private static JsonArray vec(Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(Double.parseDouble(String.format(Locale.ROOT, "%.3f", v.x)));
		a.add(Double.parseDouble(String.format(Locale.ROOT, "%.3f", v.y)));
		a.add(Double.parseDouble(String.format(Locale.ROOT, "%.3f", v.z)));
		return a;
	}

	/**
	 * The client's class folders (what {@code ./gradlew classes} rebuilds; jars are left out) and its debug port, if it
	 * was started with one ({@code -Photswap}).
	 */
	static JsonObject info(int driverPort) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "info");
		o.addProperty("driverPort", driverPort);
		Integer debug = null;
		for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			Matcher m = DEBUG_PORT.matcher(arg);
			if (m.find()) debug = Integer.parseInt(m.group(1));
		}
		o.addProperty("debugPort", debug);
		JsonArray dirs = new JsonArray(), jars = new JsonArray();
		for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
			if (entry.isEmpty() || entry.contains("/.gradle/caches/") || entry.contains("/loom-cache/")) continue;
			if (new File(entry).isDirectory()) dirs.add(entry);
			else jars.add(entry);
		}
		o.add("classDirs", dirs);
		o.add("localJars", jars);
		o.addProperty("gameDir", Minecraft.getInstance().gameDirectory.getAbsolutePath());
		o.addProperty("started", ManagementFactory.getRuntimeMXBean().getStartTime());
		// A JDK that can run Gradle for the reload (the system's may be too new for it).
		o.addProperty("javaHome", System.getProperty("java.home"));
		return o;
	}
}
