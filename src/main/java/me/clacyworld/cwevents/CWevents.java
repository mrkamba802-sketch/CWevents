package me.clacyworld.cwevents;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class CWevents extends JavaPlugin {
    private AirConvoyEvent convoy;
    private BukkitTask autoTask;
    private NamespacedKey mobKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mobKey = new NamespacedKey(this, "air_convoy_mob");
        cleanPersistedBlocks();

        convoy = new AirConvoyEvent(this);
        convoy.removeStaleMobs();
        getServer().getPluginManager().registerEvents(convoy, this);

        EventCommand command = new EventCommand(this);
        if (getCommand("cwevents") != null) {
            getCommand("cwevents").setExecutor(command);
            getCommand("cwevents").setTabCompleter(command);
        }

        startAutoScheduler();
        getLogger().info("CWevents enabled. Paper 1.21.4+ / Java 21.");
    }

    @Override
    public void onDisable() {
        if (autoTask != null) {
            autoTask.cancel();
            autoTask = null;
        }
        if (convoy != null) {
            convoy.shutdown();
        }
    }

    public AirConvoyEvent getConvoy() {
        return convoy;
    }

    public NamespacedKey getMobKey() {
        return mobKey;
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public String message(String path) {
        return color(getConfig().getString("messages.prefix", "&8[&6CWevents&8] ")
                + getConfig().getString("messages." + path, ""));
    }

    public void send(CommandSender sender, String path) {
        sender.sendMessage(message(path));
    }

    public void startAutoScheduler() {
        if (autoTask != null) {
            autoTask.cancel();
            autoTask = null;
        }
        if (!getConfig().getBoolean("settings.auto.enabled", true)) {
            return;
        }

        long intervalMinutes = Math.max(1L,
                getConfig().getLong("settings.auto.interval-minutes", 90L));
        long intervalTicks = intervalMinutes * 60L * 20L;

        autoTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (convoy == null || convoy.isRunning()) {
                return;
            }
            double chance = Math.max(0.0, Math.min(100.0,
                    getConfig().getDouble("settings.auto.chance-percent", 35.0)));
            double roll = ThreadLocalRandom.current().nextDouble(100.0);
            if (roll < chance) {
                convoy.start(true);
            } else {
                getLogger().info("Air Convoy auto-start roll failed (" + roll + " >= " + chance + ").");
            }
        }, intervalTicks, intervalTicks);
    }

    public void persistBlocks(List<Location> locations) {
        File file = new File(getDataFolder(), "active.yml");
        YamlConfiguration data = new YamlConfiguration();
        List<String> encoded = new ArrayList<>();
        for (Location location : locations) {
            if (location.getWorld() == null) {
                continue;
            }
            encoded.add(location.getWorld().getName() + ";"
                    + location.getBlockX() + ";"
                    + location.getBlockY() + ";"
                    + location.getBlockZ());
        }
        data.set("blocks", encoded);
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Could not create plugin data folder.");
                return;
            }
            data.save(file);
        } catch (IOException exception) {
            getLogger().severe("Could not persist convoy blocks: " + exception.getMessage());
        }
    }

    public void clearPersistedBlocks() {
        File file = new File(getDataFolder(), "active.yml");
        if (file.exists() && !file.delete()) {
            getLogger().warning("Could not delete active.yml after event cleanup.");
        }
    }

    private void cleanPersistedBlocks() {
        File file = new File(getDataFolder(), "active.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        for (String encoded : data.getStringList("blocks")) {
            String[] parts = encoded.split(";");
            if (parts.length != 4) {
                continue;
            }
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                if (y >= world.getMinHeight() && y < world.getMaxHeight()) {
                    world.getBlockAt(x, y, z).setType(org.bukkit.Material.AIR, false);
                }
            } catch (NumberFormatException ignored) {
                getLogger().warning("Skipped invalid saved block location: " + encoded);
            }
        }
        if (!file.delete()) {
            getLogger().warning("Could not remove stale active.yml.");
        }
    }
}