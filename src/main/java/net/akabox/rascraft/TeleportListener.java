package net.akabox.rascraft;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class TeleportListener implements Listener {
    private final RascraftPluginPacks plugin;

    public TeleportListener(RascraftPluginPacks plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        FileConfiguration config = plugin.getConfig();

        // 有効化チェック
        if (!plugin.isTpEffectEnabled()) return;

        // コマンド(COMMAND)またはプラグイン(PLUGIN)によるテレポートのみを対象にする
        // これにより、エンドパール、ネザーゲート、コーラスフルーツ等は除外される
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }

        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        // 1. ワープ元 (FROM) の演出
        if (from.getWorld() != null) {
            String fromPName = config.getString("teleport-effect.from-particle", "trial_spawner_detection");
            Particle fromParticle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(fromPName));
            if (fromParticle != null) {
                from.getWorld().spawnParticle(fromParticle, from.clone().add(0, 1.0, 0), 40, 0.4, 0.4, 0.4, 0.05);
            }
        }

        // 2. ワープ先 (TO) の演出 (遅延実行)
        if (to != null && to.getWorld() != null) {
            long delay = config.getLong("teleport-effect.delay-ticks", 10L);
            String toPName = config.getString("teleport-effect.to-particle", "trial_spawner_detection_ominous");

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) return;

                    Location current = player.getLocation();
                    Particle toParticle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(toPName));

                    if (toParticle != null) {
                        // 本人に直接送るパケット
                        player.spawnParticle(toParticle, current.clone().add(0, 1.0, 0), 60, 0.5, 0.5, 0.5, 0.05);
                        // 周囲のプレイヤーに見せるパケット
                        current.getWorld().spawnParticle(toParticle, current.clone().add(0, 1.0, 0), 40, 0.5, 0.5, 0.5, 0.05);
                    }
                }
            }.runTaskLater(plugin, delay);
        }
    }
}