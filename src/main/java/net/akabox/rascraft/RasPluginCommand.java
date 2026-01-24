package net.akabox.rascraft;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class RasPluginCommand implements CommandExecutor, TabCompleter {
    private final RascraftPluginPacks plugin;

    public RasPluginCommand(RascraftPluginPacks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("version")) {
            sendVersionInfo(sender);
            return true;
        }


        String sub = args[0].toLowerCase();

        switch (sub) {
            case "help" -> {
                sendHelp(sender, label);
                return true;
            }
            case "status" -> {
                if (checkAdmin(sender)) sendStatus(sender);
                return true;
            }
            case "reload" -> {
                if (checkAdmin(sender)) {
                    plugin.loadConfiguration();
                    sender.sendMessage(plugin.getMessage("reload-success"));
                }
                return true;
            }
            case "sneakgrow" -> {
                if (checkAdmin(sender)) handleSneakGrow(sender, label, args);
                return true;
            }
            case "trail" -> {
                handleTrailCommand(sender, label, args);
                return true;
            }
            case "tpeffect" -> {
                if (checkAdmin(sender)) handleTpEffect(sender, label, args);
                return true;
            }
            case "spawn" -> {
                if (checkAdmin(sender)) handleSpawnEnchanted(sender, args);
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "不明なコマンドです。 /" + label + " help を確認してください。");
                return true;
            }
        }
    }

    // --- SneakGrow コマンド処理 ---
    private void handleSneakGrow(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "使用法: /" + label + " sneakgrow <enable|disable|set>");
            return;
        }

        String action = args[1].toLowerCase();
        if (action.equals("enable") || action.equals("disable")) {
            boolean enable = action.equals("enable");
            plugin.setSneakGrowEnabled(enable);
            // lang.yml からメッセージ取得
            String msg = enable ? plugin.getMessage("sneakgrow-enabled") : plugin.getMessage("sneakgrow-disabled");
            sender.sendMessage(msg);
        } else if (action.equals("set")) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "使用法: /" + label + " sneakgrow set <chance|radius> <値>");
                return;
            }
            try {
                String type = args[2].toLowerCase();
                if (type.equals("chance")) {
                    double val = Double.parseDouble(args[3]);
                    plugin.setSuccessChance(val, true);
                    sender.sendMessage(plugin.getMessage("sneakgrow-set").replace("{type}", "成功確率").replace("{value}", (val * 100) + "%"));
                } else if (type.equals("radius")) {
                    int val = Integer.parseInt(args[3]);
                    plugin.setGrowRadius(val);
                    sender.sendMessage(plugin.getMessage("sneakgrow-set").replace("{type}", "成長半径").replace("{value}", String.valueOf(val)));
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getMessage("invalid-number"));
            }
        }
    }

    // --- Trail コマンド処理 ---
    private void handleTrailCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            if (sender instanceof Player p) openTrailMenu(p, 0);
            else sender.sendMessage(plugin.getMessage("player-only"));
            return;
        }

        if (!checkAdmin(sender)) return;

        String action = args[1].toLowerCase();
        if (action.equals("enable") || action.equals("disable")) {
            boolean enable = action.equals("enable");
            plugin.setTrailEnabled(enable);
            // lang.yml からメッセージ取得
            String msg = enable ? plugin.getMessage("trail-enabled") : plugin.getMessage("trail-disabled");
            sender.sendMessage(msg);
        } else if (action.equals("check")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "使用法: /" + label + " trail check <player>");
                return;
            }
            Player target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(plugin.getMessage("player-not-found"));
                return;
            }
            String currentEffect = plugin.getPlayerEffect(target.getUniqueId());

            List<String> info = new ArrayList<>();
            info.add(ChatColor.GRAY + "対象プレイヤー: " + ChatColor.WHITE + target.getName());
            info.add(ChatColor.GRAY + "装着中のエフェクト: " + (currentEffect == null ? ChatColor.RED + "なし" : ChatColor.GREEN + currentEffect));
            if (target.hasPermission("rascraft.trail.vip")) {
                info.add(ChatColor.GOLD + "★ VIP権限を保有しています");
            }
            sendStyledMsg(sender, "トレイル照会", info);
        } else {
            // /rpp trail <player> <effect|off>
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "使用法: /" + label + " trail <player> <trails|off>");
                return;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.getMessage("player-not-found"));
                return;
            }
            String effect = args[2].equalsIgnoreCase("off") ? null : args[2].toUpperCase();
            plugin.setPlayerEffect(target.getUniqueId(), effect);
            String msgKey = (effect == null) ? "trail-remove" : "trail-equip";
            sender.sendMessage(plugin.getMessage(msgKey).replace("{effect}", (effect == null ? "" : effect)));
        }
    }

    // --- TpEffect コマンド処理 ---
    private void handleTpEffect(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "使用法: /" + label + " tpeffect <enable|disable>");
            return;
        }
        boolean enable = args[1].equalsIgnoreCase("enable");
        plugin.setTpEffectEnabled(enable);
        // lang.yml からメッセージ取得
        String msg = enable ? plugin.getMessage("tpeffect-enabled") : plugin.getMessage("tpeffect-disabled");
        sender.sendMessage(msg);
    }

    private void handleSpawnEnchanted(CommandSender sender, String[] args) {
        // 1. 実行者がプレイヤーかチェックし、変数 'player' を確定させる
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみ実行可能です。");
            return;
        }

        // 2. 引数の数をチェック
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "使用法: /" + plugin.getName().toLowerCase() + " spawn <zombie|skeleton|creeper|spider>");
            return;
        }

        // 3. エンティティタイプをパース
        EntityType type;
        try {
            type = EntityType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(ChatColor.RED + "無効なエンティティタイプです。");
            return;
        }

        // 4. スポーンと強化の実行
        player.getWorld().spawn(player.getLocation(), type.getEntityClass(), entity -> {
            if (entity instanceof Monster monster) {
                // メインクラス(plugin)経由でMobSystemの強化メソッドを呼び出す
                // 注意: plugin.getMobSystem() などのGetterがメインクラスにある前提です
                // もしメソッド名が applyEnchanted ならそちらに書き換えてください
                plugin.getMobSystem().makeEnchanted(monster);

                player.sendMessage(ChatColor.LIGHT_PURPLE + "Enchanted " + type.name() + " を召喚しました！");
            } else {
                player.sendMessage(ChatColor.RED + "Monster（敵対生物）以外はEnchanted化できません。");
            }
        });
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("trail", "help", "version"));
            if (sender.hasPermission("rppacks.admin")) {
                subs.addAll(Arrays.asList("status", "reload", "sneakgrow", "tpeffect"));
            }
            return StringUtil.copyPartialMatches(args[0], subs, new ArrayList<>());
        }

        String sub = args[0].toLowerCase();
        if (sender.hasPermission("rppacks.admin")) {
            // SneakGrow 補完
            if (sub.equals("sneakgrow")) {
                if (args.length == 2)
                    return StringUtil.copyPartialMatches(args[1], Arrays.asList("enable", "disable", "set"), new ArrayList<>());
                if (args.length == 3 && args[1].equalsIgnoreCase("set"))
                    return StringUtil.copyPartialMatches(args[2], Arrays.asList("chance", "radius"), new ArrayList<>());
                if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
                    if (args[2].equalsIgnoreCase("chance"))
                        return StringUtil.copyPartialMatches(args[3], Arrays.asList("0.1", "0.5", "1.0"), new ArrayList<>());
                    if (args[2].equalsIgnoreCase("radius"))
                        return StringUtil.copyPartialMatches(args[3], Arrays.asList("1", "2", "3"), new ArrayList<>());
                }
            }
            // Trail 補完
            if (sub.equals("trail")) {
                if (args.length == 2) {
                    return StringUtil.copyPartialMatches(args[1], Arrays.asList("enable", "disable", "check"), new ArrayList<>());
                }
                if (args.length == 3) {
                    if (args[1].equalsIgnoreCase("check")) return null; // Player names
                    List<String> effects = new ArrayList<>(plugin.getAllowedTrails());
                    effects.addAll(plugin.getVipTrails());
                    effects.add("off");
                    return StringUtil.copyPartialMatches(args[2], effects, new ArrayList<>());
                }
            }
            // TpEffect 補完
            if (sub.equals("tpeffect")) {
                if (args.length == 2)
                    return StringUtil.copyPartialMatches(args[1], Arrays.asList("enable", "disable"), new ArrayList<>());
            }
            if (sub.equals("spawn")) {
                if (args.length == 2) {
                    // 召喚可能なMOBリストを提案
                    return StringUtil.copyPartialMatches(args[1], Arrays.asList("zombie", "skeleton", "creeper", "spider"), new ArrayList<>());
                }
            }
        }

        return Collections.emptyList();
    }

    // --- GUI 生成関連 ---

    public void openTrailMenu(Player player, int page) {
        String title = plugin.getRawMessage("trail-menu-title").replace("{page}", String.valueOf(page + 1));
        Inventory gui = Bukkit.createInventory(null, 54, title);
        ItemStack filler = createBtn(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack border = createBtn(Material.CYAN_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) gui.setItem(i, i >= 45 ? border : filler);

        String currentEffect = plugin.getPlayerEffect(player.getUniqueId());
        gui.setItem(4, createStatusIcon(player, currentEffect));
        List<String> list = new ArrayList<>(plugin.getAllowedTrails());
        list.addAll(plugin.getVipTrails());

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int start = page * slots.length;

        for (int i = 0; i < slots.length && (start + i) < list.size(); i++) {
            String name = list.get(start + i);
            gui.setItem(slots[i], createIcon(name, currentEffect, plugin.getVipTrails().contains(name)));
        }

        if (page > 0) gui.setItem(48, createBtn(Material.ARROW, ChatColor.YELLOW + "← 前のページ"));
        gui.setItem(49, createBtn(Material.BARRIER, plugin.getRawMessage("remove-effect-button")));
        if (list.size() > (page + 1) * slots.length)
            gui.setItem(50, createBtn(Material.ARROW, ChatColor.YELLOW + "次のページ →"));
        player.openInventory(gui);
    }

    private ItemStack createIcon(String name, String current, boolean isVip) {
        String matName = plugin.getConfig().getString("footprint-trail.icons." + name, "FIREWORK_STAR");
        Material mat = Material.getMaterial(matName.toUpperCase());
        ItemStack item = new ItemStack(mat == null ? Material.FIREWORK_STAR : mat);
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;

        boolean isEquipped = name.equalsIgnoreCase(current);
        String pre = isVip ? plugin.getRawMessage("trail-item-vip-prefix") : plugin.getRawMessage("trail-item-common-prefix");
        m.setDisplayName(pre + " " + ChatColor.AQUA + name);

        List<String> lore = new ArrayList<>();
        lore.add(isVip ? ChatColor.GOLD + " ≫ VIP限定エフェクト" : ChatColor.GREEN + " ≫ 一般公開エフェクト");
        lore.add(ChatColor.GRAY + " 密度: " + ChatColor.WHITE + plugin.getEffectDensity(name));
        lore.add("");
        if (isEquipped) {
            lore.add(plugin.getRawMessage("trail-status-equipped"));
            m.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        } else {
            lore.add(plugin.getRawMessage("trail-status-click"));
        }
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        m.setLore(lore);
        item.setItemMeta(m);
        return item;
    }

    private ItemStack createStatusIcon(Player p, String current) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.setDisplayName(ChatColor.YELLOW + "あなたのステータス");
            m.setLore(Arrays.asList(
                    ChatColor.GRAY + " プレイヤー: " + ChatColor.WHITE + p.getName(),
                    ChatColor.GRAY + " 現在のトレイル: " + (current == null ? ChatColor.RED + "なし" : ChatColor.GREEN + current)
            ));
            item.setItemMeta(m);
        }
        return item;
    }

    // --- メッセージ意匠を適用した出力 ---

    private void sendHelp(CommandSender s, String label) {
        List<String> help = new ArrayList<>();
        help.add(ChatColor.YELLOW + "/" + label + " trail " + ChatColor.GRAY + ": メニューを表示");
        if (s.hasPermission("rppacks.admin")) {
            help.add(ChatColor.GOLD + "/" + label + " sneakgrow <enable|disable>");
            help.add(ChatColor.GOLD + "/" + label + " sneakgrow set <chance|radius> <値>");
            help.add(ChatColor.GOLD + "/" + label + " trail <enable|disable>");
            help.add(ChatColor.GOLD + "/" + label + " trail check <player>");
            help.add(ChatColor.GOLD + "/" + label + " trail <player> <trail|off>");
            help.add(ChatColor.GOLD + "/" + label + " tpeffect <enable|disable>");
            help.add(ChatColor.GOLD + "/" + label + " reload " + ChatColor.GRAY + ": 設定再読み込み");
        }
        sendStyledMsg(s, "コマンドヘルプ", help);
    }

    private void sendVersionInfo(CommandSender s) {
        sendStyledMsg(s, "システム情報", Arrays.asList(
                ChatColor.YELLOW + "▶ " + ChatColor.GRAY + "バージョン : " + ChatColor.AQUA + plugin.getDescription().getVersion(),
                ChatColor.YELLOW + "▶ " + ChatColor.GRAY + "作成者     : " + ChatColor.AQUA + String.join(", ", plugin.getDescription().getAuthors()),
                ChatColor.YELLOW + "▶ " + ChatColor.GRAY + "状態       : " + ChatColor.GREEN + "● 稼働中"
        ));
    }

    private void sendStatus(CommandSender s) {
        // トレイルの全種類を合算
        int totalTrails = plugin.getAllowedTrails().size() + plugin.getVipTrails().size();
        // エフェクトを装備中の人数
        long activeUsers = plugin.getEquippedPlayerCount();

        sendStyledMsg(s, "現在の詳細設定状況", Arrays.asList(
                ChatColor.AQUA + "[ Sneak Grow ]",
                ChatColor.GRAY + " » 状態: " + (plugin.isSneakGrowEnabled() ? "§a有効" : "§c無効"),
                ChatColor.GRAY + " » 成功率: " + (plugin.getSuccessChance() * 100) + "%",
                ChatColor.GRAY + " » 有効範囲: " + ChatColor.WHITE + "半径 " + plugin.getGrowRadius() + " ブロック",
                "",
                ChatColor.LIGHT_PURPLE + "[ Footprint Trail ]",
                ChatColor.GRAY + " » 状態: " + (plugin.isTrailEnabled() ? "§a有効" : "§c無効"),
                ChatColor.GRAY + " » トレイル種類: " + ChatColor.WHITE + totalTrails + " 種",
                ChatColor.GRAY + " » 現在の利用者: " + ChatColor.YELLOW + activeUsers + " 名",
                "",
                ChatColor.YELLOW + "[ TP Effect ]",
                ChatColor.GRAY + " » 状態: " + (plugin.isTpEffectEnabled() ? "§a有効" : "§c無効")
        ));
    }

    private void sendStyledMsg(CommandSender s, String title, List<String> lines) {
        s.sendMessage(ChatColor.DARK_GRAY + "§m----------------------------------------");
        s.sendMessage(ChatColor.GOLD + "  §lRPPacks §7- §f" + title);
        s.sendMessage("");
        for (String line : lines) s.sendMessage("  " + line);
        s.sendMessage(ChatColor.DARK_GRAY + "§m----------------------------------------");
    }

    private boolean checkAdmin(CommandSender s) {
        if (s.hasPermission("rppacks.admin")) return true;
        s.sendMessage(plugin.getMessage("no-permission"));
        return false;
    }

    private ItemStack createBtn(Material m, String n) {
        ItemStack i = new ItemStack(m);
        ItemMeta mt = i.getItemMeta();
        if (mt != null) {
            mt.setDisplayName(n);
            i.setItemMeta(mt);
        }
        return i;
    }
}