package net.akabox.rascraft.menu;

import net.akabox.rascraft.RascraftPluginPacks;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MenuManager {
    private final RascraftPluginPacks plugin;
    private final Map<String, MenuData> menus = new HashMap<>();
    private File menuFile;
    private FileConfiguration menuConfig;

    public MenuManager(RascraftPluginPacks plugin) {
        this.plugin = plugin;
        loadMenus();
    }

    public void loadMenus() {
        menus.clear();
        menuFile = new File(plugin.getDataFolder(), "menu.yml");

        if (!menuFile.exists()) {
            plugin.saveResource("menu.yml", false);
        }

        try {
            menuConfig = YamlConfiguration.loadConfiguration(menuFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load menu.yml: " + e.getMessage());
            return;
        }

        ConfigurationSection menuSection = menuConfig.getConfigurationSection("menu");
        if (menuSection == null)
            return;

        for (String menuId : menuSection.getKeys(false)) {
            ConfigurationSection config = menuSection.getConfigurationSection(menuId);
            if (config == null)
                continue;

            String title = config.getString("title", "Menu");
            int size = config.getInt("size", 27);
            String menuType = config.getString("menu-type", "normal");
            String tabTarget = config.getString("tab-target", null); // tab-complete用

            Map<String, MenuItemData> itemsMap = new HashMap<>();
            ConfigurationSection itemsSection = config.getConfigurationSection("items");

            if (itemsSection != null) {
                for (String itemKey : itemsSection.getKeys(false)) {
                    ConfigurationSection itemConfig = itemsSection.getConfigurationSection(itemKey);
                    if (itemConfig == null)
                        continue;

                    String material = itemConfig.getString("material", "STONE");
                    String displayName = itemConfig.getString("display-name", "");
                    String displayNameColor = itemConfig.getString("display-name-color", "");
                    List<String> lore = itemConfig.getStringList("lore");
                    int slot = itemConfig.getInt("slot", -1);
                    String type = itemConfig.getString("type", "none");

                    // 複数コマンド・単一コマンド両対応
                    List<String> commands = null;
                    if (itemConfig.isList("commands")) {
                        commands = itemConfig.getStringList("commands");
                    } else if (itemConfig.isString("action.command")) {
                        commands = List.of(itemConfig.getString("action.command"));
                    }

                    String openMenu = itemConfig.getString("action.open_menu");
                    String playSound = itemConfig.getString("action.play_sound");
                    String texture = itemConfig.getString("texture");

                    MenuItemData itemData = new MenuItemData(
                            itemKey, material, displayName, displayNameColor, lore, slot, type, commands, openMenu,
                            playSound, texture);
                    itemsMap.put(itemKey, itemData);
                }
            }

            MenuData menuData = new MenuData(menuId, title, size, menuType, tabTarget, itemsMap);
            menus.put(menuId, menuData);
        }

        plugin.getLogger().info("Loaded " + menus.size() + " menus from menu.yml");
    }

    public MenuData getMenu(String id) {
        return menus.get(id);
    }

    public Map<String, MenuData> getAllMenus() {
        return menus;
    }
}
