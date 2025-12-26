package pl.verCodeTPA;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main extends JavaPlugin implements TabExecutor, Listener {

    private final Map<UUID, Set<UUID>> tpaRequests = new HashMap<>();
    private final Map<UUID, Location> teleportLocations = new HashMap<>();
    private final Map<UUID, Location> savedTargetLocations = new HashMap<>();
    private final Map<UUID, BukkitRunnable> activeTeleports = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final Map<UUID, Long> lastRequestTime = new HashMap<>();

    private String prefix;
    private int playerTeleportDuration;
    private int permTeleportDuration;
    private List<String> lockedWorlds;
    private boolean teleportPlayerLocation;

    private Map<String, Object> messages;
    private Map<String, Object> buttons;

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getCommand("tpa").setExecutor(this);
        getCommand("tpaaccept").setExecutor(this);
        getCommand("tpadeny").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadConfig() {
        reloadConfig();
        prefix = getConfig().getString("options.prefix", "&8[&3VerCode &7| &bTPA&8|&r ");
        lockedWorlds = getConfig().getStringList("options.locked_worlds");
        teleportPlayerLocation = getConfig().getBoolean("options.teleport_player_location", false);

        playerTeleportDuration = parseTime(getConfig().getString("options.teleportation_duration.player", "5s"));
        permTeleportDuration = parseTime(getConfig().getString("options.teleportation_duration.permission", "1s"));

        messages = getConfig().getConfigurationSection("messages_teleportation").getValues(true);
        buttons = getConfig().getConfigurationSection("buttons").getValues(true);
    }

    private int parseTime(String timeStr) {
        if (timeStr == null) return 5;
        Pattern pattern = Pattern.compile("(\\d+)(s|m|mm)");
        Matcher matcher = pattern.matcher(timeStr.toLowerCase());
        if (!matcher.matches()) return 5;
        int time = Integer.parseInt(matcher.group(1));
        String unit = matcher.group(2);
        if (unit.equals("m")) return time * 60;
        if (unit.equals("mm")) return time;
        return time;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cTylko dla graczy!");
            return true;
        }
        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "tpa":
                handleTpaCommand(player, args);
                return true;
            case "tpaaccept":
                handleTpaAccept(player, args);
                return true;
            case "tpadeny":
                handleTpaDeny(player, args);
                return true;
            default:
                return false;
        }
    }

    private void handleTpaCommand(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(formatColors(prefix + "&cUżycie: /tpa <gracz>"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(formatColors(prefix + "&cGracz nie jest online!"));
            return;
        }

        if (target.equals(player)) {
            player.sendMessage(formatColors(prefix + "&cNie możesz teleportować się do siebie!"));
            return;
        }

        if (lockedWorlds.contains(player.getWorld().getName()) || lockedWorlds.contains(target.getWorld().getName())) {
            player.sendMessage(formatColors(prefix + "&cTeleportacja zablokowana w tym świecie!"));
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastRequestTime.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 10_000) {
            player.sendMessage(formatColors(prefix + "&cMusisz chwilę poczekać przed wysłaniem kolejnej prośby."));
            return;
        }

        tpaRequests.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>()).add(player.getUniqueId());
        lastRequestTime.put(player.getUniqueId(), now);
        sendTpaRequest(player, target);
        playSound(player, getConfig().getString("options.sounds.tpa_send", "ENTITY_EXPERIENCE_ORB_PICKUP"));
    }

    private void handleTpaAccept(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(formatColors(prefix + "&cPodaj nick lub *"));
            return;
        }

        if (args[0].equalsIgnoreCase("*")) {
            Set<UUID> requests = tpaRequests.get(player.getUniqueId());
            if (requests == null || requests.isEmpty()) {
                player.sendMessage(formatColors(prefix + "&cNie masz żadnych próśb do zaakceptowania!"));
                return;
            }
            for (UUID requesterUUID : new HashSet<>(requests)) {
                Player requester = Bukkit.getPlayer(requesterUUID);
                if (requester != null && requester.isOnline()) {
                    acceptRequest(player, requester);
                    requests.remove(requesterUUID);
                }
            }
            if (requests.isEmpty()) tpaRequests.remove(player.getUniqueId());
            return;
        }

        Player requester = Bukkit.getPlayerExact(args[0]);
        if (requester == null) {
            player.sendMessage(formatColors(prefix + "&cNie znaleziono takiego gracza!"));
            return;
        }
        Set<UUID> myReq = tpaRequests.get(player.getUniqueId());
        if (myReq == null || !myReq.contains(requester.getUniqueId())) {
            player.sendMessage(formatColors(prefix + "&cNie masz prośby od tego gracza!"));
            return;
        }
        acceptRequest(player, requester);
        myReq.remove(requester.getUniqueId());
        if (myReq.isEmpty()) tpaRequests.remove(player.getUniqueId());
    }

    private void handleTpaDeny(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(formatColors(prefix + "&cPodaj nick lub *"));
            return;
        }

        Set<UUID> requests = tpaRequests.get(player.getUniqueId());
        if (requests == null || requests.isEmpty()) {
            player.sendMessage(formatColors(prefix + "&cNie masz żadnych próśb do odrzucenia!"));
            return;
        }

        if (args[0].equalsIgnoreCase("*")) {
            for (UUID uuid : new HashSet<>(requests)) {
                Player requester = Bukkit.getPlayer(uuid);
                if (requester != null && requester.isOnline()) {
                    requests.remove(uuid);
                    sendRejectionMessages(player, requester);
                }
            }
        } else {
            Player denyRequester = Bukkit.getPlayerExact(args[0]);
            if (denyRequester == null) {
                player.sendMessage(formatColors(prefix + "&cNie znaleziono takiego gracza!"));
                return;
            }
            if (!requests.remove(denyRequester.getUniqueId())) {
                player.sendMessage(formatColors(prefix + "&cNie masz prośby od tego gracza!"));
                return;
            }
            sendRejectionMessages(player, denyRequester);
        }

        if (requests.isEmpty()) tpaRequests.remove(player.getUniqueId());
    }

    private void sendRejectionMessages(Player player, Player requester) {
        List<String> chat = getStringList(messages, "rejection_request.chat");
        for (String line : chat) {
            player.sendMessage(formatColors(replaceVars(line, requester, player)));
        }
        requester.sendMessage(formatColors(prefix + "&cTwoja prośba została odrzucona przez &e" + player.getName()));
        playSound(player, getConfig().getString("options.sounds.deny", "BLOCK_NOTE_BLOCK_BASS"));
    }

    private void sendTpaRequest(Player sender, Player target) {
        List<String> chatSender = getStringList(messages, "sending_request.chat");
        for (String line : chatSender) {
            sender.sendMessage(formatColors(replaceVars(line, target, sender)));
        }
        String title = (String) get(messages, "sending_request.title.title");
        String subtitle = (String) get(messages, "sending_request.title.subtitle");
        int dur = parseTime((String) get(messages, "sending_request.title.message_duration"));
        sender.sendTitle(
                formatColors(replaceVars(title, target, sender)),
                formatColors(replaceVars(subtitle, target, sender)),
                0, dur * 20, 0
        );

        List<String> chatTarget = getStringList(messages, "receiving_request_teleportation.chat");
        String btnAccept = createButton("accept", sender);
        String btnDeny = createButton("deny", sender);

        for (String raw : chatTarget) {
            String line = raw.replace("{button1}", btnAccept).replace("{button2}", btnDeny);
            target.sendMessage(formatColors(replaceVars(line, sender, target)));
        }

        showBossBar(target, sender);
        playSound(target, getConfig().getString("options.sounds.tpa_receive", "ENTITY_PLAYER_LEVELUP"));
    }

    private void showBossBar(Player target, Player sender) {
        String msg = formatColors(replaceVars((String) get(messages, "receiving_request_teleportation.bossbar.message"), sender, target));
        List<String> colorsRaw = (List<String>) get(messages, "receiving_request_teleportation.bossbar.color");
        int changeTime = parseTime((String) get(messages, "receiving_request_teleportation.bossbar.color_changing_time"));

        if (colorsRaw == null || colorsRaw.isEmpty()) colorsRaw = Collections.singletonList("BLUE");
        BarColor[] colors = new BarColor[colorsRaw.size()];
        for (int i = 0; i < colorsRaw.size(); i++) {
            colors[i] = BarColor.valueOf(colorsRaw.get(i).toUpperCase());
        }

        BossBar bossBar = Bukkit.createBossBar(msg, colors[0], BarStyle.SOLID);
        bossBar.addPlayer(target);
        playerBossBars.put(target.getUniqueId(), bossBar);

        if (colors.length > 1 && changeTime > 0) {
            long ticks = Math.max(1L, changeTime / 50L);
            new BukkitRunnable() {
                int idx = 0;

                @Override
                public void run() {
                    if (!target.isOnline()) {
                        bossBar.removeAll();
                        playerBossBars.remove(target.getUniqueId());
                        cancel();
                        return;
                    }
                    bossBar.setColor(colors[idx++ % colors.length]);
                }
            }.runTaskTimer(this, 0L, ticks);
        }
    }

    @SuppressWarnings("unchecked")
    private String createButton(String key, Player requestingPlayer) {
        Object raw = buttons.get(key);
        if (!(raw instanceof Map)) return "";
        Map<String, Object> btn = (Map<String, Object>) raw;
        String text = String.valueOf(btn.get("text"));
        List<String> cmds = (List<String>) btn.get("command_executes");
        String cmd = cmds.isEmpty() ? "" : cmds.get(0).replace("{player}", requestingPlayer.getName());

        TextComponent component = new TextComponent(formatColors(text));
        component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + cmd));
        component.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(formatColors("&eKliknij, aby wykonać!")).create()
        ));
        return component.toLegacyText();
    }

    private void acceptRequest(Player target, Player requester) {
        int duration = target.hasPermission("vercode.tpa.teleport.speed") ? permTeleportDuration : playerTeleportDuration;

        Location targetLoc = target.getLocation().clone();
        if (teleportPlayerLocation) {
            teleportLocations.put(requester.getUniqueId(), targetLoc);
        } else {
            savedTargetLocations.put(requester.getUniqueId(), targetLoc);
            teleportLocations.put(requester.getUniqueId(), targetLoc);
        }

        List<String> chat = getStringList(messages, "accepting_request.chat");
        for (String line : chat) {
            target.sendMessage(formatColors(replaceVars(line, requester, target)));
        }

        String title = (String) get(messages, "accepting_request.title");
        String subtitle = (String) get(messages, "accepting_request.subtitle");
        target.sendTitle(
                formatColors(title),
                formatColors(subtitle.replace("{time_teleportation}", duration + "s")),
                0, 40, 10
        );

        startTeleportCountdown(requester, duration);
        playSound(target, getConfig().getString("options.sounds.accept", "UI_TOAST_CHALLENGE_COMPLETE"));
        playSound(requester, getConfig().getString("options.sounds.accept", "UI_TOAST_CHALLENGE_COMPLETE"));
    }

    private void startTeleportCountdown(Player player, int duration) {
        BukkitRunnable task = new BukkitRunnable() {
            int timeLeft = duration;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelTeleport(player);
                    cancel();
                    return;
                }
                if (timeLeft > 0) {
                    String sub = (String) get(messages, "accepting_request.subtitle");
                    String formatted = formatColors(sub.replace("{time_teleportation}", timeLeft + "s"));
                    player.sendTitle("", formatted, 0, 20, 0);
                    timeLeft--;
                } else {
                    Location loc = teleportLocations.get(player.getUniqueId());
                    if (loc == null) loc = savedTargetLocations.getOrDefault(player.getUniqueId(), player.getLocation());
                    player.teleport(loc);
                    playSound(player, getConfig().getString("options.sounds.teleport", "ENTITY_ENDERMAN_TELEPORT"));
                    cancelTeleport(player);
                    cancel();
                }
            }
        };
        task.runTaskTimer(this, 0L, 20L);
        activeTeleports.put(player.getUniqueId(), task);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!activeTeleports.containsKey(player.getUniqueId())) return;

        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        activeTeleports.get(player.getUniqueId()).cancel();
        cancelTeleport(player);

        String t = (String) get(messages, "after_moving.title");
        String s = (String) get(messages, "after_moving.subtitle");
        player.sendTitle(formatColors(t), formatColors(s), 0, 40, 10);
        playSound(player, getConfig().getString("options.sounds.cancel", "ENTITY_VILLAGER_NO"));
    }

    private void cancelTeleport(Player player) {
        activeTeleports.remove(player.getUniqueId());
        teleportLocations.remove(player.getUniqueId());
        savedTargetLocations.remove(player.getUniqueId());
        BossBar bar = playerBossBars.remove(player.getUniqueId());
        if (bar != null) bar.removeAll();
    }

    private List<String> getStringList(Map<String, Object> map, String path) {
        Object val = get(map, path);
        if (val instanceof List) return (List<String>) val;
        return Collections.emptyList();
    }

    private Object get(Map<String, Object> map, String path) {
        String[] keys = path.split("\\.");
        Object current = map;
        for (String k : keys) {
            if (!(current instanceof Map)) return null;
            current = ((Map<?, ?>) current).get(k);
            if (current == null) return null;
        }
        return current;
    }

    private String replaceVars(String msg, Player player, Player sendingPlayer) {
        if (msg == null) return "";
        if (player != null) msg = msg.replace("{player}", player.getName());
        if (sendingPlayer != null) msg = msg.replace("{sending_player}", sendingPlayer.getName());
        msg = msg.replace("{prefix}", prefix);
        if (player != null) {
            int count = tpaRequests.getOrDefault(player.getUniqueId(), Collections.emptySet()).size();
            msg = msg.replace("{active_requests}", String.valueOf(count));
        }
        return msg;
    }

    private String formatColors(String input) {
        if (input == null) return "";
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        String out = buffer.toString();
        out = out.replace('&', '§');
        return out;
    }

    private void playSound(Player player, String soundName) {
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        Player player = (Player) sender;

        if ((command.getName().equalsIgnoreCase("tpa")
                || command.getName().equalsIgnoreCase("tpaaccept")
                || command.getName().equalsIgnoreCase("tpadeny"))
                && args.length == 1) {
            String pref = args[0].toLowerCase();
            List<String> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(player) && p.getName().toLowerCase().startsWith(pref)) {
                    list.add(p.getName());
                }
            }
            if (!command.getName().equalsIgnoreCase("tpa")) {
                list.add("*");
            }
            return list;
        }
        return Collections.emptyList();
    }
}
