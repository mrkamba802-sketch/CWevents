package me.clacyworld.cwevents;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class AirConvoyEvent implements Listener {
    private enum Stage { IDLE, COUNTDOWN, GUARDS, BOSS, LOOT }

    private final CWevents plugin;
    private final Random random = new Random();
    private final Set<UUID> eventMobs = new HashSet<>();
    private final List<Location> modifiedBlocks = new ArrayList<>();
    private final Map<UUID, Double> playerDamage = new HashMap<>();

    private Stage stage = Stage.IDLE;
    private boolean running;
    private World world;
    private Location center;
    private Location chestLocation;
    private UUID chestOwner;
    private boolean chestUnlocked;
    private LivingEntity boss;
    private int waveIndex;
    private int secondsRemaining;
    private BukkitTask countdownTask;
    private BukkitTask durationTask;
    private BukkitTask phaseTask;
    private BossBar bossBar;

    public AirConvoyEvent(CWevents plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running;
    }

    public String getStatus() {
        if (!running) {
            return "не запущен";
        }
        String place = center == null ? "" : " | " + center.getWorld().getName()
                + " " + center.getBlockX() + " " + center.getBlockY() + " " + center.getBlockZ();
        return switch (stage) {
            case COUNTDOWN -> "подготовка, запуск через " + secondsRemaining + " сек." + place;
            case GUARDS -> "идёт штурм охраны, волна " + waveIndex + place;
            case BOSS -> "бой с капитаном конвоя" + place;
            case LOOT -> chestOwner == null ? "сундук открыт для захвата" + place
                    : "груз забрал " + Bukkit.getOfflinePlayer(chestOwner).getName() + place;
            default -> "не запущен";
        };
    }

    public boolean isInsideEvent(Location location) {
        if (!running || center == null || location == null || location.getWorld() == null
                || !location.getWorld().equals(world)) {
            return false;
        }
        double horizontal = plugin.getConfig().getDouble("protection.horizontal-radius", 28.0);
        double vertical = plugin.getConfig().getDouble("protection.vertical-radius", 20.0);
        return Math.abs(location.getX() - center.getX()) <= horizontal
                && Math.abs(location.getZ() - center.getZ()) <= horizontal
                && Math.abs(location.getY() - center.getY()) <= vertical;
    }

    public boolean start(boolean automatic) {
        if (running) {
            return false;
        }

        Location spawn = findSafeSpawn();
        if (spawn == null) {
            plugin.getLogger().warning("Could not find a clear location for the Air Convoy.");
            return false;
        }

        center = spawn;
        world = spawn.getWorld();
        chestLocation = spawn.clone().add(0, 1, 0);
        modifiedBlocks.clear();
        eventMobs.clear();
        playerDamage.clear();
        chestOwner = null;
        chestUnlocked = false;
        boss = null;
        waveIndex = 0;
        running = true;
        stage = Stage.COUNTDOWN;

        buildAirship();
        plugin.persistBlocks(modifiedBlocks);
        createBossBar();

        String name = plugin.getConfig().getString("event.name", "Воздушный конвой");
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6CWevents&8] ");
        String coordinates = plugin.getConfig().getBoolean("event.show-coordinates", true)
                ? " &7Координаты: &f" + world.getName() + " " + center.getBlockX()
                + " " + center.getBlockY() + " " + center.getBlockZ()
                : "";
        Bukkit.broadcastMessage(plugin.color(prefix + "&6☁ &eОбнаружен " + name + "!"
                + " &7Найдите воздушный корабль и отбейте груз." + coordinates));
        if (automatic) {
            plugin.getLogger().info("Automatic Air Convoy event started at " + center);
        }

        secondsRemaining = Math.max(5, plugin.getConfig().getInt("event.countdown-seconds", 45));
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running || stage != Stage.COUNTDOWN) {
                cancelTask(countdownTask);
                return;
            }
            if (secondsRemaining <= 0) {
                cancelTask(countdownTask);
                countdownTask = null;
                beginCombat();
                return;
            }
            if (secondsRemaining == 30 || secondsRemaining == 15
                    || secondsRemaining == 10 || secondsRemaining <= 5) {
                Bukkit.broadcastMessage(plugin.color(prefix + "&eОхрана конвоя активируется через &6"
                        + secondsRemaining + " &eсекунд!"));
            }
            secondsRemaining--;
            refreshBossBar();
        }, 20L, 20L);
        return true;
    }

    private Location findSafeSpawn() {
        String worldName = plugin.getConfig().getString("spawn.world", "world");
        World targetWorld = Bukkit.getWorld(worldName);
        if (targetWorld == null && !Bukkit.getWorlds().isEmpty()) {
            targetWorld = Bukkit.getWorlds().get(0);
            plugin.getLogger().warning("Configured world '" + worldName
                    + "' was not found. Using '" + targetWorld.getName() + "'.");
        }
        if (targetWorld == null) {
            return null;
        }

        String mode = plugin.getConfig().getString("spawn.mode", "RANDOM").toUpperCase(Locale.ROOT);
        int y = plugin.getConfig().getInt("spawn.y", 170);
        if (y - 2 <= targetWorld.getMinHeight() || y + 11 >= targetWorld.getMaxHeight()) {
            plugin.getLogger().warning("spawn.y is too close to the world height limit.");
            return null;
        }

        int attempts = Math.max(1, plugin.getConfig().getInt("spawn.safe-search-attempts", 30));
        int minX = plugin.getConfig().getInt("spawn.min-x", -5000);
        int maxX = plugin.getConfig().getInt("spawn.max-x", 5000);
        int minZ = plugin.getConfig().getInt("spawn.min-z", -5000);
        int maxZ = plugin.getConfig().getInt("spawn.max-z", 5000);
        if (minX > maxX) { int swap = minX; minX = maxX; maxX = swap; }
        if (minZ > maxZ) { int swap = minZ; minZ = maxZ; maxZ = swap; }

        for (int attempt = 0; attempt < attempts; attempt++) {
            int x;
            int z;
            if (mode.equals("FIXED")) {
                x = plugin.getConfig().getInt("spawn.fixed-x", 0);
                z = plugin.getConfig().getInt("spawn.fixed-z", 0);
            } else {
                x = minX + random.nextInt(maxX - minX + 1);
                z = minZ + random.nextInt(maxZ - minZ + 1);
            }
            targetWorld.getChunkAt(x >> 4, z >> 4).load();
            Location candidate = new Location(targetWorld, x + 0.5, y, z + 0.5);
            if (canBuildAt(candidate)) {
                return candidate;
            }
            if (mode.equals("FIXED")) {
                break;
            }
        }
        return null;
    }

    private boolean canBuildAt(Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        for (int bx = x - 8; bx <= x + 8; bx++) {
            for (int by = y - 2; by <= y + 11; by++) {
                for (int bz = z - 7; bz <= z + 7; bz++) {
                    if (!worldBlockIsAir(location.getWorld(), bx, by, bz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean worldBlockIsAir(World targetWorld, int x, int y, int z) {
        return targetWorld.getBlockAt(x, y, z).getType().isAir();
    }

    private void buildAirship() {
        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();

        // Main wooden deck.
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                place(x + dx, y, z + dz, Material.SPRUCE_PLANKS);
            }
        }

        // Dark hull trim and guard rails.
        for (int dx = -7; dx <= 7; dx++) {
            place(x + dx, y - 1, z - 3, Material.DARK_OAK_PLANKS);
            place(x + dx, y - 1, z + 3, Material.DARK_OAK_PLANKS);
            place(x + dx, y + 1, z - 4, Material.SPRUCE_FENCE);
            place(x + dx, y + 1, z + 4, Material.SPRUCE_FENCE);
        }
        for (int dz = -3; dz <= 3; dz++) {
            place(x - 8, y - 1, z + dz, Material.DARK_OAK_PLANKS);
            place(x + 8, y - 1, z + dz, Material.DARK_OAK_PLANKS);
            place(x - 8, y + 1, z + dz, Material.SPRUCE_FENCE);
            place(x + 8, y + 1, z + dz, Material.SPRUCE_FENCE);
        }

        // Balloon envelope.
        int balloonY = y + 8;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    double shape = (dx * dx) / 25.0 + (dy * dy) / 9.0 + (dz * dz) / 9.0;
                    if (shape <= 1.0 && shape >= 0.62) {
                        Material wool = ((dx + dy + dz) & 1) == 0
                                ? Material.WHITE_WOOL : Material.LIGHT_GRAY_WOOL;
                        place(x + dx, balloonY + dy, z + dz, wool);
                    }
                }
            }
        }

        // Ropes, mast and a small signal flag.
        for (int ropeY = y + 2; ropeY <= y + 5; ropeY++) {
            place(x - 2, ropeY, z - 1, Material.CHAIN);
            place(x + 2, ropeY, z - 1, Material.CHAIN);
            place(x - 2, ropeY, z + 1, Material.CHAIN);
            place(x + 2, ropeY, z + 1, Material.CHAIN);
        }
        for (int mastY = y + 1; mastY <= y + 5; mastY++) {
            place(x - 5, mastY, z, Material.SPRUCE_FENCE);
        }
        place(x - 5, y + 5, z + 1, Material.RED_WOOL);
        place(x - 5, y + 5, z + 2, Material.RED_WOOL);

        // The reward chest starts locked until the boss is defeated.
        place(x, y + 1, z, Material.CHEST);
    }

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (!block.getType().isAir()) {
            return;
        }
        block.setType(material, false);
        modifiedBlocks.add(block.getLocation());
    }

    private void beginCombat() {
        if (!running) {
            return;
        }
        stage = Stage.GUARDS;
        secondsRemaining = Math.max(60, plugin.getConfig().getInt("event.duration-seconds", 1200));
        durationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running) {
                cancelTask(durationTask);
                return;
            }
            secondsRemaining--;
            if (secondsRemaining <= 0) {
                finish(true, true, "время события истекло");
            } else {
                refreshBossBar();
            }
        }, 20L, 20L);
        Bukkit.broadcastMessage(plugin.color(plugin.getConfig().getString("messages.prefix", "&8[&6CWevents&8] ")
                + "&cОхрана конвоя активирована! &eПробейтесь к грузу."));
        spawnNextWave();
    }

    private void spawnNextWave() {
        if (!running || stage != Stage.GUARDS) {
            return;
        }
        List<Map<?, ?>> waves = plugin.getConfig().getMapList("guards.waves");
        if (waveIndex >= waves.size()) {
            spawnBoss();
            return;
        }

        Map<?, ?> wave = waves.get(waveIndex);
        waveIndex++;
        EntityType type = parseEntityType(value(wave, "type", "PILLAGER"), EntityType.PILLAGER);
        int amount = Math.max(0, number(wave.get("amount"), 4));
        double health = Math.max(1.0, decimal(wave.get("health"), 30.0));
        double damage = Math.max(0.0, decimal(wave.get("damage"), 5.0));
        String name = value(wave, "name", "&cОхрана конвоя");

        for (int i = 0; i < amount; i++) {
            spawnMob(type, randomDeckLocation(), health, damage, name);
        }
        refreshBossBar();
        if (eventMobs.isEmpty()) {
            scheduleNextWave();
        }
    }

    private void spawnBoss() {
        if (!running) {
            return;
        }
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("guards.boss");
        String typeName = section == null ? "RAVAGER" : section.getString("type", "RAVAGER");
        double health = section == null ? 260.0 : section.getDouble("health", 260.0);
        double damage = section == null ? 18.0 : section.getDouble("damage", 18.0);
        String name = section == null ? "&6Капитан воздушного конвоя"
                : section.getString("name", "&6Капитан воздушного конвоя");

        boss = spawnMob(parseEntityType(typeName, EntityType.RAVAGER),
                center.clone().add(0.5, 1, 3.5), Math.max(1.0, health),
                Math.max(0.0, damage), name);
        stage = Stage.BOSS;
        Bukkit.broadcastMessage(plugin.color(plugin.getConfig().getString("messages.prefix", "&8[&6CWevents&8] ")
                + "&4Появился капитан конвоя! &cПобедите босса, чтобы открыть груз."));
        refreshBossBar();
        if (boss == null) {
            unlockChest();
        }
    }

    private LivingEntity spawnMob(EntityType type, Location location, double health,
                                  double damage, String name) {
        Entity entity = world.spawnEntity(location, type);
        if (!(entity instanceof LivingEntity living)) {
            entity.remove();
            return null;
        }
        AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(Math.min(2048.0, health));
            living.setHealth(Math.min(maxHealth.getValue(), health));
        }
        AttributeInstance attackDamage = living.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attackDamage != null) {
            attackDamage.setBaseValue(damage);
        }
        living.setCustomName(plugin.color(name));
        living.setCustomNameVisible(true);
        living.setPersistent(true);
        living.setRemoveWhenFarAway(false);
        living.setCanPickupItems(false);
        living.getPersistentDataContainer().set(plugin.getMobKey(), PersistentDataType.BYTE, (byte) 1);
        eventMobs.add(living.getUniqueId());

        Player nearest = nearestPlayer(location, 32.0);
        if (nearest != null && living instanceof Mob mob) {
            mob.setTarget(nearest);
        }
        return living;
    }

    private Location randomDeckLocation() {
        int radius = Math.max(1, plugin.getConfig().getInt("guards.spawn-radius", 6));
        int dx = random.nextInt(radius * 2 + 1) - radius;
        int dz = random.nextInt(7) - 3;
        return new Location(world, center.getBlockX() + dx + 0.5,
                center.getBlockY() + 1, center.getBlockZ() + dz + 0.5);
    }

    private Player nearestPlayer(Location location, double maxDistance) {
        Player nearest = null;
        double best = maxDistance * maxDistance;
        for (Player player : world.getPlayers()) {
            if (!isInsideEvent(player.getLocation())) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private void scheduleNextWave() {
        if (phaseTask != null) {
            phaseTask.cancel();
        }
        long delay = Math.max(1, plugin.getConfig().getInt("guards.wave-delay-seconds", 4)) * 20L;
        phaseTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            phaseTask = null;
            if (running && stage == Stage.GUARDS && eventMobs.isEmpty()) {
                spawnNextWave();
            }
        }, delay);
    }

    private void unlockChest() {
        if (!running || chestUnlocked) {
            return;
        }
        chestUnlocked = true;
        stage = Stage.LOOT;
        fillRewardChest();
        Bukkit.broadcastMessage(plugin.color(plugin.getConfig().getString("messages.prefix", "&8[&6CWevents&8] ")
                + "&aКапитан повержен! &eГруз разблокирован — первым откройте сундук, чтобы забрать конвой."));
        refreshBossBar();
    }

    private void fillRewardChest() {
        Block block = chestLocation.getBlock();
        if (block.getType() != Material.CHEST) {
            block.setType(Material.CHEST, false);
            modifiedBlocks.add(block.getLocation());
            plugin.persistBlocks(modifiedBlocks);
        }
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        Inventory inventory = chest.getBlockInventory();
        inventory.clear();

        List<Map<?, ?>> rewards = plugin.getConfig().getMapList("rewards.items");
        int rolls = Math.max(0, Math.min(27, plugin.getConfig().getInt("rewards.chest-rolls", 12)));
        for (int i = 0; i < rolls; i++) {
            Map<?, ?> reward = chooseWeightedReward(rewards);
            if (reward == null) {
                inventory.addItem(new ItemStack(Material.DIAMOND, 1));
                continue;
            }
            Material material = Material.matchMaterial(value(reward, "material", "DIAMOND"));
            if (material == null || !material.isItem() || material == Material.AIR) {
                continue;
            }
            int min = Math.max(1, number(reward.get("min"), 1));
            int max = Math.max(min, number(reward.get("max"), min));
            int amount = Math.min(64, min + random.nextInt(max - min + 1));
            ItemStack item = new ItemStack(material, amount);
            String displayName = value(reward, "name", "");
            if (!displayName.isBlank()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(plugin.color(displayName));
                    item.setItemMeta(meta);
                }
            }
            inventory.addItem(item);
        }
    }

    private Map<?, ?> chooseWeightedReward(List<Map<?, ?>> rewards) {
        int total = 0;
        for (Map<?, ?> reward : rewards) {
            total += Math.max(0, number(reward.get("weight"), 1));
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        int cursor = 0;
        for (Map<?, ?> reward : rewards) {
            cursor += Math.max(0, number(reward.get("weight"), 1));
            if (roll < cursor) {
                return reward;
            }
        }
        return rewards.isEmpty() ? null : rewards.get(rewards.size() - 1);
    }

    private EntityType parseEntityType(String name, EntityType fallback) {
        try {
            EntityType type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
            return type.isAlive() ? type : fallback;
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid mob type '" + name + "' in config; using " + fallback + ".");
            return fallback;
        }
    }

    private String value(Map<?, ?> map, String key, String fallback) {
        Object object = map.get(key);
        return object == null ? fallback : String.valueOf(object);
    }

    private int number(Object object, int fallback) {
        return object instanceof Number n ? n.intValue() : fallback;
    }

    private double decimal(Object object, double fallback) {
        return object instanceof Number n ? n.doubleValue() : fallback;
    }

    private void createBossBar() {
        bossBar = Bukkit.createBossBar(plugin.color("&6Воздушный конвой &7| &eПодготовка"),
                BarColor.YELLOW, BarStyle.SEGMENTED_10);
        bossBar.setVisible(true);
        for (Player player : world.getPlayers()) {
            if (isInsideEvent(player.getLocation())) {
                bossBar.addPlayer(player);
            }
        }
    }

    private void refreshBossBar() {
        if (bossBar == null) {
            return;
        }
        String title;
        double progress = 1.0;
        if (stage == Stage.COUNTDOWN) {
            title = "&6Воздушный конвой &7| &eСтарт через " + secondsRemaining + " сек.";
        } else if (stage == Stage.GUARDS) {
            List<Map<?, ?>> waves = plugin.getConfig().getMapList("guards.waves");
            title = "&6Воздушный конвой &7| &cВолна " + waveIndex + "/" + waves.size();
        } else if (stage == Stage.BOSS && boss != null && boss.isValid()) {
            title = "&4Капитан конвоя &7| &c" + (int) Math.ceil(boss.getHealth())
                    + "/" + (int) Math.ceil(boss.getAttribute(Attribute.MAX_HEALTH).getValue()) + " HP";
            progress = boss.getHealth() / Math.max(1.0, boss.getAttribute(Attribute.MAX_HEALTH).getValue());
        } else if (stage == Stage.LOOT) {
            title = chestOwner == null ? "&aГруз разблокирован &7| &eОткройте сундук первым"
                    : "&aГруз захвачен &7| &f" + Bukkit.getOfflinePlayer(chestOwner).getName();
        } else {
            title = "&6Воздушный конвой";
        }
        bossBar.setTitle(plugin.color(title));
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    private void claimChest(Player player) {
        if (!chestUnlocked) {
            return;
        }
        if (chestOwner == null) {
            chestOwner = player.getUniqueId();
            refreshBossBar();
            executeCommands("rewards.winner-commands", player, playerDamage.getOrDefault(player.getUniqueId(), 0.0));
            String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6CWevents&8] ");
            Bukkit.broadcastMessage(plugin.color(prefix + "&6" + player.getName()
                    + " &eпервым добрался до груза и захватил воздушный конвой!"));
            int cleanup = Math.max(15, plugin.getConfig().getInt("event.claim-cleanup-seconds", 180));
            secondsRemaining = Math.min(secondsRemaining, cleanup);
        } else if (!chestOwner.equals(player.getUniqueId())) {
            player.sendMessage(plugin.message("chest-owned"));
        }
    }

    private void executeCommands(String path, Player player, double damage) {
        for (String raw : plugin.getConfig().getStringList(path)) {
            String command = raw.replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .replace("%damage%", String.format(Locale.US, "%.1f", damage))
                    .replace("%event%", "airconvoy");
            if (!command.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        }
    }

    private void giveParticipationRewards() {
        for (Map.Entry<UUID, Double> entry : playerDamage.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && entry.getValue() > 0.0) {
                executeCommands("rewards.participation-commands", player, entry.getValue());
            }
        }
    }

    private void finish(boolean giveParticipation, boolean announce, String reason) {
        if (!running) {
            return;
        }
        if (giveParticipation) {
            giveParticipationRewards();
        }
        cancelTask(countdownTask);
        cancelTask(durationTask);
        cancelTask(phaseTask);
        countdownTask = null;
        durationTask = null;
        phaseTask = null;

        for (World loadedWorld : Bukkit.getWorlds()) {
            for (Entity entity : loadedWorld.getEntities()) {
                if (entity.getPersistentDataContainer().has(plugin.getMobKey(), PersistentDataType.BYTE)) {
                    entity.remove();
                }
            }
        }
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
            bossBar = null;
        }

        for (Location location : modifiedBlocks) {
            if (location.getWorld() != null
                    && location.getBlock().getType() != Material.AIR) {
                location.getBlock().setType(Material.AIR, false);
            }
        }
        plugin.clearPersistedBlocks();

        if (announce) {
            String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6CWevents&8] ");
            Bukkit.broadcastMessage(plugin.color(prefix + "&7Воздушный конвой завершён: &f" + reason + "&7."));
        }

        eventMobs.clear();
        modifiedBlocks.clear();
        playerDamage.clear();
        stage = Stage.IDLE;
        running = false;
        world = null;
        center = null;
        chestLocation = null;
        chestOwner = null;
        chestUnlocked = false;
        boss = null;
    }

    public void stopManually() {
        finish(false, true, "остановлен администрацией");
    }

    public void shutdown() {
        finish(false, false, "server shutdown");
    }

    public void removeStaleMobs() {
        for (World loadedWorld : Bukkit.getWorlds()) {
            for (Entity entity : loadedWorld.getEntities()) {
                if (entity.getPersistentDataContainer().has(plugin.getMobKey(), PersistentDataType.BYTE)) {
                    entity.remove();
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Also remove tagged guards from chunks that were not loaded during plugin startup.
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity.getPersistentDataContainer().has(plugin.getMobKey(), PersistentDataType.BYTE)) {
                entity.remove();
            }
        }
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private boolean isEventChest(Block block) {
        return chestLocation != null && block != null
                && block.getWorld().equals(chestLocation.getWorld())
                && block.getX() == chestLocation.getBlockX()
                && block.getY() == chestLocation.getBlockY()
                && block.getZ() == chestLocation.getBlockZ();
    }

    private boolean isEventChestInventory(Inventory inventory) {
        if (inventory == null || chestLocation == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            return isEventChest(chest.getBlock());
        }
        return false;
    }

    private Player getAttackingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    // Track damage for participation rewards and the final scoreboard.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDamage(EntityDamageByEntityEvent event) {
        if (!running || !(event.getEntity() instanceof LivingEntity target)
                || !eventMobs.contains(target.getUniqueId())) {
            return;
        }
        Player attacker = getAttackingPlayer(event.getDamager());
        if (attacker != null) {
            playerDamage.merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
            if (stage == Stage.BOSS && boss != null && target.getUniqueId().equals(boss.getUniqueId())) {
                refreshBossBar();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        if (!event.getEntity().getPersistentDataContainer()
                .has(plugin.getMobKey(), PersistentDataType.BYTE)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        UUID id = event.getEntity().getUniqueId();
        eventMobs.remove(id);

        if (boss != null && id.equals(boss.getUniqueId())) {
            unlockChest();
            return;
        }
        if (running && stage == Stage.GUARDS && eventMobs.isEmpty()) {
            scheduleNextWave();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        if (!running || !eventMobs.contains(event.getEntity().getUniqueId())) {
            return;
        }
        if (event.getTarget() instanceof Player player && !isInsideEvent(player.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!running || event.getTo() == null || event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        boolean wasInside = isInsideEvent(event.getFrom());
        boolean isInside = isInsideEvent(event.getTo());
        if (bossBar != null) {
            if (!wasInside && isInside) {
                bossBar.addPlayer(player);
            } else if (wasInside && !isInside) {
                bossBar.removePlayer(player);
            }
        }
        if (isInside) {
            for (UUID mobId : eventMobs) {
                Entity entity = Bukkit.getEntity(mobId);
                if (entity instanceof Mob mob && mob.getTarget() == null) {
                    mob.setTarget(player);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (bossBar != null) {
            bossBar.removePlayer(event.getPlayer());
        }
    }

    // The event zone is intentionally a protected volume; the reward chest is the only permitted block interaction.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("blocked-action"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("blocked-action"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null || !isInsideEvent(clicked.getLocation())) {
            return;
        }
        if (isEventChest(clicked) && event.getAction().isRightClick()) {
            if (!chestUnlocked) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(plugin.message("chest-locked"));
            }
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.message("blocked-action"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isInsideEvent(event.getBlockClicked().getRelative(event.getBlockFace()).getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isInsideEvent(event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isInsideEvent(event.getLocation())
                || event.blockList().stream().anyMatch(block -> isInsideEvent(block.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())
                || event.getBlocks().stream().anyMatch(block -> isInsideEvent(block.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())
                || event.getBlocks().stream().anyMatch(block -> isInsideEvent(block.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())
                || isInsideEvent(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())
                || isInsideEvent(event.getSource().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isInsideEvent(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (isInsideEvent(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (isInsideEvent(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (isInsideEvent(event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (isInsideEvent(event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isInsideEvent(player.getLocation()) || player.hasPermission("cwevents.admin")) {
            return;
        }
        String raw = event.getMessage().substring(1).trim();
        if (raw.isEmpty()) {
            return;
        }
        String root = raw.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        List<String> whitelist = plugin.getConfig().getStringList("protection.command-whitelist");
        if (!whitelist.contains("*") && !whitelist.stream()
                .map(value -> value.toLowerCase(Locale.ROOT).replaceFirst("^/", ""))
                .anyMatch(root::equals)) {
            event.setCancelled(true);
            player.sendMessage(plugin.message("blocked-command"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("cwevents.admin")) {
            return;
        }
        boolean fromInside = isInsideEvent(event.getFrom());
        boolean toInside = isInsideEvent(event.getTo());
        if (!fromInside && toInside
                && plugin.getConfig().getBoolean("protection.deny-teleport-into-zone", true)) {
            event.setCancelled(true);
        } else if (fromInside && !toInside
                && plugin.getConfig().getBoolean("protection.deny-teleport-out-of-zone", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !isEventChestInventory(event.getInventory())) {
            return;
        }
        if (!chestUnlocked) {
            event.setCancelled(true);
            player.sendMessage(plugin.message("chest-locked"));
            return;
        }
        if (chestOwner == null) {
            claimChest(player);
        } else if (!chestOwner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(plugin.message("chest-owned"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChestClick(InventoryClickEvent event) {
        if (isEventChestInventory(event.getView().getTopInventory())
                && chestOwner != null && !chestOwner.equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChestDrag(InventoryDragEvent event) {
        if (isEventChestInventory(event.getView().getTopInventory())
                && chestOwner != null && !chestOwner.equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (isEventChestInventory(event.getSource()) || isEventChestInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }
}