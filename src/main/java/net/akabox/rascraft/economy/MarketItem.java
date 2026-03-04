package net.akabox.rascraft.economy;

import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public class MarketItem {
    private final UUID id;
    private final ItemStack itemStack;
    private final double price;
    private final UUID sellerUuid;
    private final String sellerName;
    private final long listTime;

    public MarketItem(UUID id, ItemStack itemStack, double price, UUID sellerUuid, String sellerName, long listTime) {
        this.id = id;
        this.itemStack = itemStack;
        this.price = price;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.listTime = listTime;
    }

    public UUID getId() {
        return id;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public double getPrice() {
        return price;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public String getSellerName() {
        return sellerName;
    }

    public long getListTime() {
        return listTime;
    }
}
