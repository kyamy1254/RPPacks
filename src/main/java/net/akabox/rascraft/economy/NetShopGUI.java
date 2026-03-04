package net.akabox.rascraft.economy;

import net.akabox.rascraft.RascraftPluginPacks;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.DyeColor;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.*;

public class NetShopGUI implements Listener {

    private final RascraftPluginPacks plugin;
    private final NamespacedKey marketItemIdKey;

    private static final String MAIN_TITLE = ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "ネットショップ: メインメニュー";
    private static final String BUY_TITLE = ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "ネットショップ: 購入";
    private static final String SELL_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "ネットショップ: 出品設定";
    private static final String CONFIRM_TITLE = ChatColor.DARK_RED + "" + ChatColor.BOLD + "ネットショップ: 購入確認";

    private final Map<UUID, Double> sellPrices = new HashMap<>(); // プレイヤーの出品設定価格
    private final Map<UUID, Integer> buyPages = new HashMap<>(); // プレイヤーの購入メニューの現在のページ
    private final Map<UUID, NetShopManager.SortType> buySorts = new HashMap<>(); // プレイヤーの購入メニューのソート方法
    private final Map<UUID, UUID> pendingBuys = new HashMap<>(); // 確認画面で選択中のアイテムID

    private final Map<String, ItemStack> headCache = new HashMap<>(); // Base64テクスチャ文字列をキーとしたHeadキャッシュ

    public NetShopGUI(RascraftPluginPacks plugin) {
        this.plugin = plugin;
        this.marketItemIdKey = new NamespacedKey(plugin, "market_item_id");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // アイテム作成ヘルパー
    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // カスタムヘッド生成ヘルパー
    private ItemStack createCustomHead(String base64, String name, String... lore) {
        ItemStack item;
        if (headCache.containsKey(base64)) {
            item = headCache.get(base64).clone();
        } else {
            item = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            if (meta != null) {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", base64));
                meta.setPlayerProfile(profile);
                item.setItemMeta(meta);
            }
            headCache.put(base64, item.clone());
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            } else {
                meta.setLore(null);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // Pdc書き込みヘルパー
    private ItemStack setItemId(ItemStack item, UUID itemId) {
        ItemStack clone = item.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(marketItemIdKey, PersistentDataType.STRING, itemId.toString());
            clone.setItemMeta(meta);
        }
        return clone;
    }

    private UUID getItemIdFromPdc(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;
        String idStr = item.getItemMeta().getPersistentDataContainer().get(marketItemIdKey, PersistentDataType.STRING);
        if (idStr == null)
            return null;
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // 数字バナー生成ヘルパー
    private ItemStack getNumberBanner(char digit, int currentPrice) {
        ItemStack banner = new ItemStack(Material.WHITE_BANNER);
        ItemMeta rawMeta = banner.getItemMeta();
        if (!(rawMeta instanceof BannerMeta meta))
            return banner;

        meta.setDisplayName(ChatColor.WHITE + "現在の桁: " + ChatColor.YELLOW + digit);
        meta.setLore(Arrays.asList(ChatColor.GRAY + "現在価格: " + currentPrice + "円"));

        List<Pattern> patterns = new ArrayList<>();
        switch (digit) {
            case '0' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_RIGHT));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_LEFT));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_TOP));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_BOTTOM));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_DOWNLEFT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '1' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_CENTER));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.SQUARE_TOP_LEFT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.CURLY_BORDER));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_BOTTOM));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '2' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_TOP));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.RHOMBUS));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_BOTTOM));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_DOWNLEFT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '3' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_TOP));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_MIDDLE));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_BOTTOM));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.CURLY_BORDER));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_RIGHT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '4' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_LEFT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.HALF_HORIZONTAL_BOTTOM));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_RIGHT));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_MIDDLE));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '5' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_BOTTOM));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.RHOMBUS));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_DOWNRIGHT));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_TOP));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '6' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_RIGHT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.HALF_HORIZONTAL));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_BOTTOM));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_LEFT));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_MIDDLE));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '7' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_TOP));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_DOWNLEFT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '8' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_LEFT));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_TOP));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_BOTTOM));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_MIDDLE));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_RIGHT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
            case '9' -> {
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_TOP));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_LEFT));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.HALF_HORIZONTAL_BOTTOM));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_RIGHT));
                patterns.add(new Pattern(DyeColor.BLACK, PatternType.STRIPE_MIDDLE));
                patterns.add(new Pattern(DyeColor.WHITE, PatternType.BORDER));
            }
        }

        meta.setPatterns(patterns);
        banner.setItemMeta(meta);
        return banner;
    }

    // --- メインメニュー ---
    public void openMainMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, MAIN_TITLE);

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++)
            gui.setItem(i, filler);

        ItemStack buyBtn = createItem(Material.EMERALD, ChatColor.AQUA + "プレイヤーから買う", ChatColor.GRAY + "他プレイヤーが出品した",
                ChatColor.GRAY + "アイテムを購入します");
        ItemStack sellBtn = createItem(Material.GOLD_INGOT, ChatColor.GOLD + "アイテムを出品する", ChatColor.GRAY + "手持ちのアイテムを",
                ChatColor.GRAY + "ネットショップに出品します");

        gui.setItem(11, buyBtn);
        gui.setItem(15, sellBtn);

        player.openInventory(gui);
    }

    // --- 購入メニュー ---
    public void openBuyMenu(Player player) {
        int page = buyPages.getOrDefault(player.getUniqueId(), 0);
        NetShopManager.SortType sortType = buySorts.getOrDefault(player.getUniqueId(), NetShopManager.SortType.NEWEST);

        Inventory gui = Bukkit.createInventory(null, 54, BUY_TITLE);

        List<MarketItem> items = plugin.getNetShopManager().getItems(sortType);

        int start = page * 45;
        for (int i = 0; i < 45 && (start + i) < items.size(); i++) {
            MarketItem mItem = items.get(start + i);
            ItemStack displayItem = mItem.getItemStack().clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.YELLOW + "=== 販売情報 ===");
                lore.add(ChatColor.GRAY + "出品者: " + ChatColor.WHITE + mItem.getSellerName());
                lore.add(ChatColor.GRAY + "価格: " + ChatColor.GOLD + mItem.getPrice() + " 円");
                lore.add(ChatColor.GREEN + "► クリックして購入確認画面へ");
                meta.setLore(lore);
                displayItem.setItemMeta(meta);
            }
            displayItem = setItemId(displayItem, mItem.getId());
            gui.setItem(i, displayItem);
        }

        // 下部コントロールバー
        ItemStack filler = createItem(Material.CYAN_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++)
            gui.setItem(i, filler);

        if (page > 0) {
            gui.setItem(45, createItem(Material.ARROW, ChatColor.YELLOW + "← 前のページ"));
        }
        if (start + 45 < items.size()) {
            gui.setItem(53, createItem(Material.ARROW, ChatColor.YELLOW + "次のページ →"));
        }

        gui.setItem(48, createItem(Material.BARRIER, ChatColor.RED + "メインメニューに戻る"));

        String sortName = switch (sortType) {
            case NEWEST -> "新着順";
            case PRICE_HIGH_TO_LOW -> "価格が高い順";
            case PRICE_LOW_TO_HIGH -> "価格が安い順";
            case NAME -> "名前順";
        };
        gui.setItem(49, createItem(Material.HOPPER, ChatColor.GOLD + "ソート順変更",
                ChatColor.GRAY + "現在の設定: " + ChatColor.WHITE + sortName));

        player.openInventory(gui);
    }

    // --- 出品（ダイヤル式値段設定）メニュー ---
    public void openSellMenu(Player player) {
        double currentPrice = sellPrices.getOrDefault(player.getUniqueId(), 1000.0);
        Inventory gui = Bukkit.createInventory(null, 54, SELL_TITLE);

        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++)
            gui.setItem(i, border);

        // 枠 13 を開けておく (出品アイテムを置く場所)
        gui.setItem(13, null);

        // 7桁ダイヤルUI (スロット 19~25 が上の+, 28~34が数字, 37~43が下の-)
        String priceStr = String.format("%07d", (int) currentPrice);
        if (priceStr.length() > 7)
            priceStr = "9999999";

        int[] increments = { 1000000, 100000, 10000, 1000, 100, 10, 1 };
        for (int i = 0; i < 7; i++) {
            int inc = increments[i];
            char digitStr = priceStr.charAt(i);

            // 上ボタン
            gui.setItem(19 + i, createCustomHead(
                    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzA0MGZlODM2YTZjMmZiZDJjN2E5YzhlYzZiZTUxNzRmZGRmMWFjMjBmNTVlMzY2MTU2ZmE1ZjcxMmUxMCJ9fX0=",
                    ChatColor.GREEN + "▲ +" + inc));
            // 数字表示 (バナー)
            gui.setItem(28 + i, getNumberBanner(digitStr, (int) currentPrice));
            // 下ボタン
            gui.setItem(37 + i, createCustomHead(
                    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzQzNzM0NmQ4YmRhNzhkNTI1ZDE5ZjU0MGE5NWU0ZTc5ZGFlZGE3OTVjYmM1YTEzMjU2MjM2MzEyY2YifX19",
                    ChatColor.RED + "▼ -" + inc));
        }

        gui.setItem(48, createCustomHead(
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTc5YTVjOTVlZTE3YWJmZWY0NWM4ZGMyMjQxODk5NjQ5NDRkNTYwZjE5YTQ0ZjE5ZjhhNDZhZWYzZmVlNDc1NiJ9fX0=",
                ChatColor.GREEN + "" + ChatColor.BOLD + "【出品する】",
                ChatColor.GRAY + "左のスロットのアイテムを",
                ChatColor.GOLD + String.valueOf((int) currentPrice) + "円" + ChatColor.GRAY + " で出品します"));

        gui.setItem(50, createCustomHead(
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjc1NDgzNjJhMjRjMGZhODQ1M2U0ZDkzZTY4YzU5NjlkZGJkZTU3YmY2NjY2YzAzMTljMWVkMWU4NGQ4OTA2NSJ9fX0=",
                ChatColor.RED + "" + ChatColor.BOLD + "【キャンセルして戻る】",
                ChatColor.GRAY + "アイテムを返却して", ChatColor.GRAY + "メインメニューに戻ります"));

        player.openInventory(gui);
    }

    // 出品メニューの再描画 (値段変更時用, 置いてあるアイテムは維持)
    private void updateSellMenuDials(Inventory gui, Player player) {
        double currentPrice = sellPrices.getOrDefault(player.getUniqueId(), 1000.0);
        String priceStr = String.format("%07d", (int) currentPrice);
        if (priceStr.length() > 7)
            priceStr = "9999999";
        int[] increments = { 1000000, 100000, 10000, 1000, 100, 10, 1 };

        for (int i = 0; i < 7; i++) {
            int inc = increments[i];
            char digitStr = priceStr.charAt(i);
            gui.setItem(19 + i, createCustomHead(
                    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzA0MGZlODM2YTZjMmZiZDJjN2E5YzhlYzZiZTUxNzRmZGRmMWFjMjBmNTVlMzY2MTU2ZmE1ZjcxMmUxMCJ9fX0=",
                    ChatColor.GREEN + "▲ +" + inc));
            gui.setItem(28 + i, getNumberBanner(digitStr, (int) currentPrice));
            gui.setItem(37 + i, createCustomHead(
                    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzQzNzM0NmQ4YmRhNzhkNTI1ZDE5ZjU0MGE5NWU0ZTc5ZGFlZGE3OTVjYmM1YTEzMjU2MjM2MzEyY2YifX19",
                    ChatColor.RED + "▼ -" + inc));
        }

        gui.setItem(48, createCustomHead(
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTc5YTVjOTVlZTE3YWJmZWY0NWM4ZGMyMjQxODk5NjQ5NDRkNTYwZjE5YTQ0ZjE5ZjhhNDZhZWYzZmVlNDc1NiJ9fX0=",
                ChatColor.GREEN + "" + ChatColor.BOLD + "【出品する】",
                ChatColor.GRAY + "左のスロットのアイテムを",
                ChatColor.GOLD + String.valueOf((int) currentPrice) + "円" + ChatColor.GRAY + " で出品します"));
    }

    // --- 購入確認メニュー ---
    public void openConfirmMenu(Player player, UUID itemId) {
        MarketItem mItem = plugin.getNetShopManager().getItem(itemId);
        if (mItem == null) {
            player.sendMessage(ChatColor.RED + "このアイテムは既に売れてしまったか、取り下げられました。");
            openBuyMenu(player);
            return;
        }

        pendingBuys.put(player.getUniqueId(), itemId);
        Inventory gui = Bukkit.createInventory(null, 27, CONFIRM_TITLE);

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++)
            gui.setItem(i, filler);

        ItemStack displayItem = mItem.getItemStack().clone();
        ItemMeta meta = displayItem.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + "販売価格: " + ChatColor.GOLD + mItem.getPrice() + " 円");
            meta.setLore(lore);
            displayItem.setItemMeta(meta);
        }

        gui.setItem(13, displayItem); // 中央に商品

        if (mItem.getSellerUuid().equals(player.getUniqueId())) {
            // 自分の出品アイテムの場合：取り下げボタンを表示
            gui.setItem(11, createCustomHead(
                    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTM4NmQwZjE0MDI0ZTRkZWNiNjc2ZjA2ZTM2OTA0MzIwOWU3NGE0ZDVmNWFjN2UyM2I4YWE4ZjNiN2UxZWU3NiJ9fX0=",
                    ChatColor.YELLOW + "" + ChatColor.BOLD + "【出品を取り下げる】",
                    ChatColor.GRAY + "このアイテムの出品をキャンセルし、",
                    ChatColor.GRAY + "手元に返却します。"));
        } else {
            gui.setItem(11, createCustomHead(
                    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTc5YTVjOTVlZTE3YWJmZWY0NWM4ZGMyMjQxODk5NjQ5NDRkNTYwZjE5YTQ0ZjE5ZjhhNDZhZWYzZmVlNDc1NiJ9fX0=",
                    ChatColor.GREEN + "" + ChatColor.BOLD + "【購入する】",
                    ChatColor.GRAY + "所持金から " + ChatColor.GOLD + mItem.getPrice() + " 円 " + ChatColor.GRAY
                            + "引き落とされます"));
        }

        gui.setItem(15, createCustomHead(
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjc1NDgzNjJhMjRjMGZhODQ1M2U0ZDkzZTY4YzU5NjlkZGJkZTU3YmY2NjY2YzAzMTljMWVkMWU4NGQ4OTA2NSJ9fX0=",
                ChatColor.RED + "" + ChatColor.BOLD + "【戻る】",
                ChatColor.GRAY + "一覧に戻ります"));

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        String title = event.getView().getTitle();

        // メインメニュー
        if (title.equals(MAIN_TITLE)) {
            event.setCancelled(true);
            if (event.getRawSlot() == 11) {
                plugin.playConfigSound(player, "page-change");
                openBuyMenu(player);
            } else if (event.getRawSlot() == 15) {
                plugin.playConfigSound(player, "page-change");
                sellPrices.put(player.getUniqueId(), 1000.0); // 初期価格
                openSellMenu(player);
            }
        }
        // 購入（一覧）メニュー
        else if (title.equals(BUY_TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < 45) {
                ItemStack clicked = event.getCurrentItem();
                UUID itemId = getItemIdFromPdc(clicked);
                if (itemId != null) {
                    plugin.playConfigSound(player, "page-change");
                    openConfirmMenu(player, itemId);
                }
            } else if (slot == 45) {
                int page = buyPages.getOrDefault(player.getUniqueId(), 0);
                if (page > 0) {
                    buyPages.put(player.getUniqueId(), page - 1);
                    plugin.playConfigSound(player, "page-change");
                    openBuyMenu(player);
                }
            } else if (slot == 53) {
                int page = buyPages.getOrDefault(player.getUniqueId(), 0);
                List<MarketItem> items = plugin.getNetShopManager()
                        .getItems(buySorts.getOrDefault(player.getUniqueId(), NetShopManager.SortType.NEWEST));
                if ((page + 1) * 45 < items.size()) {
                    buyPages.put(player.getUniqueId(), page + 1);
                    plugin.playConfigSound(player, "page-change");
                    openBuyMenu(player);
                }
            } else if (slot == 48) {
                plugin.playConfigSound(player, "page-change");
                openMainMenu(player);
            } else if (slot == 49) {
                NetShopManager.SortType current = buySorts.getOrDefault(player.getUniqueId(),
                        NetShopManager.SortType.NEWEST);
                NetShopManager.SortType[] values = NetShopManager.SortType.values();
                int nextIdx = (current.ordinal() + 1) % values.length;
                buySorts.put(player.getUniqueId(), values[nextIdx]);
                buyPages.put(player.getUniqueId(), 0); // ページをリセット
                plugin.playConfigSound(player, "page-change");
                openBuyMenu(player);
            }
        }
        // 出品メニュー
        else if (title.equals(SELL_TITLE)) {
            // プレイヤーのインベントリ(下部)のクリックは許可
            if (event.getRawSlot() >= 54) {
                // Shiftクリックなどでの上部への移動は、特定スロット(13)以外はキャンセルしたいが
                // 実装を簡単にするために上部へのShiftクリックは全面禁止にして手置きのみ許可する
                if (event.isShiftClick()) {
                    event.setCancelled(true);
                }
                return;
            }

            // 出品アイテムスロット (13) の操作は許可する
            if (event.getRawSlot() == 13) {
                return;
            }

            // それ以外のGUIクリックはすべてキャンセル
            event.setCancelled(true);

            if (event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
                return; // 連打時の重複発火(DOUBLE_CLICK)を防止
            }

            int slot = event.getRawSlot();
            double price = sellPrices.getOrDefault(player.getUniqueId(), 1000.0);

            int[] increments = { 1000000, 100000, 10000, 1000, 100, 10, 1 };
            boolean valueChanged = false;

            // プラスボタン
            if (slot >= 19 && slot <= 25) {
                int i = slot - 19;
                price += increments[i];
                valueChanged = true;
            }
            // マイナスボタン
            else if (slot >= 37 && slot <= 43) {
                int i = slot - 37;
                price -= increments[i];
                valueChanged = true;
            }

            if (valueChanged) {
                if (price < 0)
                    price = 0;
                if (price > 9999999)
                    price = 9999999;
                sellPrices.put(player.getUniqueId(), price);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                updateSellMenuDials(event.getInventory(), player);
            }
            // 決定ボタン
            else if (slot == 48) {
                ItemStack sellItem = event.getInventory().getItem(13);
                if (sellItem == null || sellItem.getType() == Material.AIR) {
                    player.sendMessage(ChatColor.RED + "出品するアイテムをスロットに置いてください。");
                    return;
                }
                if (price <= 0) {
                    player.sendMessage(ChatColor.RED + "価格は1円以上で設定してください。");
                    return;
                }
                if (sellItem.getAmount() > sellItem.getMaxStackSize()) {
                    player.sendMessage(ChatColor.RED + "出品できるのは1スタック分までです。");
                    return;
                }

                // 出品処理
                plugin.getNetShopManager().addItem(sellItem, price, player.getUniqueId(), player.getName());
                event.getInventory().setItem(13, null); // 提出したのでスロットから消す

                plugin.playConfigSound(player, "netshop-sell");
                player.sendMessage(ChatColor.GREEN + "アイテムを " + price + " 円でネットショップに出品しました！");
                player.closeInventory();
            }
            // キャンセルボタン
            else if (slot == 50) {
                // Return item implicitly happens in InventoryCloseEvent
                plugin.playConfigSound(player, "page-change");
                openMainMenu(player);
            }
        }
        // 確認メニュー
        else if (title.equals(CONFIRM_TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            UUID pendingId = pendingBuys.get(player.getUniqueId());
            if (pendingId == null)
                return;

            if (slot == 11) { // 購入する または 取り下げる
                if (!plugin.getNetShopManager().lockItem(pendingId)) {
                    player.sendMessage(ChatColor.RED + "他のプレイヤーがこのアイテムの取引処理中です。");
                    return;
                }

                try {
                    MarketItem mItem = plugin.getNetShopManager().getItem(pendingId);
                    if (mItem == null) {
                        player.sendMessage(ChatColor.RED + "このアイテムは既に売れてしまったか、取り下げられました。");
                        player.closeInventory();
                        return;
                    }

                    // 自身のアイテム取り下げ処理
                    if (mItem.getSellerUuid().equals(player.getUniqueId())) {
                        ItemStack itemToReturn = mItem.getItemStack().clone();
                        HashMap<Integer, ItemStack> notFits = player.getInventory().addItem(itemToReturn);
                        if (!notFits.isEmpty()) {
                            for (ItemStack dropItem : notFits.values()) {
                                player.getWorld().dropItem(player.getLocation(), dropItem);
                            }
                        }
                        plugin.getNetShopManager().removeItem(pendingId);
                        plugin.playConfigSound(player, "page-change");
                        player.sendMessage(ChatColor.YELLOW + "[NetShop] 出品を取り下げ、アイテムを回収しました。");
                        player.closeInventory();
                        return;
                    }

                    VaultManager vault = plugin.getVaultManager();
                    if (vault == null || !vault.hasEnough(player, mItem.getPrice())) {
                        player.sendMessage(ChatColor.RED + "所持金が足りません。 (価格: " + mItem.getPrice() + " 円)");
                        return;
                    }

                    // 1. 支払い (先に引く)
                    vault.withdraw(player, mItem.getPrice());

                    // 2. 売主にお金を追加
                    org.bukkit.OfflinePlayer seller = Bukkit.getOfflinePlayer(mItem.getSellerUuid());
                    vault.deposit(seller, mItem.getPrice());

                    // 3. アイテムの受け渡し (複製を渡す + オーバーフロー対応)
                    ItemStack itemToGive = mItem.getItemStack().clone();
                    HashMap<Integer, ItemStack> notFits = player.getInventory().addItem(itemToGive);
                    if (!notFits.isEmpty()) {
                        for (ItemStack dropItem : notFits.values()) {
                            player.getWorld().dropItem(player.getLocation(), dropItem);
                        }
                        player.sendMessage(ChatColor.YELLOW + "インベントリが一杯だったため、一部のアイテムが足元にドロップされました。");
                    }

                    // 4. リストから削除
                    plugin.getNetShopManager().removeItem(pendingId);

                    plugin.playConfigSound(player, "netshop-buy");
                    player.sendMessage(ChatColor.AQUA + "ネットショップで "
                            + (itemToGive.hasItemMeta() && itemToGive.getItemMeta().hasDisplayName()
                                    ? itemToGive.getItemMeta().getDisplayName()
                                    : itemToGive.getType().name())
                            + ChatColor.AQUA + " を購入しました！");

                    // 5. 売主に通知(オンラインの場合)
                    if (seller.isOnline() && seller.getPlayer() != null) {
                        seller.getPlayer()
                                .sendMessage(ChatColor.GOLD + "[ネットショップ] あなたの出品したアイテムが売れました！ " + ChatColor.GREEN
                                        + "+" + mItem.getPrice() + " 円");
                        plugin.playConfigSound(seller.getPlayer(), "netshop-buy");
                    }

                    pendingBuys.remove(player.getUniqueId());
                    player.closeInventory();
                } finally {
                    plugin.getNetShopManager().unlockItem(pendingId);
                }
            } else if (slot == 15) { // キャンセル
                pendingBuys.remove(player.getUniqueId());
                plugin.playConfigSound(player, "page-change");
                openBuyMenu(player);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();
        if (title.equals(SELL_TITLE)) {
            // 出品画面を閉じたとき、スロット13にアイテムが残っていればプレイヤーに返す
            ItemStack returning = event.getInventory().getItem(13);
            if (returning != null && returning.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> drop = event.getPlayer().getInventory().addItem(returning);
                for (ItemStack d : drop.values()) {
                    event.getPlayer().getWorld().dropItem(event.getPlayer().getLocation(), d);
                }
                event.getInventory().setItem(13, null);
                event.getPlayer().sendMessage(ChatColor.YELLOW + "出品をキャンセルし、アイテムを返却しました。");
            }
        }

        if (title.equals(CONFIRM_TITLE)) {
            pendingBuys.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sellPrices.remove(uuid);
        buyPages.remove(uuid);
        buySorts.remove(uuid);
        pendingBuys.remove(uuid);
    }
}
