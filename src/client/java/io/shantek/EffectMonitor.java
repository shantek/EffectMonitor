package io.shantek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class EffectMonitor implements ClientModInitializer {

	enum DisplayMode {
		TITLE, ACTIONBAR
	}

	enum EffectFilter {
		ALL, POSITIVE, NEGATIVE
	}

	private DisplayMode currentMode = DisplayMode.ACTIONBAR;
	private EffectFilter effectFilter = EffectFilter.ALL;
	private boolean soundEnabled = true;
	private boolean notificationsEnabled = true;

	private final Map<UUID, Map<String, Set<Integer>>> alerted = new HashMap<>();
	private final List<Integer> thresholds = new ArrayList<>(List.of(60, 30, 5));

	private KeyBinding openConfigKey;
	private KeyBinding toggleNotificationsKey;

	private final File configFile = new File("config/effectmonitor.properties");

	@Override
	public void onInitializeClient() {
		loadSettings();

		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.effectmonitor.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "Effect Monitor"
		));

		toggleNotificationsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.effectmonitor.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "Effect Monitor"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openConfigKey.wasPressed()) {
				client.setScreen(new ConfigScreen());
			}

			while (toggleNotificationsKey.wasPressed()) {
				notificationsEnabled = !notificationsEnabled;
				saveSettings();
				if (client.player != null) {
					client.player.sendMessage(Text.literal("Effect Monitor: " + (notificationsEnabled ? "Enabled" : "Disabled")), true);
				}
			}

			if (client.player == null) return;

			ClientPlayerEntity player = client.player;
			UUID uuid = player.getUuid();

			Map<String, Set<Integer>> playerAlerts = alerted.computeIfAbsent(uuid, k -> new HashMap<>());

			for (StatusEffectInstance effect : player.getStatusEffects()) {
				StatusEffect effectType = effect.getEffectType().value();

				StatusEffectCategory category = effectType.getCategory();
				if (effectFilter == EffectFilter.POSITIVE && category != StatusEffectCategory.BENEFICIAL) continue;
				if (effectFilter == EffectFilter.NEGATIVE && category != StatusEffectCategory.HARMFUL) continue;

				int secondsLeft = effect.getDuration() / 20;
				String effectName = effectType.getTranslationKey().replace("effect.minecraft.", "");

				Set<Integer> alertedThresholds = playerAlerts.computeIfAbsent(effectName, k -> new HashSet<>());

				// Reset alerts if the effect was reapplied with more time
				int previousLowest = alertedThresholds.stream().min(Integer::compare).orElse(Integer.MAX_VALUE);
				if (secondsLeft > previousLowest) {
					alertedThresholds.clear();
				}

				for (int threshold : thresholds) {
					if (secondsLeft < threshold) {
						// If we're already past the threshold, mark it as alerted silently
						alertedThresholds.add(threshold);
						continue;
					}

					if (!alertedThresholds.contains(threshold) && secondsLeft == threshold) {
						alertedThresholds.add(threshold);

						if (!notificationsEnabled) continue;

						String formattedName = effectName.isEmpty()
								? "Unknown"
								: Arrays.stream(effectName.replace("_", " ").split(" "))
								.filter(w -> !w.isEmpty())
								.map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
								.collect(Collectors.joining(" "));

						String timeText = secondsLeft >= 60
								? (secondsLeft / 60) + "m" + (secondsLeft % 60 > 0 ? " " + (secondsLeft % 60) + "s" : "")
								: secondsLeft + "s";

						if (currentMode == DisplayMode.TITLE) {
							client.inGameHud.setTitle(Text.literal("⏳ Effect Fading"));
							client.inGameHud.setSubtitle(Text.literal(formattedName + " ends in " + timeText));
							client.inGameHud.setTitleTicks(10, 40, 10);
						} else {
							player.sendMessage(Text.literal("⏳ " + formattedName + " ends in " + timeText), true);
						}

						if (soundEnabled) {
							player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 4.0F, 1.0F);
						}
					}
				}
			}

			// Remove alerts for effects that are no longer active
			playerAlerts.entrySet().removeIf(entry ->
					player.getStatusEffects().stream().noneMatch(effect ->
							effect.getEffectType().value().getTranslationKey().replace("effect.minecraft.", "").equals(entry.getKey())));
		});
	}

	private void loadSettings() {
		if (configFile.exists()) {
			try (FileReader reader = new FileReader(configFile)) {
				Properties props = new Properties();
				props.load(reader);
				currentMode = DisplayMode.valueOf(props.getProperty("mode", "ACTIONBAR"));
				soundEnabled = Boolean.parseBoolean(props.getProperty("sound", "true"));
				notificationsEnabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
				effectFilter = EffectFilter.valueOf(props.getProperty("effectFilter", "ALL"));

				String thresholdStr = props.getProperty("thresholds", "60,30,5");
				thresholds.clear();
				for (String s : thresholdStr.split(",")) {
					try {
						int val = Integer.parseInt(s.trim());
						if (!thresholds.contains(val)) thresholds.add(val);
					} catch (NumberFormatException ignored) {}
				}
				Collections.sort(thresholds);
			} catch (IOException ignored) {}
		}
	}

	private void saveSettings() {
		try {
			configFile.getParentFile().mkdirs();
			Properties props = new Properties();
			props.setProperty("mode", currentMode.name());
			props.setProperty("sound", Boolean.toString(soundEnabled));
			props.setProperty("enabled", Boolean.toString(notificationsEnabled));
			props.setProperty("effectFilter", effectFilter.name());
			props.setProperty("thresholds", thresholds.stream().map(Object::toString).collect(Collectors.joining(",")));
			props.store(new FileWriter(configFile), "Effect Monitor Config");
		} catch (IOException ignored) {}
	}

	private class ConfigScreen extends Screen {
		protected ConfigScreen() {
			super(Text.literal("Effect Monitor Config"));
		}

		@Override
		public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
			super.render(context, mouseX, mouseY, delta);

			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§lEffect Monitor"), this.width / 2, 15, 0xFFFFFF);

		}

		@Override
		protected void init() {
			int centerX = this.width / 2;
			int topY = 35; // less top padding for the whole content

			// Title is rendered in render(), this is control section now
			int buttonHeight = 20;
			int lineSpacing = 25;
			int sectionY = topY;

			// Mode toggle
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Mode: " + currentMode), button -> {
				currentMode = (currentMode == DisplayMode.TITLE) ? DisplayMode.ACTIONBAR : DisplayMode.TITLE;
				saveSettings();
				button.setMessage(Text.literal("Mode: " + currentMode));
			}).dimensions(centerX - 100, sectionY, 200, buttonHeight).build());
			sectionY += lineSpacing;

			// Sound toggle
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Sound: " + (soundEnabled ? "On" : "Off")), button -> {
				soundEnabled = !soundEnabled;
				saveSettings();
				button.setMessage(Text.literal("Sound: " + (soundEnabled ? "On" : "Off")));
			}).dimensions(centerX - 100, sectionY, 200, buttonHeight).build());
			sectionY += lineSpacing;

			// Notifications toggle
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Notifications: " + (notificationsEnabled ? "Enabled" : "Disabled")), button -> {
				notificationsEnabled = !notificationsEnabled;
				saveSettings();
				button.setMessage(Text.literal("Notifications: " + (notificationsEnabled ? "Enabled" : "Disabled")));
			}).dimensions(centerX - 100, sectionY, 200, buttonHeight).build());
			sectionY += lineSpacing;

			// Effect filter toggle
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Effects Shown: " + formatEffectFilter(effectFilter)), button -> {
				switch (effectFilter) {
					case ALL -> effectFilter = EffectFilter.POSITIVE;
					case POSITIVE -> effectFilter = EffectFilter.NEGATIVE;
					case NEGATIVE -> effectFilter = EffectFilter.ALL;
				}
				saveSettings();
				button.setMessage(Text.literal("Effects Shown: " + formatEffectFilter(effectFilter)));
			}).dimensions(centerX - 100, sectionY, 200, buttonHeight).build());
			sectionY += lineSpacing + 10;

			// Threshold buttons centered
			for (int i = 0; i < thresholds.size(); i++) {
				final int index = i;
				int threshold = thresholds.get(index);
				int posY = sectionY + i * lineSpacing;

				String formatted = threshold >= 60
						? (threshold / 60) + "m" + (threshold % 60 > 0 ? " " + (threshold % 60) + "s" : "")
						: threshold + "s";

				this.addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
							int newVal = threshold - 5;
							if (newVal >= 5 && !thresholds.contains(newVal)) {
								thresholds.set(index, newVal);
								thresholds.sort(Collections.reverseOrder());
								saveSettings();
								MinecraftClient.getInstance().setScreen(new ConfigScreen());
							}
						}).dimensions(centerX - 70, posY, 20, 20)
						.tooltip(Tooltip.of(Text.literal("Decrease by 5s"))).build());

				this.addDrawableChild(ButtonWidget.builder(Text.literal(formatted), b -> {})
						.dimensions(centerX - 45, posY, 90, 20).build());

				this.addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
							int newVal = threshold + 5;
							if (newVal <= 240 && !thresholds.contains(newVal)) {
								thresholds.set(index, newVal);
								thresholds.sort(Collections.reverseOrder());
								saveSettings();
								MinecraftClient.getInstance().setScreen(new ConfigScreen());
							}
						}).dimensions(centerX + 50, posY, 20, 20)
						.tooltip(Tooltip.of(Text.literal("Increase by 5s"))).build());

				if (thresholds.size() > 1) {
					this.addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> {
								thresholds.remove(index);
								thresholds.sort(Collections.reverseOrder());
								saveSettings();
								MinecraftClient.getInstance().setScreen(new ConfigScreen());
							}).dimensions(centerX + 75, posY, 20, 20)
							.tooltip(Tooltip.of(Text.literal("Remove this threshold"))).build());
				}
			}

			// Add button
			if (thresholds.size() < 3) {
				this.addDrawableChild(ButtonWidget.builder(Text.literal("+ Add Threshold"), b -> {
							int candidate = 5;
							while (thresholds.contains(candidate)) candidate += 5;
							if (candidate <= 240) {
								thresholds.add(candidate);
								thresholds.sort(Collections.reverseOrder());
								saveSettings();
								MinecraftClient.getInstance().setScreen(new ConfigScreen());
							}
						}).dimensions(centerX - 100, sectionY + thresholds.size() * lineSpacing, 200, 20)
						.tooltip(Tooltip.of(Text.literal("Add a new threshold (max 3)"))).build());
			}

			// Close button
			this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> this.close())
					.dimensions(centerX - 100, sectionY + thresholds.size() * lineSpacing + 30, 200, 20).build());
		}

		private String formatEffectFilter(EffectFilter filter) {
			String name = filter.name().toLowerCase();
			return Character.toUpperCase(name.charAt(0)) + name.substring(1);
		}


		@Override
		public boolean shouldPause() {
			return false;
		}
	}

}
