package net.akabox.rascraft.command;

import net.akabox.rascraft.RascraftPluginPacks;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import org.bukkit.command.TabCompleter;
import java.util.ArrayList;
import java.util.List;

public class NetShopCommand implements CommandExecutor, TabCompleter {
    private final RascraftPluginPacks plugin;

    public NetShopCommand(RascraftPluginPacks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみ実行可能です。");
            return true;
        }

        if (plugin.getVaultManager() == null || plugin.getVaultManager().getEconomy() == null) {
            player.sendMessage(ChatColor.RED + "Vault連携エラー: 経済プラグインが見つかりません。");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("sellhand")) {
            try {
                double price = Double.parseDouble(args[1]);
                if (price < 1 || price > 9999999) {
                    player.sendMessage(ChatColor.RED + "価格は1～9999999円の間で設定してください。");
                    return true;
                }

                org.bukkit.inventory.ItemStack handItem = player.getInventory().getItemInMainHand();
                if (handItem == null || handItem.getType() == org.bukkit.Material.AIR) {
                    player.sendMessage(ChatColor.RED + "出品するアイテムをメインハンドに持ってください。");
                    return true;
                }

                plugin.getNetShopManager().addItem(handItem, price, player.getUniqueId(), player.getName());
                player.getInventory().setItemInMainHand(null); // アイテムを消費

                player.sendMessage(ChatColor.GREEN + "手に持っていたアイテムを " + price + " 円でネットショップに出品しました！");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                return true;
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "価格には数値を指定してください。");
                return true;
            }
        }

        plugin.getNetShopGUI().openMainMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String arg = args[0].toLowerCase();
            if ("sellhand".startsWith(arg)) {
                completions.add("sellhand");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("sellhand")) {
            String arg = args[1];
            if (arg.isEmpty()) {
                completions.add("<price>");
                completions.add("1000");
                completions.add("5000");
                completions.add("10000");
            } else {
                // Remove <price> suggestion if user starts typing numbers
                if ("<price>".startsWith(arg) && !Character.isDigit(arg.charAt(0))) {
                    completions.add("<price>");
                }
            }
        }

        return completions;
    }
}
