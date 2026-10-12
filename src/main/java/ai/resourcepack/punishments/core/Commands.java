package ai.resourcepack.verdict;

import ai.resourcepack.verdict.Punishment.Type;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every command, registered into the server's command map under the names
 * config.yml gives them. These are the "backup" the dialogs never take
 * away: everything the panel does is one of these, run as the staff member.
 */
final class Commands {

    private final VerdictPlugin plugin;
    private final List<Command> registered = new ArrayList<>();

    Commands(VerdictPlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        unregister();
        add("punish", "verdict.punish", "/<command> [player]", this::punish);
        add("warn", "verdict.warn", "/<command> <player> [-s] [reason]", (s, a) -> punishment(s, a, Type.WARN, false, "warn"));
        add("unwarn", "verdict.unwarn", "/<command> <player>", (s, a) -> lift(s, a, Type.WARN, "unwarn"));
        add("mute", "verdict.mute", "/<command> <player> [length] [-s] [reason]", (s, a) -> punishment(s, a, Type.MUTE, true, "mute"));
        add("unmute", "verdict.unmute", "/<command> <player>", (s, a) -> lift(s, a, Type.MUTE, "unmute"));
        add("kick", "verdict.kick", "/<command> <player> [-s] [reason]", (s, a) -> punishment(s, a, Type.KICK, false, "kick"));
        add("tempban", "verdict.tempban", "/<command> <player> <length> [-s] [-ip] [reason]", (s, a) -> punishment(s, a, Type.BAN, true, "tempban"));
        add("ban", "verdict.ban", "/<command> <player> [length] [-s] [-ip] [reason]", (s, a) -> punishment(s, a, Type.BAN, false, "ban"));
        add("unban", "verdict.unban", "/<command> <player>", (s, a) -> lift(s, a, Type.BAN, "unban"));
        add("history", "verdict.history", "/<command> <player>", this::history);
        add("verdict", null, "/<command> <reload|revoke <id>>", this::admin);
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
    }

    void unregister() {
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        for (Command c : registered) {
            c.unregister(map);
            known.values().removeIf(x -> x == c);
        }
        registered.clear();
    }

    @FunctionalInterface
    private interface Handler {
        void run(CommandSender sender, String[] args);
    }

    private void add(String key, String permission, String usage, Handler handler) {
        Settings.CommandSpec spec = plugin.settings().command(key);
        if (!spec.enabled()) return;
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        // Take the name over from whoever has it (vanilla /ban, Essentials /mute):
        // a server installing Verdict wants Verdict's.
        List<String> names = new ArrayList<>(spec.aliases());
        names.add(spec.name());
        for (String n : names) {
            Command old = known.get(n);
            if (old != null && !(old instanceof VerdictCommand)) known.remove(n);
        }
        VerdictCommand cmd = new VerdictCommand(key, spec.name(), spec.aliases(), permission, usage, handler);
        Bukkit.getCommandMap().register("verdict", cmd);
        // register() keeps an existing name's owner on a clash; make sure ours is the bare name.
        known.put(spec.name(), cmd);
        for (String a : spec.aliases()) known.put(a, cmd);
        registered.add(cmd);
    }

    private final class VerdictCommand extends Command {
        private final String key;
        private final Handler handler;

        VerdictCommand(String key, String name, List<String> aliases, String permission, String usage, Handler handler) {
            super(name, "Verdict: " + key, usage, aliases);
            this.key = key;
            this.handler = handler;
            setPermission(permission);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            // "/execute as Steve run punish x" arrives proxied: act as Steve.
            if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied) sender = proxied.getCallee();
            if (!testPermissionSilent(sender)) {
                plugin.msg().tell(sender, "errors.no-permission", null);
                return true;
            }
            handler.run(sender, args);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!testPermissionSilent(sender)) return List.of();
            return complete(key, sender, args);
        }
    }

    // ---------------------------------------------------------------- handlers

    private void usage(CommandSender s, String key) {
        String name = plugin.settings().command(key).name();
        String usage = switch (key) {
            case "warn", "kick" -> "/" + name + " <player> [-s] [reason]";
            case "mute" -> "/" + name + " <player> [length] [-s] [reason]";
            case "tempban" -> "/" + name + " <player> <length> [-s] [-ip] [reason]";
            case "ban" -> "/" + name + " <player> [length] [-s] [-ip] [reason]";
            case "history" -> "/" + name + " <player>";
            case "verdict" -> "/" + name + " reload | revoke <id>";
            default -> "/" + name + " <player>";
        };
        plugin.msg().tell(s, "errors.usage", Msg.vars().text("usage", usage));
    }

    private void punishment(CommandSender sender, String[] args, Type type, boolean temp, String key) {
        if (args.length < 1) {
            usage(sender, key);
            return;
        }
        boolean silent = false;
        boolean ip = false;
        Long length = null;
        boolean lengthDone = type == Type.WARN || type == Type.KICK;
        List<String> reason = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            String lower = a.toLowerCase(Locale.ROOT);
            if (lower.equals("-s") || lower.equals("-silent")) {
                silent = true;
                continue;
            }
            if (lower.equals("-ip")) {
                if (type == Type.BAN) ip = true;
                continue;
            }
            if (reason.isEmpty() && lower.equals("default")) {
                // The panel's "reason's default": same as leaving the length out.
                continue;
            }
            if (!lengthDone && reason.isEmpty() && Durations.isDuration(lower)) {
                length = Durations.parse(lower);
                lengthDone = true;
                continue;
            }
            reason.add(a);
        }
        if (ip && !sender.hasPermission("verdict.ban.ip")) {
            plugin.msg().tell(sender, "errors.no-permission", null);
            return;
        }
        plugin.service().punish(new Service.Request(sender, type, args[0], length, silent, ip, String.join(" ", reason), temp));
    }

    private void lift(CommandSender sender, String[] args, Type type, String key) {
        if (args.length < 1) {
            usage(sender, key);
            return;
        }
        plugin.service().lift(sender, type, args[0]);
    }

    private void punish(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            usage(sender, "history");
            return;
        }
        Dialogs dialogs = plugin.dialogs();
        if (dialogs == null) {
            plugin.msg().tell(sender, "errors.no-dialogs", null);
            return;
        }
        if (args.length == 0) {
            dialogs.players(player);
            return;
        }
        plugin.service().summary(player, args[0], summary -> dialogs.panel(player, summary));
    }

    private void history(CommandSender sender, String[] args) {
        if (args.length < 1) {
            usage(sender, "history");
            return;
        }
        Service service = plugin.service();
        service.summary(sender, args[0], summary -> service.history(sender, summary.target().name(), rows -> {
            Dialogs dialogs = plugin.dialogs();
            if (sender instanceof Player p && dialogs != null) dialogs.history(p, summary, rows);
            else service.historyInChat(sender, summary, rows);
        }));
    }

    private void admin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("verdict.admin")) {
                    plugin.msg().tell(sender, "errors.no-permission", null);
                    return;
                }
                plugin.reloadEverything();
                plugin.msg().tell(sender, "reloaded", null);
            }
            case "revoke" -> {
                if (args.length < 2) {
                    usage(sender, "verdict");
                    return;
                }
                if (!sender.hasPermission("verdict.revoke")) {
                    plugin.msg().tell(sender, "errors.no-permission", null);
                    return;
                }
                try {
                    plugin.service().revokeId(sender, Long.parseLong(args[1].replace("#", "")));
                } catch (NumberFormatException e) {
                    plugin.msg().tell(sender, "errors.unknown-id", Msg.vars().text("id", args[1]));
                }
            }
            case "ack" -> {
                // The warning popup's button. Marks it read, and only if it is the presser's own.
                if (args.length >= 2 && sender instanceof Player p) {
                    try {
                        plugin.service().acknowledge(p, Long.parseLong(args[1]));
                    } catch (NumberFormatException ignored) {
                        // not an id: nothing to mark
                    }
                }
            }
            case "act" -> act(sender, args);
            default -> {
                if (sender.hasPermission("verdict.admin")) usage(sender, "verdict");
                else plugin.msg().tell(sender, "errors.no-permission", null);
            }
        }
    }

    /**
     * What the panel's buttons run: {@code /verdict act <what> [target]}. Each
     * is the same punishment the plain command gives, with the reason, length
     * and silence read off the staff member's own panel controls, and checked
     * against the same permission the plain command needs.
     */
    private void act(CommandSender sender, String[] args) {
        Dialogs dialogs = plugin.dialogs();
        if (!(sender instanceof Player player) || dialogs == null || args.length < 2) return;
        String what = args[1].toLowerCase(Locale.ROOT);
        String target = args.length >= 3 ? args[2] : null;
        Settings settings = plugin.settings();
        Service service = plugin.service();
        switch (what) {
            case "warn", "mute", "kick", "tempban", "ban" -> {
                if (target == null) return;
                if (!player.hasPermission("verdict." + what)) {
                    plugin.msg().tell(player, "errors.no-permission", null);
                    return;
                }
                String preset = dialogs.reasonKey(player);
                boolean silent = dialogs.silent(player);
                Long length = what.equals("mute") || what.equals("tempban") ? dialogs.length(player) : null;
                Type type = switch (what) {
                    case "warn" -> Type.WARN;
                    case "mute" -> Type.MUTE;
                    case "kick" -> Type.KICK;
                    default -> Type.BAN;
                };
                if (what.equals("ban")) length = Durations.PERMANENT;
                Settings.Reason reason = settings.reasons.get(preset);
                String reasonLabel = reason == null ? preset : reason.label();
                if (type == Type.BAN) {
                    long shown = length != null ? length
                            : reason != null && reason.tempban() != 0 ? reason.tempban() : settings.defaultTempban;
                    dialogs.confirm(player, new Dialogs.Pending(type, target, length, silent, "#" + preset,
                            what.equals("tempban"), reasonLabel, Durations.format(shown, plugin.msg().raw("permanent"))));
                    return;
                }
                service.punish(new Service.Request(player, type, target, length, silent, false, "#" + preset, type == Type.MUTE));
                if (type == Type.KICK || type == Type.WARN) player.closeDialog();
                else dialogs.refreshPanel(player, target);
            }
            case "confirm" -> {
                Dialogs.Pending p = dialogs.takePending(player);
                if (p == null) {
                    player.closeDialog();
                    return;
                }
                if (!player.hasPermission(p.temp() ? "verdict.tempban" : "verdict.ban")) {
                    plugin.msg().tell(player, "errors.no-permission", null);
                    return;
                }
                // Anything typed in the screen's Details box rides after the preset.
                String details = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim() : "";
                String input = details.isEmpty() ? p.reasonInput() : p.reasonInput() + " " + details;
                service.punish(new Service.Request(player, p.type(), p.target(), p.length(), p.silent(), false, input, p.temp()));
                dialogs.refreshPanel(player, p.target());
            }
            case "unmute", "unban" -> {
                if (target == null) return;
                if (!player.hasPermission("verdict." + what)) {
                    plugin.msg().tell(player, "errors.no-permission", null);
                    return;
                }
                service.lift(player, what.equals("unmute") ? Type.MUTE : Type.BAN, target);
                dialogs.refreshPanel(player, target);
            }
            case "revoke" -> {
                if (target == null) return;
                if (!player.hasPermission("verdict.revoke")) {
                    plugin.msg().tell(player, "errors.no-permission", null);
                    return;
                }
                try {
                    service.revokeId(player, Long.parseLong(target));
                } catch (NumberFormatException e) {
                    return;
                }
                dialogs.refreshHistory(player);
            }
            case "page" -> dialogs.turn(player, "prev".equalsIgnoreCase(target) ? -1 : 1);
            default -> { }
        }
    }

    // ---------------------------------------------------------------- completion

    private List<String> complete(String key, CommandSender sender, String[] args) {
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (key.equals("verdict")) {
            if (args.length == 1) for (String s : List.of("reload", "revoke")) if (s.startsWith(last)) out.add(s);
            return out;
        }
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player s && !s.canSee(p)) continue;
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(p.getName());
            }
            return out;
        }
        boolean hasLength = key.equals("mute") || key.equals("tempban") || key.equals("ban");
        if (!hasLength && !List.of("warn", "kick").contains(key)) return out;
        if (args.length == 2 && hasLength) {
            for (Settings.Length l : plugin.settings().lengths) {
                if (!l.id().equals("default") && l.id().startsWith(last)) out.add(l.id());
            }
        }
        if (last.startsWith("#") || last.isEmpty()) {
            for (String id : plugin.settings().reasons.keySet()) if (("#" + id).startsWith(last)) out.add("#" + id);
        }
        if (last.startsWith("-")) {
            out.add("-s");
            if (key.equals("ban") || key.equals("tempban")) out.add("-ip");
        }
        return out;
    }
}
