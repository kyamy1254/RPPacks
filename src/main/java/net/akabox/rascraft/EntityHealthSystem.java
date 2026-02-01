package net.akabox.rascraft;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;

public class EntityHealthSystem implements Listener {
    private final RascraftPluginPacks plugin;
    private final HashMap<UUID, LivingEntity> lastAttackedTarget = new HashMap<>();
    private final HashMap<UUID, Integer> displayTimer = new HashMap<>();
    private final HashMap<UUID, String> lastSentMessage = new HashMap<>();
    private final HashMap<UUID, Long> lastSentTime = new HashMap<>(); // アクションバー消失防止用
    private final DecimalFormat df = new DecimalFormat("0.0");
    private final Random random = new Random();
    private org.bukkit.scheduler.BukkitTask rayTask = null;

    private final String[] gaugeLeft = new String[21];
    private final String[] gaugeRight = new String[21];

    public EntityHealthSystem(RascraftPluginPacks plugin) {
        this.plugin = plugin;
        precomputeGauges();
        // 起動時は plugin のフラグに従ってタスクを開始
        if (plugin.isEntityHealthEnabled()) startRayTraceTask();
    }

    private void precomputeGauges() {
        for (int i = 0; i <= 20; i++) {
            gaugeLeft[i] = "|".repeat(i);
            gaugeRight[i] = "|".repeat(20 - i);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastAttackedTarget.remove(uuid);
        displayTimer.remove(uuid);
        lastSentMessage.remove(uuid);
        lastSentTime.remove(uuid);
    }

    private void startRayTraceTask() {
        rayTask = new BukkitRunnable() {
            @Override
            public void run() {
                double maxDistance = plugin.getHealthViewDistance();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    LivingEntity eyeTarget = getTargetEntity(player, maxDistance);

                    if (eyeTarget != null) {
                        lastAttackedTarget.put(uuid, eyeTarget);
                        // ここは変更せずコンフィグ値を維持
                        displayTimer.put(uuid, plugin.getHealthDisplayTicks());
                        sendHealthBar(player, eyeTarget);
                    } else {
                        int ticksLeft = displayTimer.getOrDefault(uuid, 0);
                        if (ticksLeft > 0) {
                            displayTimer.put(uuid, ticksLeft - 4);
                            LivingEntity last = lastAttackedTarget.get(uuid);
                            if (last != null && last.isValid() && !last.isDead()) {
                                sendHealthBar(player, last);
                            } else {
                                clearActionBar(player, uuid);
                            }
                        } else {
                            clearActionBar(player, uuid);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 4L);
    }

    public void disable() {
        if (rayTask != null) {
            rayTask.cancel();
            rayTask = null;
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    public void enable() {
        // リスナー登録は外側で行われることを想定
        if (rayTask == null) startRayTraceTask();
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // config.yml から範囲を取得 (デフォルト値は 16.0)
        double configRange = plugin.getConfig().getDouble("damage-indicator.share-continuous-damage.range", 16.0);
        double rangeSquared = configRange * configRange;

        // 最大体力を取得して実際の回復量を計算
        double maxH = target.getAttribute(Attribute.MAX_HEALTH).getValue();
        double currentH = target.getHealth();
        double actualAmount = Math.min(event.getAmount(), maxH - currentH);

        // 0.1未満の微細な回復は表示しない
        if (actualAmount < 0.1) return;

        // ワールド内の全プレイヤーに対して処理
        for (Player p : target.getWorld().getPlayers()) {
            // config で設定された範囲内かどうかを判定
            if (p.getLocation().distanceSquared(target.getLocation()) <= rangeSquared) {

                // 1. 自己回復以外ならインジケーターを表示
                if (!target.equals(p)) {
                    showIndicator(p, target, actualAmount, false, true);
                }

                // 2. 現在ターゲット中ならアクションバーを即座に更新
                if (target.equals(lastAttackedTarget.get(p.getUniqueId()))) {
                    displayTimer.put(p.getUniqueId(), plugin.getHealthDisplayTicks());
                    sendHealthBar(p, target);
                }
            }
        }
    }

    private void sendHealthBar(Player player, LivingEntity target) {
        UUID uuid = player.getUniqueId();
        var maxAttr = target.getAttribute(Attribute.MAX_HEALTH);
        if (maxAttr == null) return;

        double max = maxAttr.getValue();
        double current = Math.max(0, target.getHealth());
        double ratio = current / max;
        int index = Math.max(0, Math.min(20, (int) (ratio * 20)));

        // 2. 状態異常アイコンの構築
        StringBuilder icons = new StringBuilder();
        if (target.getFireTicks() > 0) icons.append("§6🔥");
        if (target.hasPotionEffect(PotionEffectType.POISON)) icons.append("§2☣");
        if (target.hasPotionEffect(PotionEffectType.WITHER)) icons.append("§8☠");
        if (target.hasPotionEffect(PotionEffectType.DARKNESS) || target.hasPotionEffect(PotionEffectType.WEAKNESS))
            icons.append("§d✦");
        if (icons.length() > 0) icons.insert(0, " ");

        // 3. 低体力時の点滅 (20%以下)
        String color = (ratio > 0.5) ? "§a" : (ratio > 0.25) ? "§6" : "§c";
        if (ratio <= 0.2 && (System.currentTimeMillis() / 250) % 2 == 0) {
            color = "§f"; // 0.25秒ごとに白く光る
        }

        String name = target.getCustomName() != null ? target.getCustomName() : target.getName();
        String message = "§f" + name + icons + " §7[" + color + gaugeLeft[index] + "§8" + gaugeRight[index] + "§7] " + color + df.format(current) + "§f/§e" + df.format(max);

        // アクションバーの消失防止（パケット再送）
        long now = System.currentTimeMillis();
        if (message.equals(lastSentMessage.get(uuid)) && (now - lastSentTime.getOrDefault(uuid, 0L) < 2000)) {
            return;
        }

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
        lastSentMessage.put(uuid, message);
        lastSentTime.put(uuid, now);
    }

    private void spawnEasingIndicator(Player observer, Location loc, String text, boolean isCritical) {
        TextDisplay display = (TextDisplay) loc.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
        display.setVisibleByDefault(false);
        observer.showEntity(plugin, display);

        display.setText(text);
        display.setBillboard(TextDisplay.Billboard.CENTER);
        display.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
        display.setShadowed(true);
        display.setSeeThrough(true);
        display.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));

        final double randX = (random.nextDouble() - 0.5) * 0.5;
        final double randZ = (random.nextDouble() - 0.5) * 0.5;

        new BukkitRunnable() {
            final int maxTicks = 16;
            final Location startLoc = loc.clone();
            int ticks = 0;

            @Override
            public void run() {
                if (!display.isValid() || !observer.isOnline() || ticks >= maxTicks) {
                    display.remove();
                    this.cancel();
                    return;
                }

                double t = (double) ticks / maxTicks;
                double progressY = 1 - Math.pow(1 - t, 4);

                // 座標更新（飛び散り）
                display.teleport(startLoc.clone().add(randX * t, progressY * 1.1, randZ * t));

                // スケール計算
                float baseScale = isCritical ? 1.1f : 1.0f;
                float dist = (float) observer.getLocation().distance(display.getLocation());
// 出現時のポップアップ演出 (t=0.2で最大になり、その後緩やかに縮小)
                float scaleEffect = (t < 0.2 ? (float) (t / 0.2) : (float) (1 - (t - 0.2) * 0.5));
// 遠くても見えるようにする補正を維持しつつ最終サイズを決定
                float finalScale = baseScale * scaleEffect * Math.max(1.0f, dist / 7.0f);

// --- 回転アニメーション (Z軸: 45度(PI/4)から0度へ) ---
// 出現時(t=0)に45度傾き、0.3秒(t=0.3)で水平に戻る
                float rotationAngle = (t < 0.3) ? (float) ((Math.PI / 4.0) * (1.0 - (t / 0.3))) : 0f;

// 修正ポイント: rotateY ではなく rotateZ を使用
// 反時計回りにしたい場合は -(Math.PI / 4.0) にしてください
                Quaternionf rotation = new Quaternionf().rotateZ(rotationAngle);

                display.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),    // Translation
                        rotation,                 // Left Rotation (Z軸回転を適用)
                        new Vector3f(finalScale), // Scale (調整したサイズを適用)
                        new Quaternionf()         // Right Rotation
                ));

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // --- 既存の補助メソッド ---

    private void clearActionBar(Player player, UUID uuid) {
        if (lastSentMessage.containsKey(uuid)) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            lastSentMessage.remove(uuid);
            lastSentTime.remove(uuid);
        }
    }

    private LivingEntity getTargetEntity(Player player, double maxDistance) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult blockHit = player.getWorld().rayTraceBlocks(eye, direction, maxDistance, FluidCollisionMode.NEVER, false);
        double effectiveDistance = (blockHit != null && blockHit.getHitPosition() != null) ? eye.toVector().distance(blockHit.getHitPosition()) : maxDistance;

        RayTraceResult entityHit = player.getWorld().rayTraceEntities(eye, direction, effectiveDistance, 0.5, entity -> (entity instanceof LivingEntity && !entity.equals(player) && !entity.isDead()));
        return (entityHit != null && entityHit.getHitEntity() instanceof LivingEntity target) ? target : null;
    }

    private void showIndicator(Player observer, LivingEntity target, double amount, boolean isCritical, boolean isHeal) {
        if (!plugin.isIndicatorEnabled()) return;
        String text = isHeal ? "§a+" + df.format(amount) + "❤" : (isCritical ? "§6§l-" + df.format(amount) + "❤" : "§c-" + df.format(amount) + "❤");
        spawnEasingIndicator(observer, calculateIndicatorLocation(observer, target), text, isCritical);
    }

    private Location calculateIndicatorLocation(Player observer, LivingEntity target) {
        Location targetLoc = target.getLocation();
        Location playerEye = observer.getEyeLocation();
        double entityHeight = target.getBoundingBox().getHeight();
        double distance = playerEye.distance(targetLoc);

        double yOffset = (distance < 2.0) ? entityHeight * 0.10 : (distance < 5.0) ? entityHeight * 0.5 : entityHeight + 0.2;
        Location baseLoc = targetLoc.clone().add(0, yOffset, 0);

        Vector toPlayer = playerEye.toVector().subtract(baseLoc.toVector());
        if (toPlayer.lengthSquared() > 0.01) {
            baseLoc.add(toPlayer.normalize().multiply(Math.min(0.6, distance * 0.2)));
        }
        return baseLoc;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target) || event.getFinalDamage() <= 0) return;
        Player playerSource = null;
        boolean isCritical = false;

        if (event.getDamager() instanceof Player p) {
            playerSource = p;
            isCritical = p.getFallDistance() > 0.0F && !p.isOnGround();
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            playerSource = p;
            if (proj instanceof AbstractArrow arrow) isCritical = arrow.isCritical();
        }

        if (playerSource != null) {
            displayTimer.put(playerSource.getUniqueId(), plugin.getHealthDisplayTicks());
            lastAttackedTarget.put(playerSource.getUniqueId(), target);
            sendHealthBar(playerSource, target);
            showIndicator(playerSource, target, event.getFinalDamage(), isCritical, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGeneralDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // --- 設定から範囲を取得 ---
        // config.yml の damage-indicator.share-continuous-damage.range を参照
        double configRange = plugin.getConfig().getDouble("damage-indicator.share-continuous-damage.range", 16.0);
        double rangeSquared = configRange * configRange;

        EntityDamageEvent.DamageCause cause = event.getCause();
        String indicatorText = null;
        double dmg = event.getFinalDamage();

        // 🔥 修正: MAGIC かつ ポーション投擲などによって発生したダメージ（間接的）をスキップする
        // これにより onEntityDamageByEntity 側での表示と重複するのを防ぐ
        if (cause == EntityDamageEvent.DamageCause.MAGIC) {
            // ダメージポーションなどは通常、攻撃者が存在するため onEntityDamageByEntity で処理される
            // 毒やウィザーなど「持続的な魔法ダメージ」ではない純粋なMAGICはここでは無視する
            return;
        }

        // 状態異常に応じたテキスト設定
        switch (cause) {
            case FIRE_TICK:
            case FIRE:
            case LAVA:
                indicatorText = "§6-" + df.format(dmg) + "🔥";
                break;
            case POISON:
                indicatorText = "§2-" + df.format(dmg) + "☣";
                break;
            case WITHER:
                indicatorText = "§8-" + df.format(dmg) + "☠";
                break;
            case MAGIC:
                indicatorText = "§d-" + df.format(dmg) + "✦";
                break;
        }

        if (indicatorText != null) {
            // 同じワールド内のプレイヤーに対して範囲判定を行う
            for (Player p : target.getWorld().getPlayers()) {
                // コンフィグで設定された距離（2乗比較）以内にいるか
                if (p.getLocation().distanceSquared(target.getLocation()) <= rangeSquared) {

                    if (!target.equals(p)) {
                        spawnEasingIndicator(p, calculateIndicatorLocation(p, target), indicatorText, false);
                    }

                    // もしターゲット中ならアクションバーも更新
                    if (target.equals(lastAttackedTarget.get(p.getUniqueId()))) {
                        displayTimer.put(p.getUniqueId(), plugin.getHealthDisplayTicks());
                        sendHealthBar(p, target);
                    }
                }
            }
        }
    }
}