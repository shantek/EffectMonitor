package io.shantek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.*;
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

    private KeyMapping openConfigKey;
    private KeyMapping toggleNotificationsKey;

    private final File configFile = new File("config/effectmonitor.properties");

    @Override
    public void onInitializeClient() {
        loadSettings();

        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.effectmonitor.config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                KeyMapping.Category.MISC
        ));

        toggleNotificationsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.effectmonitor.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                client.gui.setScreen(new ConfigScreen());
            }

            while (toggleNotificationsKey.consumeClick()) {
                notificationsEnabled = !notificationsEnabled;
                saveSettings();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("Effect Monitor: " + (notificationsEnabled ? "Enabled" : "Disabled")));
                }
            }

            if (client.player == null) return;

            LocalPlayer player = client.player;
            UUID uuid = player.getUUID();

            Map<String, Set<Integer>> playerAlerts = alerted.computeIfAbsent(uuid, k -> new HashMap<>());

            for (MobEffectInstance effect : player.getActiveEffects()) {
                MobEffect effectType = effect.getEffect().value();

                MobEffectCategory category = effectType.getCategory();
                if (effectFilter == EffectFilter.POSITIVE && category != MobEffectCategory.BENEFICIAL) continue;
                if (effectFilter == EffectFilter.NEGATIVE && category != MobEffectCategory.HARMFUL) continue;

                int secondsLeft = effect.getDuration() / 20;
                String effectName = effectType.getDescriptionId().replace("effect.minecraft.", "");

                Set<Integer> alertedThresholds = playerAlerts.computeIfAbsent(effectName, k -> new HashSet<>());

                int previousLowest = alertedThresholds.stream().min(Integer::compare).orElse(Integer.MAX_VALUE);
                if (secondsLeft > previousLowest) {
                    alertedThresholds.clear();
                }

                for (int threshold : thresholds) {
                    if (secondsLeft < threshold) {
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
                            client.gui.hud.setTitle(Component.literal("⏳ Effect Fading"));
                            client.gui.hud.setSubtitle(Component.literal(formattedName + " ends in " + timeText));
                            client.gui.hud.setTimes(10, 40, 10);
                        } else {
                            player.sendSystemMessage(Component.literal("⏳ " + formattedName + " ends in " + timeText));
                        }

                        if (soundEnabled) {
                            player.playSound(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 4.0F, 1.0F);
                        }
                    }
                }
            }

            playerAlerts.entrySet().removeIf(entry ->
                    player.getActiveEffects().stream().noneMatch(effect ->
                            effect.getEffect().value().getDescriptionId().replace("effect.minecraft.", "").equals(entry.getKey())));
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
            super(Component.literal("Effect Monitor Config"));
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int topY = 35;
            int buttonHeight = 20;
            int lineSpacing = 25;
            int sectionY = topY;

            this.addRenderableWidget(Button.builder(Component.literal("Mode: " + currentMode), button -> {
                currentMode = (currentMode == DisplayMode.TITLE) ? DisplayMode.ACTIONBAR : DisplayMode.TITLE;
                saveSettings();
                button.setMessage(Component.literal("Mode: " + currentMode));
            }).bounds(centerX - 100, sectionY, 200, buttonHeight).build());
            sectionY += lineSpacing;

            this.addRenderableWidget(Button.builder(Component.literal("Sound: " + (soundEnabled ? "On" : "Off")), button -> {
                soundEnabled = !soundEnabled;
                saveSettings();
                button.setMessage(Component.literal("Sound: " + (soundEnabled ? "On" : "Off")));
            }).bounds(centerX - 100, sectionY, 200, buttonHeight).build());
            sectionY += lineSpacing;

            this.addRenderableWidget(Button.builder(Component.literal("Notifications: " + (notificationsEnabled ? "Enabled" : "Disabled")), button -> {
                notificationsEnabled = !notificationsEnabled;
                saveSettings();
                button.setMessage(Component.literal("Notifications: " + (notificationsEnabled ? "Enabled" : "Disabled")));
            }).bounds(centerX - 100, sectionY, 200, buttonHeight).build());
            sectionY += lineSpacing;

            this.addRenderableWidget(Button.builder(Component.literal("Effects Shown: " + formatEffectFilter(effectFilter)), button -> {
                switch (effectFilter) {
                    case ALL -> effectFilter = EffectFilter.POSITIVE;
                    case POSITIVE -> effectFilter = EffectFilter.NEGATIVE;
                    case NEGATIVE -> effectFilter = EffectFilter.ALL;
                }
                saveSettings();
                button.setMessage(Component.literal("Effects Shown: " + formatEffectFilter(effectFilter)));
            }).bounds(centerX - 100, sectionY, 200, buttonHeight).build());
            sectionY += lineSpacing + 10;

            for (int i = 0; i < thresholds.size(); i++) {
                final int index = i;
                int threshold = thresholds.get(index);
                int posY = sectionY + i * lineSpacing;

                String formatted = threshold >= 60
                        ? (threshold / 60) + "m" + (threshold % 60 > 0 ? " " + (threshold % 60) + "s" : "")
                        : threshold + "s";

                this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                    int newVal = threshold - 5;
                    if (newVal >= 5 && !thresholds.contains(newVal)) {
                        thresholds.set(index, newVal);
                        thresholds.sort(Collections.reverseOrder());
                        saveSettings();
                        Minecraft.getInstance().gui.setScreen(new ConfigScreen());
                    }
                }).bounds(centerX - 70, posY, 20, 20).tooltip(Tooltip.create(Component.literal("Decrease by 5s"))).build());

                this.addRenderableWidget(Button.builder(Component.literal(formatted), b -> {})
                        .bounds(centerX - 45, posY, 90, 20).build());

                this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                    int newVal = threshold + 5;
                    if (newVal <= 240 && !thresholds.contains(newVal)) {
                        thresholds.set(index, newVal);
                        thresholds.sort(Collections.reverseOrder());
                        saveSettings();
                        Minecraft.getInstance().gui.setScreen(new ConfigScreen());
                    }
                }).bounds(centerX + 50, posY, 20, 20).tooltip(Tooltip.create(Component.literal("Increase by 5s"))).build());

                if (thresholds.size() > 1) {
                    this.addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                        thresholds.remove(index);
                        thresholds.sort(Collections.reverseOrder());
                        saveSettings();
                        Minecraft.getInstance().gui.setScreen(new ConfigScreen());
                    }).bounds(centerX + 75, posY, 20, 20).tooltip(Tooltip.create(Component.literal("Remove this threshold"))).build());
                }
            }

            if (thresholds.size() < 3) {
                this.addRenderableWidget(Button.builder(Component.literal("+ Add Threshold"), b -> {
                            int candidate = 5;
                            while (thresholds.contains(candidate)) candidate += 5;
                            if (candidate <= 240) {
                                thresholds.add(candidate);
                                thresholds.sort(Collections.reverseOrder());
                                saveSettings();
                                Minecraft.getInstance().gui.setScreen(new ConfigScreen());
                            }
                        }).bounds(centerX - 100, sectionY + thresholds.size() * lineSpacing, 200, 20)
                        .tooltip(Tooltip.create(Component.literal("Add a new threshold (max 3)"))).build());
            }

            this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
                    .bounds(centerX - 100, sectionY + thresholds.size() * lineSpacing + 30, 200, 20).build());
        }

        private String formatEffectFilter(EffectFilter filter) {
            String name = filter.name().toLowerCase();
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}