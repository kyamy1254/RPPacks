package net.akabox.rascraft.economy;

import net.akabox.rascraft.RascraftPluginPacks;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class NetShopManager {
    private final RascraftPluginPacks plugin;
    private final File file;
    private FileConfiguration config;
    private final List<MarketItem> items = new ArrayList<>();
    private final Set<UUID> lockedItems = ConcurrentHashMap.newKeySet();

    public enum SortType {
        NEWEST,
        PRICE_HIGH_TO_LOW,
        PRICE_LOW_TO_HIGH,
        NAME
    }

    public NetShopManager(RascraftPluginPacks plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "netshop.yml");
        loadItems();
    }

    public void loadItems() {
        items.clear();
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create empty netshop.yml!");
            }
        }
        config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection section = config.getConfigurationSection("items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    ItemStack itemStack = section.getItemStack(key + ".item");
                    double price = section.getDouble(key + ".price");
                    UUID sellerUuid = UUID.fromString(section.getString(key + ".sellerUuid"));
                    String sellerName = section.getString(key + ".sellerName");
                    long listTime = section.getLong(key + ".listTime");

                    if (itemStack != null) {
                        items.add(new MarketItem(id, itemStack, price, sellerUuid, sellerName, listTime));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load netshop item: " + key);
                }
            }
        }
    }

    public void saveItems() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        config.set("items", null); // Clear existing

        for (MarketItem item : items) {
            String key = "items." + item.getId().toString();
            config.set(key + ".item", item.getItemStack());
            config.set(key + ".price", item.getPrice());
            config.set(key + ".sellerUuid", item.getSellerUuid().toString());
            config.set(key + ".sellerName", item.getSellerName());
            config.set(key + ".listTime", item.getListTime());
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save netshop.yml!");
            e.printStackTrace();
        }
    }

    public void addItem(ItemStack itemStack, double price, UUID sellerUuid, String sellerName) {
        MarketItem item = new MarketItem(UUID.randomUUID(), itemStack.clone(), price, sellerUuid, sellerName,
                System.currentTimeMillis());
        items.add(item);
        saveItems();
    }

    public void removeItem(UUID id) {
        items.removeIf(item -> item.getId().equals(id));
        saveItems();
    }

    public boolean lockItem(UUID id) {
        return lockedItems.add(id);
    }

    public void unlockItem(UUID id) {
        lockedItems.remove(id);
    }

    public MarketItem getItem(UUID id) {
        return items.stream().filter(i -> i.getId().equals(id)).findFirst().orElse(null);
    }

    public List<MarketItem> getItems(SortType sortType) {
        List<MarketItem> sorted = new ArrayList<>(items);
        switch (sortType) {
            case NEWEST -> sorted.sort(Comparator.comparingLong(MarketItem::getListTime).reversed());
            case PRICE_HIGH_TO_LOW -> sorted.sort(Comparator.comparingDouble(MarketItem::getPrice).reversed());
            case PRICE_LOW_TO_HIGH -> sorted.sort(Comparator.comparingDouble(MarketItem::getPrice));
            case NAME -> sorted.sort((i1, i2) -> {
                String n1 = i1.getItemStack().getItemMeta() != null && i1.getItemStack().getItemMeta().hasDisplayName()
                        ? i1.getItemStack().getItemMeta().getDisplayName()
                        : i1.getItemStack().getType().name();
                String n2 = i2.getItemStack().getItemMeta() != null && i2.getItemStack().getItemMeta().hasDisplayName()
                        ? i2.getItemStack().getItemMeta().getDisplayName()
                        : i2.getItemStack().getType().name();
                return n1.compareToIgnoreCase(n2);
            });
        }
        return sorted;
    }
}
