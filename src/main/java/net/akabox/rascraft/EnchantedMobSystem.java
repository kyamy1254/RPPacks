package net.akabox.rascraft;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;


public class EnchantedMobSystem implements Listener {
    private final RascraftPluginPacks plugin;
    private final NamespacedKey enchantedKey;
    private final Random random = new Random();
    private final NamespacedKey reinforcedKey;
    private final java.util.Map<Location, Integer> activeBlocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Location, java.util.concurrent.atomic.AtomicInteger> activeDamageStage = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Location, java.util.UUID> occupiedBlocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, java.util.List<Integer>> mobTasks = new java.util.concurrent.ConcurrentHashMap<>();
    // タスクIDではなく、設置されたブロックの「種類」と「現在のダメージ量」を管理する
    private final java.util.Map<Location, Material> temporaryBlockTypes = new java.util.HashMap<>();
    private final java.util.Map<Location, Integer> activeDamageStages = new java.util.HashMap<>();

    // === Configuration values (config.yml から読み込み) ===
    // スポーン時Enchanted化確率
    public double enchantedSpawnChance;
    // ステータス
    public double hpMultiplier;
    public double movementSpeed;
    // パーティクル
    public int auraUpdateInterval;
    public double spiralAngleIncrement;
    public double spiralRadius;
    // ナビゲーション
    public double detectionRange;
    public double baseSpeed;
    public double farDistanceThreshold;
    public double farDistanceSpeed;
    public double closeDistanceThreshold;
    public double closeDistanceSpeed;
    public int stuckTicksThreshold;
    public double stuckJumpPower;
    public double stuckJumpHorizontal;
    // スケルトン
    public double arrowSpeedMultiplier;
    public double homingArrowChance;
    public double homingArrowSpeed;
    // ゾンビ
    public double commanderAuraRange;
    public double commanderAuraHeight;
    public int swarmAuraInterval;
    public double swarmDetectionRange;
    public double reinforcementHpThreshold;
    public double reinforcementSpawnChance;
    public int reinforcementCountMin;
    public int reinforcementCountMax;
    public double enchantedReinforcementChance;
    public double reinforcementSpawnDistance;
    // クリーパー
    public double creeperBaseSpeed;
    public double creeperBackstabSpeed;
    public double creeperSidestepForce;
    public double creeperVisibilityThreshold;
    public double creeperVisionRange;
    public double creeperBackstabDistance;
    public double creeperSidestepDistance;
    public double creeperBurstMinDistance;
    public double creeperBurstMaxDistance;
    public double creeperBurstSpeed;
    public double creeperBurstVerticalPower;
    public int creeperBurstCooldown;
    // クモ
    public double spiderWallClimbForce;
    public double spiderWallClimbVertical;
    public double spiderWallClimbHeightDiff;
    public double spiderLeapMinDistance;
    public double spiderLeapMaxDistance;
    public double spiderLeapSpeed;
    public double spiderLeapChance;
    public int spiderWebTrapDuration;
    public double spiderSwarmCallRange;
    public double spiderSwarmCallHeight;
    // クリーパー毒ガス
    public double creeperPoisonCloudRadius;
    public int creeperPoisonCloudDuration;
    public double creeperPoisonCloudRadiusOnUse;
    // 建築AI
    public double climbingSpeed;
    public double stuckDistanceThreshold;
    public int stuckTickLimit;
    public double lookaheadDistance;
    public double verticalBuildHeight;
    public double verticalBuildCloseDistance;
    public double bridgeMinDistance;
    public int temporaryBlockDuration;
    public int damageAnimationInterval;
    // 拾得AI
    public double equipmentDetectionRange;
    public int lootingAiInterval;
    // キャンプファイア回復
    public double campfireHealAmount;
    public double campfireScanRange;
    public double campfireScanHeight;
    public int campfireTaskInterval;
    private org.bukkit.scheduler.BukkitTask campfireTask = null;

    private void registerMobTask(Monster m, int taskId) {
        mobTasks.computeIfAbsent(m.getUniqueId(), k -> new java.util.ArrayList<>()).add(taskId);
    }

    private void cancelMobTasks(Monster m) {
        java.util.List<Integer> ids = mobTasks.remove(m.getUniqueId());
        if (ids != null) {
            ids.forEach(id -> Bukkit.getScheduler().cancelTask(id));
        }
    }


    public EnchantedMobSystem(RascraftPluginPacks plugin) {
        this.plugin = plugin;
        this.enchantedKey = new NamespacedKey(plugin, "is_enchanted");
        this.reinforcedKey = new NamespacedKey(plugin, "has_reinforced");

        // Config値を読み込む
        loadConfiguration();
        startGlobalBlockDamageTask();
        startCampfireHealingTask();
    }

    /**
     * config.yml から設定値を読み込む
     */
    private void loadConfiguration() {
        var config = plugin.getConfig();
        var emConfig = config.getConfigurationSection("enchanted-mobs");

        if (emConfig == null) {
            plugin.getLogger().warning("enchanted-mobs section not found in config.yml! Using defaults.");
            loadDefaults();
            return;
        }

        // スポーン確率
        enchantedSpawnChance = emConfig.getDouble("enchanted-spawn-chance", 0.15);

        // ステータス
        var statsSection = emConfig.getConfigurationSection("stats");
        if (statsSection != null) {
            hpMultiplier = statsSection.getDouble("hp-multiplier", 2.5);
            movementSpeed = statsSection.getDouble("movement-speed", 0.275);
        } else {
            hpMultiplier = 2.5;
            movementSpeed = 0.275;
        }

        // パーティクル
        var particlesSection = emConfig.getConfigurationSection("particles");
        if (particlesSection != null) {
            auraUpdateInterval = particlesSection.getInt("aura-update-interval", 4);
            spiralAngleIncrement = particlesSection.getDouble("spiral-angle-increment", 0.4);
            spiralRadius = particlesSection.getDouble("spiral-radius", 0.6);
        } else {
            auraUpdateInterval = 4;
            spiralAngleIncrement = 0.4;
            spiralRadius = 0.6;
        }

        // ナビゲーション
        var navSection = emConfig.getConfigurationSection("navigation");
        if (navSection != null) {
            detectionRange = navSection.getDouble("detection-range", 48.0);
            baseSpeed = navSection.getDouble("base-speed", 1.2);
            farDistanceThreshold = navSection.getDouble("far-distance-threshold", 20);
            farDistanceSpeed = navSection.getDouble("far-distance-speed", 1.6);
            closeDistanceThreshold = navSection.getDouble("close-distance-threshold", 6);
            closeDistanceSpeed = navSection.getDouble("close-distance-speed", 1.3);
            stuckTicksThreshold = navSection.getInt("stuck-ticks-threshold", 15);
            stuckJumpPower = navSection.getDouble("stuck-jump-power", 0.4);
            stuckJumpHorizontal = navSection.getDouble("stuck-jump-horizontal", 0.2);
        } else {
            detectionRange = 48.0;
            baseSpeed = 1.2;
            farDistanceThreshold = 20;
            farDistanceSpeed = 1.6;
            closeDistanceThreshold = 6;
            closeDistanceSpeed = 1.3;
            stuckTicksThreshold = 15;
            stuckJumpPower = 0.4;
            stuckJumpHorizontal = 0.2;
        }

        // スケルトン
        var skelSection = emConfig.getConfigurationSection("skeleton");
        if (skelSection != null) {
            arrowSpeedMultiplier = skelSection.getDouble("arrow-speed-multiplier", 1.4);
            homingArrowChance = skelSection.getDouble("homing-arrow-chance", 0.10);
            homingArrowSpeed = skelSection.getDouble("homing-arrow-speed", 0.4);
        } else {
            arrowSpeedMultiplier = 1.4;
            homingArrowChance = 0.10;
            homingArrowSpeed = 0.4;
        }

        // ゾンビ
        var zombieSection = emConfig.getConfigurationSection("zombie");
        if (zombieSection != null) {
            commanderAuraRange = zombieSection.getDouble("commander-aura-range", 16);
            commanderAuraHeight = zombieSection.getDouble("commander-aura-height", 8);
            swarmAuraInterval = zombieSection.getInt("swarm-aura-interval", 40);
            swarmDetectionRange = zombieSection.getDouble("swarm-detection-range", 15);
            reinforcementHpThreshold = zombieSection.getDouble("reinforcement-hp-threshold", 10.0);
            reinforcementSpawnChance = zombieSection.getDouble("reinforcement-spawn-chance", 0.25);
            reinforcementCountMin = zombieSection.getInt("reinforcement-count-min", 2);
            reinforcementCountMax = zombieSection.getInt("reinforcement-count-max", 5);
            enchantedReinforcementChance = zombieSection.getDouble("enchanted-reinforcement-chance", 0.05);
            reinforcementSpawnDistance = zombieSection.getDouble("reinforcement-spawn-distance", 3);
        } else {
            commanderAuraRange = 16;
            commanderAuraHeight = 8;
            swarmAuraInterval = 40;
            swarmDetectionRange = 15;
            reinforcementHpThreshold = 10.0;
            reinforcementSpawnChance = 0.25;
            reinforcementCountMin = 2;
            reinforcementCountMax = 5;
            enchantedReinforcementChance = 0.05;
            reinforcementSpawnDistance = 3;
        }

        // クリーパー
        var creeperSection = emConfig.getConfigurationSection("creeper");
        if (creeperSection != null) {
            creeperBaseSpeed = creeperSection.getDouble("base-movement-speed", 0.25);
            creeperBackstabSpeed = creeperSection.getDouble("backstab-speed", 0.45);
            creeperSidestepForce = creeperSection.getDouble("sidestep-force", 0.5);
            creeperVisibilityThreshold = creeperSection.getDouble("visibility-threshold", 0.98);
            creeperVisionRange = creeperSection.getDouble("vision-range", 15);
            creeperBackstabDistance = creeperSection.getDouble("backstab-distance", 3.0);
            creeperSidestepDistance = creeperSection.getDouble("sidestep-distance", 15);
            creeperBurstMinDistance = creeperSection.getDouble("burst-min-distance", 5);
            creeperBurstMaxDistance = creeperSection.getDouble("burst-max-distance", 12);
            creeperBurstSpeed = creeperSection.getDouble("burst-speed", 1.4);
            creeperBurstVerticalPower = creeperSection.getDouble("burst-vertical-power", 0.4);
            creeperBurstCooldown = creeperSection.getInt("burst-cooldown", 40);
        } else {
            creeperBaseSpeed = 0.25;
            creeperBackstabSpeed = 0.45;
            creeperSidestepForce = 0.5;
            creeperVisibilityThreshold = 0.98;
            creeperVisionRange = 15;
            creeperBackstabDistance = 3.0;
            creeperSidestepDistance = 15;
            creeperBurstMinDistance = 5;
            creeperBurstMaxDistance = 12;
            creeperBurstSpeed = 1.4;
            creeperBurstVerticalPower = 0.4;
            creeperBurstCooldown = 40;
        }

        // クモ
        var spiderSection = emConfig.getConfigurationSection("spider");
        if (spiderSection != null) {
            spiderWallClimbForce = spiderSection.getDouble("wall-climb-force", 0.15);
            spiderWallClimbVertical = spiderSection.getDouble("wall-climb-vertical", 0.25);
            spiderWallClimbHeightDiff = spiderSection.getDouble("wall-climb-height-diff", 1);
            spiderLeapMinDistance = spiderSection.getDouble("leap-min-distance", 5);
            spiderLeapMaxDistance = spiderSection.getDouble("leap-max-distance", 10);
            spiderLeapSpeed = spiderSection.getDouble("leap-speed", 1.2);
            spiderLeapChance = spiderSection.getDouble("leap-chance", 0.2);
            spiderWebTrapDuration = spiderSection.getInt("web-trap-duration", 60);
            spiderSwarmCallRange = spiderSection.getDouble("swarm-call-range", 20);
            spiderSwarmCallHeight = spiderSection.getDouble("swarm-call-height", 10);
        } else {
            spiderWallClimbForce = 0.15;
            spiderWallClimbVertical = 0.25;
            spiderWallClimbHeightDiff = 1;
            spiderLeapMinDistance = 5;
            spiderLeapMaxDistance = 10;
            spiderLeapSpeed = 1.2;
            spiderLeapChance = 0.2;
            spiderWebTrapDuration = 60;
            spiderSwarmCallRange = 20;
            spiderSwarmCallHeight = 10;
        }

        // クリーパー毒ガス
        var poisonSection = emConfig.getConfigurationSection("creeper-poison");
        if (poisonSection != null) {
            creeperPoisonCloudRadius = poisonSection.getDouble("cloud-radius", 4.0);
            creeperPoisonCloudDuration = poisonSection.getInt("cloud-duration", 200);
            creeperPoisonCloudRadiusOnUse = poisonSection.getDouble("cloud-radius-on-use", -0.1);
        } else {
            creeperPoisonCloudRadius = 4.0;
            creeperPoisonCloudDuration = 200;
            creeperPoisonCloudRadiusOnUse = -0.1;
        }

        // 建築AI
        var climbingSection = emConfig.getConfigurationSection("climbing");
        if (climbingSection != null) {
            climbingSpeed = climbingSection.getDouble("climbing-speed", 1.2);
            stuckDistanceThreshold = climbingSection.getDouble("stuck-distance-threshold", 0.1);
            stuckTickLimit = climbingSection.getInt("stuck-tick-limit", 5);
            lookaheadDistance = climbingSection.getDouble("lookahead-distance", 1.6);
            verticalBuildHeight = climbingSection.getDouble("vertical-build-height", 2.5);
            verticalBuildCloseDistance = climbingSection.getDouble("vertical-build-close-distance", 1.5);
            bridgeMinDistance = climbingSection.getDouble("bridge-min-distance", 1.5);
            temporaryBlockDuration = climbingSection.getInt("temporary-block-duration", 100);
            damageAnimationInterval = climbingSection.getInt("damage-animation-interval", 10);
        } else {
            climbingSpeed = 1.2;
            stuckDistanceThreshold = 0.1;
            stuckTickLimit = 5;
            lookaheadDistance = 1.6;
            verticalBuildHeight = 2.5;
            verticalBuildCloseDistance = 1.5;
            bridgeMinDistance = 1.5;
            temporaryBlockDuration = 100;
            damageAnimationInterval = 10;
        }

        // 拾得AI
        var lootingSection = emConfig.getConfigurationSection("looting");
        if (lootingSection != null) {
            equipmentDetectionRange = lootingSection.getDouble("equipment-detection-range", 6);
            lootingAiInterval = lootingSection.getInt("looting-ai-interval", 10);
        } else {
            equipmentDetectionRange = 6;
            lootingAiInterval = 10;
        }

        // キャンプファイア回復
        var campfireSection = emConfig.getConfigurationSection("campfire-healing");
        if (campfireSection != null) {
            campfireHealAmount = campfireSection.getDouble("heal-amount", 2.0);
            campfireScanRange = campfireSection.getDouble("scan-range", 5);
            campfireScanHeight = campfireSection.getDouble("scan-height", 3);
            campfireTaskInterval = campfireSection.getInt("task-interval", 40);
        } else {
            campfireHealAmount = 2.0;
            campfireScanRange = 5;
            campfireScanHeight = 3;
            campfireTaskInterval = 40;
        }

        plugin.getLogger().info("Enchanted Mobs configuration loaded successfully.");
    }

    /**
     * プラグインのリロード時に呼び出す（外部からの再読み込み）
     */
    public void reloadConfiguration() {
        loadConfiguration();

        // キャンプファイアタスクは周期が変わる可能性があるため再起動する
        if (campfireTask != null) {
            campfireTask.cancel();
            campfireTask = null;
        }
        startCampfireHealingTask();
        // 既にスポーンしている Enchanted モブのタスクを再スケジュールして新設定を反映する
        rescheduleAllEnchantedMobs();
    }

    /**
     * デフォルト値をセット（互換性維持）
     */
    private void loadDefaults() {
        enchantedSpawnChance = 0.15;
        hpMultiplier = 2.5;
        movementSpeed = 0.275;
        auraUpdateInterval = 4;
        spiralAngleIncrement = 0.4;
        spiralRadius = 0.6;
        detectionRange = 48.0;
        baseSpeed = 1.2;
        farDistanceThreshold = 20;
        farDistanceSpeed = 1.6;
        closeDistanceThreshold = 6;
        closeDistanceSpeed = 1.3;
        stuckTicksThreshold = 15;
        stuckJumpPower = 0.4;
        stuckJumpHorizontal = 0.2;
        arrowSpeedMultiplier = 1.4;
        homingArrowChance = 0.10;
        homingArrowSpeed = 0.4;
        commanderAuraRange = 16;
        commanderAuraHeight = 8;
        swarmAuraInterval = 40;
        swarmDetectionRange = 15;
        reinforcementHpThreshold = 10.0;
        reinforcementSpawnChance = 0.25;
        reinforcementCountMin = 2;
        reinforcementCountMax = 5;
        enchantedReinforcementChance = 0.05;
        reinforcementSpawnDistance = 3;
        creeperBaseSpeed = 0.25;
        creeperBackstabSpeed = 0.45;
        creeperSidestepForce = 0.5;
        creeperVisibilityThreshold = 0.98;
        creeperVisionRange = 15;
        creeperBackstabDistance = 3.0;
        creeperSidestepDistance = 15;
        creeperBurstMinDistance = 5;
        creeperBurstMaxDistance = 12;
        creeperBurstSpeed = 1.4;
        creeperBurstVerticalPower = 0.4;
        creeperBurstCooldown = 40;
        spiderWallClimbForce = 0.15;
        spiderWallClimbVertical = 0.25;
        spiderWallClimbHeightDiff = 1;
        spiderLeapMinDistance = 5;
        spiderLeapMaxDistance = 10;
        spiderLeapSpeed = 1.2;
        spiderLeapChance = 0.2;
        spiderWebTrapDuration = 60;
        spiderSwarmCallRange = 20;
        spiderSwarmCallHeight = 10;
        creeperPoisonCloudRadius = 4.0;
        creeperPoisonCloudDuration = 200;
        creeperPoisonCloudRadiusOnUse = -0.1;
        climbingSpeed = 1.2;
        stuckDistanceThreshold = 0.1;
        stuckTickLimit = 5;
        lookaheadDistance = 1.6;
        verticalBuildHeight = 2.5;
        verticalBuildCloseDistance = 1.5;
        bridgeMinDistance = 1.5;
        temporaryBlockDuration = 100;
        damageAnimationInterval = 10;
        equipmentDetectionRange = 6;
        lootingAiInterval = 10;
        campfireHealAmount = 2.0;
        campfireScanRange = 5;
        campfireScanHeight = 3;
        campfireTaskInterval = 40;
    }

    // --- 1. スポーン時にEnchanted化 ---
    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) { // EntitySpawnEvent から CreatureSpawnEvent に変更
        // 1. まず「Monsterであるか」を判定し、同時に変数 'monster' を定義する
        if (!(event.getEntity() instanceof Monster monster)) return;

        // 2. PDCチェック (増援処理などで「判定済み」のマークがある場合はスキップ)
        if (monster.getPersistentDataContainer().has(enchantedKey, PersistentDataType.BYTE)) return;

        // 3. スポーン理由のチェック (プラグインによるCUSTOMスポーンは除外)
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;

        // --- ここから共通の処理 ---
        startLootingAI(monster);

        // Enchanted Mobに進化する確率（config.yml から読み込み）
        if (ThreadLocalRandom.current().nextDouble() < enchantedSpawnChance) {
            makeEnchanted(monster);
        }
    }



    // コードの可読性のために装備処理を分離
    private void applyEnchantedEquipment(Monster monster) {
        EntityEquipment equip = monster.getEquipment();
        if (equip == null) return;

        // --- C. 【追加】ウィザースケルトン (ネザライト上半身 + 鉄装飾) ---
        if (monster instanceof WitherSkeleton) {
            ItemStack helmet = new ItemStack(Material.NETHERITE_HELMET);
            ItemStack chest = new ItemStack(Material.NETHERITE_CHESTPLATE);

            // 鉄の装飾を適用
            applyArmorTrim(helmet, TrimMaterial.IRON);
            applyArmorTrim(chest, TrimMaterial.IRON);

            // 防具エンチャント
            applyVanillaEnchants(helmet, "ARMOR");
            applyVanillaEnchants(chest, "ARMOR");

            equip.setHelmet(helmet);
            equip.setChestplate(chest);
            equip.setLeggings(null);
            equip.setBoots(null);

            // 初期武器は弓
            ItemStack bow = new ItemStack(Material.BOW);
            bow.addUnsafeEnchantment(Enchantment.FLAME, 1);
            applyVanillaEnchants(bow, "BOW");
            equip.setItemInMainHand(bow);

            // ドロップ率設定
            equip.setHelmetDropChance(0f);
            equip.setChestplateDropChance(0f);
            equip.setItemInMainHandDropChance(0f);
            return;
        }

        // =========================================================
        //  A. ピグリン系 (ブルート / 通常 / ゾンビ)
        // =========================================================
        // ※ PigZombie(ZombifiedPiglin)等のクラス名解決のため getType も併用
        if (monster instanceof Piglin || monster instanceof PiglinBrute ||
                monster instanceof PigZombie || monster.getType().name().contains("PIGLIN")) {

            ItemStack helmet;
            ItemStack chest = null;

            if (monster instanceof PiglinBrute) {
                // --- 1. ブルート：ネザライトヘルメットのみ ---
                helmet = new ItemStack(Material.NETHERITE_HELMET);

                // 武器の抽選：金の斧(90%) / ネザライトの斧(10%)
                double weaponRoll = ThreadLocalRandom.current().nextDouble();
                ItemStack axe;
                if (weaponRoll < 0.90) {
                    axe = new ItemStack(Material.GOLDEN_AXE);
                } else {
                    axe = new ItemStack(Material.NETHERITE_AXE);
                }

                applyVanillaEnchants(axe, "AXE");
                equip.setItemInMainHand(axe);
            } else {
                // --- 2. 通常・ゾンビピグリン：金の上半身のみ ---
                helmet = new ItemStack(Material.GOLDEN_HELMET);
                chest = new ItemStack(Material.GOLDEN_CHESTPLATE);

                // 武器：確率抽選
                double roll = ThreadLocalRandom.current().nextDouble();
                ItemStack weapon;
                String category = "SWORD";

                if (roll < 0.60) weapon = new ItemStack(Material.GOLDEN_SWORD);
                else if (roll < 0.80) weapon = new ItemStack(Material.IRON_SWORD);
                else if (roll < 0.95) { weapon = new ItemStack(Material.GOLDEN_AXE); category = "AXE"; }
                else weapon = new ItemStack(Material.NETHERITE_SWORD);

                applyVanillaEnchants(weapon, category);
                equip.setItemInMainHand(weapon);
            }

            // 装飾とエンチャントの適用 (共通: 金装飾)
            applyArmorTrim(helmet, TrimMaterial.GOLD);
            applyVanillaEnchants(helmet, "ARMOR");
            equip.setHelmet(helmet);

            if (chest != null) {
                applyArmorTrim(chest, TrimMaterial.GOLD);
                applyVanillaEnchants(chest, "ARMOR");
                equip.setChestplate(chest);
            } else {
                equip.setChestplate(null); // ブルート用
            }

            // 足装備は一律なし
            equip.setLeggings(null);
            equip.setBoots(null);

            // ドロップ率設定 (0%)
            equip.setHelmetDropChance(0f);
            equip.setChestplateDropChance(0f);
            equip.setItemInMainHandDropChance(0f);

            return; // ピグリン系の処理終了
        }

        // =========================================================
        //  B. 通常のゾンビ & スケルトン (既存のロジック)
        // =========================================================
        ItemStack helmet = new ItemStack(Material.IRON_HELMET);
        ItemStack chest = new ItemStack(Material.IRON_CHESTPLATE);

        if (monster instanceof Zombie) {
            applyArmorTrim(helmet, TrimMaterial.DIAMOND);
            applyArmorTrim(chest, TrimMaterial.DIAMOND);
        } else if (monster instanceof Skeleton) {
            applyArmorTrim(helmet, TrimMaterial.AMETHYST);
            applyArmorTrim(chest, TrimMaterial.AMETHYST);
        }

        // 呪いとエンチャント
        helmet.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
        chest.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
        applyVanillaEnchants(helmet, "ARMOR");
        applyVanillaEnchants(chest, "ARMOR");

        equip.setHelmet(helmet);
        equip.setChestplate(chest);
        equip.setHelmetDropChance(0f);
        equip.setChestplateDropChance(0f);

        ItemStack weapon = null;
        String category = "";

        if (monster instanceof Zombie) {
            double roll = ThreadLocalRandom.current().nextDouble();
            if (roll < 0.4) {
                if (roll < 0.05) { weapon = new ItemStack(Material.DIAMOND_SWORD); category = "SWORD"; }
                else if (roll < 0.15) { weapon = new ItemStack(Material.IRON_AXE); category = "AXE"; }
                else if (roll < 0.25) { weapon = new ItemStack(Material.CROSSBOW); category = "CROSSBOW"; }
                else { weapon = new ItemStack(Material.IRON_SWORD); category = "SWORD"; }
            }
        } else if (monster instanceof Skeleton) {
            weapon = new ItemStack(Material.BOW);
            category = "BOW";
        }

        if (weapon != null) {
            applyVanillaEnchants(weapon, category);
            equip.setItemInMainHand(weapon);
            equip.setItemInMainHandDropChance(0.05f);
        }

    }

    /**
     * 防具に鍛冶型（装飾）を適用するヘルパーメソッド
     */
    private void applyArmorTrim(ItemStack item, org.bukkit.inventory.meta.trim.TrimMaterial material) {
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.ArmorMeta armorMeta)) return;

        // パターン：SENTRY（番人）- 職人が作り込んだような幾何学模様
        org.bukkit.inventory.meta.trim.ArmorTrim trim = new org.bukkit.inventory.meta.trim.ArmorTrim(
                material,
                org.bukkit.inventory.meta.trim.TrimPattern.SHAPER
        );

        armorMeta.setTrim(trim);
        item.setItemMeta(armorMeta);
    }

    /**
     * カテゴリーに応じたバニラ準拠のランダムエンチャントを適用する
     */
    private void applyVanillaEnchants(ItemStack item, String category) {
        List<Enchantment> possible = new ArrayList<>();

        // カテゴリー別・付与可能リスト
        switch (category) {
            case "ARMOR":
                possible.addAll(Arrays.asList(Enchantment.PROTECTION, Enchantment.THORNS, Enchantment.UNBREAKING, Enchantment.THORNS, Enchantment.BLAST_PROTECTION, Enchantment.FIRE_PROTECTION, Enchantment.PROJECTILE_PROTECTION));
                break;
            case "SWORD":
                possible.addAll(Arrays.asList(Enchantment.SHARPNESS, Enchantment.KNOCKBACK, Enchantment.FIRE_ASPECT));
                break;
            case "AXE":
                possible.addAll(Arrays.asList(Enchantment.SHARPNESS, Enchantment.EFFICIENCY));
                break;
            case "BOW":
                possible.addAll(Arrays.asList(Enchantment.POWER, Enchantment.PUNCH, Enchantment.FLAME, Enchantment.INFINITY));
                break;
            case "CROSSBOW":
                possible.addAll(Arrays.asList(Enchantment.QUICK_CHARGE, Enchantment.MULTISHOT, Enchantment.PIERCING));
                break;
        }

        if (possible.isEmpty()) return;

        // シャッフルして付与する個数を決める (1〜3個)
        Collections.shuffle(possible);
        int amount = ThreadLocalRandom.current().nextInt(1, 4); // 1..3 包含

        for (int i = 0; i < amount && i < possible.size(); i++) {
            Enchantment ench = possible.get(i);

            // バニラの最大レベルを取得 (例: Sharpnessなら5)
            int maxLevel = ench.getMaxLevel();
            // 1 〜 最大レベルの間でランダムに決定
            int level = ThreadLocalRandom.current().nextInt(1, maxLevel + 1);

            // 安全に付与（競合チェックを無視する場合は addUnsafeEnchantment）
            item.addUnsafeEnchantment(ench, level);
        }
    }
    private boolean isEnchanted(Entity entity) {
        Byte b = entity.getPersistentDataContainer().get(enchantedKey, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }


    private void startVisualAura(Monster monster) {
        org.bukkit.scheduler.BukkitTask t = new BukkitRunnable() {
            double angle = 0;

            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    cancel();
                    return;
                }

                Location baseLoc = monster.getLocation();
                Location eyeLoc = baseLoc.clone().add(0, 1.2, 0);

                // REDSTONE を DustOptions で表現 (カラー指定)
                monster.getWorld().spawnParticle(Particle.DUST, eyeLoc, 1, new Particle.DustOptions(Color.FUCHSIA, 1.0f));

                // 螺旋 (WITCH はデータ不要)
                double x = Math.cos(angle) * spiralRadius;
                double z = Math.sin(angle) * spiralRadius;
                Location spiralLoc = baseLoc.clone().add(x, (angle % (Math.PI * 2)) / 3, z);
                monster.getWorld().spawnParticle(Particle.WITCH, spiralLoc, 1, 0.0, 0.0, 0.0, 0.0);
                angle += spiralAngleIncrement;

                // --- 2. 【個別】種族別 ---
                if (monster instanceof Zombie) {
                    monster.getWorld().spawnParticle(Particle.FALLING_SPORE_BLOSSOM, eyeLoc, 2, 0.3, 0.5, 0.3, 0.0);
                } else if (monster instanceof Skeleton) {
                    monster.getWorld().spawnParticle(Particle.ASH, eyeLoc, 5, 0.2, 0.4, 0.2, 0.01);
                } else if (monster instanceof Creeper) {
                    if (ThreadLocalRandom.current().nextDouble() < 0.3) {
                        monster.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, eyeLoc, 1, 0.2, 0.2, 0.2, 0.0);
                    }
                } else if (monster instanceof Spider) {
                    monster.getWorld().spawnParticle(Particle.SQUID_INK, eyeLoc, 1, 0.3, 0.2, 0.3, 0.05);
                } else {
                    // REDSTONE を DustOptions で表現 (カラー指定)
                    monster.getWorld().spawnParticle(Particle.DUST, eyeLoc, 1, new Particle.DustOptions(Color.FUCHSIA, 1.0f));
                }
            }
        }.runTaskTimer(plugin, 0, auraUpdateInterval);
        registerMobTask(monster, t.getTaskId());
    }

    public void makeEnchanted(Monster monster) {
        monster.getPersistentDataContainer().set(enchantedKey, PersistentDataType.BYTE, (byte) 1);

        // 基本ステータス（HP・名前）の適用
        var hp = monster.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * hpMultiplier);
            monster.setHealth(hp.getBaseValue());
        }

        var speed = monster.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(movementSpeed);
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
            
            // ※クモのWebTrap/飛びかかりはEvent/個体判定で処理
        }else if (monster instanceof PiglinBrute brute) {
            startClimbingAI(brute);
            startLootingAI(brute);
            // 指揮官ゾンビと同様の「群れ呼び出し」をブルートにも適用
            // 注意：このメソッドを呼び出すためのトリガー（索敵時など）が必要です
            startBruteCommanderAI(brute);
        }

        // 共通オーラエフェクトの開始
        startVisualAura(monster);
    }

    private void startCoreNavigationAI(Monster monster) {
        org.bukkit.scheduler.BukkitTask t = new BukkitRunnable() {
            private Location lastKnownLocation = null;
            private Location lastLoc = monster.getLocation();
            private int stuckTicks = 0;
            private int searchTimer = 0;
            LivingEntity target = monster.getTarget();

            @Override
            public void run() {
                if (!monster.isValid() || monster.isDead()) {
                    cancel();
                    return;
                }
                LivingEntity target = monster.getTarget();

                if (target == null) {
                    return;
                }

                if (!monster.getWorld().equals(target.getWorld())) {
                    return; // 異なるワールドにいる場合は何もしない
                }

                Location mLoc = monster.getLocation();

                // 1. 【感知強化】ターゲットの徹底捜索と記憶
                if (target == null || !target.isValid() || target.isDead()) {
                    // 超広範囲スキャン
                    target = findExtendedTarget(monster, detectionRange);
                    if (target != null) {
                        monster.setTarget(target);
                    } else if (lastKnownLocation != null) {
                        // ターゲットを見失っても、最後に見た場所へ向かう
                        monster.getPathfinder().moveTo(lastKnownLocation, baseSpeed);
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

                if (stuckTicks > stuckTicksThreshold && monster.isOnGround()) {
                    // スタック時の自己復帰ジャンプ
                    Vector jumpDir = target.getLocation().toVector().subtract(mLoc.toVector()).normalize().multiply(stuckJumpHorizontal).setY(stuckJumpPower);
                    monster.setVelocity(jumpDir);
                    stuckTicks = 0;
                }

                // 3. 【速度の動的制御】距離に応じた「追い込み」
                double speed = baseSpeed;
                if (distance > farDistanceThreshold) {
                    speed = farDistanceSpeed; // 遠距離：一気に距離を詰める（ダッシュ）
                } else if (distance < closeDistanceThreshold) {
                    speed = closeDistanceSpeed; // 近距離：逃がさない速度
                }

                if (monster instanceof WitherSkeleton wither) {
                    EntityEquipment equipment = wither.getEquipment();
                    if (equipment != null) {
                        double dist = mLoc.distance(target.getLocation());
                        Material currentItem = equipment.getItemInMainHand().getType();

                        if (dist > 7.0) { // 7マスより遠いなら弓
                            if (currentItem != Material.BOW) {
                                ItemStack bow = new ItemStack(Material.BOW);
                                bow.addUnsafeEnchantment(Enchantment.FLAME, 1);
                                applyVanillaEnchants(bow, "BOW");
                                equipment.setItemInMainHand(bow);
                            }
                        } else { // 7マス以内なら石の剣
                            if (currentItem != Material.STONE_SWORD) {
                                ItemStack sword = new ItemStack(Material.STONE_SWORD);
                                applyVanillaEnchants(sword, "SWORD");
                                equipment.setItemInMainHand(sword);
                                // 持ち替え時に音を鳴らすと格好良い
                                wither.getWorld().playSound(wither.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 0.5f);
                            }
                        }
                    }
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
        registerMobTask(monster, t.getTaskId());
    }

    /**
     * 既にスポーンしている Enchanted Mob に対して、タスクをキャンセルして
     * 新しい設定で再スケジュールします。
     */
    public void rescheduleAllEnchantedMobs() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity e : world.getEntities()) {
                if (!(e instanceof Monster m)) continue;
                if (!isEnchanted(m)) continue;

                // 既存タスクをキャンセル
                cancelMobTasks(m);

                // ステータス反映（移動速度などは即時適用）
                var speedAttr = m.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speedAttr != null) {
                    speedAttr.setBaseValue(movementSpeed);
                }

                // 各種AIを再スケジュール
                startCoreNavigationAI(m);
                startVisualAura(m);

                if (m instanceof Zombie zombie) {
                    startClimbingAI(zombie);
                    startCommanderAura(zombie);
                    startLootingAI(zombie);
                } else if (m instanceof Skeleton skeleton) {
                    startClimbingAI(skeleton);
                    startLootingAI(skeleton);
                } else if (m instanceof Creeper creeper) {
                    startBurstAI(creeper);
                } else if (m instanceof Spider) {
                    // Spider-specific behaviors are event-driven; nothing to schedule here
                }
            }
        }
    }

    // --- 2. スケルトンのAI強化 (偏差撃ち & バックステップ) ---
    @EventHandler
    public void onSkeletonShoot(EntityShootBowEvent event) {
        // 判定に WitherSkeleton を追加
        if (!(event.getEntity() instanceof AbstractSkeleton skeleton) || !isEnchanted(skeleton)) return;
        if (!(skeleton.getTarget() instanceof Player player)) return;

        // 1. 基本情報の取得
        Location sLoc = skeleton.getEyeLocation();
        Location pLoc = player.getEyeLocation();

        // バニラの初速を取得し、強化倍率を適用
        double baseSpeed = event.getProjectile().getVelocity().length();

        // ウィザースケルトンの場合は弾速をさらに1.3倍速くする
        double currentMultiplier = arrowSpeedMultiplier;
        if (skeleton instanceof WitherSkeleton) {
            currentMultiplier *= 1.3;
        }
        double straightSpeed = baseSpeed * currentMultiplier;

        // --- 2. 弾種の分岐 (ホーミング矢) ---
        if (ThreadLocalRandom.current().nextDouble() < homingArrowChance) {
            launchHomingArrow(skeleton, player);
            event.setCancelled(true);
            return;
        }

        // --- 3. 直線スナイプ（偏差なし・重力無視） ---
        Vector direction = pLoc.toVector().subtract(sLoc.toVector()).normalize();

        // 矢を生成して物理挙動を上書き
        Entity arrow = event.getProjectile();
        arrow.setVelocity(direction.multiply(straightSpeed));

        // 重力の影響を無効化（レーザーのような弾道）
        arrow.setGravity(false);

        // 負荷対策：5秒後に消去
        new BukkitRunnable() {
            @Override
            public void run() {
                if (arrow.isValid() && !arrow.isOnGround()) arrow.remove();
            }
        }.runTaskLater(plugin, 100);

        // ウィザースケルトンの場合は少し低いピッチで音を鳴らす
        float pitch = (skeleton instanceof WitherSkeleton) ? 1.2f : 1.8f;
        skeleton.getWorld().playSound(sLoc, Sound.ENTITY_ARROW_SHOOT, 1.0f, pitch);
    }

    /**
     * 低速ホーミング弾の発射ロジック
     */
    private void launchHomingArrow(AbstractSkeleton skeleton, Player target) {
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

                // 低速でじわじわ追いかける
                homingArrow.setVelocity(toTarget.multiply(homingArrowSpeed));

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
        org.bukkit.scheduler.BukkitTask t = new BukkitRunnable() {
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
                    ItemStack main = equip.getItemInMainHand();
                    Material mainType = (main == null) ? Material.AIR : main.getType();
                    if (mainType != Material.STONE_AXE) {
                        equip.setItemInMainHand(new ItemStack(Material.STONE_AXE));
                        skeleton.getWorld().playSound(skeleton.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 1f, 1.2f);
                    }

                    // 【変更点】物理 Velocity ではなくスピードポーション を付与 (Speed II)
                    // 持続時間を短く設定し、このタスク(10tick周期)で上書きし続けることで、
                    // 「接近中だけ速い」状態を自然に作ります。
                    skeleton.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 15, 1, false, false, false));

                }
                // --- 狙撃モード (5マスより遠い) ---
                else {
                    ItemStack main = equip.getItemInMainHand();
                    Material mainType = (main == null) ? Material.AIR : main.getType();
                    if (mainType != Material.BOW) {
                        equip.setItemInMainHand(new ItemStack(Material.BOW));
                        // 弓に戻したときはスピードを解除（バニラの挙動に合わせる）
                        skeleton.removePotionEffect(PotionEffectType.SPEED);
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 10);
        registerMobTask(skeleton, t.getTaskId());
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
    public void onMonsterDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity() instanceof Monster m) {
            cancelMobTasks(m);
        }
    }

    @EventHandler
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        for (org.bukkit.entity.Entity e : event.getChunk().getEntities()) {
            if (e instanceof Monster m) {
                cancelMobTasks(m);
            }
        }
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
                if (dot > creeperVisibilityThreshold && distance < creeperSidestepDistance) { // 視線がほぼクリーパーを捉えている
                    Vector sideStep = new Vector(-playerDirection.getZ(), 0, playerDirection.getX()).normalize().multiply(creeperSidestepForce);
                    if (random.nextBoolean()) sideStep.multiply(-1); // 左右ランダム
                    creeper.setVelocity(creeper.getVelocity().add(sideStep));
                    creeper.getWorld().spawnParticle(Particle.CLOUD, cLoc, 1, 0.1, 0.1, 0.1, 0.05);
                }

                // B. 不意打ち加速 (プレイヤーが背を向けている時)
                if (dot < 0 && speed != null) {
                    speed.setBaseValue(creeperBackstabSpeed); // 背後フェイズの速度
                } else if (speed != null) {
                    speed.setBaseValue(creeperBaseSpeed);
                }

                // C. 跳躍爆破 (起爆寸前)
                if (creeper.isIgnited() || (distance < creeperBackstabDistance && random.nextDouble() < 0.1)) {
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
        for (Entity nearby : leadZombie.getNearbyEntities(swarmDetectionRange, commanderAuraHeight / 2, swarmDetectionRange)) {
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

    }
    private void applyPiglinBruteSwarm(PiglinBrute leadBrute, Player target) {
        // 1. 本人も確実にターゲットを追わせる
        leadBrute.setTarget(target);

        // 2. 周囲のピグリン・ブルート・ゾンビピグリンを呼ぶ
        // 範囲はゾンビ版の変数を流用、または設定値に合わせて調整
        for (Entity nearby : leadBrute.getNearbyEntities(swarmDetectionRange, commanderAuraHeight / 2, swarmDetectionRange)) {

            // 判定対象：ピグリン、ピグリンブルート、ゾンビピグリン
            if (nearby instanceof Piglin || nearby instanceof PiglinBrute || nearby instanceof PigZombie) {
                Monster fellow = (Monster) nearby;

                // 指揮官と同じターゲットを強制設定
                fellow.setTarget(target);

                /*
                 * 猪突猛進バフ：
                 * 移動速度上昇 II (Speed II) と、攻撃の重さを出すために 強さ I (Strength I) を付与
                 */
                fellow.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1, false, true, true));
                fellow.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 0, false, true, true));

                // ピグリンらしい視覚演出（溶岩のパチパチ音や火花）
                if (random.nextDouble() < 0.4) {
                    // LAVA（溶岩の火花）で興奮状態を表現
                    fellow.getWorld().spawnParticle(Particle.LAVA, fellow.getLocation().add(0, 1.5, 0), 5, 0.3, 0.3, 0.3, 0.1);
                    // 豚の鼻鳴らし音
                    fellow.getWorld().playSound(fellow.getLocation(), Sound.ENTITY_PIGLIN_BRUTE_ANGRY, 1.0f, 1.2f);
                }
            }
        }
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

            // 設定値に基づいて削除するタスク
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (feet.getBlock().getType() == Material.COBWEB) {
                        feet.getBlock().setType(Material.AIR);
                    }
                }
            }.runTaskLater(plugin, spiderWebTrapDuration);

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

                // 飛びかかり奇襲 (間合いが設定値の時)
                if (dist > spiderLeapMinDistance && dist < spiderLeapMaxDistance && spider.isOnGround() && random.nextDouble() < spiderLeapChance) {
                    Vector leap = player.getLocation().toVector().subtract(spider.getLocation().toVector()).normalize().multiply(spiderLeapSpeed);
                    leap.setY(0.5);
                    spider.setVelocity(leap);
                    spider.getWorld().playSound(spider.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.5f);
                }
            }
        }.runTaskTimer(plugin, 0, 20);
    }



    /**
     * 周囲のクモを呼び寄せ、特定のプレイヤーを攻撃させる
     */
    private void callNearbySpidersTo(Player target, Spider attacker) {
        // 周囲のエンティティを検索
        List<Entity> nearby = attacker.getNearbyEntities(spiderSwarmCallRange, spiderSwarmCallHeight, spiderSwarmCallRange);

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

    // --- 8. 指揮官のオーラ (ゾンビの周囲強化) ---
    private void startCommanderAura(Zombie commander) {
        org.bukkit.scheduler.BukkitTask t = new BukkitRunnable() {
            @Override
            public void run() {
                // 指揮官が無効になったらタスク終了
                if (!commander.isValid() || commander.isDead()) {
                    cancel();
                    return;
                }

                // 周囲のエンティティを取得
                List<Entity> nearby = commander.getNearbyEntities(commanderAuraRange, commanderAuraHeight, commanderAuraRange);
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
        }.runTaskTimer(plugin, 0, swarmAuraInterval); // config値で周期を制御
        registerMobTask(commander, t.getTaskId());
    }
    // --- 9. クリーパーの毒ガス爆発 ---
    @EventHandler
    public void onCreeperExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper) || !isEnchanted(creeper)) return;

        Location loc = creeper.getLocation();

        // 爆発後に残留ポーションの雲 (AreaEffectCloud) を生成
        AreaEffectCloud cloud = (AreaEffectCloud) loc.getWorld().spawnEntity(loc, EntityType.AREA_EFFECT_CLOUD);
        cloud.setRadius((float) creeperPoisonCloudRadius);
        cloud.setDuration(creeperPoisonCloudDuration); // config値で持続時間を制御
        cloud.setRadiusOnUse((float) creeperPoisonCloudRadiusOnUse); // 誰かが触れるたびに少し小さくなる
        cloud.setWaitTime(0);

        // デバフ効果（鈍鈍 + 弱体化）
        cloud.addCustomEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, creeperPoisonCloudDuration, 1), true);
        cloud.addCustomEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, creeperPoisonCloudDuration, 1), true);

        cloud.setColor(org.bukkit.Color.PURPLE); // 禍々しい紫色
        creeper.getWorld().playSound(loc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1f, 0.5f);
    }


    @EventHandler
    public void onZombieLowHP(EntityDamageEvent event) {
        // Zombie(ハスク等含む) または PigZombie(ゾンビピグリン) を対象にする
        if (!(event.getEntity() instanceof Monster monster) || !isEnchanted(monster)) return;
        if (!(monster instanceof Zombie || monster instanceof PigZombie)) return;

        if (monster.getPersistentDataContainer().has(reinforcedKey, PersistentDataType.BYTE)) return;

        // 体力判定
        if (monster.getHealth() - event.getFinalDamage() <= reinforcementHpThreshold) {
            if (ThreadLocalRandom.current().nextDouble() < reinforcementSpawnChance) {

                monster.getPersistentDataContainer().set(reinforcedKey, PersistentDataType.BYTE, (byte) 1);

                Location loc = monster.getLocation();
                monster.getWorld().playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0f, 0.5f);
                monster.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, loc.add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0);

                int count = ThreadLocalRandom.current().nextInt(reinforcementCountMin, reinforcementCountMax + 1);
                for (int i = 0; i < count; i++) {
                    // リーダー個体を渡す
                    spawnReinforcement(monster);
                }
            }
        }
    }

    /**
     * 増援ゾンビのスポーン処理
     */
    /**
     * 増援のスポーン処理（種類維持版）
     */
    /**
     * 増援のスポーン処理（エラー修正版）
     */
    private void spawnReinforcement(Monster leader) {
        double x = (ThreadLocalRandom.current().nextDouble() - 0.5) * (reinforcementSpawnDistance * 2);
        double z = (ThreadLocalRandom.current().nextDouble() - 0.5) * (reinforcementSpawnDistance * 2);
        Location spawnLoc = leader.getLocation().add(x, 0, z);

        if (spawnLoc.getBlock().getType().isSolid()) {
            spawnLoc.add(0, 1, 0);
        }

        // 【重要修正】leader.getClass() ではなく EntityClass を取得する
        // これにより CraftPigZombie ではなく PigZombie.class が正しく渡されます
        Class<? extends Entity> entityClass = leader.getType().getEntityClass();
        if (entityClass == null) return; // 万が一取得できない場合の安全策

        leader.getWorld().spawn(spawnLoc, (Class<? extends Monster>) entityClass, fellow -> {
            fellow.getPersistentDataContainer().set(enchantedKey, PersistentDataType.BYTE, (byte) 0);
            fellow.getPersistentDataContainer().set(reinforcedKey, PersistentDataType.BYTE, (byte) 1);

            if (ThreadLocalRandom.current().nextDouble() < enchantedReinforcementChance) {
                makeEnchanted(fellow);
                fellow.getWorld().strikeLightningEffect(fellow.getLocation());
                fellow.setCustomName("§d§lEnchanted Reinforcement");
            } else {
                // 種類名をカスタム名に反映
                String typeName = leader.getType().name().replace("_", " ").toLowerCase();
                fellow.setCustomName("§7" + capitalize(typeName) + " Grunt");

                EntityEquipment equip = fellow.getEquipment();
                if (equip != null) {
                    // ゾンビピグリン以外なら装備を配布
                    if (fellow instanceof Zombie && !(fellow instanceof PigZombie)) {
                        equip.setHelmet(new ItemStack(Material.LEATHER_HELMET));
                        equip.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
                    }
                    equip.setHelmetDropChance(0f);
                    equip.setChestplateDropChance(0f);
                }
            }

            if (leader.getTarget() != null) {
                fellow.setTarget(leader.getTarget());
            }

            fellow.getWorld().spawnParticle(Particle.CLOUD, fellow.getLocation(), 5, 0.2, 0.2, 0.2, 0.05);
        });
    }

    // ヘルパーメソッド：名前を綺麗にする用（例：PIG_ZOMBIE -> Pig zombie）
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
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
        org.bukkit.scheduler.BukkitTask t = new BukkitRunnable() {
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
                if (mLoc.distance(lastLoc) < stuckDistanceThreshold) {
                    stuckTicks++;
                } else {
                    stuckTicks = 0;
                }
                lastLoc = mLoc.clone();

                // 2. 基本移動指示
                monster.getPathfinder().moveTo(target, climbingSpeed);

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
                java.util.UUID occupier = occupiedBlocks.get(currentBlock.getLocation());
                if (occupier != null && !occupier.equals(monster.getUniqueId())) {
                    Entity other = Bukkit.getEntity(occupier);
                    if (other != null && other.isValid() && other.getLocation().distance(mLoc) < 1.0) {
                        Vector escape = mLoc.toVector().subtract(other.getLocation().toVector()).setY(0).normalize().multiply(0.2);
                        monster.setVelocity(monster.getVelocity().add(escape));
                        return;
                    } else {
                        occupiedBlocks.remove(currentBlock.getLocation());
                    }
                }

                // --- 6. 建築ロジック ---

                // A: 【柵・段差対策】進行方向2マス先まで検知
                // 段差がある場合、手前に足場を作る
                Location probe1 = mLoc.clone().add(dir.clone().multiply(0.8));
                Location probe2 = mLoc.clone().add(dir.clone().multiply(lookaheadDistance));

                if (diffY > verticalBuildCloseDistance && (probe1.getBlock().getType().isSolid() || probe2.getBlock().getType().isSolid())) {
                    org.bukkit.block.Block stepBase = mLoc.getBlock();
                    if (stepBase.getType() == Material.AIR) {
                        refreshTemporaryBlock(monster, stepBase.getLocation());
                        monster.setVelocity(new Vector(0, 0.42, 0));
                        return;
                    }
                }

                // B: 【垂直縦積み】壁が高い、または密着・スタック時
                if ((diffY >= verticalBuildHeight && distH <= 2.0) || (distH <= verticalBuildCloseDistance || stuckTicks > stuckTickLimit)) {
                    org.bukkit.block.Block feet = mLoc.getBlock();
                    // 足元が空気かつ、下が土台ブロックであること
                    if (feet.getType() == Material.AIR && mLoc.getBlock().getRelative(0, -1, 0).getType().isSolid()) {
                        occupiedBlocks.put(feet.getLocation(), monster.getUniqueId());
                        Location center = feet.getLocation().add(0.5, 0.1, 0.5);
                        center.setDirection(mLoc.getDirection());
                        monster.teleport(center);
                        refreshTemporaryBlock(monster, feet.getLocation());
                        monster.setVelocity(new Vector(0, 0.42, 0));
                        stuckTicks = 0;
                        return;
                    }
                }

                // C: 【橋渡し】2マス以上の崖がある場合
                if (distH > bridgeMinDistance) {
                    occupiedBlocks.values().removeIf(uuid -> uuid.equals(monster.getUniqueId()));
                    org.bukkit.block.Block bridgeBlock = probe1.getBlock().getRelative(0, -1, 0);
                    org.bukkit.block.Block deepBlock = probe1.getBlock().getRelative(0, -2, 0);

                    if (bridgeBlock.getType() == Material.AIR && deepBlock.getType() == Material.AIR) {
                        if (!probe1.getBlock().getRelative(0, 1, 0).getType().isSolid() &&
                                !probe1.getBlock().getRelative(0, 2, 0).getType().isSolid()) {
                            refreshTemporaryBlock(monster, bridgeBlock.getLocation());
                            monster.setVelocity(monster.getVelocity().add(dir.multiply(0.1)));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 5);
        registerMobTask(monster, t.getTaskId());
    }

    private void refreshTemporaryBlock(Monster monster, Location blockLoc) {
        org.bukkit.block.Block block = blockLoc.getBlock();

        // ネザー系（ピグリン、ウィザスケ、ゾンビピグリン）ならブラックストーン
        Material blockType = Material.MOSSY_COBBLESTONE;
        if (monster instanceof PiglinAbstract || monster instanceof WitherSkeleton || monster instanceof PigZombie) {
            blockType = Material.GILDED_BLACKSTONE;
        }

        // ブロックが空気なら設置
        if (block.getType() == Material.AIR) {
            block.setType(blockType);
            temporaryBlockTypes.put(blockLoc, blockType);
            block.getWorld().playSound(blockLoc, Sound.BLOCK_STONE_PLACE, 0.5f, 0.8f);
        }

        // ダメージを加算（0から開始し、最大10まで）
        activeDamageStages.merge(blockLoc, 1, (old, val) -> Math.min(10, old + 1));

        // まだ全体タイマーが動いていない場合は、別途管理タスクを1つだけ回す（コンストラクタ等で1回起動するのが理想）
    }
    public void startGlobalBlockDamageTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Map.Entry<Location, Integer>> it = activeDamageStages.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Location, Integer> entry = it.next();
                    Location loc = entry.getKey();
                    int stage = entry.getValue();
                    org.bukkit.block.Block block = loc.getBlock();
                    Material originalType = temporaryBlockTypes.getOrDefault(loc, Material.MOSSY_COBBLESTONE);

                    // ブロックがプレイヤーによって壊されていた場合などは中断
                    if (block.getType() != originalType) {
                        clearDamageAt(loc);
                        it.remove();
                        temporaryBlockTypes.remove(loc);
                        continue;
                    }

                    // 全プレイヤーに現在のダメージ段階を送信 (0.0f ~ 1.0f)
                    float progress = stage / 10.0f;
                    loc.getWorld().getPlayers().forEach(p -> p.sendBlockDamage(loc, progress));

                    // 次の更新に向けてステージを進める（または refresh で加算されたものを使う）
                    // ここでは「時間経過で自然に壊れる」ように毎秒少しずつ進める処理
                    if (stage >= 10) {
                        // 破壊処理
                        block.setType(Material.AIR);
                        block.getWorld().spawnParticle(Particle.BLOCK, loc.clone().add(0.5, 0.5, 0.5), 15, 0.2, 0.2, 0.2, originalType.createBlockData());
                        block.getWorld().playSound(loc, Sound.BLOCK_STONE_BREAK, 1.0f, 1.2f);

                        clearDamageAt(loc);
                        it.remove();
                        temporaryBlockTypes.remove(loc);
                    } else {
                        // 自動で壊れる速度（refreshされない場合でも少しずつ進む）
                        entry.setValue(stage + 1);
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 20L); // 1秒ごとに一括更新
    }

    private void clearDamageAt(Location loc) {
        loc.getWorld().getPlayers().forEach(p -> p.sendBlockDamage(loc, 0.0f));
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // 設置した一時ブロック（苔石）をプレイヤーが壊した場合
        if (event.getBlock().getType() == Material.MOSSY_COBBLESTONE) {
            Location loc = event.getBlock().getLocation();
            if (activeBlocks.containsKey(loc)) {
                // ドロップを無効にして破壊
                event.setDropItems(false);
                // タスクをキャンセルしてから削除
                Bukkit.getScheduler().cancelTask(activeBlocks.get(loc));
                activeBlocks.remove(loc);
                // ひびのパケットをリセット
                event.getBlock().getWorld().getPlayers().forEach(p -> p.sendBlockDamage(event.getBlock().getLocation(), 0.0f));
            }
        }
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
                    for (Entity e : monster.getNearbyEntities(equipmentDetectionRange, equipmentDetectionRange / 2, equipmentDetectionRange)) {
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
                        monster.getPathfinder().moveTo(targetItem.getLocation(), baseSpeed);
                    }
                }
            }
        };

        org.bukkit.scheduler.BukkitTask t = new BukkitRunnable() {
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
                    for (Entity e : monster.getNearbyEntities(equipmentDetectionRange, equipmentDetectionRange / 2, equipmentDetectionRange)) {
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
                        monster.getPathfinder().moveTo(targetItem.getLocation(), baseSpeed);
                    }
                }
            }
        }.runTaskTimer(plugin, 0, lootingAiInterval); // 判定周期をconfig値で制御

        registerMobTask(monster, t.getTaskId());
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

                // 3. 突進の発動条件 (config値の範囲)
                if (dist > creeperBurstMinDistance && dist < creeperBurstMaxDistance && !isLeaping) {
                    // ターゲットへの方向を計算
                    Vector toTarget = tLoc.toVector().subtract(cLoc.toVector()).normalize();

                    // 物理的な「打ち出し」
                    // XZ平面に強い推進力、Y軸にふんわり浮き上がる力を加える
                    Vector leapVel = toTarget.multiply(creeperBurstSpeed).setY(creeperBurstVerticalPower);
                    creeper.setVelocity(leapVel);

                    // 4. 【演出】ウィンドチャージの爆風
                    // クリーパーの少し背後から爆風を出すことで、前に押し出されているように見せる
                    Location burstLoc = cLoc.clone().subtract(toTarget.multiply(0.5));
                    creeper.getWorld().spawnParticle(Particle.EXPLOSION, burstLoc, 1);
                    creeper.getWorld().playSound(burstLoc, Sound.ENTITY_WIND_CHARGE_THROW, 1.0f, 1.2f);

                    isLeaping = true;
                    cooldown = creeperBurstCooldown; // config値でクールダウンを制御
                }
            }
        }.runTaskTimer(plugin, 0, 2); // 判定精度を上げて接触検知を確実にする
    }
    // EnchantedMobSystemクラス内に追加するメソッド
    public void cleanup() {
        // 残っている一時ブロックを全て消去
        for (Location loc : new ArrayList<>(activeBlocks.keySet())) {
            org.bukkit.block.Block block = loc.getBlock();
            if (block.getType() == Material.MOSSY_COBBLESTONE) {
                block.setType(Material.AIR);
            }
        }
        activeBlocks.clear();
        occupiedBlocks.clear();
    }

    public void disable() {
        // キャンプファイアタスク停止
        if (campfireTask != null) {
            campfireTask.cancel();
            campfireTask = null;
        }

        // 全モブの登録タスクをキャンセル
        for (java.util.UUID uid : new java.util.ArrayList<>(mobTasks.keySet())) {
            java.util.List<Integer> ids = mobTasks.remove(uid);
            if (ids != null) ids.forEach(id -> Bukkit.getScheduler().cancelTask(id));
        }

        cleanup();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (campfireTask == null) startCampfireHealingTask();
        rescheduleAllEnchantedMobs();
    }
    // コンストラクタに追加、または初期化時に呼び出し
    public void startCampfireHealingTask() {
        campfireTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 全世界で焚き火を探すと重いため、オンラインプレイヤーの周囲のみをスキャン
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Location loc = player.getLocation();

                    // プレイヤーの周囲のブロックを確認
                    int radius = (int) campfireScanRange;
                    for (int x = -radius; x <= radius; x++) {
                        for (int y = -2; y <= 2; y++) {
                            for (int z = -radius; z <= radius; z++) {
                                org.bukkit.block.Block block = loc.clone().add(x, y, z).getBlock();

                                // 焚き火（火がついているもの）を発見
                                if (block.getType() == Material.CAMPFIRE || block.getType() == Material.SOUL_CAMPFIRE) {
                                    org.bukkit.block.data.type.Campfire data = (org.bukkit.block.data.type.Campfire) block.getBlockData();
                                    if (data.isLit()) {
                                        applyCampfireHeal(block.getLocation());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0, campfireTaskInterval); // config値でタスク周期を制御
    }

    private void applyCampfireHeal(Location fireLoc) {
        // 統一された回復量をconfig値から取得
        double healAmount = campfireHealAmount;

        // 焚き火の周囲のエンティティを確認
        for (Entity entity : fireLoc.getWorld().getNearbyEntities(fireLoc, campfireScanRange, campfireScanHeight, campfireScanRange)) {
            // プレイヤーのみを対象にする
            if (entity instanceof Player player) {
                // サバイバルまたはアドベンチャーモードのプレイヤーのみ対象
                if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {

                    double maxHp = player.getAttribute(Attribute.MAX_HEALTH).getValue();

                    // 現在の体力が最大体力未満の場合のみ回復
                    if (player.getHealth() < maxHp) {
                        double newHealth = Math.min(maxHp, player.getHealth() + healAmount);
                        player.setHealth(newHealth);

                        // --- 演出の統一 ---
                        // 統一された演出（パーティクル）を表示
                        player.getWorld().spawnParticle(
                                Particle.HAPPY_VILLAGER,
                                player.getLocation().add(0, 1.2, 0),
                                10,           // 粒の数
                                0.3, 0.5, 0.35, // 広がり
                                0.35           // 速度
                        );

                        // 統一された回復音（必要に応じて追加）

                    }
                }
            }
        }
    }
    private void startBruteCommanderAI(PiglinBrute brute) {
        org.bukkit.scheduler.BukkitTask t = new BukkitRunnable() {
            @Override
            public void run() {
                if (!brute.isValid() || brute.isDead()) {
                    cancel();
                    return;
                }

                LivingEntity target = brute.getTarget();
                // ターゲットがプレイヤーかつサバイバル/アドベンチャーモードの場合のみ発動
                if (target instanceof Player player &&
                        (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {

                    // 群れ召喚スキルの実行
                    applyPiglinBruteSwarm(brute, player);
                }
            }
        }.runTaskTimer(plugin, 0, 40); // 2秒(40ticks)ごとに索敵・号令

        registerMobTask(brute, t.getTaskId());
    }

}

