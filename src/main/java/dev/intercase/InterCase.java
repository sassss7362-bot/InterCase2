package dev.intercase;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class InterCase extends JavaPlugin implements Listener {

    // ───────────────────────── моделі ─────────────────────────

    static class Reward {
        String name; int rgb; TextColor tc; Material icon; double weight; int tier;
        List<String> commands = new ArrayList<>();
        Component title() { return Component.text(name, tc, TextDecoration.BOLD); }
        Color bc() { return Color.fromRGB(rgb); }
    }

    static class PreviewHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    static final double PD = Math.toRadians(225); // меч вістрям вниз
    static final EntityType[] MOBS = {
            EntityType.WITHER_SKELETON, EntityType.IRON_GOLEM, EntityType.CREEPER, EntityType.VILLAGER,
            EntityType.ENDERMAN, EntityType.WITCH, EntityType.SPIDER, EntityType.HUSK, EntityType.STRAY,
            EntityType.RAVAGER, EntityType.EVOKER, EntityType.PILLAGER, EntityType.VINDICATOR};
    static final Material[] SWORDS = {
            Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
            Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD};
    static final String[] PICK_WHAT = {"мечу", "мобу", "сундуку", "кристалу"};

    final List<Reward> pool = new ArrayList<>();
    double total;
    String caseName = "&6&lДонат кейс";
    final Set<String> caseBlocks = new HashSet<>();
    final Map<String, TextDisplay> holos = new HashMap<>();
    final Map<UUID, Show> shows = new HashMap<>();
    final Map<UUID, Show> hitOwner = new HashMap<>();
    final Set<String> busy = new HashSet<>();
    final Random rnd = new Random();
    NamespacedKey kKey;
    File dataFile;

    // ───────────────────────── життєвий цикл ─────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kKey = new NamespacedKey(this, "donate_key");
        dataFile = new File(getDataFolder(), "data.yml");
        loadCfg();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::ensureHolos, 20L, 100L);
    }

    @Override
    public void onDisable() {
        for (Show s : new ArrayList<>(shows.values())) s.forceFinish(true);
        for (TextDisplay h : holos.values()) if (h.isValid()) h.remove();
        holos.clear();
    }

    // ───────────────────────── утиліти ─────────────────────────

    Component legacy(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s); }
    Component noItalic(Component c) { return c.decoration(TextDecoration.ITALIC, false); }
    String bkey(Location l) { return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ(); }

    Location fromKey(String k) {
        String[] a = k.split(";");
        World w = Bukkit.getWorld(a[0]);
        if (w == null) return null;
        return new Location(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
    }

    TextDisplay mkText(Location l, Component c, float scale) {
        TextDisplay td = l.getWorld().spawn(l, TextDisplay.class);
        td.text(c);
        td.setBillboard(Display.Billboard.CENTER);
        td.setShadowed(true);
        td.setBackgroundColor(Color.fromARGB(70, 0, 0, 0));
        td.setPersistent(false);
        td.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        return td;
    }

    ItemDisplay mkItem(Location l, ItemStack is, float scale, Display.Billboard bb) {
        ItemDisplay id = l.getWorld().spawn(l, ItemDisplay.class);
        id.setItemStack(is);
        id.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        id.setBillboard(bb);
        id.setPersistent(false);
        tf(id, scale, 0);
        return id;
    }

    void tf(Display d, float s, double angZ) {
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateZ((float) angZ),
                new Vector3f(s, s, s), new Quaternionf()));
    }

    // ───────────────────────── конфіг і дані ─────────────────────────

    void loadCfg() {
        reloadConfig();
        pool.clear();
        total = 0;
        caseName = getConfig().getString("case-name", "&6&lДонат кейс");
        int idx = 0;
        for (Map<?, ?> m : getConfig().getMapList("rewards")) {
            Reward r = new Reward();
            r.name = String.valueOf(m.get("name"));
            try { r.rgb = Integer.parseInt(String.valueOf(m.get("color")).replace("#", ""), 16); }
            catch (NumberFormatException e) { r.rgb = 0xFFFFFF; }
            r.tc = TextColor.color(r.rgb);
            Material mat = Material.matchMaterial(String.valueOf(m.get("icon")));
            r.icon = mat != null ? mat : Material.NETHER_STAR;
            Object ch = m.get("chance");
            r.weight = ch instanceof Number n ? n.doubleValue() : 1.0;
            Object co = m.get("commands");
            if (co instanceof List<?> l) for (Object o : l) r.commands.add(String.valueOf(o));
            r.tier = idx++;
            pool.add(r);
            total += r.weight;
        }
        if (pool.size() < 8) getLogger().warning("У config.yml потрібно мінімум 8 привілегій у rewards, а є " + pool.size());
    }

    void loadData() {
        caseBlocks.clear();
        if (!dataFile.exists()) return;
        caseBlocks.addAll(YamlConfiguration.loadConfiguration(dataFile).getStringList("blocks"));
    }

    void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("blocks", new ArrayList<>(caseBlocks));
        try { y.save(dataFile); } catch (IOException ex) { getLogger().warning(ex.getMessage()); }
    }

    // ───────────────────────── голограми ─────────────────────────

    void ensureHolos() {
        for (String k : caseBlocks) {
            if (busy.contains(k)) continue;
            TextDisplay h = holos.get(k);
            if (h != null && h.isValid()) continue;
            Location l = fromKey(k);
            if (l == null) continue;
            if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            Component c = legacy("&6&l✦ InterTime ✦").append(Component.newline())
                    .append(legacy(caseName)).append(Component.newline())
                    .append(legacy("&7ПКМ з ключем — відкрити")).append(Component.newline())
                    .append(legacy("&8ЛКМ — подивитись привілегії"));
            holos.put(k, mkText(l.clone().add(0.5, 2.1, 0.5), c, 1.6f));
        }
    }

    void dropHolo(String k) {
        TextDisplay h = holos.remove(k);
        if (h != null && h.isValid()) h.remove();
    }

    // ───────────────────────── ключ ─────────────────────────

    ItemStack makeKey(int n) {
        String val = getConfig().getString("key-head-value", "");
        ItemStack it = new ItemStack(val.isEmpty() ? Material.TRIPWIRE_HOOK : Material.PLAYER_HEAD, n);
        ItemMeta m = it.getItemMeta();
        if (!val.isEmpty() && m instanceof SkullMeta sm) {
            PlayerProfile prof = Bukkit.createProfile(UUID.nameUUIDFromBytes(val.getBytes()), "intercase");
            prof.setProperty(new ProfileProperty("textures", val));
            sm.setPlayerProfile(prof);
        }
        m.displayName(noItalic(legacy("&e&lКлюч &8» ").append(legacy(caseName))));
        m.lore(List.of(
                noItalic(legacy("&7Ключ від кейса сервера &6InterTime")),
                noItalic(legacy("&7ПКМ по кейсу — відкрити"))));
        m.getPersistentDataContainer().set(kKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isKey(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(kKey, PersistentDataType.BYTE);
    }

    void giveKey(Player p, int n) {
        for (ItemStack left : p.getInventory().addItem(makeKey(n)).values())
            p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    // ───────────────────────── команди ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("intercase.admin")) { s.sendMessage(legacy("&cНемає прав.")); return true; }
        if (a.length == 0) { help(s); return true; }
        switch (a[0].toLowerCase()) {
            case "key" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Тільки для гравців."); return true; }
                int n = amount(a, 1);
                giveKey(p, n);
                p.sendMessage(legacy("&aТи отримав ключ x" + n + "."));
            }
            case "give" -> {
                if (a.length < 2) { help(s); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(legacy("&cГравця не знайдено (він має бути онлайн).")); return true; }
                int n = amount(a, 2);
                giveKey(t, n);
                s.sendMessage(legacy("&aВидано " + t.getName() + " ключ x" + n + "."));
                t.sendMessage(legacy("&6&l✦ InterTime &8» &fТи отримав ключ x" + n + " від ").append(legacy(caseName)));
            }
            case "set" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Тільки для гравців."); return true; }
                Block b = p.getTargetBlockExact(6);
                if (b == null) { p.sendMessage(legacy("&cДивись на блок (скриня, бочка...) ближче 6 блоків.")); return true; }
                String k = bkey(b.getLocation());
                dropHolo(k);
                caseBlocks.add(k);
                saveData();
                ensureHolos();
                p.sendMessage(legacy("&aБлок став кейсом: ").append(legacy(caseName)));
            }
            case "remove" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Тільки для гравців."); return true; }
                Block b = p.getTargetBlockExact(6);
                if (b == null) { p.sendMessage(legacy("&cДивись на блок кейса.")); return true; }
                String k = bkey(b.getLocation());
                if (!caseBlocks.remove(k)) { p.sendMessage(legacy("&cЦе не кейс.")); return true; }
                dropHolo(k);
                saveData();
                p.sendMessage(legacy("&aКейс видалено."));
            }
            case "reload" -> {
                loadCfg();
                loadData();
                for (String k : new ArrayList<>(holos.keySet())) dropHolo(k);
                ensureHolos();
                s.sendMessage(legacy("&aПерезавантажено. Привілегій: " + pool.size()));
            }
            default -> help(s);
        }
        return true;
    }

    void help(CommandSender s) {
        s.sendMessage(legacy("&6&l✦ InterCase &8» &7команди:"));
        s.sendMessage(legacy("&e/case key [к-сть] &7— ключ собі в руки"));
        s.sendMessage(legacy("&e/case give <гравець> [к-сть] &7— видати ключ гравцю"));
        s.sendMessage(legacy("&e/case set &7— зробити блок, на який дивишся, кейсом"));
        s.sendMessage(legacy("&e/case remove &7— прибрати кейс"));
        s.sendMessage(legacy("&e/case reload &7— перезавантажити config.yml"));
    }

    int amount(String[] a, int i) {
        if (a.length <= i) return 1;
        try { return Math.max(1, Math.min(64, Integer.parseInt(a[i]))); } catch (NumberFormatException e) { return 1; }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> r = new ArrayList<>();
        if (!s.hasPermission("intercase.admin")) return r;
        if (a.length == 1) {
            for (String x : List.of("key", "give", "set", "remove", "reload")) if (x.startsWith(a[0].toLowerCase())) r.add(x);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) r.add(p.getName());
        }
        return r;
    }

    // ───────────────────────── події ─────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) return;
        if (!caseBlocks.contains(bkey(b.getLocation()))) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (a == Action.RIGHT_CLICK_BLOCK) {
            if (isKey(p.getInventory().getItemInMainHand())) { startShow(p, b); return; }
            p.sendMessage(legacy("&6&l✦ InterTime &8» &7Потрібен ключ від ").append(legacy(caseName)));
        }
        preview(p);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (caseBlocks.contains(bkey(e.getBlock().getLocation()))) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(legacy("&cЦе кейс. Прибрати: /case remove"));
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isKey(e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Show s = shows.get(e.getPlayer().getUniqueId());
        if (s != null) s.forceFinish(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PreviewHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PreviewHolder) e.setCancelled(true);
    }

    // удар по мобу / стійці-хітбоксу
    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        Show s = hitOwner.get(e.getEntity().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent be) {
            Entity d = be.getDamager();
            Player pl = null;
            if (d instanceof Player x) pl = x;
            else if (d instanceof Projectile pr && pr.getShooter() instanceof Player y) pl = y;
            if (pl != null) s.onHit(pl, e.getEntity());
        }
    }

    // ПКМ по мобу / стійці теж зараховується
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Show s = hitOwner.get(e.getRightClicked().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) s.onHit(e.getPlayer(), e.getRightClicked());
    }

    @EventHandler
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        Show s = hitOwner.get(e.getRightClicked().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) s.onHit(e.getPlayer(), e.getRightClicked());
    }

    @EventHandler
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (hitOwner.containsKey(e.getRightClicked().getUniqueId())) e.setCancelled(true);
    }

    // ───────────────────────── перегляд привілегій ─────────────────────────

    void preview(Player p) {
        PreviewHolder h = new PreviewHolder();
        Inventory inv = Bukkit.createInventory(h, 36, legacy("&6&l✦ InterTime &8» ").append(legacy(caseName)));
        h.inv = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(Component.text(" "));
        pane.setItemMeta(pm);
        for (int i = 0; i < 36; i++) inv.setItem(i, pane);
        int[] slots = {9, 10, 11, 12, 13, 14, 15, 16, 17, 21, 23, 22, 20, 24};
        for (int i = 0; i < pool.size() && i < slots.length; i++) {
            Reward r = pool.get(i);
            ItemStack it = new ItemStack(r.icon);
            ItemMeta m = it.getItemMeta();
            m.displayName(noItalic(r.title()));
            m.lore(List.of(noItalic(Component.text("Привілегія", NamedTextColor.GRAY))));
            it.setItemMeta(m);
            inv.setItem(slots[i], it);
        }
        p.openInventory(inv);
    }

    // ───────────────────────── запуск ─────────────────────────

    void startShow(Player p, Block b) {
        if (pool.size() < 8) { p.sendMessage(legacy("&cКейс не налаштований (config.yml).")); return; }
        String k = bkey(b.getLocation());
        if (busy.contains(k)) { p.sendMessage(legacy("&cЦей кейс зараз відкривається.")); return; }
        if (shows.containsKey(p.getUniqueId())) { p.sendMessage(legacy("&cТи вже відкриваєш кейс.")); return; }
        ItemStack hand = p.getInventory().getItemInMainHand().clone();
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        int anim = getConfig().getInt("force-animation", -1);
        if (anim < 0 || anim > 5) anim = rnd.nextInt(6);
        busy.add(k);
        dropHolo(k);
        Show s = new Show(p, b, anim);
        shows.put(p.getUniqueId(), s);
        s.runTaskTimer(this, 0L, 1L);
    }

    // ───────────────────────── саме шоу ─────────────────────────
    // 0,1 — автоматичні; 2 мечі, 3 моби, 4 сундуки, 5 кристали — гравець б'є сам

    class Show extends BukkitRunnable {
        final Player p; final Location base; final String bkey; final int anim;
        final Reward[] slot = new Reward[8];
        final Entity[] obj = new Entity[8];
        final Entity[] hb = new Entity[8];
        final TextDisplay[] lab = new TextDisplay[8];
        final boolean[] out = new boolean[8];
        final int[] landT = new int[8];
        final Vector[] fv = new Vector[8];
        final int[] fEnd = new int[8];
        final List<Entity> all = new ArrayList<>();
        final List<Integer> elim = new ArrayList<>();
        final List<Integer> others = new ArrayList<>();
        final List<EntityType> mobTypes = new ArrayList<>();
        final List<Material> swordMats = new ArrayList<>();
        final double R = getConfig().getDouble("ring-radius", 3.6);
        final int timeout = getConfig().getInt("pick-timeout-seconds", 45) * 20;
        final Reward prize;
        int prizeSlot, win, t = 0, endAt = -1, revT = 0, fwLeft = 0;
        boolean revealed, finished, rewardGiven;
        double theta = 0;
        int cursor, steps, kStep, nextAt = -1, doneT = -1;
        int arrivedAt = -1, pickT = -1, ch = -1;
        double hbY = 0;
        Entity drop; Location dropTo;

        Show(Player p, Block b, int anim) {
            this.p = p; this.anim = anim;
            this.base = b.getLocation().add(0.5, 0, 0.5);
            this.bkey = InterCase.this.bkey(b.getLocation());
            double r = rnd.nextDouble() * total, acc = 0;
            Reward w = pool.get(pool.size() - 1);
            for (Reward x : pool) { acc += x.weight; if (r < acc) { w = x; break; } }
            prize = w;
            List<Reward> rest = new ArrayList<>(pool);
            rest.remove(w);
            Collections.shuffle(rest, rnd);
            List<Reward> eight = new ArrayList<>(rest.subList(0, 7));
            eight.add(w);
            Collections.shuffle(eight, rnd);
            for (int i = 0; i < 8; i++) { slot[i] = eight.get(i); if (slot[i] == w) prizeSlot = i; }
            win = prizeSlot;
            for (int i = 0; i < 8; i++) if (i != win) elim.add(i);
            Collections.shuffle(elim, rnd);
            List<EntityType> mt = new ArrayList<>(List.of(MOBS));
            Collections.shuffle(mt, rnd);
            for (int i = 0; i < 8; i++) mobTypes.add(mt.get(i));
            for (int i = 0; i < 8; i++) swordMats.add(SWORDS[rnd.nextInt(SWORDS.length)]);
        }

        // ── допоміжне ──
        Location pos(int i, double y) {
            double a = i * Math.PI * 2 / 8;
            return base.clone().add(R * Math.cos(a), y, R * Math.sin(a));
        }
        void snd(Sound s, float v, float pitch) { base.getWorld().playSound(base, s, v, pitch); }
        void fx(Particle pt, Location l, int n, double speed) { base.getWorld().spawnParticle(pt, l, n, 0.3, 0.3, 0.3, speed); }
        <T extends Entity> T track(T e) { all.add(e); return e; }
        TextDisplay text(Location l, Component c, float sc) { return track(mkText(l, c, sc)); }
        ItemDisplay item(Location l, ItemStack is, float sc, Display.Billboard bb) { return track(mkItem(l, is, sc, bb)); }
        Component mystery() { return Component.text("?", NamedTextColor.GRAY, TextDecoration.BOLD); }

        void rmObj(int i) { if (obj[i] != null) { obj[i].remove(); obj[i] = null; } }
        void rm(int i) {
            rmObj(i);
            if (hb[i] != null && anim != 3) { hitOwner.remove(hb[i].getUniqueId()); hb[i].remove(); hb[i] = null; }
            if (lab[i] != null) { lab[i].remove(); lab[i] = null; }
        }

        void glow(int i, boolean on) {
            if (obj[i] == null) return;
            obj[i].setGlowing(on);
            if (obj[i] instanceof Display dsp) dsp.setGlowColorOverride(slot[i].bc());
        }

        ArmorStand stand(Location l) {
            ArmorStand as = l.getWorld().spawn(l, ArmorStand.class);
            as.setInvisible(true);
            as.setGravity(false);
            as.setMarker(false);
            as.setBasePlate(false);
            as.setArms(false);
            as.setSilent(true);
            as.setPersistent(false);
            as.setCollidable(false);
            track(as);
            hitOwner.put(as.getUniqueId(), this);
            return as;
        }

        void firework(Location l, Color c) {
            Firework f = l.getWorld().spawn(l, Firework.class);
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(c)
                    .withFade(Color.WHITE).flicker(true).trail(true).build());
            m.setPower(0);
            f.setFireworkMeta(m);
            f.detonate();
        }

        // ── головний цикл ──
        @Override
        public void run() {
            if (!p.isOnline()) { forceFinish(true); return; }
            if (t == 0) intro();
            switch (anim) {
                case 0 -> autoRoulette();
                case 1 -> autoVortex();
                case 2 -> pickSwords();
                case 3 -> pickMobs();
                case 4 -> pickChests();
                default -> pickCrystals();
            }
            if (anim >= 2) pickFlow();
            if (revealed && fwLeft > 0 && (t - revT) % 8 == 0) {
                firework(base.clone().add(rnd.nextDouble() * 4 - 2, 2.5, rnd.nextDouble() * 4 - 2), prize.bc());
                fwLeft--;
            }
            if (endAt >= 0 && t >= endAt) { finish(); return; }
            t++;
        }

        void intro() {
            snd(Sound.BLOCK_ENDER_CHEST_OPEN, 2f, 0.8f);
            p.showTitle(Title.title(legacy("&6&l✦ InterTime ✦"), legacy(caseName),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(500))));
            text(base.clone().add(0, 3.4, 0), legacy("&6&l✦ InterTime ✦").append(Component.newline()).append(legacy(caseName)), 2.6f);
        }

        // ═════════ 0: ПАДІННЯ + РУЛЕТКА (авто) ═════════
        void highlight(int c) {
            for (int i = 0; i < 8; i++) {
                if (obj[i] == null) continue;
                glow(i, i == c);
                tf((Display) obj[i], i == c ? 1.8f : 1.0f, 0);
            }
        }

        void autoRoulette() {
            final int ST = 8, GAP = 5, FD = 18;
            for (int i = 0; i < 8; i++) {
                int st = ST + i * GAP;
                Location to = pos(i, 1.5);
                if (t == st) {
                    obj[i] = item(to.clone().add(0, 9, 0), new ItemStack(slot[i].icon), 1.0f, Display.Billboard.CENTER);
                    snd(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 0.8f + i * 0.05f);
                }
                if (t > st && t <= st + FD && obj[i] != null && landT[i] == 0) {
                    double f = (double) (t - st) / FD;
                    Location cur = to.clone();
                    cur.setY(to.getY() + 9 * (1 - f * f));
                    obj[i].teleport(cur);
                    tf((Display) obj[i], 1.0f, t * 0.3);
                    fx(Particle.END_ROD, cur, 2, 0.01);
                    if (t == st + FD) {
                        landT[i] = t;
                        tf((Display) obj[i], 1.0f, 0);
                        fx(Particle.POOF, to, 14, 0.05);
                        snd(Sound.BLOCK_ANVIL_LAND, 0.8f, 1.4f);
                        lab[i] = text(pos(i, 2.6), slot[i].title(), 1.1f);
                    }
                }
            }
            int start = ST + 7 * GAP + FD + 25;
            if (t == start) {
                cursor = rnd.nextInt(8);
                steps = 24 + ((win - cursor + 8) % 8);
                kStep = 0;
                nextAt = t;
            }
            if (nextAt >= 0 && doneT < 0 && t == nextAt) {
                highlight(cursor);
                snd(Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, 0.6f + 1.4f * kStep / steps);
                if (kStep == steps) {
                    doneT = t;
                    snd(Sound.ENTITY_PLAYER_LEVELUP, 2f, 1f);
                    fx(Particle.TOTEM_OF_UNDYING, pos(win, 1.8), 30, 0.2);
                } else {
                    kStep++;
                    cursor = (cursor + 1) % 8;
                    nextAt = t + 2 + (kStep * kStep) / (steps * 3);
                }
            }
            if (doneT >= 0) {
                if (t == doneT + 25) {
                    for (int i = 0; i < 8; i++) if (i != win) { out[i] = true; fx(Particle.POOF, pos(i, 1.5), 15, 0.1); rm(i); }
                    snd(Sound.ENTITY_ENDERMAN_TELEPORT, 2f, 1.3f);
                }
                if (t > doneT + 25 && t <= doneT + 55 && obj[win] != null) {
                    double f = (t - doneT - 25) / 30.0;
                    Location from = pos(win, 1.5), to = base.clone().add(0, 2.7, 0);
                    Location l = from.clone().add((to.getX() - from.getX()) * f, (to.getY() - from.getY()) * f, (to.getZ() - from.getZ()) * f);
                    obj[win].teleport(l);
                    tf((Display) obj[win], (float) (1.8 + 1.4 * f), t * 0.15);
                    if (lab[win] != null) lab[win].teleport(l.clone().add(0, 1.4, 0));
                    fx(Particle.END_ROD, l, 4, 0.02);
                }
                if (t == doneT + 55) reveal();
            }
        }

        // ═════════ 1: ПРИВІЛЕЇ ПАДАЮТЬ У СУНДУК + ВИХОР (авто) ═════════
        void startFly(int i) {
            out[i] = true;
            if (obj[i] == null) return;
            Vector dir = obj[i].getLocation().toVector().subtract(base.toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
            dir.normalize();
            fv[i] = dir.multiply(0.6).setY(0.3);
            fEnd[i] = t + 12;
            if (lab[i] != null) { lab[i].remove(); lab[i] = null; }
            snd(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 1f);
        }

        void autoVortex() {
            final int ST = 8, GAP = 6, FD = 16;
            Location S = base.clone().add(0, 1.4, 0);
            for (int i = 0; i < 8; i++) {
                int st = ST + i * GAP;
                if (t == st) {
                    obj[i] = item(S.clone().add(0, 9, 0), new ItemStack(slot[i].icon), 0.9f, Display.Billboard.CENTER);
                    glow(i, true);
                    snd(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 0.8f + i * 0.05f);
                }
                if (t > st && t <= st + FD && obj[i] != null && landT[i] == 0) {
                    double f = (double) (t - st) / FD;
                    Location cur = S.clone();
                    cur.setY(S.getY() + 9 * (1 - f * f));
                    obj[i].teleport(cur);
                    tf((Display) obj[i], 0.9f, t * 0.3);
                    fx(Particle.END_ROD, cur, 2, 0.01);
                    if (t == st + FD) {
                        landT[i] = t;
                        snd(Sound.BLOCK_ENDER_CHEST_OPEN, 1.2f, 1.3f);
                        fx(Particle.ENCHANT, S, 25, 0.5);
                        lab[i] = text(S.clone().add(0, 0.9, 0), slot[i].title(), 0.9f);
                    }
                }
            }
            int lastLand = ST + 7 * GAP + FD;
            int es = lastLand + 40, cs = es + 6 * 9 + 25;
            double speed = 0.05 + 0.38 * Math.pow(Math.min(1.0, Math.max(0, t - 30) / (double) (es - 10)), 2);
            if (t >= cs) speed = 0.45;
            theta += speed;
            for (int k = 0; k < 7; k++) if (t == es + k * 9) startFly(elim.get(k));
            double cf = t >= cs ? Math.min(1.0, (t - cs) / 30.0) : 0;
            for (int i = 0; i < 8; i++) {
                if (obj[i] == null || landT[i] == 0) continue;
                if (fEnd[i] > 0) {
                    obj[i].teleport(obj[i].getLocation().add(fv[i]));
                    fx(Particle.CLOUD, obj[i].getLocation(), 2, 0.01);
                    if (t >= fEnd[i]) { fx(Particle.POOF, obj[i].getLocation(), 15, 0.1); rm(i); fEnd[i] = 0; }
                    continue;
                }
                if (out[i]) continue;
                double ex = Math.min(1.0, (t - landT[i]) / 24.0);
                double a = i * Math.PI * 2 / 8 + theta;
                double r = (i == win) ? R * ex * (1 - cf) : R * ex;
                double y = 1.4 + 0.2 * ex + 0.3 * Math.sin(t * 0.12 + i) + (i == win ? 1.3 * cf : 0);
                Location l = base.clone().add(r * Math.cos(a), y, r * Math.sin(a));
                obj[i].teleport(l);
                tf((Display) obj[i], (float) (0.9 + (i == win ? 1.7 * cf : 0)), t * 0.12 + i);
                if (lab[i] != null) lab[i].teleport(l.clone().add(0, 0.9 + (i == win ? 1.1 * cf : 0), 0));
            }
            if (t >= cs && t < cs + 30 && t % 2 == 0) fx(Particle.END_ROD, base.clone().add(0, 2.7, 0), 6, 0.05);
            if (t == cs + 30) reveal();
        }

        // ═════════ 2: МЕЧІ — треба вдарити по мечу ═════════
        void pickSwords() {
            final int L0 = 15, GAP = 6, FL = 22;
            hbY = 0;
            Location S = base.clone().add(0, 1.3, 0);
            for (int i = 0; i < 8; i++) {
                int ls = L0 + i * GAP;
                if (t == ls) {
                    obj[i] = item(S, new ItemStack(swordMats.get(i)), 1.6f, Display.Billboard.VERTICAL);
                    snd(Sound.ITEM_TRIDENT_THROW, 1.5f, 0.9f + i * 0.05f);
                }
                if (t > ls && t <= ls + FL && obj[i] != null) {
                    double f = (double) (t - ls) / FL;
                    Location T = pos(i, 0.65);
                    Location cur = S.clone().add((T.getX() - S.getX()) * f,
                            (T.getY() - S.getY()) * f + 3.4 * Math.sin(Math.PI * f),
                            (T.getZ() - S.getZ()) * f);
                    obj[i].teleport(cur);
                    tf((Display) obj[i], 1.6f, PD - (1 - f) * 4 * Math.PI);
                    fx(Particle.CRIT, cur, 3, 0.02);
                    if (t == ls + FL) {
                        snd(Sound.ITEM_TRIDENT_HIT_GROUND, 1.5f, 0.8f + i * 0.04f);
                        fx(Particle.POOF, pos(i, 0.15), 12, 0.05);
                        fx(Particle.CLOUD, pos(i, 0.15), 6, 0.02);
                        lab[i] = text(pos(i, 2.7), mystery(), 2.2f);
                    }
                }
            }
            if (t == L0 + 7 * GAP + FL) arrivedAt = t;
        }

        // ═════════ 3: МОБИ-СТАТУЇ — треба вдарити по мобу ═════════
        void pickMobs() {
            for (int i = 0; i < 8; i++) {
                if (t == 10 + i * 9) {
                    Location l = pos(i, 0);
                    l.setDirection(base.toVector().subtract(l.toVector()));
                    Entity e = l.getWorld().spawnEntity(l, mobTypes.get(i));
                    track(e);
                    if (e instanceof LivingEntity le) {
                        le.setAI(false);
                        le.setSilent(true);
                        le.setPersistent(false);
                        le.setRemoveWhenFarAway(true);
                        le.setCollidable(false);
                        le.setCanPickupItems(false);
                    }
                    obj[i] = e;
                    hb[i] = e;
                    hitOwner.put(e.getUniqueId(), this);
                    lab[i] = text(l.clone().add(0, e.getHeight() + 0.7, 0), mystery(), 2.2f);
                    snd(Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 1f);
                    fx(Particle.POOF, l.clone().add(0, 1, 0), 25, 0.08);
                }
            }
            if (t == 10 + 7 * 9 + 8) arrivedAt = t;
        }

        // ═════════ 4: СУНДУКИ — треба вдарити по сундуку ═════════
        void pickChests() {
            final int ST = 8, GAP = 6, FD = 18;
            hbY = 0;
            for (int i = 0; i < 8; i++) {
                int st = ST + i * GAP;
                Location to = pos(i, 0.8);
                if (t == st) {
                    obj[i] = item(to.clone().add(0, 9, 0), new ItemStack(Material.CHEST), 1.6f, Display.Billboard.FIXED);
                    snd(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 0.8f + i * 0.05f);
                }
                if (t > st && t <= st + FD && obj[i] != null && landT[i] == 0) {
                    double f = (double) (t - st) / FD;
                    Location cur = to.clone();
                    cur.setY(to.getY() + 9 * (1 - f * f));
                    obj[i].teleport(cur);
                    fx(Particle.CLOUD, cur, 2, 0.01);
                    if (t == st + FD) {
                        landT[i] = t;
                        snd(Sound.BLOCK_CHEST_CLOSE, 1.5f, 0.8f);
                        fx(Particle.POOF, pos(i, 0.2), 16, 0.06);
                        lab[i] = text(pos(i, 2.4), mystery(), 2.2f);
                    }
                }
            }
            if (t == ST + 7 * GAP + FD) arrivedAt = t;
        }

        // ═════════ 5: КРИСТАЛИ — треба вдарити по кристалу ═════════
        void pickCrystals() {
            final int ST = 10, GAP = 5, FL = 20;
            hbY = 0.9;
            Location S = base.clone().add(0, 1.3, 0);
            for (int i = 0; i < 8; i++) {
                int st = ST + i * GAP;
                if (t == st) {
                    obj[i] = item(S, new ItemStack(Material.END_CRYSTAL), 1.3f, Display.Billboard.CENTER);
                    snd(Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, 0.6f + i * 0.12f);
                }
                if (t > st && t <= st + FL && obj[i] != null) {
                    double f = (double) (t - st) / FL;
                    Location T = pos(i, 1.7);
                    Location cur = S.clone().add((T.getX() - S.getX()) * f,
                            (T.getY() - S.getY()) * f + 2.0 * Math.sin(Math.PI * f),
                            (T.getZ() - S.getZ()) * f);
                    obj[i].teleport(cur);
                    tf((Display) obj[i], 1.3f, t * 0.2);
                    fx(Particle.END_ROD, cur, 2, 0.01);
                    if (t == st + FL) {
                        landT[i] = t;
                        fx(Particle.POOF, T, 10, 0.05);
                        lab[i] = text(pos(i, 3.0), mystery(), 2.2f);
                    }
                } else if (t > st + FL && obj[i] != null && landT[i] > 0 && !out[i]) {
                    Location T = pos(i, 1.7 + 0.12 * Math.sin(t * 0.1 + i));
                    obj[i].teleport(T);
                    tf((Display) obj[i], 1.3f, t * 0.05 + i);
                }
            }
            if (t == ST + 7 * GAP + FL) arrivedAt = t;
        }

        // ═════════ спільна логіка «ударь і відкрий» ═════════
        void onHit(Player pl, Entity ent) {
            if (anim < 2 || arrivedAt < 0 || t < arrivedAt || pickT >= 0) return;
            if (!pl.getUniqueId().equals(p.getUniqueId())) { pl.sendMessage(legacy("&cЦе не твій кейс.")); return; }
            for (int i = 0; i < 8; i++)
                if (hb[i] != null && hb[i].getUniqueId().equals(ent.getUniqueId())) { ch = i; pickT = t; return; }
        }

        void pickFlow() {
            if (arrivedAt < 0 || t < arrivedAt) return;
            if (t == arrivedAt) {
                if (anim != 3) for (int i = 0; i < 8; i++) hb[i] = stand(pos(i, hbY));
                p.showTitle(Title.title(Component.text(" "),
                        legacy("&e&lВдар по " + PICK_WHAT[anim - 2] + "!"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(400))));
                snd(Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 1.6f);
            }
            if (pickT < 0) {
                if ((t - arrivedAt) % 40 == 0)
                    p.sendActionBar(legacy("&e⚔ Вдар по " + PICK_WHAT[anim - 2] + ", щоб відкрити!"));
                if (t - arrivedAt > timeout) {
                    ch = rnd.nextInt(8);
                    pickT = t;
                    p.sendMessage(legacy("&7Час вийшов — вибір зроблено за тебе."));
                }
                return;
            }
            int pt = t - pickT;
            if (pt == 0) {
                if (ch != prizeSlot) { Reward tmp = slot[ch]; slot[ch] = slot[prizeSlot]; slot[prizeSlot] = tmp; }
                win = ch;
                others.clear();
                for (int i = 0; i < 8; i++) if (i != win) others.add(i);
                Collections.shuffle(others, rnd);
                chosenEffect();
            }
            if (pt == 8) {
                for (int j : others) {
                    if (lab[j] != null) lab[j].text(slot[j].title());
                    if (lab[j] != null) lab[j].setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.1f, 1.1f, 1.1f), new Quaternionf()));
                    glow(j, true);
                }
                snd(Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, 1.2f);
            }
            if (pt == 14) {
                dropTo = pos(win, 0);
                dropTo.setY(base.getY() + 2.9);
                if (lab[win] != null) { lab[win].remove(); lab[win] = null; }
                drop = item(dropTo.clone().add(0, 9, 0), new ItemStack(prize.icon), 1.6f, Display.Billboard.CENTER);
                drop.setGlowing(true);
                ((Display) drop).setGlowColorOverride(prize.bc());
                snd(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2f, 0.7f);
            }
            if (pt > 14 && pt <= 38 && drop != null) {
                double f = (pt - 14) / 24.0;
                Location cur = dropTo.clone();
                cur.setY(dropTo.getY() + 9 * (1 - f * f));
                drop.teleport(cur);
                tf((Display) drop, 1.6f, pt * 0.25);
                fx(Particle.END_ROD, cur, 3, 0.01);
            }
            if (pt == 38) {
                fx(Particle.EXPLOSION, dropTo, 1, 0);
                fx(Particle.POOF, dropTo, 30, 0.12);
                fx(Particle.TOTEM_OF_UNDYING, dropTo, 40, 0.3);
                snd(Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.4f);
                text(dropTo.clone().add(0, 1.2, 0), prize.title(), 1.8f);
                reveal();
            }
            if (pt > 38 && drop != null && drop.isValid()) {
                drop.teleport(dropTo.clone().add(0, 0.15 * Math.sin(t * 0.15), 0));
                tf((Display) drop, 1.6f, t * 0.1);
            }
            if (pt >= 60 && (pt - 60) % 3 == 0) {
                int k = (pt - 60) / 3;
                if (k < others.size()) {
                    int j = others.get(k);
                    Location l = obj[j] != null ? obj[j].getLocation() : pos(j, 1);
                    fx(Particle.POOF, l.clone().add(0, 0.8, 0), 18, 0.08);
                    if (anim == 3) base.getWorld().strikeLightningEffect(pos(j, 0));
                    out[j] = true;
                    rm(j);
                    if (anim == 3 && obj[j] != null) obj[j].remove();
                }
            }
        }

        void chosenEffect() {
            Location l = pos(ch, 1);
            switch (anim) {
                case 2 -> {
                    glow(ch, true);
                    if (obj[ch] != null) tf((Display) obj[ch], 2.0f, PD);
                    snd(Sound.ITEM_TRIDENT_RETURN, 2f, 1f);
                    fx(Particle.END_ROD, l, 30, 0.1);
                }
                case 3 -> {
                    base.getWorld().strikeLightningEffect(pos(ch, 0));
                    if (obj[ch] != null) { obj[ch].setVelocity(new Vector(0, 0.55, 0)); obj[ch].setGlowing(true); }
                    fx(Particle.TOTEM_OF_UNDYING, l, 40, 0.3);
                }
                case 4 -> {
                    snd(Sound.BLOCK_CHEST_OPEN, 2f, 0.9f);
                    if (obj[ch] != null) tf((Display) obj[ch], 2.0f, 0);
                    fx(Particle.ENCHANT, l, 60, 0.6);
                }
                default -> {
                    snd(Sound.BLOCK_GLASS_BREAK, 2f, 0.8f);
                    fx(Particle.EXPLOSION, pos(ch, 1.7), 1, 0);
                    fx(Particle.POOF, pos(ch, 1.7), 25, 0.1);
                    rmObj(ch);
                }
            }
        }

        // ── фінал ──
        void reveal() {
            if (revealed) return;
            revealed = true;
            revT = t;
            fwLeft = 2 + prize.tier / 2;
            text(base.clone().add(0, 5.0, 0),
                    prize.title().append(Component.newline()).append(Component.text("Привілегія", NamedTextColor.GRAY)), 3.4f);
            firework(base.clone().add(0, 2.5, 0), prize.bc());
            snd(Sound.UI_TOAST_CHALLENGE_COMPLETE, 2f, 1f);
            snd(Sound.ENTITY_PLAYER_LEVELUP, 2f, 1.2f);
            p.showTitle(Title.title(prize.title(), Component.text("Привілегія", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3000), Duration.ofMillis(600))));
            giveReward();
            endAt = t + 90;
        }

        void giveReward() {
            if (rewardGiven) return;
            rewardGiven = true;
            for (String c : prize.commands)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
            p.sendMessage(legacy("&6&l✦ InterTime &8» &fТи отримав привілегію ").append(prize.title()));
            if (prize.tier >= 6) {
                Bukkit.broadcast(legacy("&6&l✦ InterTime &8» &e" + p.getName() + " &fвідкрив ").append(legacy(caseName))
                        .append(legacy(" &fі виграв привілегію ")).append(prize.title()));
            }
        }

        void forceFinish(boolean give) {
            if (give) giveReward();
            finish();
        }

        void finish() {
            if (finished) return;
            finished = true;
            try { cancel(); } catch (IllegalStateException ignored) { }
            for (Entity e : all) { hitOwner.remove(e.getUniqueId()); if (e.isValid()) e.remove(); }
            shows.remove(p.getUniqueId());
            busy.remove(bkey);
        }
    }
}
