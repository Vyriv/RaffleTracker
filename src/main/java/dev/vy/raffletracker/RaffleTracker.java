package dev.vy.raffletracker;

import dev.vy.raffletracker.config.RaffleTrackerConfigManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RaffleTracker implements ModInitializer {
	public static final String MOD_ID = "raffletracker";
	public static final Logger LOGGER = LoggerFactory.getLogger("RaffleTracker");

	@Override
	public void onInitialize() {
		RaffleTrackerConfigManager.load();
		LOGGER.info("[RT] RaffleTracker loaded");
	}
}
