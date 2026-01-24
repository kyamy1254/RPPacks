package net.akabox.rascraft;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Random;

public class SneakListener implements Listener {
    private final RascraftPluginPacks plugin;
    private final Random random = new Random();

    public SneakListener(RascraftPluginPacks plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("rascraft.sneakgrow") || !plugin.isSneakGrowEnabled()) return;

        if (event.isSneaking()) {
            if (random.nextDouble() > plugin.getSuccessChance()) return;

            Location playerLoc = player.getLocation();
            int radius = plugin.getGrowRadius();

            for (int x = -radius; x <= radius; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        Block block = playerLoc.clone().add(x, y, z).getBlock();
                        if (tryToGrow(block)) {
                            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 5, 0.3, 0.3, 0.3, 0);
                        }
                    }
                }
            }
        }
    }

    private boolean tryToGrow(Block block) {
        RascraftPluginPacks.GrowType type = plugin.getGrowType(block.getType());
        if (type == RascraftPluginPacks.GrowType.NONE) return false;

        switch (type) {
            case AGEABLE:
                if (block.getBlockData() instanceof Ageable ageable) {
                    if (ageable.getAge() < ageable.getMaximumAge()) {
                        ageable.setAge(ageable.getAge() + 1);
                        block.setBlockData(ageable);
                        return true;
                    }
                }
                break;
            case STACK:
                Block above = block.getRelative(BlockFace.UP);
                if (above.getType() == Material.AIR) {
                    above.setType(block.getType());
                    return true;
                }
                break;
            case BONEMEAL:
                // メインスレッドで骨粉を適用
                return block.applyBoneMeal(BlockFace.UP);
        }
        return false;
    }
}