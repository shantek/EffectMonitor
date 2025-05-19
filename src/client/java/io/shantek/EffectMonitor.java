package io.shantek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.client.MinecraftClient;

import java.util.*;

public class EffectMonitor implements ClientModInitializer {

	private final Map<String, Integer> thresholds = Map.of(
			"60", 60,
			"30", 30,
			"5", 5
	);

	private final Map<UUID, Map<String, Set<Integer>>> alerted = new HashMap<>();

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null) {
				ClientPlayerEntity player = client.player;
				UUID uuid = player.getUuid();

				Map<String, Set<Integer>> playerAlerts = alerted.computeIfAbsent(uuid, k -> new HashMap<>());

				for (StatusEffectInstance effect : player.getStatusEffects()) {
					int secondsLeft = effect.getDuration() / 20;
					String effectName = effect.getTranslationKey().replace("effect.minecraft.", "");

					for (int threshold : thresholds.values()) {
						if (secondsLeft == threshold && !playerAlerts.getOrDefault(effectName, new HashSet<>()).contains(threshold)) {

							String formattedName = effectName.substring(0, 1).toUpperCase() + effectName.substring(1);

							client.inGameHud.setTitle(Text.literal("⏳ Effect Fading"));
							client.inGameHud.setSubtitle(Text.literal(formattedName + " ends in " + threshold + "s"));
							client.inGameHud.setTitleTicks(10, 40, 10);


							// ✅ Play appropriate sound
							player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);

							// Cache that we've already shown this alert
							playerAlerts.computeIfAbsent(effectName, k -> new HashSet<>()).add(threshold);
						}
					}
				}

				// Clear alerts for removed effects
				playerAlerts.entrySet().removeIf(entry ->
						player.getStatusEffects().stream().noneMatch(effect ->
								effect.getTranslationKey().replace("effect.minecraft.", "").equals(entry.getKey()))
				);
			}
		});
	}

}
