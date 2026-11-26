package com.havoc.customweapon;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.ChatColor;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

public final class CustomWeaponPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, Weapon> weapons = new LinkedHashMap<>();
    private NamespacedKey weaponKey;
    private HttpServer packServer;
    private byte[] packBytes;
    private String packHash;
    private final Map<UUID, String> editing = new HashMap<>();
    private static final int[] GRID = {10,11,12,19,20,21,28,29,30};
    private static final Material[] CYCLE = {Material.AIR, Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND,
            Material.EMERALD, Material.NETHERITE_INGOT, Material.BLAZE_ROD, Material.BLAZE_POWDER,
            Material.NETHER_STAR, Material.AMETHYST_SHARD, Material.PRISMARINE_SHARD, Material.PHANTOM_MEMBRANE,
            Material.WITHER_SKELETON_SKULL, Material.SOUL_SAND, Material.TRIDENT, Material.STICK,
            Material.DIAMOND_SWORD, Material.NETHERITE_SWORD, Material.DIAMOND_AXE, Material.DIAMOND_PICKAXE,
            Material.DIAMOND_HOE, Material.DIAMOND_SHOVEL};

    @Override public void onEnable() {
        saveDefaultConfig();
        weaponKey = new NamespacedKey(this, "weapon_id");
        loadWeapons();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ct")).setExecutor(this);
        Objects.requireNonNull(getCommand("ct")).setTabCompleter(this);
        loadPack();
        startPackServer();
        getLogger().info("CustomWeapon enabled with " + weapons.size() + " weapons.");
    }

    @Override public void onDisable() {
        if (packServer != null) packServer.stop(0);
    }

    private void loadWeapons() {
        weapons.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("weapons");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;
            Material base = Material.matchMaterial(s.getString("base", "IRON_SWORD"));
            if (base == null || !base.isItem()) base = Material.IRON_SWORD;
            List<String> raw = s.getStringList("recipe");
            Material[] recipe = new Material[9];
            for (int i=0;i<9;i++) {
                Material m = i < raw.size() ? Material.matchMaterial(raw.get(i)) : Material.AIR;
                recipe[i] = m == null ? Material.AIR : m;
            }
            weapons.put(id, new Weapon(id, s.getString("name", id), base,
                    s.getInt("custom-model-data"), s.getString("permission", "customweapon.craft."+id),
                    s.getDouble("damage", 8), s.getDouble("attack-speed", 1.6),
                    s.getInt("max-durability", 1000), recipe));
        }
    }

    private void loadPack() {
        try (InputStream in = getResource("pack.zip")) {
            if (in == null) throw new IOException("pack.zip missing from plugin jar");
            packBytes = in.readAllBytes();
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(packBytes);
            packHash = HexFormat.of().formatHex(digest);
        } catch (Exception e) { getLogger().severe("Could not load embedded resource pack: " + e.getMessage()); }
    }

    private void startPackServer() {
        if (!getConfig().getBoolean("resource-pack.enabled", true) || packBytes == null) return;
        try {
            int port = getConfig().getInt("resource-pack.port", 8765);
            String host = getConfig().getString("resource-pack.host", "0.0.0.0");
            packServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            packServer.createContext("/pack.zip", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(packBytes.length));
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, packBytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(packBytes); }
            });
            packServer.setExecutor(null);
            packServer.start();
            getLogger().info("Resource pack HTTP server listening on port " + port);
        } catch (Exception e) { getLogger().severe("Resource pack server failed: " + e.getMessage()); }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("resource-pack.enabled", true)) return;
        String url = getConfig().getString("resource-pack.public-url", "");
        if (url == null || url.contains("CHANGE-ME") || !url.startsWith("http")) {
            getLogger().warning("Set resource-pack.public-url in config.yml to your public server URL.");
            return;
        }
        byte[] hash;
        try { hash = HexFormat.of().parseHex(packHash); } catch (Exception e) { return; }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = event.getPlayer();
            p.setResourcePack(url, hash, "CustomWeapon textures", getConfig().getBoolean("resource-pack.required", false));
        }, 20L);
    }

    private ItemStack makeWeapon(Weapon w) {
        ItemStack item = new ItemStack(w.base);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(w.name));
        meta.setCustomModelData(w.cmd);
        meta.setMaxDamage(Math.max(1, w.durability));
        meta.setUnbreakable(false);
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, w.id);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE,
                new AttributeModifier(new NamespacedKey(this, w.id+"_damage"), w.damage - baseDamage(w.base),
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED,
                new AttributeModifier(new NamespacedKey(this, w.id+"_speed"), w.speed - baseSpeed(w.base),
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        item.setItemMeta(meta);
        return item;
    }

    private double baseDamage(Material m) {
        return switch (m) { case IRON_SWORD -> 6; case IRON_AXE -> 9; case IRON_PICKAXE -> 4;
            case IRON_SHOVEL -> 4.5; case IRON_HOE -> 1; default -> 1; };
    }
    private double baseSpeed(Material m) {
        return switch (m) { case IRON_SWORD -> 1.6; case IRON_AXE -> 1.0; case IRON_PICKAXE -> 1.2;
            case IRON_SHOVEL -> 1.0; case IRON_HOE -> 3.0; default -> 4; };
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String plain(String s) { return ChatColor.stripColor(color(s)); }
    private void msg(CommandSender s, String m) { s.sendMessage(color("&8[&6CustomWeapon&8] &r"+m)); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "&e/ct list &7| &e/ct edit &7| &e/ct give <player> <weapon> &7| &e/ct reload");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                msg(sender, "&eDaftar weapon: &f"+String.join(", ", weapons.keySet()));
            }
            case "reload" -> {
                if (!sender.hasPermission("customweapon.admin")) { msg(sender,"&cTidak punya permission."); return true; }
                reloadConfig(); loadWeapons(); msg(sender,"&aConfig berhasil di-reload.");
            }
            case "give" -> {
                if (!sender.hasPermission("customweapon.admin")) { msg(sender,"&cTidak punya permission."); return true; }
                if (args.length < 3) { msg(sender,"&cGunakan /ct give <player> <weapon>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]); Weapon w = weapons.get(args[2].toLowerCase(Locale.ROOT));
                if (target == null || w == null) { msg(sender,"&cPlayer atau weapon tidak ditemukan."); return true; }
                target.getInventory().addItem(makeWeapon(w)); msg(sender,"&aDiberikan ke "+target.getName()+".");
            }
            case "edit" -> {
                if (!(sender instanceof Player p)) { msg(sender,"&cCommand ini hanya untuk player."); return true; }
                if (!p.hasPermission("customweapon.admin")) { msg(p,"&cTidak punya permission."); return true; }
                openSelect(p);
            }
            default -> msg(sender,"&cSubcommand tidak dikenal.");
        }
        return true;
    }

    private void openSelect(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&8CustomWeapon &7- Pilih Weapon"));
        int slot=0;
        for (Weapon w : weapons.values()) {
            if (slot >= 45) break;
            inv.setItem(slot++, makeWeapon(w));
        }
        p.openInventory(inv);
    }

    private void openEditor(Player p, Weapon w) {
        Inventory inv = Bukkit.createInventory(null, 36, color("&8Edit: "+plain(w.name)));
        for (int i=0;i<9;i++) inv.setItem(GRID[i], icon(w.recipe[i], i));
        inv.setItem(22, makeWeapon(w));
        inv.setItem(24, named(Material.LIME_CONCRETE, "&aSimpan Recipe", "&7Klik untuk menyimpan"));
        inv.setItem(31, named(Material.BARRIER, "&cKembali", "&7Kembali ke daftar weapon"));
        editing.put(p.getUniqueId(), w.id);
        p.openInventory(inv);
    }

    private ItemStack icon(Material m, int index) {
        if (m == Material.AIR) return named(Material.GRAY_STAINED_GLASS_PANE, "&7Kosong", "&8Slot "+(index+1)+" - klik untuk ubah");
        return named(m, "&f"+m.name(), "&8Slot "+(index+1)+" - klik untuk ubah");
    }
    private ItemStack named(Material m, String name, String lore) {
        ItemStack it = new ItemStack(m); ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(color(name)); meta.setLore(List.of(color(lore))); it.setItemMeta(meta); return it;
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!ChatColor.stripColor(title).startsWith("CustomWeapon") && !ChatColor.stripColor(title).startsWith("Edit:")) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (ChatColor.stripColor(title).startsWith("CustomWeapon")) {
            ItemStack clicked=e.getCurrentItem(); if (clicked==null || !clicked.hasItemMeta()) return;
            String id=clicked.getItemMeta().getPersistentDataContainer().get(weaponKey,PersistentDataType.STRING);
            if (id!=null && weapons.containsKey(id)) openEditor(p,weapons.get(id));
            return;
        }
        String id=editing.get(p.getUniqueId()); Weapon w=weapons.get(id); if(w==null) return;
        int slot=e.getRawSlot();
        for(int i=0;i<9;i++) if(slot==GRID[i]) {
            Material current=w.recipe[i]; int idx=0;
            for(int j=0;j<CYCLE.length;j++) if(CYCLE[j]==current){idx=j;break;}
            w.recipe[i]=CYCLE[(idx+1)%CYCLE.length];
            e.getInventory().setItem(slot,icon(w.recipe[i],i)); return;
        }
        if(slot==24) {
            List<String> values=new ArrayList<>(); for(Material m:w.recipe) values.add(m.name());
            getConfig().set("weapons."+w.id+".recipe",values); saveConfig();
            msg(p,"&aRecipe "+w.id+" berhasil disimpan."); openEditor(p,w);
        } else if(slot==31) { editing.remove(p.getUniqueId()); openSelect(p); }
    }

    @EventHandler public void onPrepareCraft(PrepareItemCraftEvent e) {
        ItemStack result = e.getInventory().getResult();
        for (Weapon w : weapons.values()) {
            if (matches(e.getInventory().getMatrix(), w.recipe)) {
                if (result == null || result.getType() == Material.AIR) e.getInventory().setResult(makeWeapon(w));
                else e.getInventory().setResult(makeWeapon(w));
                return;
            }
        }
        if (result != null && result.hasItemMeta() && result.getItemMeta().getPersistentDataContainer().has(weaponKey, PersistentDataType.STRING))
            e.getInventory().setResult(null);
    }

    @EventHandler public void onCraft(CraftItemEvent e) {
        ItemStack[] matrix=e.getInventory().getMatrix();
        for(Weapon w:weapons.values()) if(matches(matrix,w.recipe)) {
            if(!(e.getWhoClicked() instanceof Player p) || !p.hasPermission(w.permission)) {
                e.setCancelled(true);
                if(e.getWhoClicked() instanceof Player p) msg(p,"&cKamu tidak memiliki permission: &f"+w.permission);
                return;
            }
            e.setCurrentItem(makeWeapon(w));
            return;
        }
    }

    private boolean matches(ItemStack[] matrix, Material[] recipe) {
        if(matrix==null || matrix.length<9) return false;
        for(int i=0;i<9;i++) {
            Material actual=matrix[i]==null?Material.AIR:matrix[i].getType();
            if(recipe[i]==Material.AIR ? actual!=Material.AIR : actual!=recipe[i]) return false;
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if(args.length==1) return Arrays.asList("list","edit","give","reload");
        if(args.length==2 && args[0].equalsIgnoreCase("give")) return null;
        if(args.length==3 && args[0].equalsIgnoreCase("give")) return weapons.keySet().stream().toList();
        return Collections.emptyList();
    }

    private record Weapon(String id, String name, Material base, int cmd, String permission,
                          double damage, double speed, int durability, Material[] recipe) {}
}
