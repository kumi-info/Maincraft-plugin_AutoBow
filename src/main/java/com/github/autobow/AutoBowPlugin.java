package com.github.autobow;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class AutoBowPlugin extends JavaPlugin implements Listener, TabCompleter {

    private enum Mode { HOLD, TOGGLE }

    private final Set<UUID> enabledPlayers = new HashSet<>();
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();
    private int intervalTicks = 20;
    private Mode mode = Mode.HOLD;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("autobow").setTabCompleter(this);
        loadConfig();
        getLogger().info("AutoBow enabled. Mode: " + mode + ", Speed: " + intervalTicks);
    }

    @Override
    public void onDisable() {
        activeTasks.values().forEach(BukkitTask::cancel);
        activeTasks.clear();
        savePluginConfig();
    }

    private void loadConfig() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        mode = config.getString("mode", "HOLD").equalsIgnoreCase("TOGGLE") ? Mode.TOGGLE : Mode.HOLD;
        intervalTicks = config.getInt("speed", 20);
        if (intervalTicks < 1) intervalTicks = 1;

        List<String> players = config.getStringList("players");
        for (String uuidStr : players) {
            try {
                enabledPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void savePluginConfig() {
        FileConfiguration config = getConfig();
        config.set("mode", mode.name());
        config.set("speed", intervalTicks);
        config.set("players", enabledPlayers.stream().map(UUID::toString).collect(Collectors.toList()));
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("autobow")) return false;

        if (args.length < 1) {
            sender.sendMessage("§e/autobow <hold|toggle> <speed> §7- Set mode & speed");
            sender.sendMessage("§e/autobow on <player> §7- Enable for player");
            sender.sendMessage("§e/autobow off <player> §7- Disable for player");
            sender.sendMessage("§e/autobow status §7- Show current status");
            sender.sendMessage("§e/autobow list §7- Show enabled players");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "hold", "toggle" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c/autobow <hold|toggle> <speed>");
                    return true;
                }
                try {
                    double speed = Double.parseDouble(args[1]);
                    if (speed < 1) speed = 1;
                    int ticks = (int) Math.round(speed);
                    if (ticks < 1) ticks = 1;
                    intervalTicks = ticks;
                    mode = args[0].equalsIgnoreCase("hold") ? Mode.HOLD : Mode.TOGGLE;
                    stopAllFiring();
                    savePluginConfig();
                    String modeDesc = mode == Mode.HOLD ? "HOLD §7(long press)" : "TOGGLE §7(click to start/stop)";
                    sender.sendMessage("§aMode: " + modeDesc);
                    sender.sendMessage("§aSpeed: " + intervalTicks + " §7(ticks, " + String.format("%.2f", intervalTicks / 20.0) + "s)");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid number: " + args[1]);
                }
            }
            case "on" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c/autobow on <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found: " + args[1]);
                    return true;
                }
                enabledPlayers.add(target.getUniqueId());
                savePluginConfig();
                sender.sendMessage("§aAutoBow enabled for " + target.getName());
            }
            case "off" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c/autobow off <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found: " + args[1]);
                    return true;
                }
                enabledPlayers.remove(target.getUniqueId());
                stopFiring(target.getUniqueId());
                savePluginConfig();
                sender.sendMessage("§cAutoBow disabled for " + target.getName());
            }
            case "status" -> {
                sender.sendMessage("§6§l=== AutoBow Status ===");
                sender.sendMessage("§eMode: §f" + mode.name().toLowerCase());
                sender.sendMessage("§eSpeed: §f" + intervalTicks + " §7(" + String.format("%.2f", intervalTicks / 20.0) + "s)");
                sender.sendMessage("§eEnabled players: §f" + enabledPlayers.size());
                sender.sendMessage("§eCurrently firing: §f" + activeTasks.size());
                if (!enabledPlayers.isEmpty()) {
                    sender.sendMessage("§ePlayers:");
                    for (UUID uuid : enabledPlayers) {
                        Player p = Bukkit.getPlayer(uuid);
                        String name = p != null ? p.getName() : uuid.toString();
                        boolean firing = activeTasks.containsKey(uuid);
                        sender.sendMessage("§7  - " + name + (firing ? " §a[FIRING]" : " §7[IDLE]"));
                    }
                }
            }
            case "list" -> {
                if (enabledPlayers.isEmpty()) {
                    sender.sendMessage("§7No players enabled.");
                } else {
                    sender.sendMessage("§eEnabled players:");
                    for (UUID uuid : enabledPlayers) {
                        Player p = Bukkit.getPlayer(uuid);
                        String name = p != null ? p.getName() : uuid.toString();
                        sender.sendMessage("§7- " + name);
                    }
                }
            }
            default -> sender.sendMessage("§c/autobow <hold|toggle|on|off|status|list>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("autobow")) return null;

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subcommands = List.of("hold", "toggle", "on", "off", "status", "list");
            String prefix = args[0].toLowerCase();
            completions = subcommands.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String prefix = args[1].toLowerCase();
            if (sub.equals("hold") || sub.equals("toggle")) {
                completions = List.of("1", "3", "5", "10", "15", "20").stream()
                        .filter(s -> s.startsWith(prefix))
                        .collect(Collectors.toList());
            } else if (sub.equals("on")) {
                completions = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .collect(Collectors.toList());
            } else if (sub.equals("off")) {
                completions = enabledPlayers.stream()
                        .map(uuid -> Bukkit.getPlayer(uuid))
                        .filter(p -> p != null)
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .collect(Collectors.toList());
            }
        }

        return completions;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!enabledPlayers.contains(player.getUniqueId())) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.BOW && item.getType() != Material.CROSSBOW) return;

        UUID uuid = player.getUniqueId();

        if (mode == Mode.TOGGLE) {
            if (activeTasks.containsKey(uuid)) {
                stopFiring(uuid);
            } else {
                startFiring(player);
            }
        } else {
            if (!activeTasks.containsKey(uuid)) {
                startFiring(player);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopFiring(event.getPlayer().getUniqueId());
    }

    private void startFiring(Player player) {
        UUID uuid = player.getUniqueId();
        forceStartUsingItem(player);
        scheduleFire(uuid);
    }

    private void scheduleFire(UUID uuid) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                stopFiring(uuid);
                return;
            }

            if (!isHoldingBow(p)) {
                stopFiring(uuid);
                return;
            }

            if (!hasArrow(p)) {
                stopFiring(uuid);
                return;
            }

            if (mode == Mode.HOLD && !isRightClickHeld(p)) {
                stopFiring(uuid);
                return;
            }

            Arrow arrow = p.launchProjectile(Arrow.class);
            arrow.setVelocity(p.getLocation().getDirection().multiply(3.0));
            arrow.setPickupStatus(AbstractArrow.PickupStatus.ALLOWED);
            arrow.setShooter(p);
            consumeArrow(p);

            // Release bow, then redraw after a short pause
            p.clearActiveItem();

            int redrawDelay = Math.max(5, intervalTicks / 2);
            BukkitTask redrawTask = Bukkit.getScheduler().runTaskLater(this, () -> {
                Player rp = Bukkit.getPlayer(uuid);
                if (rp == null || !rp.isOnline() || !isHoldingBow(rp)) {
                    stopFiring(uuid);
                    return;
                }
                if (mode == Mode.HOLD && !isRightClickHeld(rp)) {
                    stopFiring(uuid);
                    return;
                }
                forceStartUsingItem(rp);

                int drawTime = Math.max(1, intervalTicks - redrawDelay);
                BukkitTask fireTask = Bukkit.getScheduler().runTaskLater(this, () -> {
                    scheduleFire2(uuid);
                }, drawTime);
                activeTasks.put(uuid, fireTask);
            }, redrawDelay);
            activeTasks.put(uuid, redrawTask);

        }, intervalTicks);

        activeTasks.put(uuid, task);
    }

    private void scheduleFire2(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !p.isOnline() || !isHoldingBow(p) || !hasArrow(p)) {
            stopFiring(uuid);
            return;
        }
        if (mode == Mode.HOLD && !isRightClickHeld(p)) {
            stopFiring(uuid);
            return;
        }

        Arrow arrow = p.launchProjectile(Arrow.class);
        arrow.setVelocity(p.getLocation().getDirection().multiply(3.0));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.ALLOWED);
        arrow.setShooter(p);
        consumeArrow(p);

        p.clearActiveItem();

        int redrawDelay = Math.max(5, intervalTicks / 2);
        BukkitTask redrawTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            Player rp = Bukkit.getPlayer(uuid);
            if (rp == null || !rp.isOnline() || !isHoldingBow(rp)) {
                stopFiring(uuid);
                return;
            }
            if (mode == Mode.HOLD && !isRightClickHeld(rp)) {
                stopFiring(uuid);
                return;
            }
            forceStartUsingItem(rp);

            int drawTime = Math.max(1, intervalTicks - redrawDelay);
            BukkitTask fireTask = Bukkit.getScheduler().runTaskLater(this, () -> {
                scheduleFire2(uuid);
            }, drawTime);
            activeTasks.put(uuid, fireTask);
        }, redrawDelay);
        activeTasks.put(uuid, redrawTask);
    }

    private void forceStartUsingItem(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Class<?> handClass = Class.forName("net.minecraft.world.InteractionHand");
            Object mainHand = handClass.getEnumConstants()[0];
            handle.getClass().getMethod("startUsingItem", handClass).invoke(handle, mainHand);
        } catch (Exception e) {
            getLogger().warning("Failed to force bow draw: " + e.getMessage());
        }
    }

    private void stopFiring(UUID uuid) {
        BukkitTask task = activeTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private void stopAllFiring() {
        activeTasks.values().forEach(BukkitTask::cancel);
        activeTasks.clear();
    }

    private boolean isHoldingBow(Player player) {
        Material type = player.getInventory().getItemInMainHand().getType();
        return type == Material.BOW || type == Material.CROSSBOW;
    }

    private boolean isRightClickHeld(Player player) {
        return player.isHandRaised();
    }

    private boolean hasArrow(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        return player.getInventory().contains(Material.ARROW)
                || player.getInventory().contains(Material.SPECTRAL_ARROW)
                || player.getInventory().contains(Material.TIPPED_ARROW);
    }

    private void consumeArrow(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            if (item.getType() == Material.ARROW || item.getType() == Material.SPECTRAL_ARROW || item.getType() == Material.TIPPED_ARROW) {
                item.setAmount(item.getAmount() - 1);
                return;
            }
        }
    }
}
