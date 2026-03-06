package net.akabox.rascraft.menu;

import java.util.Map;

public class MenuData {
    private final String id;
    private final String title;
    private final int size;
    private final String menuType;
    private final String tabTarget;
    private final Map<String, MenuItemData> items;

    public MenuData(String id, String title, int size, String menuType, String tabTarget,
            Map<String, MenuItemData> items) {
        this.id = id;
        this.title = title;
        this.size = size;
        this.menuType = menuType != null ? menuType : "normal";
        this.tabTarget = tabTarget;
        this.items = items;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public int getSize() {
        return size;
    }

    public String getMenuType() {
        return menuType;
    }

    public String getTabTarget() {
        return tabTarget;
    }

    public Map<String, MenuItemData> getItems() {
        return items;
    }
}
