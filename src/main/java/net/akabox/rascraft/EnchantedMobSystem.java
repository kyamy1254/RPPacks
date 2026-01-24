package net.akabox.rascraft;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Random;

public class EnchantedMobSystem implements Listener {
    private final RascraftPluginPacks plugin;
    private final NamespacedKey enchantedKey;
    private final Random random = new Random();
    private final java.util.Set<java.util.UUID> reinforcedEntities = new java.util.HashSet<>();

    public EnchantedMobSystem(RascraftPluginPacks plugin) {
        this.plugin = plugin;
        this.enchantedKey = new NamespacedKey(plugin, "is_enchanted");
    }

    // --- 1. スポーン時にEnchanted化 ---
    @EventHandler
    public void onSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Monster monster)) return;
        startLootingAI(monster);

        // 15%の確率でEnchanted Mobに進化
        if (random.nextDouble() < 0.15) {
            makeEnchanted(monster);
        }
    }

    private final java.util.Map<org.bukkit.block.Block, Integer> activeBlocks = new java.util.HashMap<>();

    public void makeEnchanted(Monster monster) {
        monster.getPersistentDataContainer().set(enchantedKey, PersistentDataType.BYTE, (byte) 1);

        // 基本ステータス（HP・名前）の適用
        var hp = monster.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * 2.5);
            monster.setHealth(hp.getBaseValue());
        }
        monster.setCustomName("§d§lEnchanted " + monster.getType().getName());
        monster.setCustomNameVisible(false);

        // 装備の設定
        applyEnchantedEquipment(monster);

        startCoreNavigationAI(monster);

        // --- 種類別AIの有効化 (ここが統合のキモです) ---
        if (monster instanceof Zombie zombie) {
            startClimbingAI(zombie);    // 建築
            startCommanderAura(zombie); // 指揮官
            startLootingAI(zombie);     // 拾得
        } else if (monster instanceof Skeleton skeleton) {
            startClimbingAI(skeleton);  // 建築
            startLootingAI(skeleton);   // 拾得
            // ※偏差撃ちなどはEvent側で判定されるためタスク起動は不要
        } else if (monster instanceof Creeper creeper) {
            startBurstAI(creeper);      // 【追加】バースト突進
        } else if (monster instanceof Spider spider) {
            startLootingAI(spider);     // 拾得
            // ※クモのWebTrap/飛びかかりはEvent/個体判定で処理
        }

        // 共通オーラエフェクトの開始
        startVisualAura(monster);
    }

    // コードの可読性のために装備処理を分離
    private void applyEnchantedEquipment(Monster monster) {
        EntityEquipment equip = monster.getEquipment();
        if (equip == null) return;

        // 鉄装備のセットアップ
        ItemStack helmet = new ItemStack(Material.IRON_HELMET);
        ItemStack chest = new ItemStack(Material.IRON_CHESTPLATE);
        helmet.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
        chest.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
        equip.setHelmet(helmet);
        equip.setChestplate(chest);
        equip.setHelmetDropChance(0f);
        equip.setChestplateDropChance(0f);

        ItemStack weapon = null;
        if (monster instanceof Zombie) {
            double roll = random.nextDouble();
            if (roll < 0.4) {
                if (roll < 0.1) weapon = new ItemStack(Material.DIAMOND_SWORD);
                else if (roll < 0.2) weapon = new ItemStack(Material.IRON_AXE);
                else if (roll < 0.3) weapon = new ItemStack(Material.CROSSBOW);
                else weapon = new ItemStack(Material.IRON_SWORD);
            }
        } else if (monster instanceof Skeleton) {
            weapon = new ItemStack(Material.BOW);
        }

        if (weapon != null) {
            applyRandomEnchant(weapon);
            equip.setItemInMainHand(weapon);
            equip.setItemInMainHandDropChance(0.05f);
        }
    }

    private boolean isEnchanted(Entity entity) {
        return entity.getPersistentDataContainer().has(enchantedKey, PersistentDataType.BYTE);
    }

    private void startVisualAura(Monster monster) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    cancel();
                    return;
                }
                monster.getWorld().spawnParticle(Particle.WITCH, monster.getLocation().add(0, 1, 0), 1, 0.2, 0.4, 0.2, 0.01);
            }
        }.runTaskTimer(plugin, 0, 5);
    }

    private void startCoreNavigationAI(Monster monster) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    cancel();
                    return;
                }

                LivingEntity target = monster.getTarget();

                // --- A. 捜索範囲の拡大 (32〜48マス) ---
                // バニラの16マス制限を突破し、より遠くのプレイヤーを検知
                if (target == null || target.isDead() || !target.isValid()) {
                    target = findExtendedTarget(monster, 40.0);
                    if (target != null) monster.setTarget(target);
                }

                if (target == null) return;

                // --- B. 経路の動的更新と移動速度の最適化 ---
                double distance = monster.getLocation().distance(target.getLocation());

                // 距離に応じて移動スタイルを変更
                float speedMultiplier = 1.0f;
                if (distance > 15) {
                    speedMultiplier = 1.25f; // 遠距離では全力疾走
                } else if (distance < 4) {
                    speedMultiplier = 1.1f;  // 接敵時は慎重に接近
                }

                // バニラのPathfinderに目的地を再提示し続ける
                // これにより、建築AIが置いたブロックを即座に「通れる道」と認識させる
                monster.getPathfinder().moveTo(target, speedMultiplier);

                // --- C. クモ専用：壁登りと索敵の同期 ---
                if (monster instanceof Spider spider && distance < 10) {
                    // ターゲットが自分より高い位置にいる場合、積極的に壁へ張り付く
                    if (target.getLocation().getY() > spider.getLocation().getY()) {
                        Vector toTarget = target.getLocation().toVector().subtract(spider.getLocation().toVector()).normalize();
                        spider.setVelocity(spider.getVelocity().add(toTarget.multiply(0.1)));
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 10); // 0.5秒ごとに脳を更新
    }

    // --- 2. スケルトンのAI強化 (偏差撃ち & バックステップ) ---
    @EventHandler
    public void onSkeletonShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Skeleton skeleton) || !isEnchanted(skeleton)) return;
        if (!(skeleton.getTarget() instanceof Player player)) return;

        // --- 超精密・偏差撃ち (命中率アップ版) ---
        Vector pVel = player.getVelocity();
        // 矢の速度を取得
        Vector arrowVel = event.getProjectile().getVelocity();
        double speed = arrowVel.length();
        double dist = skeleton.getLocation().distance(player.getLocation());

        // 予測時間を計算（少しだけ長めに予測して回避を困難にする）
        double predictionTime = dist / speed;

        // 未来の位置 = プレイヤー位置 + (速度 * 予測時間 * 補正)
        // Y軸にも少し補正を入れてヘッドショットを狙いやすくする
        Vector predictedPos = player.getEyeLocation().toVector().add(pVel.multiply(predictionTime * 1.8));

        // 重力による矢のドロップを考慮して、少し上を狙う
        double gravityComp = 0.05 * Math.pow(predictionTime, 2);
        predictedPos.add(new Vector(0, gravityComp, 0));

        Vector newDir = predictedPos.subtract(skeleton.getEyeLocation().toVector()).normalize();
        event.getProjectile().setVelocity(newDir.multiply(speed));
    }

    @EventHandler
    public void onSkeletonAI(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Skeleton skeleton) || !isEnchanted(skeleton)) return;
        if (!(event.getTarget() instanceof Player player)) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!skeleton.isValid() || skeleton.isDead()) {
                    cancel();
                    return;
                }
                double dist = skeleton.getLocation().distance(player.getLocation());
                EntityEquipment equip = skeleton.getEquipment();
                if (equip == null) return;

                // 5マス以内なら斧に持ち替えて接近
                if (dist <= 5.0) {
                    if (equip.getItemInMainHand().getType() != Material.STONE_AXE) {
                        equip.setItemInMainHand(new ItemStack(Material.STONE_AXE));
                        skeleton.getWorld().playSound(skeleton.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 1.2f);
                    }
                    // 接近させるために速度を少し上げる
                    Vector dash = player.getLocation().toVector().subtract(skeleton.getLocation().toVector()).normalize().multiply(0.3);
                    skeleton.setVelocity(skeleton.getVelocity().add(dash));
                } else {
                    if (equip.getItemInMainHand().getType() != Material.BOW) {
                        equip.setItemInMainHand(new ItemStack(Material.BOW));
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 10);
    }

    @EventHandler
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Monster attacker && event.getEntity() instanceof Monster victim) {
            // 周囲10マスに Enchanted Skeleton がいれば仲間割れをキャンセル
            for (Entity nearby : attacker.getNearbyEntities(10, 5, 10)) {
                if (nearby instanceof Skeleton sk && isEnchanted(sk)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    // 拡張索敵メソッド
    private LivingEntity findExtendedTarget(Monster monster, double range) {
        return monster.getNearbyEntities(range, range / 2, range).stream()
                .filter(e -> e instanceof Player p && p.getGameMode() == GameMode.SURVIVAL)
                .map(e -> (LivingEntity) e)
                .findFirst()
                .orElse(null);
    }

    // --- 3. ゾンビのAI強化 (呼び声 & 執念) ---
    @EventHandler
    public void onZombieAttack(EntityDamageByEntityEvent event) {
        // A. 強化ゾンビがプレイヤーを殴った場合
        if (event.getDamager() instanceof Zombie zombie && isEnchanted(zombie)) {
            if (event.getEntity() instanceof Player player) {
                applyZombieSwarm(zombie, player);
            }
        }

        // B. プレイヤーが強化ゾンビを殴った場合（これがないと、殴られた瞬間にターゲットが外れることがある）
        if (event.getEntity() instanceof Zombie victim && isEnchanted(victim)) {
            if (event.getDamager() instanceof Player player) {
                applyZombieSwarm(victim, player);
            }
        }
    }

    private void applyZombieSwarm(Zombie leadZombie, Player target) {
        // 1. 本人も確実にターゲットを追わせる
        leadZombie.setTarget(target);

        // 2. 周囲のゾンビを呼ぶ
        for (Entity nearby : leadZombie.getNearbyEntities(15, 7, 15)) {
            if (nearby instanceof Zombie fellow) {
                fellow.setTarget(target);

                /* * ポーション効果による加速バフ
                 * 移動速度上昇 I (Lv1) を 10秒間 (200 ticks) 付与。
                 * 既にバフがある場合は時間がリフレッシュされるだけで、速度は蓄積されない。
                 */
                fellow.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 0, false, true, true));

                // 仲間が反応した視覚演出
                if (random.nextDouble() < 0.2) {
                    fellow.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, fellow.getLocation().add(0, 2, 0), 1);
                }
            }
        }
    }

    // デス時にUUIDをセットから削除してメモリ漏洩を防ぐ
    @EventHandler
    public void onEnchantedDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        reinforcedEntities.remove(event.getEntity().getUniqueId());
    }

    // --- 6. クリーパーのAI: サイドステップ & 不意打ち加速 ---
    @EventHandler
    public void onCreeperUpdate(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper) || !isEnchanted(creeper)) return;
        if (!(event.getTarget() instanceof Player player)) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!creeper.isValid() || creeper.isDead() || !player.isOnline()) {
                    cancel();
                    return;
                }

                Location cLoc = creeper.getLocation();
                Location pLoc = player.getLocation();
                Vector toCreeper = cLoc.toVector().subtract(pLoc.toVector());
                double distance = cLoc.distance(pLoc);

                // プレイヤーの視線方向ベクトル
                Vector playerDirection = player.getEyeLocation().getDirection();
                double dot = playerDirection.normalize().dot(toCreeper.normalize());

                AttributeInstance speed = creeper.getAttribute(Attribute.MOVEMENT_SPEED);
                AttributeInstance speedInstance = creeper.getAttribute(Attribute.MOVEMENT_SPEED);

                // A. サイドステップ (プレイヤーに見られている時)
                if (dot > 0.98 && distance < 15) { // 視線がほぼクリーパーを捉えている
                    Vector sideStep = new Vector(-playerDirection.getZ(), 0, playerDirection.getX()).normalize().multiply(0.5);
                    if (random.nextBoolean()) sideStep.multiply(-1); // 左右ランダム
                    creeper.setVelocity(creeper.getVelocity().add(sideStep));
                    creeper.getWorld().spawnParticle(Particle.CLOUD, cLoc, 1, 0.1, 0.1, 0.1, 0.05);
                }

                // B. 不意打ち加速 (プレイヤーが背を向けている時)
                if (dot < 0 && speed != null) {
                    speed.setBaseValue(0.45); // 通常の倍近い速度
                } else if (speed != null) {
                    speed.setBaseValue(0.25);
                }

                // C. 跳躍爆破 (起爆寸前)
                if (creeper.isIgnited() || (distance < 3.0 && random.nextDouble() < 0.1)) {
                    if (creeper.isOnGround()) {
                        creeper.setVelocity(new Vector(0, 0.4, 0));
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 5);
    }

    // --- 7. クモのAI: 粘着糸 & 飛びかかり ---
    @EventHandler
    public void onSpiderAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Spider spider) || !isEnchanted(spider)) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // 粘着糸 (Web Trap): プレイヤーの足元にクモの巣を設置 (3秒で消える)
        Location feet = player.getLocation();
        if (feet.getBlock().getType() == Material.AIR) {
            feet.getBlock().setType(Material.COBWEB);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (feet.getBlock().getType() == Material.COBWEB) feet.getBlock().setType(Material.AIR);
                }
            }.runTaskLater(plugin, 60); // 3秒後
            player.sendMessage("§2§lSpider Web! §a足元を固められた！");
            player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_DEATH, 1f, 2f);
        }
    }

    @EventHandler
    public void onSpiderTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Spider spider) || !isEnchanted(spider)) return;
        if (!(event.getTarget() instanceof Player player)) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!spider.isValid() || spider.isDead()) {
                    cancel();
                    return;
                }
                double dist = spider.getLocation().distance(player.getLocation());

                // 飛びかかり奇襲 (間合いが5~10ブロックの時)
                if (dist > 5 && dist < 10 && spider.isOnGround() && random.nextDouble() < 0.2) {
                    Vector leap = player.getLocation().toVector().subtract(spider.getLocation().toVector()).normalize().multiply(1.2);
                    leap.setY(0.5);
                    spider.setVelocity(leap);
                    spider.getWorld().playSound(spider.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.5f);
                }
            }
        }.runTaskTimer(plugin, 0, 20);
    }

    private void applyRandomEnchant(ItemStack item) {
        Enchantment[] enchants;
        if (item.getType() == Material.CROSSBOW) {
            enchants = new Enchantment[]{Enchantment.QUICK_CHARGE, Enchantment.MULTISHOT, Enchantment.PIERCING};
        } else if (item.getType() == Material.IRON_AXE) {
            enchants = new Enchantment[]{Enchantment.SHARPNESS, Enchantment.KNOCKBACK};
        } else {
            enchants = new Enchantment[]{Enchantment.SHARPNESS, Enchantment.FIRE_ASPECT, Enchantment.KNOCKBACK};
        }

        // ランダムに1つ選んでレベル1〜2を付y
        Enchantment selected = enchants[random.nextInt(enchants.length)];
        item.addUnsafeEnchantment(selected, random.nextInt(2) + 1);
        item.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1); // 見た目を光らせる
    }

    // --- 8. 指揮官のオーラ (ゾンビの周囲16ブロック強化) ---
    private void startCommanderAura(Zombie commander) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 指揮官が無効（死亡・デスポーン）になったらタスク終了
                if (!commander.isValid() || commander.isDead()) {
                    cancel();
                    return;
                }

                // 周囲16ブロックのエンティティを取得
                List<Entity> nearby = commander.getNearbyEntities(16, 8, 16);
                for (Entity entity : nearby) {
                    // 通常のモンスター（自分以外かつEnchantedでない）を対象にする
                    if (entity instanceof Monster minion && !minion.equals(commander) && !isEnchanted(minion)) {

                        /* * ポーション効果でバフを管理
                         * 持続時間を 3秒 (60 ticks) に設定。
                         * このタスクは 2秒 (40 ticks) ごとに実行されるため、
                         * 範囲内にいる限り効果が途切れず、範囲外に出ると1秒後に消える。
                         */

                        // 攻撃力上昇 I (増幅値 0 = レベル1)
                        minion.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60, 0, false, true, true));

                        // 移動速度上昇 I
                        minion.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, false, true, true));

                        // エフェクト（パーティクル）
                        if (random.nextDouble() < 0.2) {
                            minion.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, minion.getLocation().add(0, 1.5, 0), 1);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 40); // 2秒周期
    }

    // --- 9. クリーパーの毒ガス爆発 ---
    @EventHandler
    public void onCreeperExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper) || !isEnchanted(creeper)) return;

        Location loc = creeper.getLocation();

        // 爆発後に残留ポーションの雲 (AreaEffectCloud) を生成
        AreaEffectCloud cloud = (AreaEffectCloud) loc.getWorld().spawnEntity(loc, EntityType.AREA_EFFECT_CLOUD);
        cloud.setRadius(4.0f);
        cloud.setDuration(200); // 10秒間
        cloud.setRadiusOnUse(-0.1f); // 誰かが触れるたびに少し小さくなる
        cloud.setWaitTime(0);

        // デバフ効果（鈍鈍 + 弱体化）
        cloud.addCustomEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 200, 1), true);
        cloud.addCustomEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 200, 1), true);

        cloud.setColor(org.bukkit.Color.PURPLE); // 禍々しい紫色
        creeper.getWorld().playSound(loc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1f, 0.5f);
    }

    @EventHandler
    public void onZombieLowHP(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie) || !isEnchanted(zombie)) return;

        // 既に増援を呼んだ個体は除外（一回限りの制限）
        if (reinforcedEntities.contains(zombie.getUniqueId())) return;

        // 現在の体力からダメージを引いた値が 10.0 以下かチェック
        if (zombie.getHealth() - event.getFinalDamage() <= 10.0) {

            // 攻撃を受けるたびに 25% の確率で発動
            if (random.nextDouble() < 0.25) {

                // 発動フラグを立てる（一回限り）
                reinforcedEntities.add(zombie.getUniqueId());

                // 演出
                zombie.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, zombie.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);
                zombie.getWorld().playSound(zombie.getLocation(), Sound.ENTITY_ZOMBIE_INFECT, 1.0f, 0.5f);

                // 3体の増援を召喚
                for (int i = 0; i < 3; i++) {
                    double offsetX = (random.nextDouble() - 0.5) * 3;
                    double offsetZ = (random.nextDouble() - 0.5) * 3;

                    zombie.getWorld().spawn(zombie.getLocation().add(offsetX, 0, offsetZ), Zombie.class, fellow -> {
                        fellow.setCustomName("§7Reinforcement");

                        // ターゲットを親ゾンビと同期
                        if (zombie.getTarget() != null) {
                            fellow.setTarget(zombie.getTarget());
                        }

                        // 増援用の装備
                        EntityEquipment fellowEquip = fellow.getEquipment();
                        if (fellowEquip != null) {
                            fellowEquip.setHelmet(new ItemStack(Material.LEATHER_HELMET));
                            fellowEquip.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
                            fellowEquip.setHelmetDropChance(0.0f);
                            fellowEquip.setChestplateDropChance(0.0f);
                        }

                        // 増援にも「呼び声（加速ポーション）」などのAIが適用されるように
                        // 必要に応じてここで basic stats などを設定しても良いでしょう
                    });
                }
            }
        }
    }

    private void startClimbingAI(Monster monster) {
        new BukkitRunnable() {
            private Location lastLoc = monster.getLocation();
            private int stuckTicks = 0;

            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    cancel();
                    return;
                }

                LivingEntity target = monster.getTarget();
                if (target == null) return;

                Location mLoc = monster.getLocation();
                Location tLoc = target.getLocation();

                // 【活用】水平距離(XZ)と垂直差(Y)
                double distH = Math.sqrt(Math.pow(mLoc.getX() - tLoc.getX(), 2) + Math.pow(mLoc.getZ() - tLoc.getZ(), 2));
                double diffY = tLoc.getY() - mLoc.getY();

                // 1. スタック検知（移動が止まっているかチェック）
                if (mLoc.distance(lastLoc) < 0.1) {
                    stuckTicks++;
                } else {
                    stuckTicks = 0;
                }
                lastLoc = mLoc.clone();

                // 2. 移動指示
                monster.getPathfinder().moveTo(target, 1.2);

                // 3. 高度同期（プレイヤーと同じ高さなら建築しない）
                if (diffY < 0.5) return;

                // --- 4. 建築ロジックの分岐 ---
                Vector dir = tLoc.toVector().subtract(mLoc.toVector()).setY(0).normalize();

                // A: 【橋渡し】プレイヤーと水平距離がある場合 (distH > 1.5)
                if (distH > 1.5) {
                    for (double d = 0.8; d <= 1.5; d += 0.7) {
                        Location checkLoc = mLoc.clone().add(dir.clone().multiply(d));
                        org.bukkit.block.Block bridgeBlock = checkLoc.getBlock().getRelative(0, -1, 0);

                        if (bridgeBlock.getType() == Material.AIR) {
                            refreshTemporaryBlock(bridgeBlock);
                            // 橋を架けたら少し前進を促す
                            monster.setVelocity(monster.getVelocity().add(dir.multiply(0.1)));
                            break;
                        }
                    }
                }

                // B: 【垂直縦積み】プレイヤーのほぼ真下にいる場合 (distH <= 1.5)
                // または、移動が完全に詰まっている場合
                if (distH <= 1.5 || stuckTicks > 5) {
                    org.bukkit.block.Block feet = mLoc.getBlock();
                    if (feet.getType() == Material.AIR) {
                        // 中心スナップ
                        Location center = feet.getLocation().add(0.5, 0.1, 0.5);
                        center.setDirection(mLoc.getDirection());
                        monster.teleport(center);

                        refreshTemporaryBlock(feet);
                        monster.setVelocity(new Vector(0, 0.42, 0));
                        stuckTicks = 0;
                        return;
                    }
                }

                // C: 【障害物乗り越え】目の前に壁がある場合
                if (stuckTicks > 3) {
                    org.bukkit.block.Block eyeLevel = mLoc.clone().add(dir.multiply(0.7)).getBlock().getRelative(0, 1, 0);
                    if (eyeLevel.getType() == Material.AIR) {
                        refreshTemporaryBlock(eyeLevel);
                        monster.setVelocity(new Vector(0, 0.42, 0));
                        stuckTicks = 0;
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 7);
    }

    // ブロックを設置、または既存の消去タスクを上書きして延長するメソッド
    private void refreshTemporaryBlock(org.bukkit.block.Block block) {
        // すでにタスクがある場合はキャンセルしてタイマーをリセット
        if (activeBlocks.containsKey(block)) {
            Bukkit.getScheduler().cancelTask(activeBlocks.get(block));
        } else {
            // 新規設置の場合
            block.setType(Material.MOSSY_COBBLESTONE);
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_PLACE, 0.5f, 0.8f);
        }

        // 新しい消去タスクをスケジュール（5秒後）
        int taskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (block.getType() == Material.MOSSY_COBBLESTONE) {
                    block.setType(Material.AIR);
                    block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 10, 0.2, 0.2, 0.2, Material.MOSSY_COBBLESTONE.createBlockData());
                }
                activeBlocks.remove(block);
            }
        }.runTaskLater(plugin, 100).getTaskId();

        activeBlocks.put(block, taskId);
    }

    // --- 11. 装備拾得AI (全モンスター対象) ---
    @EventHandler
    public void onMobSpawnSetPickup(EntitySpawnEvent event) {
        if (event.getEntity() instanceof LivingEntity entity) {
            // 全てのモンスターにアイテムを拾う許可を与える
            entity.setCanPickupItems(true);
        }
    }

    // 近くにアイテムが落ちているか定期的にチェックするタスク（AIをより積極的にする）
    private void startLootingAI(Monster monster) {
        // 装備スロットを持たないモンスター（クリーパー、クモ、ガスト等）は除外
        if (!(monster instanceof Zombie || monster instanceof Skeleton ||
                monster instanceof Piglin || monster instanceof WitherSkeleton)) {
            return;
        }

        monster.setCanPickupItems(true); // アイテムを拾う許可

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    cancel();
                    return;
                }

                // 周囲のアイテムをチェックして、武器・防具なら近寄る
                for (Entity e : monster.getNearbyEntities(4, 2, 4)) {
                    if (e instanceof Item item) {
                        if (isEquipment(item.getItemStack().getType())) {
                            Vector dir = item.getLocation().toVector().subtract(monster.getLocation().toVector()).normalize();
                            monster.setVelocity(monster.getVelocity().add(dir.multiply(0.1)));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 30);
    }

    // 判定用の補助メソッド
    private boolean isEquipment(Material m) {
        String name = m.name();
        return name.contains("SWORD") || name.contains("AXE") || name.contains("HELMET") ||
                name.contains("CHESTPLATE") || name.contains("LEGGINGS") || name.contains("BOOTS") ||
                m == Material.BOW || m == Material.CROSSBOW;
    }

    private void startBurstAI(Creeper creeper) {
        new BukkitRunnable() {
            private boolean hasBursted = false; // 1個体につき1回制限（連続突進は強すぎるため）
            private int cooldown = 0;

            @Override
            public void run() {
                if (!creeper.isValid() || creeper.isDead()) {
                    cancel();
                    return;
                }
                if (cooldown > 0) {
                    cooldown--;
                    return;
                }

                LivingEntity target = creeper.getTarget();
                if (target == null) return;

                double dist = creeper.getLocation().distance(target.getLocation());

                // 距離が5〜10ブロックの間で、ターゲットを見ている場合に発動
                if (dist > 5 && dist < 10 && !hasBursted) {
                    // 推進方向を計算（ターゲットの方向）
                    Vector direction = target.getLocation().toVector().subtract(creeper.getLocation().toVector()).normalize();

                    // 上方向にも少し力を加えて「飛びかかる」ようにする
                    direction.multiply(1.5).setY(0.4);

                    // 推進力を適用
                    creeper.setVelocity(direction);

                    // 演出：背後から爆風パーティクルと音
                    creeper.getWorld().spawnParticle(Particle.EXPLOSION, creeper.getLocation(), 5, 0.2, 0.2, 0.2, 0.1);
                    creeper.getWorld().playSound(creeper.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
                    creeper.getWorld().playSound(creeper.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);

                    hasBursted = true; // 突進フラグ
                    cooldown = 100;    // 次回まで5秒（死ななかった場合用）
                }
            }
        }.runTaskTimer(plugin, 0, 5); // 0.25秒ごとに判定
    }

}

