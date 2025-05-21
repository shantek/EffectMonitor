package io.shantek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.screen.Screen;
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
	private final Map<String, Integer> thresholds = Map.of(
			"60", 60,
			"30", 30,
			"5", 5
	);

	private KeyBinding openConfigKey;
	private KeyBinding toggleNotificationsKey;

	private final File configFile = new File("config/effectmonitor.properties");

	@Override
	public void onInitializeClient() {
		loadSettings();

		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.effectmonitor.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "Effect Monitor"
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

			if (!notificationsEnabled || client.player == null) return;

			ClientPlayerEntity player = client.player;
			UUID uuid = player.getUuid();

			Map<String, Set<Integer>> playerAlerts = alerted.computeIfAbsent(uuid, k -> new HashMap<>());

			for (StatusEffectInstance effect : player.getStatusEffects()) {

				StatusEffect effectType = getStatusEffect(effect);
				if (effectType == null) continue;

				StatusEffectCategory category = effectType.getCategory();

				if (effectFilter == EffectFilter.POSITIVE && category != StatusEffectCategory.BENEFICIAL) continue;
				if (effectFilter == EffectFilter.NEGATIVE && category != StatusEffectCategory.HARMFUL) continue;

				int secondsLeft = effect.getDuration() / 20;
				String effectName = effect.getTranslationKey().replace("effect.minecraft.", "");

				for (int threshold : thresholds.values()) {
					if (secondsLeft == threshold && !playerAlerts.getOrDefault(effectName, new HashSet<>()).contains(threshold)) {
						String formattedName = effectName.isEmpty()
								? "Unknown"
								: Character.toUpperCase(effectName.charAt(0)) + effectName.substring(1);


						if (currentMode == DisplayMode.TITLE) {
							client.inGameHud.setTitle(Text.literal("⏳ Effect Fading"));
							client.inGameHud.setSubtitle(Text.literal(formattedName + " ends in " + threshold + "s"));
							client.inGameHud.setTitleTicks(10, 40, 10);
						} else {
							player.sendMessage(Text.literal("⏳ " + formattedName + " ends in " + threshold + "s"), true);
						}

						if (soundEnabled) {
							player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 4.0F, 1.0F);
						}

						playerAlerts.computeIfAbsent(effectName, k -> new HashSet<>()).add(threshold);
					}
				}
			}

			playerAlerts.entrySet().removeIf(entry ->
					player.getStatusEffects().stream().noneMatch(effect ->
							effect.getTranslationKey().replace("effect.minecraft.", "").equals(entry.getKey()))
			);
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
			} catch (IOException ignored) {}
		}
	}

	private StatusEffect getStatusEffect(StatusEffectInstance instance) {
		try {
			Method getEffectType = StatusEffectInstance.class.getDeclaredMethod("getEffectType");
			getEffectType.setAccessible(true);
			return (StatusEffect) getEffectType.invoke(instance);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
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
			props.store(new FileWriter(configFile), "Effect Monitor Config");
		} catch (IOException ignored) {}
	}

	private class ConfigScreen extends Screen {
		private ButtonWidget displayToggleButton;
		private ButtonWidget soundToggleButton;
		private ButtonWidget enabledToggleButton;
		private ButtonWidget filterToggleButton;

		protected ConfigScreen() {
			super(Text.literal("Effect Monitor Config"));
		}

		@Override
		protected void init() {
			int centerX = this.width / 2;
			int centerY = this.height / 2;

			displayToggleButton = ButtonWidget.builder(getDisplayModeText(), button -> {
				currentMode = (currentMode == DisplayMode.TITLE) ? DisplayMode.ACTIONBAR : DisplayMode.TITLE;
				saveSettings();
				button.setMessage(getDisplayModeText());
			}).dimensions(centerX - 100, centerY - 70, 200, 20).build();
			this.addDrawableChild(displayToggleButton);

			soundToggleButton = ButtonWidget.builder(getSoundToggleText(), button -> {
				soundEnabled = !soundEnabled;
				saveSettings();
				button.setMessage(getSoundToggleText());
			}).dimensions(centerX - 100, centerY - 40, 200, 20).build();
			this.addDrawableChild(soundToggleButton);

			enabledToggleButton = ButtonWidget.builder(getEnabledToggleText(), button -> {
				notificationsEnabled = !notificationsEnabled;
				saveSettings();
				button.setMessage(getEnabledToggleText());
			}).dimensions(centerX - 100, centerY - 10, 200, 20).build();
			this.addDrawableChild(enabledToggleButton);

			filterToggleButton = ButtonWidget.builder(getFilterToggleText(), button -> {
				switch (effectFilter) {
					case ALL -> effectFilter = EffectFilter.POSITIVE;
					case POSITIVE -> effectFilter = EffectFilter.NEGATIVE;
					case NEGATIVE -> effectFilter = EffectFilter.ALL;
				}
				saveSettings();
				button.setMessage(getFilterToggleText());
			}).dimensions(centerX - 100, centerY + 20, 200, 20).build();
			this.addDrawableChild(filterToggleButton);

			this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> this.close())
					.dimensions(centerX - 100, centerY + 50, 200, 20).build());
		}

		private Text getDisplayModeText() {
			return Text.literal("Mode: " + (currentMode == DisplayMode.TITLE ? "Title" : "Actionbar"));
		}

		private Text getSoundToggleText() {
			return Text.literal("Sound: " + (soundEnabled ? "On" : "Off"));
		}

		private Text getEnabledToggleText() {
			return Text.literal("Notifications: " + (notificationsEnabled ? "Enabled" : "Disabled"));
		}

		private Text getFilterToggleText() {
			return Text.literal("Effects Shown: " + switch (effectFilter) {
				case ALL -> "All";
				case POSITIVE -> "Positive Only";
				case NEGATIVE -> "Negative Only";
			});
		}

		@Override
		public boolean shouldPause() {
			return false;
		}
	}