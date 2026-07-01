package dev.vy.raffletracker.client;

import com.mojang.brigadier.Command;
import dev.vy.raffletracker.RaffleTracker;
import dev.vy.raffletracker.client.cosmetics.DrtCosmetics;
import dev.vy.raffletracker.client.screen.RaffleTrackerMoveScreen;
import dev.vy.raffletracker.client.tracker.RaffleTrackerFeature;
import dev.vy.raffletracker.config.RaffleTrackerConfigManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
//? if >= 26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
//? } else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
//? if >= 26.1 {
import net.minecraft.resources.Identifier;
//?}

public final class RaffleTrackerClient implements ClientModInitializer {
	private static RaffleTrackerFeature tracker;
	private static boolean openMoveBoxNextTick;
	private static boolean showedDownloadTitle;

	@Override
	public void onInitializeClient() {
		DrtCosmetics.initialize();

		tracker = new RaffleTrackerFeature();
		tracker.applyConfig(RaffleTrackerConfigManager.getConfig());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tracker.tick(client);

			if (!showedDownloadTitle && client.player != null && client.level != null) {
				showedDownloadTitle = true;
				client.gui.setTitle(Component.literal("Thanks for downloading! mod by Vyriv <3"));
			}

			if (!openMoveBoxNextTick) {
				return;
			}
			if (client.player == null) {
				openMoveBoxNextTick = false;
				return;
			}

			openMoveBoxNextTick = false;
			client.setScreen(new RaffleTrackerMoveScreen(tracker));
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("rt")
				.executes(context -> showStatus(context.getSource()))
				.then(ClientCommands.literal("move")
					.executes(context -> openMoveBox(context.getSource()))
				)
				.then(ClientCommands.literal("movetebox")
					.executes(context -> openMoveBox(context.getSource()))
				)
				.then(ClientCommands.literal("toggle")
					.executes(context -> toggleHud(context.getSource()))
				)
				.then(ClientCommands.literal("rescan")
					.executes(context -> rescan(context.getSource()))
				)
				.then(ClientCommands.literal("clear")
					.executes(context -> clearCache(context.getSource()))
				)
			);
			dispatcher.register(ClientCommands.literal("raffletracker")
				.executes(context -> showStatus(context.getSource()))
				.then(ClientCommands.literal("move")
					.executes(context -> openMoveBox(context.getSource()))
				)
				.then(ClientCommands.literal("movetebox")
					.executes(context -> openMoveBox(context.getSource()))
				)
				.then(ClientCommands.literal("toggle")
					.executes(context -> toggleHud(context.getSource()))
				)
				.then(ClientCommands.literal("rescan")
					.executes(context -> rescan(context.getSource()))
				)
				.then(ClientCommands.literal("clear")
					.executes(context -> clearCache(context.getSource()))
				)
			);
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> tracker.handleGameMessage(message, overlay));
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			tracker.handleChatMessage(message);
			return true;
		});

		//? if >= 26.1 {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath(RaffleTracker.MOD_ID, "tracker"), (guiGraphics, deltaTracker) -> {
		//? } else {
		/*HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) -> {
		*///?}
			Minecraft client = Minecraft.getInstance();
			if (client.screen == null && !isPlayerListOpen(client)) {
				renderTrackerOverlay(client, guiGraphics, scaledMouseX(client), scaledMouseY(client));
			}
		});

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			registerScreenOverlay(client, screen);
			ScreenMouseEvents.allowMouseClick(screen).register(new ScreenMouseEvents.AllowMouseClick() {
				@Override
				public boolean allowMouseClick(Screen s, MouseButtonEvent event) {
					if (s instanceof RaffleTrackerMoveScreen) {
						return true;
					}
					if (tracker != null && tracker.handleScreenMouseClick(client, event.x(), event.y(), event.button())) {
						return false;
					}
					return true;
				}
			});
		});

		RaffleTracker.LOGGER.info("[RT] Client initialized");
	}

	public static RaffleTrackerFeature getTracker() {
		return tracker;
	}

	private static void registerScreenOverlay(Minecraft client, Screen screen) {
		//? if >= 26.1 {
		ScreenEvents.afterExtract(screen).register((s, guiGraphics, mouseX, mouseY, tickProgress) -> renderTrackerOverScreen(client, s, guiGraphics, mouseX, mouseY));
		//? } else {
		/*ScreenEvents.afterRender(screen).register((s, guiGraphics, mouseX, mouseY, tickProgress) -> renderTrackerOverScreen(client, s, guiGraphics, mouseX, mouseY));
		*///?}
	}

	private static void renderTrackerOverScreen(Minecraft client, Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
		if (screen instanceof RaffleTrackerMoveScreen) {
			return;
		}
		renderTrackerOverlay(client, guiGraphics, mouseX, mouseY);
	}

	private static void renderTrackerOverlay(Minecraft client, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
		if (tracker == null || client.player == null || client.level == null) {
			return;
		}
		tracker.extractRenderState(client, guiGraphics, mouseX, mouseY);
	}

	private static int scaledMouseX(Minecraft client) {
		double guiScaleX = (double) client.getWindow().getGuiScaledWidth() / Math.max(1, client.getWindow().getWidth());
		return (int) Math.round(client.mouseHandler.xpos() * guiScaleX);
	}

	private static int scaledMouseY(Minecraft client) {
		double guiScaleY = (double) client.getWindow().getGuiScaledHeight() / Math.max(1, client.getWindow().getHeight());
		return (int) Math.round(client.mouseHandler.ypos() * guiScaleY);
	}

	private static boolean isPlayerListOpen(Minecraft client) {
		return client.options != null && client.options.keyPlayerList.isDown();
	}

	private static int showStatus(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		if (client.player == null) {
			return 0;
		}
		source.sendFeedback(Component.literal("§a[RT] §fUse §d/rt move§f to move, §d/rt rescan§f to force scan, §d/rt toggle§f to hide/show."));
		return Command.SINGLE_SUCCESS;
	}

	private static int openMoveBox(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		if (client.player == null) {
			return 0;
		}
		openMoveBoxNextTick = true;
		return Command.SINGLE_SUCCESS;
	}

	private static int toggleHud(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		if (client.player == null) {
			return 0;
		}
		tracker.setEnabled(!tracker.isEnabled());
		String status = tracker.isEnabled() ? "shown" : "hidden";
		source.sendFeedback(Component.literal("§a[RT] HUD " + status + ". §7Task tracking is still active."));
		return Command.SINGLE_SUCCESS;
	}

	private static int rescan(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		if (client.player == null) {
			return 0;
		}
		int count = tracker.forceRescan(client);
		source.sendFeedback(Component.literal("§a[RT] §fScanned §d" + count + "§f raffle task(s) from the open UI."));
		return Command.SINGLE_SUCCESS;
	}

	private static int clearCache(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		if (client.player == null) {
			return 0;
		}
		tracker.clearCachedTasks();
		source.sendFeedback(Component.literal("§a[RT] §fCleared cached raffle tasks."));
		return Command.SINGLE_SUCCESS;
	}
}
