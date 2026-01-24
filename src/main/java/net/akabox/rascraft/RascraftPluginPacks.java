package net.akabox.rascraft;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class RascraftPluginPacks extends JavaPlugin {

    private final Map<Material, GrowType> growableMap = new EnumMap<>(Material.class);
    private final Map<UUID, String> playerSelectedEffect = new HashMap<>();
    private final List<String> allowedTrails = new ArrayList<>();
    private final List<String> vipTrails = new ArrayList<>();
    private final Map<String, ColoredParticleData> coloredEffectMap = new HashMap<>();
    private final Map<String, Integer> effectDensityMap = new HashMap<>();
    // 各機能の有効・無効フラグ
    private boolean sneakGrowEnabled;
    private boolean trailEnabled;
    private boolean tpEffectEnabled;
    private double successChance;
    private int growRadius;
    private int defaultTrailDensity;
    private double trailInterval;
    private boolean isOverridden = false;
    private File playerDataFile;
    private FileConfiguration playerDataConfig;
    private File langFile;
    private FileConfiguration langConfig;
    private double healthViewDistance;
    private EnchantedMobSystem mobSystem;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createPlayerDataFile();
        createLangFile();
        loadConfiguration();
        loadPlayerData();
        this.mobSystem = new EnchantedMobSystem(this);

        org.bukkit.command.ConsoleCommandSender console = getServer().getConsoleSender();
        String version = getDescription().getVersion();
        String authors = String.join(", ", getDescription().getAuthors());

        console.sendMessage("§e==================================================");
        console.sendMessage("§6   _____  _____  _____           _        ");
        console.sendMessage("§6  |  __ \\|  __ \\|  __ \\         | |       ");
        console.sendMessage("§e  | |__) | |__) | |__) |_ _  ___| | _____ ");
        console.sendMessage("§e  |  _  /|  ___/|  ___/ _` |/ __| |/ / __|");
        console.sendMessage("§6  | | \\ \\| |    | |  | (_| | (__|   (\\__ \\");
        console.sendMessage("§6  |_|  \\_\\_|    |_|   \\__,_|\\___|_|\\_\\___/");
        console.sendMessage("§f             Version: §b" + version);
        console.sendMessage("§a   Successfully enabled. Developed by §f" + authors);
        console.sendMessage("§e==================================================");

        getServer().getPluginManager().registerEvents(new SneakListener(this), this);
        getServer().getPluginManager().registerEvents(new TrailListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new TeleportListener(this), this);
        getServer().getPluginManager().registerEvents(new EntityHealthSystem(this), this);
        getServer().getPluginManager().registerEvents(new EnchantedMobSystem(this), this);

        RasPluginCommand cmd = new RasPluginCommand(this);
        getCommand("rppacks").setExecutor(cmd);
        getCommand("rppacks").setTabCompleter(cmd);
    }

    private void createPlayerDataFile() {
        playerDataFile = new File(getDataFolder(), "player.yml");
        if (!playerDataFile.exists()) {
            try {
                playerDataFile.createNewFile();
            } catch (IOException ignored) {
            }
        }
        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void createLangFile() {
        langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    public void loadConfiguration() {
        reloadConfig();
        if (langFile == null) langFile = new File(getDataFolder(), "lang.yml");
        langConfig = YamlConfiguration.loadConfiguration(langFile);

        FileConfiguration config = getConfig();

        // SneakGrow 設定
        growableMap.clear();
        ConfigurationSection growSection = config.getConfigurationSection("sneak-grow.growable-blocks");
        if (growSection != null) {
            for (String key : growSection.getKeys(false)) {
                Material mat = Material.getMaterial(key.toUpperCase());
                String typeStr = growSection.getString(key);
                if (mat != null && typeStr != null) {
                    try {
                        growableMap.put(mat, GrowType.valueOf(typeStr.toUpperCase()));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        sneakGrowEnabled = config.getBoolean("sneak-grow.enabled", true);
        if (!isOverridden) {
            successChance = config.getDouble("sneak-grow.success-chance", 0.8);
            growRadius = config.getInt("sneak-grow.radius", 1);
        }

        // Trail 設定
        trailEnabled = config.getBoolean("footprint-trail.enabled", true);
        allowedTrails.clear();
        vipTrails.clear();
        effectDensityMap.clear();
        defaultTrailDensity = config.getInt("footprint-trail.density", 2);
        trailInterval = config.getDouble("footprint-trail.interval", 0.2);

        parseEffectList(config.getStringList("footprint-trail.allowed-effects"), allowedTrails);
        parseEffectList(config.getStringList("footprint-trail.vip-effects"), vipTrails);

        coloredEffectMap.clear();
        ConfigurationSection colorSection = config.getConfigurationSection("footprint-trail.colored-effects");
        if (colorSection != null) {
            for (String key : colorSection.getKeys(false)) {
                try {
                    String pStr = config.getString("footprint-trail.colored-effects." + key + ".particle");
                    if (pStr == null) continue;
                    Particle p = Particle.valueOf(pStr.toUpperCase());
                    String hex = config.getString("footprint-trail.colored-effects." + key + ".color", "#FFFFFF");
                    java.awt.Color awtColor = java.awt.Color.decode(hex);
                    org.bukkit.Color bukkitColor = org.bukkit.Color.fromRGB(awtColor.getRed(), awtColor.getGreen(), awtColor.getBlue());
                    float size = (float) config.getDouble("footprint-trail.colored-effects." + key + ".size", 1.0);
                    double note = config.getDouble("footprint-trail.colored-effects." + key + ".note-color", 0.0);
                    coloredEffectMap.put(key.toUpperCase(), new ColoredParticleData(p, bukkitColor, size, note));
                    if (config.contains("footprint-trail.colored-effects." + key + ".density")) {
                        effectDensityMap.put(key.toUpperCase(), config.getInt("footprint-trail.colored-effects." + key + ".density"));
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // TeleportEffect 設定
        tpEffectEnabled = config.getBoolean("teleport-effect.enabled", true);

        this.healthViewDistance = config.getDouble("features.health-bar.view-distance", 8.0);
    }

    private void parseEffectList(List<String> source, List<String> target) {
        for (String entry : source) {
            if (entry == null) continue;
            if (entry.contains(":")) {
                String[] parts = entry.split(":");
                String name = parts[0].toUpperCase();
                target.add(name);
                try {
                    effectDensityMap.put(name, Integer.parseInt(parts[1]));
                } catch (Exception ignored) {
                }
            } else {
                target.add(entry.toUpperCase());
            }
        }
    }

    private void loadPlayerData() {
        ConfigurationSection section = playerDataConfig.getConfigurationSection("trails");
        if (section != null) {
            for (String uuidStr : section.getKeys(false)) {
                playerSelectedEffect.put(UUID.fromString(uuidStr), section.getString(uuidStr).toUpperCase());
            }
        }
    }

    public void savePlayerData() {
        playerDataConfig.set("trails", null);
        for (Map.Entry<UUID, String> entry : playerSelectedEffect.entrySet()) {
            playerDataConfig.set("trails." + entry.getKey().toString(), entry.getValue());
        }
        try {
            playerDataConfig.save(playerDataFile);
        } catch (IOException ignored) {
        }
    }

    public String getMessage(String key) {
        if (langConfig == null) return ChatColor.RED + "Lang file error.";
        String msg = langConfig.getString(key, "&cMessage not found: " + key);
        String prefix = langConfig.getString("prefix", "&6[RPPacks] &r");
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }

    public String getRawMessage(String key) {
        if (langConfig == null) return key;
        return ChatColor.translateAlternateColorCodes('&', langConfig.getString(key, key));
    }

    public EnchantedMobSystem getMobSystem() {
        return mobSystem;
    }

    public long getEquippedPlayerCount() {
        return playerSelectedEffect.size();
    }

    public boolean isIndicatorEnabled() {
        return getConfig().getBoolean("features.damage-indicator.enabled", true);
    }

    public int getHealthDisplayTicks() {
        return getConfig().getInt("features.health-bar.display-ticks", 60);
    }

    public GrowType getGrowType(Material m) {
        return growableMap.getOrDefault(m, GrowType.NONE);
    }

    public int getEffectDensity(String name) {
        return effectDensityMap.getOrDefault(name.toUpperCase(), defaultTrailDensity);
    }

    // --- Getter / Setter 追加分 ---

    public String getPlayerEffect(UUID uuid) {
        return playerSelectedEffect.get(uuid);
    }

    public void setPlayerEffect(UUID uuid, String name) {
        if (name == null) playerSelectedEffect.remove(uuid);
        else playerSelectedEffect.put(uuid, name.toUpperCase());
        savePlayerData();
    }

    public List<String> getAllowedTrails() {
        return allowedTrails;
    }

    public List<String> getVipTrails() {
        return vipTrails;
    }

    public Map<String, ColoredParticleData> getColoredEffectMap() {
        return coloredEffectMap;
    }

    public double getTrailInterval() {
        return trailInterval;
    }

    // SneakGrow 制御
    public boolean isSneakGrowEnabled() {
        return sneakGrowEnabled;
    }

    public void setSneakGrowEnabled(boolean enabled) {
        this.sneakGrowEnabled = enabled;
    }

    public double getSuccessChance() {
        return successChance;
    }

    public void setSuccessChance(double d, boolean o) {
        this.successChance = d;
        this.isOverridden = o;
    }

    public int getGrowRadius() {
        return growRadius;
    }

    public void setGrowRadius(int r) {
        this.growRadius = r;
    }

    // Trail 制御
    public boolean isTrailEnabled() {
        return trailEnabled;
    }

    public void setTrailEnabled(boolean enabled) {
        this.trailEnabled = enabled;
    }

    // TpEffect 制御
    public boolean isTpEffectEnabled() {
        return tpEffectEnabled;
    }

    public void setTpEffectEnabled(boolean enabled) {
        this.tpEffectEnabled = enabled;
    }

    public void reloadPlugin() {
        this.isOverridden = false;
        loadConfiguration();
    }

    public double getHealthViewDistance() {
        return healthViewDistance;
    }

    public enum GrowType {AGEABLE, STACK, BONEMEAL, NONE}

    public record ColoredParticleData(Particle particle, org.bukkit.Color color, float size, double noteColor) {
    }
}