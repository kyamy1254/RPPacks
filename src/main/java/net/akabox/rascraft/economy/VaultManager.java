package net.akabox.rascraft.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class VaultManager {

    private Economy econ = null;

    public boolean setupEconomy(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    public Economy getEconomy() {
        return econ;
    }

    public boolean hasEnough(OfflinePlayer player, double amount) {
        if (econ == null)
            return false;
        return econ.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (econ == null)
            return false;
        return econ.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (econ == null)
            return false;
        return econ.depositPlayer(player, amount).transactionSuccess();
    }
}
