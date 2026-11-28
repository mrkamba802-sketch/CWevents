package me.clacyworld.cwevents;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EventCommand implements CommandExecutor, TabCompleter {
    private final CWevents plugin;

    public EventCommand(CWevents plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.color("&6CWevents &7— &fВоздушный конвой"));
            sender.sendMessage(plugin.color("&e/cwevents status &7— статус события"));
            if (sender.hasPermission("cwevents.admin")) {
                sender.sendMessage(plugin.color("&e/cwevents start|stop|reload|setlocation"));
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status")) {
            sender.sendMessage(plugin.color("&6[CWevents] &f" + plugin.getConvoy().getStatus()));
            return true;
        }

        if (!sender.hasPermission("cwevents.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }

        switch (sub) {
            case "start" -> {
                if (plugin.getConvoy().isRunning()) {
                    plugin.send(sender, "already-running");
                } else if (plugin.getConvoy().start(false)) {
                    plugin.send(sender, "started");
                } else {
                    sender.sendMessage(plugin.color("&cНе удалось найти безопасное место для конвоя. Проверьте spawn.world, spawn.y и диапазон координат."));
                }
            }
            case "stop" -> {
                if (!plugin.getConvoy().isRunning()) {
                    plugin.send(sender, "not-running");
                } else {
                    plugin.getConvoy().stopManually();
                    plugin.send(sender, "stopped");
                }
            }
            case "reload" -> {
                if (plugin.getConvoy().isRunning()) {
                    plugin.getConvoy().stopManually();
                }
                plugin.reloadConfig();
                plugin.startAutoScheduler();
                plugin.send(sender, "reloaded");
            }
            case "setlocation" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.color("&cЭта команда доступна только игроку."));
                    return true;
                }
                Location location = player.getLocation();
                plugin.getConfig().set("spawn.mode", "FIXED");
                plugin.getConfig().set("spawn.world", location.getWorld().getName());
                plugin.getConfig().set("spawn.fixed-x", location.getBlockX());
                plugin.getConfig().set("spawn.fixed-z", location.getBlockZ());
                plugin.getConfig().set("spawn.y", Math.min(location.getWorld().getMaxHeight() - 15,
                        Math.max(location.getWorld().getMinHeight() + 5, location.getBlockY() + 45)));
                plugin.saveConfig();
                sender.sendMessage(plugin.color("&aФиксированная точка конвоя сохранена. Корабль появится над текущей позицией."));
            }
            default -> sender.sendMessage(plugin.color("&cИспользование: /cwevents <start|stop|status|reload|setlocation>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = sender.hasPermission("cwevents.admin")
                    ? List.of("start", "stop", "status", "reload", "setlocation")
                    : List.of("status");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String option : options) {
                if (option.startsWith(prefix)) {
                    result.add(option);
                }
            }
        }
        return result;
    }
}