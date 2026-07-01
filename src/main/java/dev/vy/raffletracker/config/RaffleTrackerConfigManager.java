package dev.vy.raffletracker.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vy.raffletracker.RaffleTracker;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class RaffleTrackerConfigManager {
	private static final float MIN_HUD_SCALE = 0.5F;
	private static final float MAX_HUD_SCALE = 2.0F;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("raffletracker.json");
	private static RaffleTrackerConfig config = new RaffleTrackerConfig();

	private RaffleTrackerConfigManager() {
	}

	public static synchronized void load() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			if (Files.notExists(CONFIG_PATH)) {
				config = new RaffleTrackerConfig();
				save();
				return;
			}
			try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				RaffleTrackerConfig loaded = GSON.fromJson(root, RaffleTrackerConfig.class);
				if (loaded == null) {
					loaded = new RaffleTrackerConfig();
				}
				if (!root.has("enabled")) {
					loaded.enabled = true;
				}
				if (!root.has("hudX")) {
					loaded.hudX = 10;
				}
				if (!root.has("hudY")) {
					loaded.hudY = 10;
				}
				loaded.hudScale = clampHudScale(loaded.hudScale);
				config = loaded;
			} catch (com.google.gson.JsonSyntaxException exception) {
				RaffleTracker.LOGGER.error("[RT] Config file malformed, resetting to defaults", exception);
				config = new RaffleTrackerConfig();
				save();
			}
		} catch (IOException exception) {
			RaffleTracker.LOGGER.error("[RT] Failed to load config", exception);
			config = new RaffleTrackerConfig();
		}
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException exception) {
			RaffleTracker.LOGGER.error("[RT] Failed to save config", exception);
		}
	}

	public static synchronized RaffleTrackerConfig getConfig() {
		return config;
	}

	public static synchronized void updateEnabled(boolean enabled) {
		config.enabled = enabled;
		save();
	}

	public static synchronized void updateHudLayout(int x, int y, float scale) {
		config.hudX = Math.max(0, x);
		config.hudY = Math.max(0, y);
		config.hudScale = clampHudScale(scale);
		save();
	}

	private static float clampHudScale(float scale) {
		if (!Float.isFinite(scale) || scale <= 0.0F) {
			return 1.0F;
		}
		float rounded = Math.round(scale * 10.0F) / 10.0F;
		return Math.max(MIN_HUD_SCALE, Math.min(MAX_HUD_SCALE, rounded));
	}
}
