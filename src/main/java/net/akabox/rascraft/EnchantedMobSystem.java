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

    public void makeEnchanted(Monster monster) {
        monster.getPersistentDataContainer().set(enchantedKey, PersistentDataType.BYTE, (byte) 1);

        EntityEquipment equip = monster.getEquipment();
        if (equip != null) {
            // 1. 防具の設定 (鉄装備)
            ItemStack helmet = new ItemStack(Material.IRON_HELMET);
            ItemStack chest = new ItemStack(Material.IRON_CHESTPLATE);
            helmet.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
            chest.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
            equip.setHelmet(helmet);
            equip.setChestplate(chest);
            equip.setHelmetDropChance(0f);
            equip.setChestplateDropChance(0f);

            // 2. 武器の決定
            ItemStack weapon = null;

            if (monster instanceof Zombie) {
                double weaponRoll = random.nextDouble();
                if (weaponRoll < 0.4) { // 40%の確率で特殊武器
                    if (weaponRoll < 0.1) weapon = new ItemStack(Material.DIAMOND_SWORD);
                    else if (weaponRoll < 0.2) weapon = new ItemStack(Material.IRON_AXE);
                    else if (weaponRoll < 0.3) weapon = new ItemStack(Material.CROSSBOW);
                    else weapon = new ItemStack(Material.IRON_SWORD);
                }
            } else if (monster instanceof Skeleton) {
                weapon = new ItemStack(Material.BOW);
            }

            // 3. 武器の適用 (ここで一括処理することでスコープエラーを回避)
            if (weapon != null) {
                applyRandomEnchant(weapon);
                equip.setItemInMainHand(weapon);
                equip.setItemInMainHandDropChance(0.05f);
            }
        }
        // ステータス強化
        var hp = monster.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * 2.5); // HP 2.5倍
            monster.setHealth(hp.getBaseValue());
        }

        monster.setCustomName("§d§lEnchanted " + monster.getType().getName());
        monster.setCustomNameVisible(false);

        if (monster instanceof Zombie || monster instanceof Skeleton) {
            startClimbingAI(monster);
            if (monster instanceof Zombie) {
                startCommanderAura((Zombie) monster);
            }
        }

        // オーラエフェクト (Dungeons風)
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

    private boolean isEnchanted(Entity entity) {
        return entity.getPersistentDataContainer().has(enchantedKey, PersistentDataType.BYTE);
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


    // --- 3. ゾンビのAI強化 (呼び声 & 執念) ---
    @EventHandler
    public void onZombieAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Zombie zombie) || !isEnchanted(zombie)) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // 「呼び声」: 周囲のゾンビをターゲットに集中させる
        for (Entity nearby : zombie.getNearbyEntities(15, 7, 15)) {
            if (nearby instanceof Zombie fellow) {
                fellow.setTarget(player);
                // 執念の加速バフ
                var speed = fellow.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.setBaseValue(speed.getBaseValue() * 1.1);
                }
            }
        }
    }

    @EventHandler
    public void onZombieLowHP(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie) || !isEnchanted(zombie)) return;
        if (reinforcedEntities.contains(zombie.getUniqueId())) return;

        if (zombie.getHealth() - event.getFinalDamage() <= 10.0) {
            reinforcedEntities.add(zombie.getUniqueId());
            zombie.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, zombie.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);

            for (int i = 0; i < 3; i++) {
                double offsetX = (random.nextDouble() - 0.5) * 3;
                double offsetZ = (random.nextDouble() - 0.5) * 3;

                zombie.getWorld().spawn(zombie.getLocation().add(offsetX, 0, offsetZ), Zombie.class, fellow -> {
                    fellow.setCustomName("§7Reinforcement");
                    // ターゲットを親ゾンビと同期
                    if (zombie.getTarget() != null) {
                        fellow.setTarget(zombie.getTarget());
                    }

                    // 増援用の装備（革のヘルメットとチェストプレート）
                    EntityEquipment fellowEquip = fellow.getEquipment();
                    if (fellowEquip != null) {
                        fellowEquip.setHelmet(new ItemStack(Material.LEATHER_HELMET));
                        fellowEquip.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));

                        // 増援の装備はドロップしないように設定
                        fellowEquip.setHelmetDropChance(0.0f);
                        fellowEquip.setChestplateDropChance(0.0f);
                    }
                });
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

    // --- 10. アンデッドの建築AI (高所のプレイヤーを追う) ---
    private void startClimbingAI(Monster monster) {
        new BukkitRunnable() {
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
                double distance = mLoc.distance(tLoc);

                // 射程圏内かつ高低差がある場合
                if (distance < 12 && tLoc.getY() - mLoc.getY() > 1.0) {
                    // プレイヤー方向への水平ベクトルを取得
                    Vector direction = tLoc.toVector().subtract(mLoc.toVector()).setY(0).normalize();

                    // 「一歩先」の座標を計算 (0.8ブロック先)
                    Location frontLoc = mLoc.clone().add(direction.multiply(0.8));

                    // 設置候補：足元(Y+0)、膝(Y+1)
                    for (int yOffset = 0; yOffset <= 1; yOffset++) {
                        org.bukkit.block.Block targetBlock = frontLoc.clone().add(0, yOffset, 0).getBlock();

                        if (targetBlock.getType() == Material.AIR) {
                            // 足場を設置
                            placeTemporaryBlock(targetBlock);

                            // 登攀を補助するベクトル（斜め上へ押し出す）
                            Vector velocity = direction.clone().multiply(0.25).setY(0.45);
                            monster.setVelocity(velocity);
                            break; // 1つ置いたらこのティックは終了
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 10); // 1秒(20)だと遅いので、0.5秒(10)に短縮
    }

    // ブロック設置と消去の共通メソッド（コードをスッキリさせるため分離）
    private void placeTemporaryBlock(org.bukkit.block.Block block) {
        block.setType(Material.MOSSY_COBBLESTONE);
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_PLACE, 0.5f, 0.8f);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (block.getType() == Material.MOSSY_COBBLESTONE) {
                    block.setType(Material.AIR);
                    block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 10, 0.2, 0.2, 0.2, Material.MOSSY_COBBLESTONE.createBlockData());
                }
            }
        }.runTaskLater(plugin, 100); // 5秒後に消去
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

