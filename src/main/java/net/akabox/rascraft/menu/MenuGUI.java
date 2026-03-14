package net.akabox.rascraft.menu;

import net.akabox.rascraft.RascraftPluginPacks;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class MenuGUI implements Listener {
    private final RascraftPluginPacks plugin;
    private final NamespacedKey ACTION_KEY;
    private final NamespacedKey MENU_ID_KEY;
    private final NamespacedKey TARGET_KEY; // %player% や %tab% の展開用

    private final Map<java.util.UUID, String> currentMenuMap = new java.util.HashMap<>();
    private final Map<java.util.UUID, Integer> currentPageMap = new java.util.HashMap<>();
    private final Map<java.util.UUID, MenuItemData> pendingConfirmMap = new java.util.HashMap<>();
    private final Map<java.util.UUID, String> pendingTargetMap = new java.util.HashMap<>();

    // ページネーション用のボタン設定
    private static final int ITEMS_PER_PAGE = 45; // 54サイズインベントリの下段(9スロット)をナビゲーション用にする想定

    public MenuGUI(RascraftPluginPacks plugin) {
        this.plugin = plugin;
        this.ACTION_KEY = new NamespacedKey(plugin, "menu_action");
        this.MENU_ID_KEY = new NamespacedKey(plugin, "menu_id");
        this.TARGET_KEY = new NamespacedKey(plugin, "menu_target");
    }

    public void openMenu(Player player, String menuId) {
        openMenu(player, menuId, 0);
    }

    public void openMenu(Player player, String menuId, int page) {
        MenuData menuData = plugin.getMenuManager().getMenu(menuId);
        if (menuData == null) {
            player.sendMessage(ChatColor.RED + "メニュー '" + menuId + "' が見つかりません。");
            return;
        }

        int size = menuData.getSize();
        // player と tab-complete は可変長なので、ページサイズを満たすために最低54にするか、設定値を使う
        if (!menuData.getMenuType().equalsIgnoreCase("normal")) {
            size = Math.max(size, 54);
        }

        Inventory inventory = Bukkit.createInventory(null, size,
                ChatColor.translateAlternateColorCodes('&', menuData.getTitle()));

        switch (menuData.getMenuType().toLowerCase()) {
            case "normal":
                buildNormalMenu(player, inventory, menuData);
                break;
            case "player":
                buildPlayerMenu(player, inventory, menuData, page);
                break;
            case "tab-complete":
                buildTabCompleteMenu(player, inventory, menuData, page);
                break;
        }

        applyDecorations(player, inventory, menuId);

        player.openInventory(inventory);
        // openInventory の中で以前のインベントリのCloseEventが呼ばれてマップがクリアされるため、
        // その後に put する必要があります。
        currentMenuMap.put(player.getUniqueId(), menuId);
        currentPageMap.put(player.getUniqueId(), page);
    }

    public void refreshCurrentMenu(Player player) {
        String menuId = currentMenuMap.get(player.getUniqueId());
        if (menuId != null) {
            int page = currentPageMap.getOrDefault(player.getUniqueId(), 0);
            openMenu(player, menuId, page);
        }
    }

    private void applyDecorations(Player player, Inventory inventory, String menuId) {
        // "home" メニューの場合のみステータスヘッドをスロット4に配置
        if ("home".equalsIgnoreCase(menuId)) {
            // 所持金を取得
            String balanceInfo = "---";
            if (plugin.getVaultManager() != null) {
                balanceInfo = plugin.getVaultManager().formatCurrency(plugin.getVaultManager().getBalance(player));
            }

            ItemStack statusHead = new ItemStack(Material.PLAYER_HEAD);
            org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) statusHead
                    .getItemMeta();
            if (skullMeta != null) {
                skullMeta.setOwningPlayer(player);
                skullMeta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + player.getName() + " のステータス");

                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "所持金: " + ChatColor.YELLOW + balanceInfo);
                skullMeta.setLore(lore);
                statusHead.setItemMeta(skullMeta);
            }
            inventory.setItem(4, statusHead);
        }

        // 背景色の取得
        Material bgMaterial = Material.matchMaterial(plugin.getMenuBgColor(player.getUniqueId()));
        if (bgMaterial == null)
            bgMaterial = Material.LIGHT_GRAY_STAINED_GLASS_PANE;

        Material actionMaterial = Material.matchMaterial(plugin.getMenuActionColor(player.getUniqueId()));
        if (actionMaterial == null)
            actionMaterial = Material.CYAN_STAINED_GLASS_PANE;

        ItemStack bgFiller = new ItemStack(bgMaterial);
        ItemMeta bgMeta = bgFiller.getItemMeta();
        if (bgMeta != null) {
            bgMeta.setDisplayName(" "); // 空白名で隠す
            bgFiller.setItemMeta(bgMeta);
        }

        ItemStack actionFiller = new ItemStack(actionMaterial);
        ItemMeta actionMeta = actionFiller.getItemMeta();
        if (actionMeta != null) {
            actionMeta.setDisplayName(" ");
            actionFiller.setItemMeta(actionMeta);
        }

        int size = inventory.getSize();
        for (int i = 0; i < size; i++) {
            ItemStack current = inventory.getItem(i);
            if (current == null || current.getType() == Material.AIR) {
                // 下段9スロットはアクションバー色、それ以外は背景色
                if (i >= size - 9 && size >= 54) { // サイズが十分ある場合のみアクションバー扱い
                    inventory.setItem(i, actionFiller);
                } else {
                    inventory.setItem(i, bgFiller);
                }
            }
        }
    }

    private void buildNormalMenu(Player player, Inventory inventory, MenuData menuData) {
        for (MenuItemData itemData : menuData.getItems().values()) {
            int slot = itemData.getSlot();
            if (slot < 0 || slot >= inventory.getSize())
                continue;

            MenuItemData currentItemData = evaluateItem(player, itemData, menuData);
            if (currentItemData == null)
                continue;

            ItemStack item = createMenuItem(player, currentItemData, null);
            inventory.setItem(slot, item);
        }
    }

    private void buildPlayerMenu(Player player, Inventory inventory, MenuData menuData, int page) {
        MenuItemData template = menuData.getItems().get("template");
        if (template == null)
            return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        buildPaginatedMenu(player, inventory, players, p -> p.getName(), template, page);
    }

    private void buildTabCompleteMenu(Player player, Inventory inventory, MenuData menuData, int page) {
        MenuItemData template = menuData.getItems().get("template");
        if (template == null)
            return;

        String tabTarget = menuData.getTabTarget();
        if (tabTarget == null || tabTarget.isEmpty())
            return;

        List<String> completions = new ArrayList<>();
        // 簡易的にBukkitのディスパッチのTabCompleteを利用（引数がある前提）
        org.bukkit.command.CommandMap commandMap = Bukkit.getCommandMap();
        org.bukkit.command.Command command = commandMap.getCommand(tabTarget.split(" ")[0]);
        if (command != null) {
            try {
                // ダミーで送信元はコンソールを使用 (より正確にはプレイヤから取得すべき)
                completions = command.tabComplete(Bukkit.getConsoleSender(), tabTarget, new String[] {});
            } catch (Exception ignored) {
            }
        }

        // 補完結果がない場合はプレースホルダー表示用によく使うワールドやプレイヤーなどを入れる実装などが考えられるが
        // 今回はシンプルに取得できたリストを表示
        buildPaginatedMenu(player, inventory, completions, s -> s, template, page);
    }

    private <T> void buildPaginatedMenu(Player player, Inventory inventory, List<T> items,
            java.util.function.Function<T, String> nameExtractor, MenuItemData template, int page) {
        int maxItems = Math.min(items.size() - page * ITEMS_PER_PAGE, ITEMS_PER_PAGE);

        // ※ テンプレートの条件を評価
        // (複数種類への切り替えはここでは対応せず、単純に表示/非表示とします)
        MenuItemData evaluatedTemplate = evaluateItem(player, template, plugin.getMenuManager().getMenu(currentMenuMap.get(player.getUniqueId())));
        if (evaluatedTemplate == null) return;

        for (int i = 0; i < maxItems; i++) {
            String targetName = nameExtractor.apply(items.get(page * ITEMS_PER_PAGE + i));
            ItemStack item = createMenuItem(player, evaluatedTemplate, targetName);
            inventory.setItem(i, item);
        }

        // ナビゲーションボタン追加
        int size = inventory.getSize();
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + "前のページ");
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, "prev_page");
            prev.setItemMeta(meta);
            inventory.setItem(size - 9, prev);
        }
        if ((page + 1) * ITEMS_PER_PAGE < items.size()) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + "次のページ");
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, "next_page");
            next.setItemMeta(meta);
            inventory.setItem(size - 1, next);
        }
    }

    private MenuItemData evaluateItem(Player player, MenuItemData initialData, MenuData menuData) {
        MenuItemData currentData = initialData;
        int passLimit = 5; // 無限ループ回避
        int passes = 0;

        while (currentData != null && currentData.getViewRequirement() != null && passes < passLimit) {
            MenuItemData.ViewRequirement req = currentData.getViewRequirement();
            boolean meetsRequirement = checkRequirement(player, req);

            if (meetsRequirement) {
                break; // 条件を満たした場合、このアイテムで確定
            } else {
                // 条件を満たさない場合、fallback を探す
                String fallbackKey = currentData.getFallbackItemKey();
                if (fallbackKey != null && menuData != null) {
                    currentData = menuData.getItems().get(fallbackKey);
                } else {
                    currentData = null; // 代替アイテムがなければ非表示
                }
            }
            passes++;
        }
        return currentData;
    }

    private boolean checkRequirement(Player player, MenuItemData.ViewRequirement req) {
        if (req == null) return true;

        String type = req.type();
        String placeholder = req.placeholder();
        String expectedValue = req.value();

        if (type == null || placeholder == null || expectedValue == null) return true;

        String parsedValue = placeholder;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            parsedValue = PlaceholderAPI.setPlaceholders(player, placeholder);
        }

        return switch (type.toLowerCase()) {
            case "string equals", "equals" -> parsedValue.equalsIgnoreCase(expectedValue);
            case "string contains", "contains" -> parsedValue.toLowerCase().contains(expectedValue.toLowerCase());
            case ">", "greater than" -> {
                try {
                    yield Double.parseDouble(parsedValue) > Double.parseDouble(expectedValue);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case "<", "less than" -> {
                try {
                    yield Double.parseDouble(parsedValue) < Double.parseDouble(expectedValue);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case ">=", "greater than or equal to" -> {
                try {
                    yield Double.parseDouble(parsedValue) >= Double.parseDouble(expectedValue);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case "<=", "less than or equal to" -> {
                try {
                    yield Double.parseDouble(parsedValue) <= Double.parseDouble(expectedValue);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            default -> true; // 未知のタイプはtrueとして扱う
        };
    }

    private ItemStack createMenuItem(Player player, MenuItemData itemData, String targetName) {
        Material material = Material.matchMaterial(itemData.getMaterial());
        if (material == null)
            material = Material.STONE;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String displayName = itemData.getDisplayName();
            if (targetName != null) {
                displayName = displayName.replace("%player%", targetName).replace("%tab%", targetName);
            }
            if (displayName != null) {
                // デフォルトの斜体（Italic）を解除するために &r (RESET) を付与
                meta.setDisplayName(
                        ChatColor.translateAlternateColorCodes('&', "&r" + itemData.getColorPrefix() + displayName));
            }

            if (itemData.getLore() != null && !itemData.getLore().isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String line : itemData.getLore()) {
                    if (targetName != null) {
                        line = line.replace("%player%", targetName).replace("%tab%", targetName);
                    }
                    // Loreのデフォルト斜体も解除
                    lore.add(ChatColor.translateAlternateColorCodes('&', "&r&7" + line));
                }
                meta.setLore(lore);
            }

            // PLAYER_HEAD の場合はスキンを適用
            if (material == Material.PLAYER_HEAD) {
                org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) meta;
                if (targetName != null) {
                    skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetName));
                } else if (itemData.getTexture() != null && !itemData.getTexture().isEmpty()) {
                    try {
                        com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.getServer().createProfile(java.util.UUID.randomUUID());
                        profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", itemData.getTexture()));
                        skullMeta.setPlayerProfile(profile);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to apply custom texture to menu item: " + itemData.getKey());
                    }
                }
            }

            meta.getPersistentDataContainer().set(MENU_ID_KEY, PersistentDataType.STRING, itemData.getKey());
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, itemData.getType());
            if (targetName != null) {
                meta.getPersistentDataContainer().set(TARGET_KEY, PersistentDataType.STRING, targetName);
            }

            item.setItemMeta(meta);
        }
        return item;
    }

    public void openConfirmMenu(Player player, MenuItemData itemData, String targetName) {
        Inventory confirmInv = Bukkit.createInventory(null, 27, ChatColor.RED + "確認");

        ItemStack confirmItem = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta cMeta = confirmItem.getItemMeta();
        cMeta.setDisplayName(ChatColor.GREEN + "【 実行する 】");
        cMeta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, "confirm_yes");
        confirmItem.setItemMeta(cMeta);

        ItemStack cancelItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta xMeta = cancelItem.getItemMeta();
        xMeta.setDisplayName(ChatColor.RED + "【 キャンセル 】");
        xMeta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, "confirm_no");
        cancelItem.setItemMeta(xMeta);

        confirmInv.setItem(11, confirmItem);
        confirmInv.setItem(13, createMenuItem(player, itemData, targetName)); // 情報表示用として中央に置く
        confirmInv.setItem(15, cancelItem);

        player.openInventory(confirmInv);
        // CloseEventによるクリア後にputする
        pendingConfirmMap.put(player.getUniqueId(), itemData);
        if (targetName != null) {
            pendingTargetMap.put(player.getUniqueId(), targetName);
        } else {
            pendingTargetMap.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;

        Inventory topInv = event.getView().getTopInventory();
        if (event.getClickedInventory() != topInv) {
            // 下部インベントリでの操作などで、現在開いているのがMenuかどうか簡易判定
            if (currentMenuMap.containsKey(player.getUniqueId())
                    || pendingConfirmMap.containsKey(player.getUniqueId())) {
                if (event.isShiftClick())
                    event.setCancelled(true);
            }
            return;
        }

        if (!currentMenuMap.containsKey(player.getUniqueId()) && !pendingConfirmMap.containsKey(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta())
            return;

        ItemMeta meta = clicked.getItemMeta();
        org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (!pdc.has(ACTION_KEY, PersistentDataType.STRING))
            return;
        String actionType = pdc.get(ACTION_KEY, PersistentDataType.STRING);

        // ページネーション処理
        if ("next_page".equals(actionType)) {
            String menuId = currentMenuMap.get(player.getUniqueId());
            int page = currentPageMap.getOrDefault(player.getUniqueId(), 0);
            plugin.playConfigSound(player, "page-change");
            openMenu(player, menuId, page + 1);
            return;
        } else if ("prev_page".equals(actionType)) {
            String menuId = currentMenuMap.get(player.getUniqueId());
            int page = currentPageMap.getOrDefault(player.getUniqueId(), 0);
            plugin.playConfigSound(player, "page-change");
            openMenu(player, menuId, Math.max(0, page - 1));
            return;
        }

        // 確認画面処理
        if ("confirm_yes".equals(actionType)) {
            MenuItemData itemData = pendingConfirmMap.remove(player.getUniqueId());
            String target = pendingTargetMap.remove(player.getUniqueId());
            if (itemData != null) {
                executeItemAction(player, itemData, target, true);
            }
            return;
        } else if ("confirm_no".equals(actionType)) {
            pendingConfirmMap.remove(player.getUniqueId());
            pendingTargetMap.remove(player.getUniqueId());
            plugin.playConfigSound(player, "page-change");

            String menuId = currentMenuMap.get(player.getUniqueId());
            int page = currentPageMap.getOrDefault(player.getUniqueId(), 0);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (menuId != null) {
                    openMenu(player, menuId, page);
                } else {
                    player.closeInventory();
                }
            });
            return;
        }

        // 通常のアイテムクリック処理
        String itemKey = pdc.get(MENU_ID_KEY, PersistentDataType.STRING);
        String target = pdc.get(TARGET_KEY, PersistentDataType.STRING);
        String menuId = currentMenuMap.get(player.getUniqueId());

        if (menuId != null && itemKey != null) {
            MenuData menuData = plugin.getMenuManager().getMenu(menuId);
            if (menuData != null) {
                MenuItemData itemData = menuData.getItems().get(itemKey);
                if (itemData != null) {
                    executeItemAction(player, itemData, target, false);
                }
            }
        }
    }

    private void executeItemAction(Player player, MenuItemData itemData, String target, boolean confirmed) {
        String type = itemData.getType();

        // playSound の実行
        if (itemData.getPlaySound() != null && !itemData.getPlaySound().isEmpty()) {
            // direct bukkit sound playing could be supported, or delegate to ConfigSound
            // wrapper.
            // 本来はconfigからですが、直書きの対応もするためBukkitのサウンド変換を試みる
            try {
                org.bukkit.Sound sound = org.bukkit.Registry.SOUND_EVENT
                        .get(NamespacedKey.minecraft(itemData.getPlaySound().toLowerCase().replace(".", "_")));
                if (sound == null)
                    sound = org.bukkit.Sound.valueOf(itemData.getPlaySound().toUpperCase().replace(".", "_"));
                if (sound != null) {
                    player.playSound(player.getLocation(), sound, 1f, 1f);
                }
            } catch (Exception ignored) {
            }
        }

        if ("confirm".equalsIgnoreCase(type) && !confirmed) {
            openConfirmMenu(player, itemData, target);
            return;
        }

        if ("trigger".equalsIgnoreCase(type) || "open_menu".equalsIgnoreCase(type)) {
            String nextMenu = itemData.getOpenMenu();
            if (nextMenu != null) {
                Bukkit.getScheduler().runTask(plugin, () -> openMenu(player, nextMenu, 0));
            }
        } else if ("command".equalsIgnoreCase(type)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                if (itemData.getCommands() != null) {
                    for (String cmd : itemData.getCommands()) {
                        if (target != null) {
                            cmd = cmd.replace("%player%", target).replace("%tab%", target);
                        }
                        if (cmd.startsWith("/")) {
                            cmd = cmd.substring(1);
                        }
                        player.performCommand(cmd);
                    }
                }
            });
        } else if ("message".equalsIgnoreCase(type)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                if (itemData.getMessages() != null) {
                    for (String msg : itemData.getMessages()) {
                        if (target != null) {
                            msg = msg.replace("%player%", target).replace("%tab%", target);
                        }
                        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                    }
                }
            });
        }
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            currentMenuMap.remove(player.getUniqueId());
            currentPageMap.remove(player.getUniqueId());
            pendingConfirmMap.remove(player.getUniqueId());
            pendingTargetMap.remove(player.getUniqueId());
        }
    }
}
