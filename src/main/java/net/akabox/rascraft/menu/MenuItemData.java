package net.akabox.rascraft.menu;

import java.util.List;

public class MenuItemData {

    public record ViewRequirement(String type, String placeholder, String value) {}

    private final String key;
    private final String material;
    private final String displayName;
    private final List<String> lore;
    private final int slot;
    private final String type;
    private final List<String> commands;
    private final List<String> messages;
    private final String openMenu;
    private final String playSound;

    private final String displayNameColor;
    private final String texture;

    private final ViewRequirement viewRequirement;
    private final String fallbackItemKey;

    public MenuItemData(String key, String material, String displayName, String displayNameColor, List<String> lore,
            int slot, String type,
            List<String> commands, List<String> messages, String openMenu, String playSound, String texture,
            ViewRequirement viewRequirement, String fallbackItemKey) {
        this.key = key;
        this.material = material;
        this.displayName = displayName;
        this.displayNameColor = displayNameColor;
        this.lore = lore;
        this.slot = slot;
        this.type = type != null ? type : "none";
        this.commands = commands;
        this.messages = messages;
        this.openMenu = openMenu;
        this.playSound = playSound;
        this.texture = texture;
        this.viewRequirement = viewRequirement;
        this.fallbackItemKey = fallbackItemKey;
    }

    public ViewRequirement getViewRequirement() {
        return viewRequirement;
    }

    public String getFallbackItemKey() {
        return fallbackItemKey;
    }

    public String getTexture() {
        return texture;
    }

    public String getKey() {
        return key;
    }

    public String getMaterial() {
        return material;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getLore() {
        return lore;
    }

    public int getSlot() {
        return slot;
    }

    public String getType() {
        return type;
    }

    public List<String> getCommands() {
        return commands;
    }

    public List<String> getMessages() {
        return messages;
    }

    public String getOpenMenu() {
        return openMenu;
    }

    public String getPlaySound() {
        return playSound;
    }

    public String getDisplayNameColor() {
        return displayNameColor;
    }

    public String getColorPrefix() {
        if (displayNameColor == null || displayNameColor.isEmpty()) {
            return "";
        }
        String color = displayNameColor.toLowerCase();
        // 簡易的な英名マッピング
        return switch (color) {
            case "black" -> "&0";
            case "dark_blue", "darkblue" -> "&1";
            case "dark_green", "darkgreen" -> "&2";
            case "dark_aqua", "darkaqua", "cyan" -> "&3";
            case "dark_red", "darkred" -> "&4";
            case "dark_purple", "darkpurple", "purple" -> "&5";
            case "gold", "orange" -> "&6";
            case "gray", "grey" -> "&7";
            case "dark_gray", "darkgray" -> "&8";
            case "blue" -> "&9";
            case "green", "lime" -> "&a";
            case "aqua", "light_blue", "lightblue" -> "&b";
            case "red" -> "&c";
            case "light_purple", "lightpurple", "pink" -> "&d";
            case "yellow" -> "&e";
            case "white" -> "&f";
            default -> {
                // すでに & や § から始まっている場合はそのまま返す
                if (color.startsWith("&") || color.startsWith("§") || color.startsWith("#")) {
                    yield color;
                }
                // それ以外（たとえば "c" や "f" など1文字）なら & をつけてみる
                if (color.length() == 1) {
                    yield "&" + color;
                }
                yield ""; // 該当なし
            }
        };
    }
}
