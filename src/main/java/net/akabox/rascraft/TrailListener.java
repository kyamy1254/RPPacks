package net.akabox.rascraft;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

public class TrailListener implements Listener {
    private final RascraftPluginPacks plugin;

    public TrailListener(RascraftPluginPacks plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.isTrailEnabled()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;

        Player p = event.getPlayer();
        String effectName = plugin.getPlayerEffect(p.getUniqueId());
        if (effectName == null) return;

        // 軽量化: 水平方向に動いていない場合はスキップ
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return;

        // --- エリトラ飛行中の特殊処理 ---
        if (p.isGliding()) {
            spawnElytraParticles(p, effectName);
            return; // 通常の足あとエフェクトはスキップ
        }

        // 距離チェック（微小な動きは無視して負荷軽減）
        double distance = from.distance(to);
        if (distance < 0.05) return;

        if (effectName == null) return;

        // 設定された間隔（例: 0.2ブロックごとに1回パーティクルを出す）
        double interval = plugin.getTrailInterval();

        // 距離が間隔より短い場合は処理しない（次の移動まで待つ）
        // ※厳密には累積距離を計算すべきですが、簡易実装としてこれで十分機能します
        if (distance < interval) return;

        RascraftPluginPacks.ColoredParticleData colored = plugin.getColoredEffectMap().get(effectName.toUpperCase());
        int density = plugin.getEffectDensity(effectName);

        // --- 線形補間処理 (Interpolation) ---
        // From から To に向かって、interval ごとに点を打つ
        Vector direction = to.toVector().subtract(from.toVector()).normalize();

        // スタート地点を少しずらす（足元ぴったりだと埋もれるため +0.1）
        Location startLoc = from.clone().add(0, 0.1, 0);

        for (double d = 0; d <= distance; d += interval) {
            // 現在の描画地点を計算: Start + (方向 * 距離)
            Location point = startLoc.clone().add(direction.clone().multiply(d));
            spawnParticleAt(point, effectName, density, colored);
        }
    }

    // パーティクル生成処理をメソッド化
    private void spawnParticleAt(Location loc, String name, int density, RascraftPluginPacks.ColoredParticleData colored) {
        if (colored != null) {
            if (colored.particle() == Particle.DUST) {
                loc.getWorld().spawnParticle(Particle.DUST, loc, density, 0.1, 0, 0.1, new Particle.DustOptions(colored.color(), colored.size()));
            } else if (colored.particle() == Particle.NOTE) {
                for (int i = 0; i < density; i++) {
                    loc.getWorld().spawnParticle(Particle.NOTE, loc, 0, colored.noteColor(), 0, 0, 1);
                }
            } else {
                loc.getWorld().spawnParticle(colored.particle(), loc, density, 0.1, 0, 0.1, 0.02);
            }
        } else {
            try {
                Particle pType = Particle.valueOf(name.toUpperCase());
                loc.getWorld().spawnParticle(pType, loc, density, 0.1, 0, 0.1, 0.02);
            } catch (Exception ignored) {
            }
        }
    }

    private void spawnElytraParticles(Player p, String effectName) {
        Location loc = p.getLocation();
        // プレイヤーの向きから「右」方向のベクトルを取得
        // 90度回転させることで翼の広がる方向を計算
        Vector direction = loc.getDirection();
        Vector rightW = new Vector(-direction.getZ(), 0.1, direction.getX()).normalize();
        Vector leftW = rightW.clone().multiply(-1);

        // 翼の端の位置（中心から約1.2ブロック外側）
        Location leftWingPos = loc.clone().add(leftW.multiply(1.2)).add(0, 0.5, 0);
        Location rightWingPos = loc.clone().add(rightW.multiply(1.2)).add(0, 0.5, 0);

        int density = plugin.getEffectDensity(effectName);
        RascraftPluginPacks.ColoredParticleData colored = plugin.getColoredEffectMap().get(effectName.toUpperCase());

        // 両方の翼端に表示
        spawnParticleAt(leftWingPos, effectName, density, colored);
        spawnParticleAt(rightWingPos, effectName, density, colored);
    }
}