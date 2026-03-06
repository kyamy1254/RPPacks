package net.akabox.rascraft.command;

import net.akabox.rascraft.RascraftPluginPacks;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MenuCommand implements CommandExecutor {
    private final RascraftPluginPacks plugin;

    public MenuCommand(RascraftPluginPacks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーのみ実行可能です。");
            return true;
        }

        if (args.length == 0) {
            plugin.getMenuGUI().openMenu(player, "home");
            return true;
        }

        if (args.length == 1) {
            if (player.hasPermission("rppacks.admin")) {
                plugin.getMenuGUI().openMenu(player, args[0]);
            } else {
                player.sendMessage(ChatColor.RED + "エラー: 引数を指定する権限がありません。/menu を使用してください。");
            }
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("setbg")) {
            plugin.setMenuBgColor(player.getUniqueId(), args[1].toUpperCase());
            plugin.playConfigSound(player, "page-change");
            plugin.getMenuGUI().openMenu(player, "home");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("setaction")) {
            plugin.setMenuActionColor(player.getUniqueId(), args[1].toUpperCase());
            plugin.playConfigSound(player, "page-change");
            plugin.getMenuGUI().openMenu(player, "home");
            return true;
        }

        return false;
    }
}
