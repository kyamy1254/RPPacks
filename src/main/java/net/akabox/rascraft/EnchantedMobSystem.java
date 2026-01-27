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
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

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

    private final java.util.Map<org.bukkit.block.Block, java.util.UUID> occupiedBlocks = new java.util.HashMap<>();

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

    public void makeEnchanted(Monster monster) {
        monster.getPersistentDataContainer().set(enchantedKey, PersistentDataType.BYTE, (byte) 1);

        // 基本ステータス（HP・名前）の適用
        var hp = monster.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * 2.5);
            monster.setHealth(hp.getBaseValue());
        }

        var speed = monster.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.32);
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

    private void startCoreNavigationAI(Monster monster) {
        new BukkitRunnable() {
            private Location lastKnownLocation = null;
            private Location lastLoc = monster.getLocation();
            private int stuckTicks = 0;
            private int searchTimer = 0;

            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    cancel();
                    return;
                }

                LivingEntity target = monster.getTarget();
                Location mLoc = monster.getLocation();

                // 1. 【感知強化】ターゲットの徹底捜索と記憶
                if (target == null || !target.isValid() || target.isDead()) {
                    // 48マスの超広範囲スキャン
                    target = findExtendedTarget(monster, 48.0);
                    if (target != null) {
                        monster.setTarget(target);
                    } else if (lastKnownLocation != null) {
                        // ターゲットを見失っても、最後に見た場所へ向かう
                        monster.getPathfinder().moveTo(lastKnownLocation, 1.2);
                        if (mLoc.distance(lastKnownLocation) < 2) lastKnownLocation = null;
                        return;
                    }
                }

                if (target == null) return;

                // ターゲットの位置を常に記憶
                lastKnownLocation = target.getLocation();
                double distance = mLoc.distance(target.getLocation());

                // 2. 【スタック解決】詰まった時の自己復帰
                if (mLoc.distanceSquared(lastLoc) < 0.001) {
                    stuckTicks++;
                } else {
                    stuckTicks = 0;
                }
                lastLoc = mLoc.clone();

                if (stuckTicks > 15 && monster.isOnGround()) {
                    // 15tick(0.75秒)以上足止めされたら、進行方向に小ジャンプして強引にパスを復旧
                    Vector jumpDir = target.getLocation().toVector().subtract(mLoc.toVector()).normalize().multiply(0.2).setY(0.4);
                    monster.setVelocity(jumpDir);
                    stuckTicks = 0;
                }

                // 3. 【速度の動的制御】距離に応じた「追い込み」
                double speed = 1.2;
                if (distance > 20) {
                    speed = 1.6; // 遠距離：一気に距離を詰める（ダッシュ）
                } else if (distance < 6) {
                    speed = 1.3; // 近距離：逃がさない速度
                }

                // 壁越しでもターゲットを認識し続ける（パスの再計算頻度を上げる）
                monster.getPathfinder().moveTo(target, speed);

                // 4. 【垂直対応】高低差への執着
                double diffY = target.getLocation().getY() - mLoc.getY();

                if (monster instanceof Spider spider) {
                    // クモの場合：壁登りを物理的に加速させる
                    if (diffY > 1 && distance < 8) {
                        // 真上ではなくターゲット方向へ吸い付くような力を加える
                        Vector climb = target.getLocation().toVector().subtract(mLoc.toVector()).normalize().multiply(0.15).setY(0.25);
                        spider.setVelocity(spider.getVelocity().add(climb));
                    }
                } else if (diffY > 2 && distance < 3 && monster.isOnGround()) {
                    // ゾンビ・スケルトンの場合：ターゲットが真上にいるならジャンプして建築AIのトリガーを助ける
                    monster.setVelocity(new Vector(0, 0.42, 0));
                }
            }
        }.runTaskTimer(plugin, 0, 5); // 0.25秒ごとに更新（反応速度を倍に強化）
    }

    // --- 2. スケルトンのAI強化 (偏差撃ち & バックステップ) ---
    @EventHandler
    public void onSkeletonShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Skeleton skeleton) || !isEnchanted(skeleton)) return;
        if (!(skeleton.getTarget() instanceof Player player)) return;

        // 1. 基本情報の取得
        Location sLoc = skeleton.getEyeLocation();
        Location pLoc = player.getEyeLocation();

        // バニラの初速を取得し、1.4倍（程よい速さ）に強化
        double baseSpeed = event.getProjectile().getVelocity().length();
        double straightSpeed = baseSpeed * 1.4;

        // --- 2. 弾種の分岐 (約10%でホーミング弾) ---
        if (ThreadLocalRandom.current().nextDouble() < 0.10) {
            launchHomingArrow(skeleton, player);
            event.setCancelled(true); // 通常の矢の発射をキャンセル
            return;
        }

        // --- 3. 直線スナイプ（偏差なし・重力無視） ---
        // 偏差計算をあえて排除し、現在のプレイヤーの胸元を正確に狙う
        Vector direction = pLoc.toVector().subtract(sLoc.toVector()).normalize();

        // 矢を生成して物理挙動を上書き
        Entity arrow = event.getProjectile();
        arrow.setVelocity(direction.multiply(straightSpeed));

        // 【重要】重力の影響を無効化（API 1.13+）
        // これにより、放物線を描かずレーザーのように真っ直ぐ飛びます
        arrow.setGravity(false);

        // 5秒後（飛距離的に十分な時間）に重力を戻すか消去する（負荷対策）
        new BukkitRunnable() {
            @Override
            public void run() {
                if (arrow.isValid() && !arrow.isOnGround()) arrow.remove();
            }
        }.runTaskLater(plugin, 100);

        skeleton.getWorld().playSound(sLoc, Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.8f);
    }

    /**
     * 低速ホーミング弾の発射ロジック
     */
    private void launchHomingArrow(Skeleton skeleton, Player target) {
        // 矢の生成
        Arrow homingArrow = skeleton.launchProjectile(Arrow.class);
        homingArrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        homingArrow.setGravity(false); // 重力無視

        // ホーミング弾と判別するためのメタデータ
        homingArrow.setMetadata("homing", new FixedMetadataValue(plugin, true));

        // 視覚演出：少し怪しい色のパーティクルを纏わせる
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!homingArrow.isValid() || homingArrow.isOnGround() || ticks > 100) {
                    homingArrow.remove();
                    cancel();
                    return;
                }

                // ターゲットへの方向を毎チック再計算（ホーミング）
                Vector toTarget = target.getEyeLocation().toVector().subtract(homingArrow.getLocation().toVector()).normalize();

                // 低速 (0.4) でじわじわ追いかける
                homingArrow.setVelocity(toTarget.multiply(0.4));

                // 軌跡のパーティクル
                homingArrow.getWorld().spawnParticle(Particle.END_ROD, homingArrow.getLocation(), 1, 0, 0, 0, 0);

                ticks++;
            }
        }.runTaskTimer(plugin, 0, 1);

        skeleton.getWorld().playSound(skeleton.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 2.0f);
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

                // ターゲットが変更されたり、いなくなった場合は終了
                if (skeleton.getTarget() == null || !skeleton.getTarget().equals(player)) {
                    cancel();
                    return;
                }

                double dist = skeleton.getLocation().distance(player.getLocation());
                EntityEquipment equip = skeleton.getEquipment();
                if (equip == null) return;

                // --- 接近モード (5マス以内) ---
                if (dist <= 5.0) {
                    // 武器を斧に持ち替え
                    if (equip.getItemInMainHand().getType() != Material.STONE_AXE) {
                        equip.setItemInMainHand(new ItemStack(Material.STONE_AXE));
                        skeleton.getWorld().playSound(skeleton.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 1f, 1.2f);
                    }

                    // 【変更点】物理 Velocity ではなくスピードポーションを付与 (Speed II)
                    // 持続時間を短く(15tick)設定し、このタスク(10tick周期)で上書きし続けることで、
                    // 「接近中だけ速い」状態を自然に作ります。
                    skeleton.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 15, 1, false, false, false));

                }
                // --- 狙撃モード (5マスより遠い) ---
                else {
                    if (equip.getItemInMainHand().getType() != Material.BOW) {
                        equip.setItemInMainHand(new ItemStack(Material.BOW));
                        // 弓に戻したときはスピードを解除（バニラの挙動に合わせる）
                        skeleton.removePotionEffect(PotionEffectType.SPEED);
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 10);
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

    @EventHandler
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Entity damager = event.getDamager();

        // 1. 「真の攻撃者」を特定する
        LivingEntity realAttacker = null;

        if (damager instanceof LivingEntity) {
            // 近接攻撃の場合
            realAttacker = (LivingEntity) damager;
        } else if (damager instanceof Projectile projectile) {
            // 矢などの遠距離攻撃の場合
            if (projectile.getShooter() instanceof LivingEntity shooter) {
                realAttacker = shooter;
            }
        }

        // 2. 攻撃者と被害者が共にアンデッドモンスターか確認
        if (realAttacker instanceof Monster attacker && victim instanceof Monster victimMob) {
            if (isUndead(attacker) && isUndead(victimMob)) {

                // 3. 周囲15マスに指揮官（Enchanted）がいるか確認
                // 攻撃者（射手）の周囲をスキャンして規律をチェック
                for (Entity nearby : attacker.getNearbyEntities(15, 7, 15)) {
                    if (nearby instanceof Monster leader && isEnchanted(leader)) {

                        // 指揮官がいれば仲間割れ（ダメージ）をキャンセル
                        event.setCancelled(true);

                        // 演出：矢がカキーンと弾かれるような音
                        if (ThreadLocalRandom.current().nextDouble() < 0.2) {
                            victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.5f, 1.8f);
                        }
                        return;
                    }
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

    private void applyZombieSwarm(Zombie leadZombie, Player target) {
        // 1. 本人も確実にターゲットを追わせる
        leadZombie.setTarget(target);

        // 2. 周囲のゾンビを呼ぶ
        for (Entity nearby : leadZombie.getNearbyEntities(15, 7, 15)) {
            if (nearby instanceof Zombie fellow) {
                // 指揮官と同じターゲットを強制設定
                fellow.setTarget(target);

                /* * ポーション効果による加速バフ
                 * PotionEffect(Type, Duration, Amplifier, Ambient, Particles, Icon)
                 * 移動速度上昇 II (Lv2) を 10秒間 (200 ticks) 付与。
                 * Amplifier を 1 に設定することで、バニラの Speed II と同じ速さになります。
                 */
                fellow.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1, false, true, true));

                // 仲間が反応した視覚演出
                if (random.nextDouble() < 0.3) { // 確率を少し上げて反応をわかりやすく
                    // VILLAGER_ANGRY（怒りパーティクル）が解決できない場合は VILLAGER_HAPPY 等で代用
                    fellow.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, fellow.getLocation().add(0, 2, 0), 3, 0.2, 0.2, 0.2, 0);
                }
            }
        }

        // 演出：指揮官が咆哮するような音
        leadZombie.getWorld().playSound(leadZombie.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 1.5f, 0.5f);
    }

    // --- 7. クモのAI: 粘着糸 & 飛びかかり ---
    @EventHandler
    public void onSpiderAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Spider spider) || !isEnchanted(spider)) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // 1. 粘着糸 (Web Trap) の設置判定
        Location feet = player.getLocation().getBlock().getLocation(); // ブロック単位の座標を取得
        if (feet.getBlock().getType() == Material.AIR) {
            feet.getBlock().setType(Material.COBWEB);

            // 3秒後にクモの巣を消去するタスク
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (feet.getBlock().getType() == Material.COBWEB) {
                        feet.getBlock().setType(Material.AIR);
                    }
                }
            }.runTaskLater(plugin, 60);

            // 2. 周囲のクモを呼び寄せる (集団狩猟ロジック)
            callNearbySpidersTo(player, spider);

            // 演出：メッセージとサウンド
            player.sendMessage("§2§lSpider Web! §a周囲のクモが群がってきた！");
            player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1.2f, 0.5f); // 低い不気味な声
            player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_DEATH, 1.0f, 2.0f);
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

    /**
     * 周囲のクモを呼び寄せ、特定のプレイヤーを攻撃させる
     */
    private void callNearbySpidersTo(Player target, Spider attacker) {
        // 周囲20ブロック以内のエンティティを検索
        List<Entity> nearby = attacker.getNearbyEntities(20, 10, 20);

        for (Entity entity : nearby) {
            // 自分以外のクモ（洞窟クモ含む）を対象にする
            if (entity instanceof Spider nearbySpider && !nearbySpider.equals(attacker)) {

                // すでに別の相手と戦っていても、拘束された獲物を優先させる
                nearbySpider.setTarget(target);

                // 興奮状態の演出：スピードアップ（3秒間）
                nearbySpider.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1));

                // 呼び寄せられたクモの足元に怒りのパーティクル
                nearbySpider.getWorld().spawnParticle(
                        Particle.ANGRY_VILLAGER,
                        nearbySpider.getLocation().add(0, 1, 0),
                        3, 0.3, 0.3, 0.3, 0
                );
            }
        }
    }

    // --- 8. 指揮官のオーラ (ゾンビの周囲16ブロック強化) ---
    private void startCommanderAura(Zombie commander) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 指揮官が無効になったらタスク終了
                if (!commander.isValid() || commander.isDead()) {
                    cancel();
                    return;
                }

                // 周囲16ブロックのエンティティを取得
                List<Entity> nearby = commander.getNearbyEntities(16, 8, 16);
                for (Entity entity : nearby) {
                    // 対象の条件：
                    // 1. モンスターであること
                    // 2. 自分自身ではないこと
                    // 3. 【追加】アンデッド（ゾンビ、スケルトン、ウィザスケ等）であること
                    if (entity instanceof Monster minion && !minion.equals(commander)) {

                        // エンティティのカテゴリーがUNDEAD（アンデッド）かチェック
                        if (isUndead(minion) && !isEnchanted(minion)) {

                            // 攻撃力上昇 I (3秒)
                            minion.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60, 0, false, true, true));

                            // 移動速度上昇 I (3秒)
                            minion.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, false, true, true));

                            // エフェクト（オーラを受けている演出）
                            if (random.nextDouble() < 0.2) {
                                minion.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, minion.getLocation().add(0, 1.5, 0), 3, 0.2, 0.2, 0.2, 0);
                            }
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

    private boolean isUndead(Entity entity) {
        // ゾンビ系、スケルトン系、ウィザー、ファントム等が含まれるか判定
        // APIが提供する Tag.ENTITY_TYPES_SENSITIVE_TO_BANE_OF_ARTHROPODS 等の逆、
        // ついた「アンデッド特効」が効く相手かどうかで判別するのが最もスマートです。
        return entity instanceof Zombie ||
                entity instanceof AbstractSkeleton ||
                entity instanceof Phantom ||
                entity instanceof Wither ||
                entity instanceof Zoglin;
    }

    private void startClimbingAI(Monster monster) {
        new BukkitRunnable() {
            private Location lastLoc = monster.getLocation();
            private int stuckTicks = 0;

            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    occupiedBlocks.values().removeIf(uuid -> uuid.equals(monster.getUniqueId()));
                    cancel();
                    return;
                }

                LivingEntity target = monster.getTarget();
                if (target == null) return;

                Location mLoc = monster.getLocation();
                Location tLoc = target.getLocation();

                double distH = Math.sqrt(Math.pow(mLoc.getX() - tLoc.getX(), 2) + Math.pow(mLoc.getZ() - tLoc.getZ(), 2));
                double diffY = tLoc.getY() - mLoc.getY();

                // 1. スタック検知
                if (mLoc.distance(lastLoc) < 0.1) {
                    stuckTicks++;
                } else {
                    stuckTicks = 0;
                }
                lastLoc = mLoc.clone();

                // 2. 基本移動指示
                monster.getPathfinder().moveTo(target, 1.2);

                // 3. 高度同期（完了判定）
                if (diffY < 0.5) {
                    occupiedBlocks.values().removeIf(uuid -> uuid.equals(monster.getUniqueId()));
                    return;
                }

                Vector dir = tLoc.toVector().subtract(mLoc.toVector()).setY(0).normalize();

                // --- 4. 天井・ネズミ返し検知 ---
                boolean isCeilingBlocked = false;
                for (int y = 2; y <= 3; y++) {
                    if (mLoc.clone().add(0, y, 0).getBlock().getType().isSolid()) {
                        isCeilingBlocked = true;
                        break;
                    }
                }

                // 天井がある場合は横にスライド
                if (isCeilingBlocked) {
                    return;
                }

                // --- 5. 衝突回避（場所の予約） ---
                org.bukkit.block.Block currentBlock = mLoc.getBlock();
                java.util.UUID occupier = occupiedBlocks.get(currentBlock);
                if (occupier != null && !occupier.equals(monster.getUniqueId())) {
                    Entity other = Bukkit.getEntity(occupier);
                    if (other != null && other.isValid() && other.getLocation().distance(mLoc) < 1.0) {
                        Vector escape = mLoc.toVector().subtract(other.getLocation().toVector()).setY(0).normalize().multiply(0.2);
                        monster.setVelocity(monster.getVelocity().add(escape));
                        return;
                    } else {
                        occupiedBlocks.remove(currentBlock);
                    }
                }

                // --- 6. 建築ロジック ---

                // A: 【柵・段差対策】進行方向2マス先まで検知
                // 1.5ブロック以上の段差（柵など）がある場合、手前に足場を作る
                Location probe1 = mLoc.clone().add(dir.clone().multiply(0.8));
                Location probe2 = mLoc.clone().add(dir.clone().multiply(1.6));

                if (diffY > 1.2 && (probe1.getBlock().getType().isSolid() || probe2.getBlock().getType().isSolid())) {
                    org.bukkit.block.Block stepBase = mLoc.getBlock();
                    if (stepBase.getType() == Material.AIR) {
                        refreshTemporaryBlock(stepBase);
                        monster.setVelocity(new Vector(0, 0.42, 0));
                        return;
                    }
                }

                // B: 【垂直縦積み】壁が2.5マス以上、または密着・スタック時
                if ((diffY >= 2.5 && distH <= 2.0) || (distH <= 1.5 || stuckTicks > 5)) {
                    org.bukkit.block.Block feet = mLoc.getBlock();
                    // 足元が空気かつ、下が土台ブロックであること
                    if (feet.getType() == Material.AIR && mLoc.getBlock().getRelative(0, -1, 0).getType().isSolid()) {
                        occupiedBlocks.put(feet, monster.getUniqueId());
                        Location center = feet.getLocation().add(0.5, 0.1, 0.5);
                        center.setDirection(mLoc.getDirection());
                        monster.teleport(center);
                        refreshTemporaryBlock(feet);
                        monster.setVelocity(new Vector(0, 0.42, 0));
                        stuckTicks = 0;
                        return;
                    }
                }

                // C: 【橋渡し】2マス以上の崖がある場合
                if (distH > 1.5) {
                    occupiedBlocks.values().removeIf(uuid -> uuid.equals(monster.getUniqueId()));
                    org.bukkit.block.Block bridgeBlock = probe1.getBlock().getRelative(0, -1, 0);
                    org.bukkit.block.Block deepBlock = probe1.getBlock().getRelative(0, -2, 0);

                    if (bridgeBlock.getType() == Material.AIR && deepBlock.getType() == Material.AIR) {
                        if (!probe1.getBlock().getRelative(0, 1, 0).getType().isSolid() &&
                                !probe1.getBlock().getRelative(0, 2, 0).getType().isSolid()) {
                            refreshTemporaryBlock(bridgeBlock);
                            monster.setVelocity(monster.getVelocity().add(dir.multiply(0.1)));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 5);
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
        if (!(monster instanceof Zombie || monster instanceof Skeleton ||
                monster instanceof Piglin || monster instanceof WitherSkeleton)) {
            return;
        }

        monster.setCanPickupItems(true);

        new BukkitRunnable() {
            private Item targetItem = null;

            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    cancel();
                    return;
                }

                // 1. ターゲットアイテムが有効か確認（誰かに拾われたり消えていないか）
                if (targetItem != null && (!targetItem.isValid() || targetItem.isDead())) {
                    targetItem = null;
                }

                // 2. 周囲に良い装備がないか探す（既にターゲットがある場合はスキップして節約）
                if (targetItem == null) {
                    for (Entity e : monster.getNearbyEntities(6, 3, 6)) {
                        if (e instanceof Item item && isEquipment(item.getItemStack().getType())) {
                            targetItem = item;
                            break;
                        }
                    }
                }

                // 3. アイテムへ向かって「歩く」指示
                if (targetItem != null) {
                    // ターゲットを攻撃中ならアイテムを優先するか判断（ここではアイテムを優先）
                    // 距離が近ければ移動、遠ければ現在のターゲットを維持
                    double distance = monster.getLocation().distance(targetItem.getLocation());

                    if (distance > 0.5) {
                        // Velocityを使わず、バニラのAIに座標を指定して歩かせる
                        monster.getPathfinder().moveTo(targetItem.getLocation(), 1.2);
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 10); // 判定を少し早める(0.5秒毎)と反応が自然になります
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
            private int cooldown = 0;
            private boolean isLeaping = false; // 飛び出し中フラグ

            @Override
            public void run() {
                if (!creeper.isValid() || creeper.isDead()) {
                    cancel();
                    return;
                }

                // 1. クールダウン管理
                if (cooldown > 0) {
                    cooldown--;
                    return;
                }

                LivingEntity target = creeper.getTarget();
                if (target == null) return;

                Location cLoc = creeper.getLocation();
                Location tLoc = target.getLocation();
                double dist = cLoc.distance(tLoc);

                // 2. 接触即爆（飛行中の判定）
                if (isLeaping && dist < 1.5) {
                    creeper.explode(); // プレイヤーに接触したら即自爆
                    cancel();
                    return;
                }

                // 着地判定（飛んでいたが地面についた場合）
                if (isLeaping && creeper.isOnGround()) {
                    isLeaping = false;
                }

                // 3. 突進の発動条件 (5～12ブロック)
                if (dist > 5 && dist < 12 && !isLeaping) {
                    // ターゲットへの方向を計算
                    Vector toTarget = tLoc.toVector().subtract(cLoc.toVector()).normalize();

                    // 物理的な「打ち出し」
                    // XZ平面に強い推進力、Y軸にふんわり浮き上がる力を加える
                    Vector leapVel = toTarget.multiply(1.4).setY(0.4);
                    creeper.setVelocity(leapVel);

                    // 4. 【演出】ウィンドチャージの爆風
                    // クリーパーの少し背後から爆風を出すことで、前に押し出されているように見せる
                    Location burstLoc = cLoc.clone().subtract(toTarget.multiply(0.5));
                    creeper.getWorld().spawnParticle(Particle.EXPLOSION, burstLoc, 1);
                    creeper.getWorld().playSound(burstLoc, Sound.ENTITY_WIND_CHARGE_THROW, 1.0f, 1.2f);

                    isLeaping = true;
                    cooldown = 40; // 約2秒後に再挑戦可能
                }
            }
        }.runTaskTimer(plugin, 0, 2); // 判定精度を上げて接触検知を確実にする
    }

}

