package dev.vy.raffletracker.client.tracker;

import dev.vy.raffletracker.config.RaffleTrackerConfig;
import dev.vy.raffletracker.config.RaffleTrackerConfigManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.joml.Matrix3x2fStack;

public final class RaffleTrackerFeature {
	private static final float MIN_HUD_SCALE = 0.5F;
	private static final float MAX_HUD_SCALE = 2.0F;
	private static final float HUD_SCALE_STEP = 0.1F;
	private static final long SCAN_INTERVAL_MS = 250L;
	private static final long CHAT_DEDUP_MS = 1_000L;
	private static final long PENDING_COMPLETION_TTL_MS = 20L * 60L * 1000L;
	private static final int TOOLTIP_MAX_WIDTH = 270;
	private static final int HUD_BASE_WIDTH = 176;
	private static final int HUD_PAD = 8;
	private static final int TAB_Y = 20;
	private static final int TAB_H = 12;
	private static final int TASK_START_Y = 38;
	private static final int ROW_H = 13;
	private static final int MIN_TASK_SCREEN_STACKS = 9;
	private static final Pattern UNIT_DURATION_PATTERN = Pattern.compile(
		"(\\d+)\\s*(d|day|days|h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\\b",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern EXACT_RAFFLE_COMPLETION_PATTERN = Pattern.compile(
		"\\bRAFFLE\\s+TASK!?\\s+YOU\\s+COMPLETED\\s+THE\\s+(.+?)\\s+RAFFLE\\s+TASK\\b",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern COLON_DURATION_PATTERN = Pattern.compile("\\b(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\b");
	private static final Set<String> STOP_WORDS = Set.of(
		"THE", "AND", "FOR", "WITH", "FROM", "THIS", "THAT", "YOUR", "YOU", "RAFFLE", "TASK", "TASKS",
		"EASY", "MEDIUM", "HARD", "COMPLETE", "COMPLETED", "PROGRESS", "REWARD", "TIME", "UNTIL", "RESET"
	);

	private static final int C_FAINT_BG = 0x260B0E14;
	private static final int C_FAINT_SHADOW = 0x20000000;
	private static final int C_TITLE = 0xFF9FB2D8;
	private static final int C_MUTED = 0xFF748097;
	private static final int C_TEXT = 0xFFE8EDF8;
	private static final int C_WARN = 0xFFFFD15A;
	private static final int C_OPEN = 0xFFFF6B6B;

	private final List<RaffleTask> tasks = new ArrayList<>();
	private final Set<String> hiddenTaskIds = new HashSet<>();
	private final Map<String, Long> pendingCompletedTaskNames = new LinkedHashMap<>();
	private boolean enabled = true;
	private int hudX = 10;
	private int hudY = 10;
	private float hudScale = 1.0F;
	private long nextScanAtMillis;
	private long resetAtMillis;
	private boolean resetTimerSeen;
	private boolean needsNewTasks;
	private boolean promptOpenTasksScreen;
	private String lastMessageKey = "";
	private long lastMessageAtMillis;
	private Difficulty selectedDifficulty = Difficulty.EASY;

	public void applyConfig(RaffleTrackerConfig config) {
		enabled = config.enabled;
		hudX = Math.max(0, config.hudX);
		hudY = Math.max(0, config.hudY);
		hudScale = clampHudScale(config.hudScale);
	}

	public void tick(Minecraft client) {
		if (!enabled) {
			return;
		}

		long now = System.currentTimeMillis();
		prunePendingCompletions(now);
		if (now >= nextScanAtMillis) {
			nextScanAtMillis = now + SCAN_INTERVAL_MS;
			scanOpenRaffleScreen(client, now);
		}

		if (resetTimerSeen && resetAtMillis > 0L && now >= resetAtMillis) {
			if (!needsNewTasks) {
				tasks.clear();
				hiddenTaskIds.clear();
				pendingCompletedTaskNames.clear();
				needsNewTasks = true;
				promptOpenTasksScreen = true;
			}
		}
	}

	public void handleGameMessage(Component message, boolean overlay) {
		if (!overlay) {
			handleChatMessage(message);
		}
	}

	public void handleChatMessage(Component message) {
		if (!enabled || message == null) {
			return;
		}

		long now = System.currentTimeMillis();
		String cleaned = normalize(message.getString());
		if (cleaned.isEmpty()) {
			return;
		}
		if (cleaned.equals(lastMessageKey) && now - lastMessageAtMillis <= CHAT_DEDUP_MS) {
			return;
		}
		lastMessageKey = cleaned;
		lastMessageAtMillis = now;

		String completedTaskName = extractExactCompletionTaskName(message.getString());
		if (!completedTaskName.isBlank()) {
			if (tasks.isEmpty() || !hideCompletedTaskByName(completedTaskName)) {
				rememberPendingCompletion(completedTaskName, now);
			}
			return;
		}

		if (tasks.isEmpty()) {
			return;
		}

		if (!cleaned.contains("RAFFLE") || !looksLikeCompletionMessage(cleaned)) {
			return;
		}

		boolean matchedAny = false;
		for (RaffleTask task : tasks) {
			if (!hiddenTaskIds.contains(task.id()) && matchesTask(cleaned, task)) {
				hiddenTaskIds.add(task.id());
				matchedAny = true;
			}
		}

		if (!matchedAny && cleaned.contains("TASK") && visibleTasks().size() == 1) {
			hiddenTaskIds.add(visibleTasks().getFirst().id());
		}
	}

	public int forceRescan(Minecraft client) {
		return scanOpenRaffleScreen(client, System.currentTimeMillis());
	}

	public void clearCachedTasks() {
		tasks.clear();
		hiddenTaskIds.clear();
		resetAtMillis = 0L;
		resetTimerSeen = false;
		needsNewTasks = false;
		promptOpenTasksScreen = false;
		pendingCompletedTaskNames.clear();
	}

	public void extractRenderState(Minecraft client, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
		extractRenderState(client, guiGraphics, mouseX, mouseY, false);
	}

	public void extractRenderState(Minecraft client, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean moveMode) {
		if (!isVisible(client, moveMode)) {
			return;
		}

		int width = getBaseDisplayWidth(client);
		int height = getBaseDisplayHeight(client);
		normalizeSelectedDifficulty();
		boolean allowHover = moveMode || client.screen != null;
		RaffleTask hoveredTask = allowHover ? taskAtMouse(client, mouseX, mouseY) : null;
		Matrix3x2fStack pose = guiGraphics.pose();
		pose.pushMatrix();
		try {
			pose.translate(hudX, hudY);
			pose.scale(hudScale);
			drawPanel(client, guiGraphics, width, height, moveMode, hoveredTask);
		} finally {
			pose.popMatrix();
		}

		if (hoveredTask != null) {
			drawTooltip(client, guiGraphics, buildTaskTooltip(client, hoveredTask), mouseX, mouseY);
		}
	}

	private void drawPanel(Minecraft client, GuiGraphicsExtractor g, int width, int height, boolean moveMode, RaffleTask hoveredTask) {
		drawFaintBackground(g, width, height);

		int visible = visibleTasks().size();
		String title = "Raffle Tasks";
		g.text(client.font, title, HUD_PAD, 7, C_TITLE, true);
		g.text(client.font, visible + "/" + tasks.size(), width - HUD_PAD - client.font.width(visible + "/" + tasks.size()), 7, C_MUTED, true);

		if (needsNewTasks || promptOpenTasksScreen) {
			drawCenteredStatus(client, g, width, 34, "Open tasks screen", C_OPEN);
		} else if (visible == 0 && !tasks.isEmpty()) {
			drawCenteredStatus(client, g, width, 32, "Open tasks screen", C_TEXT);
		} else if (tasks.isEmpty()) {
			drawCenteredStatus(client, g, width, 32, "Open tasks screen", C_WARN);
		} else {
			drawTabs(client, g, width);
			drawSelectedTasks(client, g, width, hoveredTask);
		}

		String timer = timerText();
		g.text(client.font, timer, 8, height - 13, timer.startsWith("Reset in") ? C_MUTED : C_WARN, true);
	}

	private void drawFaintBackground(GuiGraphicsExtractor g, int width, int height) {
		g.fill(3, 3, width + 3, height + 3, C_FAINT_SHADOW);
		g.fill(0, 0, width, height, C_FAINT_BG);
	}

	private void drawTabs(Minecraft client, GuiGraphicsExtractor g, int width) {
		List<Difficulty> tabs = displayedDifficulties();
		if (tabs.isEmpty()) {
			return;
		}
		int gap = 4;
		int available = width - HUD_PAD * 2 - gap * (tabs.size() - 1);
		int tabW = Math.max(24, available / tabs.size());
		int x = HUD_PAD;
		for (Difficulty difficulty : tabs) {
			boolean selected = difficulty == selectedDifficulty;
			int textColor = selected ? difficulty.textColor : softenColor(difficulty.textColor);
			String label = difficulty.label;
			g.text(client.font, label, x + (tabW - client.font.width(label)) / 2, TAB_Y + 2, textColor, true);
			if (selected) {
				g.fill(x + 3, TAB_Y + TAB_H, x + tabW - 3, TAB_Y + TAB_H + 1, difficulty.textColor);
			}
			x += tabW + gap;
		}
	}

	private int softenColor(int color) {
		int a = color >>> 24;
		int r = (color >>> 16) & 0xFF;
		int g = (color >>> 8) & 0xFF;
		int b = color & 0xFF;
		r = (r + 130) / 2;
		g = (g + 130) / 2;
		b = (b + 130) / 2;
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private void drawSelectedTasks(Minecraft client, GuiGraphicsExtractor g, int width, RaffleTask hoveredTask) {
		Map<Difficulty, List<RaffleTask>> grouped = groupedVisibleTasks();
		List<RaffleTask> group = grouped.getOrDefault(selectedDifficulty, List.of());
		int x = HUD_PAD;
		int y = TASK_START_Y;
		int rowW = width - HUD_PAD * 2;
		for (RaffleTask task : group) {
			if (hoveredTask != null && hoveredTask.id().equals(task.id())) {
				g.text(client.font, ">", x - 6, y + 1, selectedDifficulty.textColor, true);
			}
			g.text(client.font, ellipsize(client, task.title(), rowW - 3), x, y + 1, selectedDifficulty.textColor, true);
			y += ROW_H;
		}
	}

	private RaffleTask taskAtMouse(Minecraft client, int mouseX, int mouseY) {
		if (tasks.isEmpty() || needsNewTasks) {
			return null;
		}
		normalizeSelectedDifficulty();
		double localX = toHudLocalX(mouseX);
		double localY = toHudLocalY(mouseY);
		int width = getBaseDisplayWidth(client);
		Map<Difficulty, List<RaffleTask>> grouped = groupedVisibleTasks();
		int x = HUD_PAD;
		int y = TASK_START_Y;
		int rowW = width - HUD_PAD * 2;
		for (RaffleTask task : grouped.getOrDefault(selectedDifficulty, List.of())) {
			if (localX >= x && localX <= x + rowW && localY >= y && localY <= y + 11) {
				return task;
			}
			y += ROW_H;
		}
		return null;
	}

	public boolean handleScreenMouseClick(Minecraft client, double mouseX, double mouseY, int button) {
		return handleScreenMouseClick(client, mouseX, mouseY, button, false);
	}

	public boolean handleScreenMouseClick(Minecraft client, double mouseX, double mouseY, int button, boolean moveMode) {
		if (button != 0 || !isVisible(client, moveMode) || tasks.isEmpty() || needsNewTasks) {
			return false;
		}
		Difficulty clicked = tabAtMouse(client, (int) mouseX, (int) mouseY);
		if (clicked == null) {
			return false;
		}
		selectedDifficulty = clicked;
		return true;
	}

	private Difficulty tabAtMouse(Minecraft client, int mouseX, int mouseY) {
		normalizeSelectedDifficulty();
		double localX = toHudLocalX(mouseX);
		double localY = toHudLocalY(mouseY);
		if (localY < TAB_Y || localY > TAB_Y + TAB_H + 3) {
			return null;
		}
		List<Difficulty> tabs = displayedDifficulties();
		if (tabs.isEmpty()) {
			return null;
		}
		int width = getBaseDisplayWidth(client);
		int gap = 4;
		int available = width - HUD_PAD * 2 - gap * (tabs.size() - 1);
		int tabW = Math.max(24, available / tabs.size());
		int x = HUD_PAD;
		for (Difficulty difficulty : tabs) {
			if (localX >= x && localX <= x + tabW) {
				return difficulty;
			}
			x += tabW + gap;
		}
		return null;
	}

	private double toHudLocalX(int mouseX) {
		return (mouseX - hudX) / (double) hudScale;
	}

	private double toHudLocalY(int mouseY) {
		return (mouseY - hudY) / (double) hudScale;
	}

	private void drawTooltip(Minecraft client, GuiGraphicsExtractor guiGraphics, List<String> lines, int mouseX, int mouseY) {
		if (lines.isEmpty()) {
			return;
		}
		int pad = 4;
		int lineH = client.font.lineHeight + 1;
		int tw = lines.stream().mapToInt(line -> client.font.width(line)).max().orElse(0);
		int th = lines.size() * lineH - 1;
		int tx = mouseX + 8;
		int ty = mouseY - th - pad * 2;
		int screenW = client.getWindow().getGuiScaledWidth();
		int screenH = client.getWindow().getGuiScaledHeight();
		if (tx + tw + pad * 2 > screenW - 4) {
			tx = screenW - tw - pad * 2 - 4;
		}
		if (tx < 4) {
			tx = 4;
		}
		if (ty < 4) {
			ty = mouseY + 12;
		}
		if (ty + th + pad * 2 > screenH - 4) {
			ty = screenH - th - pad * 2 - 4;
		}
		if (ty < 4) {
			ty = 4;
		}
		guiGraphics.fill(tx - pad, ty - pad, tx + tw + pad, ty + th + pad, 0xE0000000);
		guiGraphics.fill(tx - pad, ty - pad, tx + tw + pad, ty - pad + 1, 0xFF6666AA);
		for (int i = 0; i < lines.size(); i++) {
			guiGraphics.text(client.font, lines.get(i), tx, ty + i * lineH, i == 0 ? C_TEXT : 0xFFCCCCFF, true);
		}
	}

	private List<String> buildTaskTooltip(Minecraft client, RaffleTask task) {
		List<String> lines = new ArrayList<>();
		lines.add(task.title());
		lines.add("");

		List<String> detailLines = usefulTaskDetailLines(task);
		if (detailLines.isEmpty()) {
			detailLines = List.of(task.title());
		}

		for (String detail : detailLines) {
			lines.addAll(wrapTooltipLine(client, detail, TOOLTIP_MAX_WIDTH));
		}
		lines.add("");
		lines.add("Incomplete");
		return lines;
	}

	private List<String> usefulTaskDetailLines(RaffleTask task) {
		List<String> details = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String line : task.lore()) {
			String cleaned = stripInstructionPrefix(line);
			String normalized = normalize(cleaned);
			if (!isUsefulTaskDetail(normalized, task) || !seen.add(normalized)) {
				continue;
			}
			details.add(cleaned);
		}
		if (details.isEmpty() && !normalize(task.title()).startsWith("TASK ")) {
			details.add(task.title());
		}
		return details;
	}

	private boolean isUsefulTaskDetail(String normalized, RaffleTask task) {
		if (normalized.isBlank()) {
			return false;
		}
		if (normalized.equals(normalize(task.title()))) {
			return false;
		}
		if (isDifficultyTaskLabel(normalized) || isTaskStatusLabel(normalized)) {
			return false;
		}
		return !normalized.contains("REWARD")
			&& !normalized.contains("CLICK")
			&& !normalized.contains("DIFFICULTY")
			&& !normalized.contains("TIME UNTIL")
			&& !normalized.contains("TASKS RESET")
			&& !normalized.contains("RAFFLE TASKS")
			&& !normalized.contains("RAFFLE TASK")
			&& !normalized.contains("RAFFLE TICKET");
	}

	private boolean isDifficultyTaskLabel(String normalized) {
		return normalized.equals("EASY")
			|| normalized.equals("MEDIUM")
			|| normalized.equals("HARD")
			|| normalized.equals("EASY TASK")
			|| normalized.equals("MEDIUM TASK")
			|| normalized.equals("HARD TASK")
			|| normalized.equals("EASY RAFFLE TASK")
			|| normalized.equals("MEDIUM RAFFLE TASK")
			|| normalized.equals("HARD RAFFLE TASK");
	}

	private boolean isTaskStatusLabel(String normalized) {
		return normalized.equals("INCOMPLETE")
			|| normalized.equals("COMPLETE")
			|| normalized.equals("COMPLETED")
			|| normalized.equals("DONE");
	}

	private boolean isCompletedTaskStack(String name, List<String> lore) {
		if (isCompletedTaskStatusLine(normalize(name))) {
			return true;
		}
		for (String line : lore) {
			if (isCompletedTaskStatusLine(normalize(line))) {
				return true;
			}
		}
		return false;
	}

	private boolean isCompletedTaskStatusLine(String normalized) {
		if (normalized.isBlank() || normalized.contains("INCOMPLETE")) {
			return false;
		}
		if (normalized.equals("COMPLETE") || normalized.equals("COMPLETED") || normalized.equals("DONE")) {
			return true;
		}
		return normalized.matches("^(STATUS|PROGRESS|TASK STATUS):?\\s+(COMPLETE|COMPLETED|DONE)$")
			|| normalized.matches(".*:\\s*(COMPLETE|COMPLETED|DONE)$");
	}

	private String stripInstructionPrefix(String line) {
		String cleaned = clean(line);
		return cleaned.replaceFirst("(?i)^(objective|task|goal)\\s*:?\\s*", "").trim();
	}

	private List<String> wrapTooltipLine(Minecraft client, String line, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : line.split("\\s+")) {
			if (word.isBlank()) {
				continue;
			}
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (client.font.width(candidate) <= maxWidth) {
				current.setLength(0);
				current.append(candidate);
				continue;
			}
			if (!current.isEmpty()) {
				lines.add(current.toString());
				current.setLength(0);
			}
			while (client.font.width(word) > maxWidth && word.length() > 1) {
				String chunk = fitPrefix(client, word, maxWidth);
				lines.add(chunk);
				word = word.substring(chunk.length());
			}
			current.append(word);
		}
		if (!current.isEmpty()) {
			lines.add(current.toString());
		}
		return lines;
	}

	private String fitPrefix(Minecraft client, String value, int maxWidth) {
		int length = value.length();
		while (length > 1 && client.font.width(value.substring(0, length)) > maxWidth) {
			length--;
		}
		return value.substring(0, Math.max(1, length));
	}

	private void drawCenteredStatus(Minecraft client, GuiGraphicsExtractor g, int width, int y, String text, int color) {
		g.text(client.font, text, (width - client.font.width(text)) / 2, y, color, true);
	}

	private void drawDropShadow(GuiGraphicsExtractor g, int width, int height) {
		g.fill(5, 5, width + 5, height + 5, 0x38000000);
		g.fill(3, 3, width + 3, height + 3, 0x30000000);
		g.fill(2, 2, width + 2, height + 2, 0x28000000);
	}

	private void drawBorder(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
		g.fill(x, y, x + width, y + 1, color);
		g.fill(x, y + height - 1, x + width, y + height, color);
		g.fill(x, y, x + 1, y + height, color);
		g.fill(x + width - 1, y, x + width, y + height, color);
	}

	private int scanOpenRaffleScreen(Minecraft client, long now) {
		if (client == null || !(client.screen instanceof AbstractContainerScreen<?> screen)) {
			promptOpenTasksScreen = false;
			return 0;
		}

		String title = clean(screen.getTitle().getString());
		List<Slot> slots = screen.getMenu().slots;
		if (!screenLooksLikeRaffle(title, slots)) {
			promptOpenTasksScreen = false;
			return 0;
		}

		List<RaffleTask> discovered = new ArrayList<>();
		int taskOrdinal = 0;
		int recognizedTaskStacks = 0;
		int containerSlots = Math.max(0, slots.size() - 36);
		for (int i = 0; i < containerSlots; i++) {
			Slot slot = slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String name = clean(stack.getHoverName().getString());
			List<String> lore = getLoreLines(stack);
			cacheResetTimer(name, lore, now);

			if (!isTaskStack(title, i, name, lore)) {
				continue;
			}
			recognizedTaskStacks++;
			if (isCompletedTaskStack(name, lore)) {
				taskOrdinal++;
				continue;
			}
			discovered.add(parseTask(taskOrdinal, i, name, lore));
			taskOrdinal++;
			if (discovered.size() >= 27) {
				break;
			}
		}

		if (recognizedTaskStacks >= MIN_TASK_SCREEN_STACKS) {
			boolean wasWaitingForNewTasks = needsNewTasks;
			tasks.clear();
			tasks.addAll(discovered);
			Set<String> ids = new HashSet<>();
			for (RaffleTask task : tasks) {
				ids.add(task.id());
			}
			if (wasWaitingForNewTasks) {
				hiddenTaskIds.clear();
			} else {
				hiddenTaskIds.retainAll(ids);
			}
			applyPendingCompletions(now);
			needsNewTasks = false;
			promptOpenTasksScreen = false;
		} else if (tasks.isEmpty() || needsNewTasks) {
			promptOpenTasksScreen = true;
		}

		return recognizedTaskStacks >= MIN_TASK_SCREEN_STACKS ? discovered.size() : 0;
	}

	private boolean screenLooksLikeRaffle(String title, List<Slot> slots) {
		if (normalize(title).contains("RAFFLE")) {
			return true;
		}
		int checked = 0;
		for (Slot slot : slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String combined = normalize(stack.getHoverName().getString() + " " + String.join(" ", getLoreLines(stack)));
			if (combined.contains("RAFFLE")) {
				return true;
			}
			checked++;
			if (checked >= 12) {
				break;
			}
		}
		return false;
	}

	private void cacheResetTimer(String name, List<String> lore, long now) {
		List<String> lines = new ArrayList<>();
		lines.add(name);
		lines.addAll(lore);
		for (String line : lines) {
			String normalized = normalize(line);
			if (!normalized.contains("TIME UNTIL RESET") && !normalized.contains("TASKS RESET AFTER")) {
				continue;
			}
			long durationMs = parseDurationMillis(line);
			if (durationMs > 0L) {
				resetAtMillis = now + durationMs;
				resetTimerSeen = true;
				if (needsNewTasks && !tasks.isEmpty()) {
					needsNewTasks = false;
				}
			}
		}
	}

	private boolean isTaskStack(String screenTitle, int slotIndex, String name, List<String> lore) {
		String normalizedTitle = normalize(screenTitle);
		String normalizedName = normalize(name);
		String combined = normalize(name + " " + String.join(" ", lore));
		if (combined.isEmpty() || combined.contains("TIME UNTIL RESET") || combined.contains("TASKS RESET AFTER")) {
			return false;
		}
		if (combined.equals("RAFFLE TASKS") || combined.contains("OPEN RAFFLE TASKS") || combined.contains("GO BACK") || combined.contains("CLOSE")) {
			return false;
		}
		if (isNonTaskRaffleEntry(normalizedName, combined)) {
			return false;
		}
		if (explicitDifficulty(name, lore) != Difficulty.UNKNOWN || hasObjectiveLine(name, lore)) {
			return true;
		}
		if (combined.contains("PROGRESS") && normalizedTitle.contains("RAFFLE TASK")) {
			return true;
		}
		return normalizedTitle.contains("RAFFLE TASK")
			&& slotIndex < 36
			&& !name.isBlank()
			&& !isDifficultyTaskLabel(normalizedName)
			&& !isTaskStatusLabel(normalizedName);
	}

	private RaffleTask parseTask(int ordinal, int slotIndex, String name, List<String> lore) {
		Difficulty difficulty = explicitDifficulty(name, lore);
		if (difficulty == Difficulty.UNKNOWN) {
			difficulty = Difficulty.fromOrdinal(ordinal);
		}

		String title = chooseTaskTitle(ordinal, name, lore);
		String id = normalize(title + " " + difficulty.name() + " " + slotIndex);
		return new RaffleTask(id, title, difficulty, List.copyOf(lore), slotIndex);
	}

	private boolean isNonTaskRaffleEntry(String normalizedName, String combined) {
		return normalizedName.contains("SPEED RAFFLE")
			|| normalizedName.matches(".*\\bRAFFLE\\s+\\d+\\b.*")
			|| normalizedName.contains("RAFFLE TICKET")
			|| combined.contains("SPEED RAFFLE")
			|| combined.contains("STARTS IN")
			|| combined.contains("ENDS IN");
	}

	private boolean hasObjectiveLine(String name, List<String> lore) {
		if (isObjectiveLine(normalize(name))) {
			return true;
		}
		for (String line : lore) {
			if (isObjectiveLine(normalize(line))) {
				return true;
			}
		}
		return false;
	}

	private boolean isObjectiveLine(String normalized) {
		if (normalized.isBlank() || isDifficultyTaskLabel(normalized) || isTaskStatusLabel(normalized)) {
			return false;
		}
		return normalized.contains("OBJECTIVE")
			|| normalized.startsWith("GOAL")
			|| (normalized.startsWith("TASK") && !normalized.contains("RAFFLE TASK"));
	}

	private Difficulty explicitDifficulty(String name, List<String> lore) {
		Difficulty fromName = Difficulty.fromExplicitLine(name);
		if (fromName != Difficulty.UNKNOWN) {
			return fromName;
		}
		for (String line : lore) {
			Difficulty fromLore = Difficulty.fromExplicitLine(line);
			if (fromLore != Difficulty.UNKNOWN) {
				return fromLore;
			}
		}
		return Difficulty.UNKNOWN;
	}

	private String chooseTaskTitle(int ordinal, String name, List<String> lore) {
		String normalizedName = normalize(name);
		if (!normalizedName.isBlank()
			&& !normalizedName.equals("RAFFLE TASK")
			&& !normalizedName.equals("RAFFLE TASKS")
			&& !normalizedName.endsWith(" RAFFLE TASK")
			&& !normalizedName.endsWith(" RAFFLE TASKS")
			&& !isDifficultyTaskLabel(normalizedName)
			&& !isTaskStatusLabel(normalizedName)) {
			return name;
		}

		for (String line : lore) {
			String normalized = normalize(line);
			if (normalized.contains("TIME UNTIL")
				|| normalized.contains("TASKS RESET")
				|| normalized.contains("REWARD")
				|| normalized.contains("PROGRESS")
				|| normalized.contains("CLICK")) {
				continue;
			}
			if (normalized.contains("OBJECTIVE") || normalized.startsWith("GOAL") || normalized.startsWith("TASK ")) {
				String candidate = stripInstructionPrefix(line);
				String candidateNormalized = normalize(candidate);
				if (candidateNormalized.length() >= 4
					&& !candidateNormalized.contains("RAFFLE TASK")
					&& !isDifficultyTaskLabel(candidateNormalized)
					&& !isTaskStatusLabel(candidateNormalized)) {
					return candidate;
				}
			}
		}

		for (String line : lore) {
			String normalized = normalize(line);
			if (normalized.isBlank()
				|| normalized.contains("REWARD")
				|| normalized.contains("PROGRESS")
				|| normalized.contains("CLICK")
				|| normalized.contains("DIFFICULTY")
				|| normalized.contains("TIME UNTIL")
				|| normalized.contains("RAFFLE TASKS RESET")
				|| isDifficultyTaskLabel(normalized)
				|| isTaskStatusLabel(normalized)) {
				continue;
			}
			if (normalized.length() >= 4) {
				return line;
			}
		}
		return "Task " + (ordinal + 1);
	}

	private boolean looksLikeCompletionMessage(String cleaned) {
		return cleaned.contains("COMPLETE")
			|| cleaned.contains("COMPLETED")
			|| cleaned.contains("DONE")
			|| cleaned.contains("FINISHED")
			|| cleaned.contains("CLAIMED")
			|| cleaned.contains("TASK FINISH");
	}

	private String extractExactCompletionTaskName(String rawMessage) {
		String plain = clean(rawMessage).replaceAll("^\\[\\d{1,2}:\\d{2}:\\d{2}]\\s*", "");
		Matcher matcher = EXACT_RAFFLE_COMPLETION_PATTERN.matcher(plain);
		if (!matcher.find()) {
			return "";
		}
		return clean(matcher.group(1));
	}

	private void rememberPendingCompletion(String completedTaskName, long now) {
		String normalized = normalize(completedTaskName);
		if (normalized.isBlank()) {
			return;
		}
		pendingCompletedTaskNames.put(normalized, now);
		while (pendingCompletedTaskNames.size() > 32) {
			String oldest = pendingCompletedTaskNames.keySet().iterator().next();
			pendingCompletedTaskNames.remove(oldest);
		}
	}

	private void applyPendingCompletions(long now) {
		prunePendingCompletions(now);
		for (var iterator = pendingCompletedTaskNames.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<String, Long> entry = iterator.next();
			if (hideCompletedTaskByName(entry.getKey())) {
				iterator.remove();
			}
		}
	}

	private void prunePendingCompletions(long now) {
		pendingCompletedTaskNames.entrySet().removeIf(entry -> now - entry.getValue() > PENDING_COMPLETION_TTL_MS);
	}

	private boolean hideCompletedTaskByName(String completedTaskName) {
		String normalizedCompletedName = normalize(completedTaskName);
		if (normalizedCompletedName.isBlank()) {
			return false;
		}

		RaffleTask bestPartialMatch = null;
		int bestScore = 0;
		for (RaffleTask task : visibleTasks()) {
			String normalizedTitle = normalize(task.title());
			String normalizedTaskText = normalize(task.title() + " " + String.join(" ", task.lore()));
			if (normalizedTitle.equals(normalizedCompletedName)
				|| normalizedTaskText.equals(normalizedCompletedName)
				|| normalizedTitle.contains(normalizedCompletedName)
				|| normalizedCompletedName.contains(normalizedTitle)) {
				hiddenTaskIds.add(task.id());
				return true;
			}

			int score = tokenOverlapScore(normalizedCompletedName, normalizedTaskText);
			if (score > bestScore) {
				bestScore = score;
				bestPartialMatch = task;
			}
		}

		if (bestPartialMatch != null && bestScore >= Math.min(2, significantTokens(normalizedCompletedName).size())) {
			hiddenTaskIds.add(bestPartialMatch.id());
			return true;
		}

		List<RaffleTask> visible = visibleTasks();
		if (visible.size() == 1) {
			hiddenTaskIds.add(visible.getFirst().id());
			return true;
		}
		return false;
	}

	private boolean matchesTask(String cleanedMessage, RaffleTask task) {
		String taskText = normalize(task.title() + " " + String.join(" ", task.lore()));
		if (!taskText.isBlank() && cleanedMessage.contains(taskText)) {
			return true;
		}

		List<String> tokens = significantTokens(taskText);
		if (tokens.isEmpty()) {
			return false;
		}
		int matched = 0;
		for (String token : tokens) {
			if (cleanedMessage.contains(token)) {
				matched++;
			}
		}
		int needed = Math.min(3, Math.max(1, (tokens.size() + 1) / 2));
		return matched >= needed;
	}

	private int tokenOverlapScore(String source, String target) {
		int matched = 0;
		for (String token : significantTokens(source)) {
			if (target.contains(token)) {
				matched++;
			}
		}
		return matched;
	}

	private List<String> significantTokens(String value) {
		List<String> tokens = new ArrayList<>();
		for (String part : normalize(value).split(" ")) {
			if (part.length() < 4 || STOP_WORDS.contains(part)) {
				continue;
			}
			tokens.add(part);
		}
		return tokens;
	}

	private List<RaffleTask> visibleTasks() {
		List<RaffleTask> visible = new ArrayList<>();
		for (RaffleTask task : tasks) {
			if (!hiddenTaskIds.contains(task.id())) {
				visible.add(task);
			}
		}
		return visible;
	}

	private Map<Difficulty, List<RaffleTask>> groupedVisibleTasks() {
		Map<Difficulty, List<RaffleTask>> grouped = new LinkedHashMap<>();
		for (Difficulty difficulty : Difficulty.TAB_ORDER) {
			grouped.put(difficulty, new ArrayList<>());
		}
		for (RaffleTask task : visibleTasks()) {
			grouped.computeIfAbsent(task.difficulty(), ignored -> new ArrayList<>()).add(task);
		}
		for (List<RaffleTask> group : grouped.values()) {
			group.sort(Comparator.comparingInt(RaffleTask::slotIndex));
		}
		return grouped;
	}

	private List<Difficulty> displayedDifficulties() {
		if (tasks.isEmpty()) {
			return Difficulty.TAB_ORDER;
		}
		Map<Difficulty, List<RaffleTask>> grouped = groupedVisibleTasks();
		List<Difficulty> visibleDifficulties = new ArrayList<>();
		for (Difficulty difficulty : Difficulty.TAB_ORDER) {
			if (!grouped.getOrDefault(difficulty, List.of()).isEmpty()) {
				visibleDifficulties.add(difficulty);
			}
		}
		return visibleDifficulties.isEmpty() ? Difficulty.TAB_ORDER : visibleDifficulties;
	}

	private void normalizeSelectedDifficulty() {
		List<Difficulty> tabs = displayedDifficulties();
		if (!tabs.contains(selectedDifficulty)) {
			selectedDifficulty = tabs.isEmpty() ? Difficulty.EASY : tabs.getFirst();
		}
	}

	private String timerText() {
		long now = System.currentTimeMillis();
		if (resetAtMillis > now) {
			return "Reset in " + formatDuration(resetAtMillis - now);
		}
		if (needsNewTasks) {
			return "Task cache expired";
		}
		if (resetTimerSeen) {
			return "Reset timer elapsed";
		}
		return "Waiting for reset timer";
	}

	private long parseDurationMillis(String value) {
		String cleaned = clean(value);
		Matcher unitMatcher = UNIT_DURATION_PATTERN.matcher(cleaned);
		long totalSeconds = 0L;
		while (unitMatcher.find()) {
			long amount = parseLong(unitMatcher.group(1));
			String unit = unitMatcher.group(2).toLowerCase(Locale.ROOT);
			if (unit.startsWith("d")) {
				totalSeconds += amount * 86_400L;
			} else if (unit.startsWith("h")) {
				totalSeconds += amount * 3_600L;
			} else if (unit.startsWith("m")) {
				totalSeconds += amount * 60L;
			} else {
				totalSeconds += amount;
			}
		}
		if (totalSeconds > 0L) {
			return totalSeconds * 1000L;
		}

		Matcher colonMatcher = COLON_DURATION_PATTERN.matcher(cleaned);
		if (!colonMatcher.find()) {
			return 0L;
		}
		long first = parseLong(colonMatcher.group(1));
		long second = parseLong(colonMatcher.group(2));
		String thirdGroup = colonMatcher.group(3);
		if (thirdGroup == null) {
			return (first * 60L + second) * 1000L;
		}
		long third = parseLong(thirdGroup);
		return (first * 3_600L + second * 60L + third) * 1000L;
	}

	private long parseLong(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	private List<String> getLoreLines(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) {
			return List.of();
		}
		List<String> lines = new ArrayList<>();
		lore.styledLines().forEach(line -> {
			String cleaned = clean(line.getString());
			if (!cleaned.isBlank()) {
				lines.add(cleaned);
			}
		});
		return lines;
	}

	private String clean(String value) {
		String stripped = ChatFormatting.stripFormatting(value == null ? "" : value);
		if (stripped == null) {
			return "";
		}
		return stripped
			.replaceAll("(?i)&[0-9A-FK-OR]", "")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private String normalize(String value) {
		return clean(value)
			.toUpperCase(Locale.ROOT)
			.replaceAll("[^A-Z0-9:]+", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private String ellipsize(Minecraft client, String value, int maxWidth) {
		if (client.font.width(value) <= maxWidth) {
			return value;
		}
		String ellipsis = "...";
		int ellipsisWidth = client.font.width(ellipsis);
		String result = value;
		while (!result.isEmpty() && client.font.width(result) + ellipsisWidth > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}
		return result.isEmpty() ? ellipsis : result + ellipsis;
	}

	private String formatDuration(long millis) {
		long seconds = Math.max(0L, millis / 1000L);
		long hours = seconds / 3600L;
		long minutes = (seconds % 3600L) / 60L;
		long secs = seconds % 60L;
		if (hours > 0L) {
			return hours + "h " + minutes + "m";
		}
		if (minutes > 0L) {
			return minutes + "m " + secs + "s";
		}
		return secs + "s";
	}

	private boolean isVisible(Minecraft client, boolean moveMode) {
		if (client == null || client.player == null) {
			return false;
		}
		if (moveMode) {
			return true;
		}
		return enabled && (!tasks.isEmpty() || needsNewTasks || promptOpenTasksScreen);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		RaffleTrackerConfigManager.updateEnabled(enabled);
	}

	public int getHudX() {
		return hudX;
	}

	public int getHudY() {
		return hudY;
	}

	public int getHudScalePercent() {
		return Math.round(hudScale * 100.0F);
	}

	public int getDisplayWidth(Minecraft client) {
		return scaledHudSize(getBaseDisplayWidth(client));
	}

	public int getDisplayHeight(Minecraft client) {
		return scaledHudSize(getBaseDisplayHeight(client));
	}

	public void setHudPosition(Minecraft client, int x, int y, boolean save) {
		if (client == null) {
			return;
		}
		int maxX = Math.max(0, client.getWindow().getGuiScaledWidth() - getDisplayWidth(client));
		int maxY = Math.max(0, client.getWindow().getGuiScaledHeight() - getDisplayHeight(client));
		hudX = clamp(x, 0, maxX);
		hudY = clamp(y, 0, maxY);
		if (save) {
			saveHudLayout();
		}
	}

	public void growHudScale(Minecraft client, boolean save) {
		setHudScale(client, hudScale + HUD_SCALE_STEP, save);
	}

	public void shrinkHudScale(Minecraft client, boolean save) {
		setHudScale(client, hudScale - HUD_SCALE_STEP, save);
	}

	public void setHudScale(Minecraft client, float scale, boolean save) {
		if (client == null) {
			return;
		}
		hudScale = clampHudScale(scale);
		setHudPosition(client, hudX, hudY, false);
		if (save) {
			saveHudLayout();
		}
	}

	private void saveHudLayout() {
		RaffleTrackerConfigManager.updateHudLayout(hudX, hudY, hudScale);
	}

	private int getBaseDisplayWidth(Minecraft client) {
		return HUD_BASE_WIDTH;
	}

	private int getBaseDisplayHeight(Minecraft client) {
		List<RaffleTask> visible = visibleTasks();
		if (visible.isEmpty()) {
			return 72;
		}
		normalizeSelectedDifficulty();
		int selectedRows = groupedVisibleTasks().getOrDefault(selectedDifficulty, List.of()).size();
		return TASK_START_Y + selectedRows * ROW_H + 18;
	}

	private int scaledHudSize(int baseSize) {
		if (baseSize <= 0) {
			return 0;
		}
		return (int) Math.ceil(baseSize * hudScale);
	}

	private static float clampHudScale(float scale) {
		if (!Float.isFinite(scale) || scale <= 0.0F) {
			return 1.0F;
		}
		float rounded = Math.round(scale * 10.0F) / 10.0F;
		return Math.max(MIN_HUD_SCALE, Math.min(MAX_HUD_SCALE, rounded));
	}

	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private record RaffleTask(String id, String title, Difficulty difficulty, List<String> lore, int slotIndex) {
	}

	private enum Difficulty {
		HARD("Hard", 0xFFFF6B6B, 0x26FF4141),
		MEDIUM("Medium", 0xFFFFD15A, 0x26FFC84A),
		EASY("Easy", 0xFF73F28A, 0x2641FF6A),
		UNKNOWN("Task", C_TEXT, 0x201C2634);

		private static final List<Difficulty> TAB_ORDER = List.of(EASY, MEDIUM, HARD);
		private final String label;
		private final int textColor;
		private final int rowColor;

		Difficulty(String label, int textColor, int rowColor) {
			this.label = label;
			this.textColor = textColor;
			this.rowColor = rowColor;
		}

		private static Difficulty fromExplicitLine(String value) {
			String normalized = ChatFormatting.stripFormatting(value == null ? "" : value);
			normalized = normalized == null ? "" : normalized
				.replaceAll("(?i)&[0-9A-FK-OR]", "")
				.toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z0-9:]+", " ")
				.replaceAll("\\s+", " ")
				.trim();
			String flat = normalized.replace(':', ' ').replaceAll("\\s+", " ").trim();
			if (flat.equals("HARD") || flat.equals("HARD TASK") || flat.equals("HARD RAFFLE TASK")) {
				return HARD;
			}
			if (flat.equals("MEDIUM") || flat.equals("MEDIUM TASK") || flat.equals("MEDIUM RAFFLE TASK")) {
				return MEDIUM;
			}
			if (flat.equals("EASY") || flat.equals("EASY TASK") || flat.equals("EASY RAFFLE TASK")) {
				return EASY;
			}
			if (hasToken(flat, "DIFFICULTY")) {
				if (hasToken(flat, "HARD")) {
					return HARD;
				}
				if (hasToken(flat, "MEDIUM")) {
					return MEDIUM;
				}
				if (hasToken(flat, "EASY")) {
					return EASY;
				}
			}
			return UNKNOWN;
		}

		private static boolean hasToken(String value, String token) {
			for (String part : value.split(" ")) {
				if (part.equals(token)) {
					return true;
				}
			}
			return false;
		}

		private static Difficulty fromOrdinal(int ordinal) {
			int group = ordinal / 9;
			return switch (group) {
				case 0 -> HARD;
				case 1 -> MEDIUM;
				default -> EASY;
			};
		}
	}
}
