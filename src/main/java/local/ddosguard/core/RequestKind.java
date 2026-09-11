package local.ddosguard.core;

public enum RequestKind {
    COMMAND("command"), TAB_COMPLETE("tab-complete"), CHAT("chat"), BOOK_EDIT("book-edit"),
    INVENTORY_CLICK("inventory-click"), ITEM_DROP("item-drop"), CUSTOM_PAYLOAD("custom-payload"),
    ALL_PACKETS("all-packets");
    private final String path;
    RequestKind(String path) { this.path = path; }
    public String path() { return path; }
}
