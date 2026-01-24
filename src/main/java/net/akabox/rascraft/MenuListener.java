package net.akabox.rascraft;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class MenuListener implements Listener {
    private final RascraftPluginPacks plugin;

    public MenuListener(RascraftPluginPacks plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        String strippedTitle = ChatColor.stripColor(title);

        // lang.yml の設定に基づいたタイトル判定
        String configTitleBase = ChatColor.stripColor(plugin.getRawMessage("trail-menu-title").split("\\[")[0]);
        if (!strippedTitle.contains(configTitleBase)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        Material type = clicked.getType();
        String plainName = ChatColor.stripColor(meta.getDisplayName());

        // 装飾アイテムの無視
        if (type == Material.GRAY_STAINED_GLASS_PANE || type == Material.CYAN_STAINED_GLASS_PANE || type == Material.PLAYER_HEAD)
            return;

        // ページ切り替え
        if (type == Material.ARROW) {
            handlePageChange(p, strippedTitle, plainName);
            return;
        }

        // 解除
        if (type == Material.BARRIER || plainName.equals(ChatColor.stripColor(plugin.getRawMessage("remove-effect-button")))) {
            plugin.setPlayerEffect(p.getUniqueId(), null);
            p.sendMessage(plugin.getMessage("trail-remove"));
            playConfigSound(p, "select");
            p.closeInventory();
            return;
        }

        // エフェクト選択
        // 接頭辞（★ や ≫ 等）を除去してエフェクト名を取得
        String vipPrefix = ChatColor.stripColor(plugin.getRawMessage("trail-item-vip-prefix"));
        String commonPrefix = ChatColor.stripColor(plugin.getRawMessage("trail-item-common-prefix"));
        String effectName = plainName.replace(vipPrefix, "").replace(commonPrefix, "").trim();

        // VIP権限チェック
        if (plugin.getVipTrails().contains(effectName.toUpperCase()) && !p.hasPermission("rppacks.vip")) {
            p.sendMessage(plugin.getMessage("trail-vip-required"));
            playConfigSound(p, "error");
            return;
        }

        plugin.setPlayerEffect(p.getUniqueId(), effectName);
        p.sendMessage(plugin.getMessage("trail-equip").replace("{effect}", effectName));
        playConfigSound(p, "select");
        p.closeInventory();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        String configTitleBase = ChatColor.stripColor(plugin.getRawMessage("trail-menu-title").split("\\[")[0]);
        if (ChatColor.stripColor(event.getView().getTitle()).contains(configTitleBase)) {
            event.setCancelled(true);
        }
    }

    private void handlePageChange(Player p, String strippedTitle, String plainName) {
        int currentPage = 0;
        try {
            if (strippedTitle.contains("Page ")) {
                String pagePart = strippedTitle.substring(strippedTitle.indexOf("Page ") + 5).replace("]", "");
                currentPage = Integer.parseInt(pagePart.trim()) - 1;
            }
        } catch (Exception ignored) {
        }

        if (plugin.getCommand("rppacks").getExecutor() instanceof RasPluginCommand cmd) {
            boolean isNext = plainName.contains("次");
            cmd.openTrailMenu(p, isNext ? currentPage + 1 : currentPage - 1);
            playConfigSound(p, "page-change");
        }
    }

    private void playConfigSound(Player p, String key) {
        try {
            org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("sounds.gui." + key);
            if (section == null) return;
            String soundName = section.getString("name");
            if (soundName == null) return;

            Sound sound = Registry.SOUND_EVENT.get(NamespacedKey.minecraft(soundName.toLowerCase()));
            if (sound != null) {
                p.playSound(p.getLocation(), sound, (float) section.getDouble("volume", 1.0), (float) section.getDouble("pitch", 1.0));
            }
        } catch (Exception ignored) {
        }
    }
}