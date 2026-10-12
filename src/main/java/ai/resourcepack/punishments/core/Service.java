package ai.resourcepack.punishments.core;

import ai.resourcepack.punishments.PunishmentsPlugin;
import ai.resourcepack.punishments.api.Punishment;

import ai.resourcepack.punishments.api.Punishment.Type;
import ai.resourcepack.punishments.core.Storage.PlayerRecord;
import ai.resourcepack.punishments.api.event.PunishmentEvent;
import ai.resourcepack.punishments.api.event.PunishmentRevokeEvent;
import ai.resourcepack.punishments.core.platform.Platform;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.logging.Level;

/**
 * What Punishments does. Every punishment comes through {@link #punish}, whether
 * it was typed or clicked: the panel's buttons run the commands, and the
 * commands call this. Database work runs on one thread of its own, in order;
 * everything a player sees happens back on the main thread.
 */
public final class Service {

    @FunctionalInterface
    public interface Sql<T> {
        T run() throws SQLException;
    }

    /** Who a command is about, once looked up. */
    public record Target(UUID uuid, String name, String ip) {}

    /** What a punishment command asked for, once its arguments are read. */
    public record Request(CommandSender actor, Type type, String targetName, Long length, boolean silent, boolean ipBan, String reasonInput,
                   boolean temp) {}

    /** {@code refused} is the error key when nothing was recorded, or null. */
    private record Outcome(Target target, Punishment punishment, int warnings, boolean replaced, String refused) {}

    /** Everything the panel shows about somebody. */
    public record Summary(Target target, PlayerRecord record, Optional<Punishment> mute, Optional<Punishment> ban, int warnings,
                          int total, boolean online) {}

    private final PunishmentsPlugin plugin;
    public final Storage storage;
    private final ExecutorService db = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Punishments-DB");
        t.setDaemon(true);
        return t;
    });
    /** Mutes in force on online players, so chat never waits for the database. */
    public final Map<UUID, Punishment> mutes = new ConcurrentHashMap<>();
    /**
     * Players let through pre-login although their address is banned, because
     * the exempt permission can only be asked once they have joined.
     */
    public final Map<UUID, Punishment> addressBanned = new ConcurrentHashMap<>();

    public Service(PunishmentsPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public Settings settings() {
        return plugin.settings();
    }

    public Msg msg() {
        return plugin.msg();
    }

    public void shutdown() {
        db.shutdown();
        try {
            db.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        storage.close();
    }

    // ---------------------------------------------------------------- threads

    /** Runs database work on the database thread, then {@code then} on the main thread. */
    <T> void async(Sql<T> work, Consumer<T> then, CommandSender errorsTo) {
        db.execute(() -> {
            T result;
            try {
                result = work.run();
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Database error", e);
                if (errorsTo != null) Bukkit.getScheduler().runTask(plugin, () -> msg().tell(errorsTo, "errors.storage", null));
                return;
            }
            if (then != null && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> then.accept(result));
        });
    }

    /** For threads that may block (pre-login): runs on the database thread and waits. */
    <T> T blocking(Sql<T> work) throws Exception {
        Future<T> f = db.submit(work::run);
        return f.get(10, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- targets

    /** Looks somebody up on the database thread: an online player first, then the record. */
    public Target resolve(String name, Player online) throws SQLException {
        if (online != null) return new Target(online.getUniqueId(), online.getName(), ip(online));
        Optional<PlayerRecord> rec = storage.player(name);
        if (rec.isPresent()) return new Target(rec.get().uuid(), rec.get().name(), rec.get().ip());
        var cached = Platform.cachedOffline(name);
        if (cached != null && cached.getName() != null) return new Target(cached.getUniqueId(), cached.getName(), null);
        return null;
    }

    public static String ip(Player p) {
        InetSocketAddress a = p.getAddress();
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }

    /**
     * Whether an address is one player's own. Loopback, private (RFC 1918,
     * fc00::/7) and link-local addresses are what a proxy, a tunnel or a LAN
     * hands every player, so banning one bans everybody behind it.
     */
    public static boolean publicAddress(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        // Only ever an address literal here (getHostAddress), so no lookup happens.
        InetAddress a;
        try {
            a = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return false;
        }
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return false;
        return !(a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc);
    }

    public static String actorName(CommandSender s, Msg msg) {
        return s instanceof Player p ? p.getName() : msg.raw("console-name");
    }

    // ---------------------------------------------------------------- punishing

    public void punish(Request req) {
        Settings st = settings();
        Msg msg = msg();
        CommandSender actor = req.actor();

        // The reason: "#hacking caught flying" is the preset and some details.
        Settings.Reason preset = null;
        String details = req.reasonInput() == null ? "" : req.reasonInput().trim();
        if (details.startsWith("#")) {
            String[] parts = details.substring(1).split("\\s+", 2);
            preset = st.reasons.get(parts[0].toLowerCase(java.util.Locale.ROOT));
            if (preset != null) details = parts.length > 1 ? parts[1].trim() : "";
        }
        String reason;
        if (preset != null) {
            reason = details.isEmpty() ? preset.label()
                    : st.reasonFormat.replace("{preset}", preset.label()).replace("{details}", details);
        } else if (!details.isEmpty()) {
            reason = details;
        } else {
            if (st.requireReason && actor instanceof Player) {
                msg.tell(actor, "errors.reason-required", null);
                return;
            }
            reason = st.defaultReason;
        }

        // The length, for the two kinds that have one.
        long length = Punishment.NEVER;
        boolean capped = false;
        if (req.type() == Type.MUTE) {
            length = req.length() != null ? req.length()
                    : preset != null && preset.mute() != 0 ? preset.mute() : st.defaultMute;
        } else if (req.type() == Type.BAN) {
            if (req.length() != null) length = req.length();
            else if (req.temp()) length = preset != null && preset.tempban() != 0 ? preset.tempban() : st.defaultTempban;
            else length = Durations.PERMANENT;
        }
        Settings.Limit limit = st.limitFor(actor);
        if (limit != null) {
            if (req.type() == Type.MUTE && limit.mute() > 0 && (length == Durations.PERMANENT || length > limit.mute())) {
                length = limit.mute();
                capped = true;
            } else if (req.type() == Type.BAN) {
                if (length == Durations.PERMANENT && !limit.permanentBan()) {
                    msg.tell(actor, "errors.no-permanent", null);
                    return;
                }
                if (length != Durations.PERMANENT && limit.tempban() > 0 && length > limit.tempban()) {
                    length = limit.tempban();
                    capped = true;
                }
            }
        }
        if (capped) msg.tell(actor, "errors.capped", Msg.vars().text("length", Durations.format(length, msg.raw("permanent"))));

        Player online = Bukkit.getPlayerExact(req.targetName());
        if (req.type() == Type.KICK && online == null) {
            msg.tell(actor, "errors.not-online", Msg.vars().text("target", req.targetName()));
            return;
        }
        if (online != null && actor instanceof Player a) {
            if (online.equals(a) && !st.allowSelf) {
                msg.tell(actor, "errors.self", null);
                return;
            }
            if (!online.equals(a) && online.hasPermission(st.exemptPermission)) {
                msg.tell(actor, "errors.exempt", Msg.vars().text("target", online.getName()));
                return;
            }
        }

        var pre = new ai.resourcepack.punishments.api.event.PunishmentPreEvent(actor, req.type(), req.targetName(), reason, length, req.silent());
        Bukkit.getPluginManager().callEvent(pre);
        if (pre.isCancelled()) return;

        final String finalReason = pre.getReason();
        final long finalLength = length;
        UUID actorId = actor instanceof Player p ? p.getUniqueId() : null;
        String actorName = actorName(actor, msg);
        long now = System.currentTimeMillis();
        boolean popup = st.popup && plugin.dialogs() != null;

        async(() -> {
            Target t = resolve(req.targetName(), online);
            if (t == null) return null;
            if (actor instanceof Player a && t.uuid().equals(a.getUniqueId()) && !st.allowSelf) return new Outcome(t, null, -1, false, "errors.self");
            // Offline, the permission cannot be asked; what they held when last seen stands in for it.
            if (online == null && actor instanceof Player a && !t.uuid().equals(a.getUniqueId())
                    && storage.exempt(t.uuid())) return new Outcome(t, null, -1, false, "errors.exempt");
            // Behind a proxy every player has the proxy's address: refused, before anything is replaced.
            if (req.ipBan() && t.ip() != null && !publicAddress(t.ip())) return new Outcome(t, null, -1, false, "errors.local-ip");
            boolean replaced = false;
            if (req.type() == Type.MUTE || req.type() == Type.BAN) {
                replaced = storage.revokeAll(req.type(), t.uuid(), actorName, now) > 0;
            }
            long expires = req.type() == Type.MUTE || req.type() == Type.BAN
                    ? (finalLength == Durations.PERMANENT ? Punishment.NEVER : now + finalLength) : Punishment.NEVER;
            boolean active = req.type() != Type.KICK;
            // A warning is "seen" once the player has read it: in chat now, or in the popup when they press the button.
            boolean seen = req.type() != Type.WARN || (online != null && !popup);
            String ip = req.ipBan() ? t.ip() : null;
            Punishment p = new Punishment(0, req.type(), t.uuid(), t.name(), ip, finalReason, actorId, actorName, now, expires, active,
                    null, 0, req.silent(), seen);
            long id = storage.insert(p);
            p = new Punishment(id, p.type(), p.target(), p.targetName(), p.ip(), p.reason(), p.actor(), p.actorName(), p.created(),
                    p.expires(), p.active(), null, 0, p.silent(), p.seen());
            int warnings = req.type() == Type.WARN
                    ? storage.warnings(t.uuid(), st.warningLifetime > 0 ? now - st.warningLifetime : 0) : 0;
            return new Outcome(t, p, warnings, replaced, null);
        }, out -> {
            if (out == null) {
                msg.tell(actor, "errors.unknown-player", Msg.vars().text("target", req.targetName()));
                return;
            }
            if (out.punishment() == null) {
                msg.tell(actor, out.refused(), Msg.vars().text("target", out.target().name()).text("ip", out.target().ip()));
                return;
            }
            applied(actor, out, finalLength);
        }, actor);
    }

    /**
     * Whether {@code actor} holds the permission the escalation command needs.
     * The console (and anything else that is not a player) always may. A
     * player needs the command's own permission, and {@code punishments.ban.ip}
     * for an address ban, the same as typing it; a command the server does
     * not know is refused, because nothing can be checked about it.
     */
    private static boolean mayEscalate(CommandSender actor, String command) {
        if (!(actor instanceof Player)) return true;
        String[] words = command.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return false;
        String label = words[0].startsWith("/") ? words[0].substring(1) : words[0];
        org.bukkit.command.Command c = Platform.commandMap().getCommand(label.toLowerCase(java.util.Locale.ROOT));
        if (c == null || !c.testPermissionSilent(actor)) return false;
        for (String w : words) {
            if (w.equalsIgnoreCase("-ip") && !actor.hasPermission("punishments.ban.ip")) return false;
        }
        return true;
    }

    /** A player name that is safe to put in a console command: one word, no flag. */
    private static final Pattern SAFE_NAME = Pattern.compile("[.*]?[A-Za-z0-9_]{1,32}");

    /** Main thread: everything a punishment does once it is on the record. */
    private void applied(CommandSender actor, Outcome out, long length) {
        Msg msg = msg();
        Punishment p = out.punishment();
        Player target = Bukkit.getPlayer(p.target());
        Msg.Vars v = vars(p).text("count", out.warnings());
        if (out.replaced()) msg.tell(actor, p.type() == Type.MUTE ? "errors.already-muted" : "errors.already-banned", v);

        switch (p.type()) {
            case WARN -> {
                if (target != null) {
                    Platform.send(target, msg().component("target.warn", v));
                    if (settings().popup && plugin.dialogs() != null) plugin.dialogs().warned(target, p, out.warnings());
                }
                Integer threshold = settings().escalation.containsKey(out.warnings()) ? out.warnings() : null;
                // The name goes into a console command as text, so it has to be one
                // word that cannot read as a flag or a reason: a Java name, or a
                // Bedrock one behind Floodgate's prefix. Anything else (a name
                // from a lax offline-mode server, say) skips the escalation
                // rather than running something nobody wrote.
                if (threshold != null && !SAFE_NAME.matcher(p.targetName()).matches()) {
                    plugin.getLogger().warning("Skipped the warning escalation for " + p.target()
                            + ": their name is not safe to put in a command.");
                    threshold = null;
                }
                if (threshold != null) {
                    String command = settings().escalation.get(threshold)
                            .replace("{target}", p.targetName()).replace("{count}", String.valueOf(out.warnings()));
                    // Run as the console, but only when the warner could have
                    // run it themselves: a warning must not be a way round the
                    // permission a ban or a mute needs.
                    if (!mayEscalate(actor, command)) {
                        msg.tell(actor, "errors.escalation-denied", v.text("command", command));
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
                    }
                }
            }
            case MUTE -> {
                if (target != null) {
                    mutes.put(target.getUniqueId(), p);
                    Platform.send(target, msg().component("target.mute", v));
                }
            }
            case KICK -> {
                if (target != null) Platform.kick(target, msg.component("kick-screen", v));
            }
            case BAN -> {
                var screen = banScreen(p);
                if (target != null) Platform.kick(target, screen);
                if (p.ip() != null) {
                    for (Player other : Bukkit.getOnlinePlayers()) {
                        if (p.ip().equals(ip(other)) && !other.hasPermission(settings().exemptPermission)) Platform.kick(other, screen);
                    }
                }
            }
        }
        msg.tell(actor, "done." + p.type().key(), v);
        announce(p.type().key(), p.silent(), v, actor);
        Bukkit.getPluginManager().callEvent(new PunishmentEvent(p, actor));
    }

    public net.kyori.adventure.text.Component banScreen(Punishment p) {
        return msg().component(p.permanent() ? "ban-screen-permanent" : "ban-screen", vars(p));
    }

    /** The placeholders every message about a punishment may use. */
    public Msg.Vars vars(Punishment p) {
        Msg msg = msg();
        long now = System.currentTimeMillis();
        String length = p.type() == Type.MUTE || p.type() == Type.BAN
                ? (p.permanent() ? msg.raw("permanent") : Durations.format(p.expires() - p.created(), msg.raw("permanent"))) : "";
        String remaining = p.permanent() ? msg.raw("permanent") : Durations.format(Math.max(0, p.expires() - now), msg.raw("permanent"));
        String expires = p.permanent() ? msg.raw("never") : settings().dates.format(java.time.Instant.ofEpochMilli(p.expires()));
        return Msg.vars()
                .text("id", p.id())
                .text("target", p.targetName())
                .text("actor", p.actorName())
                .text("reason", p.reason())
                .text("length", length)
                .text("remaining", remaining)
                .text("expires", expires)
                .text("date", settings().dates.format(java.time.Instant.ofEpochMilli(p.created())))
                .raw("type", msg.type(p));
    }

    /** Tells everyone, staff, or nobody, as config.yml says for this kind. */
    public void announce(String kind, boolean silent, Msg.Vars v, CommandSender actor) {
        String mode = silent ? "staff" : settings().broadcast(kind);
        if (mode.equals("none")) return;
        String line = msg().raw("prefix") + (silent ? msg().raw("silent-tag") : "") + msg().fill(msg().raw("broadcast." + kind), v);
        var component = Msg.MM.deserialize(line);
        if (mode.equals("everyone")) {
            for (Player p : Bukkit.getOnlinePlayers()) Platform.send(p, component);
            Platform.send(Bukkit.getConsoleSender(), component);
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Whoever gave it has been told already, in their own words.
            if (p.equals(actor)) continue;
            if (p.hasPermission("punishments.notify")) Platform.send(p, component);
        }
        Platform.send(Bukkit.getConsoleSender(), component);
    }

    // ---------------------------------------------------------------- lifting

    public void lift(CommandSender actor, Type type, String name) {
        Msg msg = msg();
        String by = actorName(actor, msg);
        long now = System.currentTimeMillis();
        Player online = Bukkit.getPlayerExact(name);
        async(() -> {
            Target t = resolve(name, online);
            if (t == null) return null;
            if (type == Type.WARN) {
                Optional<Punishment> w = storage.latestWarning(t.uuid());
                if (w.isEmpty()) return new Object[]{t, 0L};
                storage.revoke(w.get().id(), by, now);
                return new Object[]{t, w.get().id()};
            }
            return new Object[]{t, (long) storage.revokeAll(type, t.uuid(), by, now)};
        }, result -> {
            if (result == null) {
                msg.tell(actor, "errors.unknown-player", Msg.vars().text("target", name));
                return;
            }
            Target t = (Target) result[0];
            long n = (Long) result[1];
            Msg.Vars v = Msg.vars().text("target", t.name()).text("actor", by).text("id", n);
            String kind = switch (type) {
                case MUTE -> "unmute";
                case BAN -> "unban";
                default -> "unwarn";
            };
            if (n == 0) {
                msg.tell(actor, switch (type) {
                    case MUTE -> "errors.not-muted";
                    case BAN -> "errors.not-banned";
                    default -> "errors.no-warnings";
                }, v);
                return;
            }
            if (type == Type.MUTE) {
                mutes.remove(t.uuid());
                Player p = Bukkit.getPlayer(t.uuid());
                if (p != null) Platform.send(p, msg().component("target.unmute", v));
            }
            msg.tell(actor, "done." + kind, v);
            announce(kind, false, v, actor);
            Bukkit.getPluginManager().callEvent(new PunishmentRevokeEvent(type, t.uuid(), t.name(), actor));
        }, actor);
    }

    public void revokeId(CommandSender actor, long id) {
        Msg msg = msg();
        String by = actorName(actor, msg);
        long now = System.currentTimeMillis();
        async(() -> {
            Optional<Punishment> p = storage.get(id);
            if (p.isEmpty()) return new Object[]{null, false};
            return new Object[]{p.get(), storage.revoke(id, by, now)};
        }, r -> {
            Punishment x = (Punishment) r[0];
            if (x == null) {
                msg.tell(actor, "errors.unknown-id", Msg.vars().text("id", id));
                return;
            }
            if (!(Boolean) r[1]) {
                msg.tell(actor, "errors.already-revoked", Msg.vars().text("id", id));
                return;
            }
            if (x.type() == Type.MUTE) {
                mutes.remove(x.target());
                Player p = Bukkit.getPlayer(x.target());
                if (p != null) Platform.send(p, msg().component("target.unmute", vars(x)));
            }
            msg.tell(actor, "done.revoke", vars(x));
            Bukkit.getPluginManager().callEvent(new PunishmentRevokeEvent(x.type(), x.target(), x.targetName(), actor));
        }, actor);
    }

    // ---------------------------------------------------------------- reading

    public void summary(CommandSender asker, String name, Consumer<Summary> then) {
        Player online = Bukkit.getPlayerExact(name);
        long now = System.currentTimeMillis();
        long since = settings().warningLifetime > 0 ? now - settings().warningLifetime : 0;
        async(() -> {
            Target t = resolve(name, online);
            if (t == null) return null;
            return new Summary(t, storage.player(t.uuid()).orElse(null),
                    storage.inForce(Type.MUTE, t.uuid(), null, now),
                    storage.inForce(Type.BAN, t.uuid(), t.ip(), now),
                    storage.warnings(t.uuid(), since),
                    storage.count(t.uuid()),
                    online != null);
        }, s -> {
            if (s == null) {
                msg().tell(asker, "errors.unknown-player", Msg.vars().text("target", name));
                return;
            }
            then.accept(s);
        }, asker);
    }

    public void history(CommandSender asker, String name, Consumer<List<Punishment>> then) {
        Player online = Bukkit.getPlayerExact(name);
        async(() -> {
            Target t = resolve(name, online);
            return t == null ? null : storage.history(t.uuid(), 200);
        }, rows -> {
            if (rows == null) {
                msg().tell(asker, "errors.unknown-player", Msg.vars().text("target", name));
                return;
            }
            then.accept(rows);
        }, asker);
    }

    /** /history in chat: the fallback, and what console sees. */
    public void historyInChat(CommandSender asker, Summary s, List<Punishment> rows) {
        Msg msg = msg();
        long now = System.currentTimeMillis();
        msg.tell(asker, "history.header", Msg.vars().text("target", s.target().name()).text("count", s.total()).text("warnings", s.warnings()));
        if (rows.isEmpty()) {
            msg.tell(asker, "history.empty", Msg.vars().text("target", s.target().name()));
            return;
        }
        int shown = 0;
        for (Punishment p : rows) {
            if (shown++ >= 15) break;
            Msg.Vars v = vars(p);
            String lengthPart = p.type() == Type.MUTE || p.type() == Type.BAN
                    ? msg.fill(msg.raw("history.length-part"), v) : "";
            v.raw("length_part", lengthPart).raw("status", msg.fill(msg.raw(statusKey(p, now)), Msg.vars().text("by", p.revokedBy())));
            Platform.send(asker, Msg.MM.deserialize(msg.fill(msg.raw("history.line"), v)));
        }
    }

    public static String statusKey(Punishment p, long now) {
        if (p.type() == Type.KICK) return "history.status-none";
        if (p.revokedBy() != null) return "history.status-revoked";
        if (p.type() == Type.WARN) return p.active() ? "history.status-active" : "history.status-expired";
        return p.inForce(now) ? "history.status-active" : "history.status-expired";
    }

    // ---------------------------------------------------------------- joining

    /**
     * Remembers whether this online player holds the exempt permission, so a
     * punishment aimed at them while they are offline can still be refused.
     * Called on join and on quit, which brackets every session.
     */
    public void rememberExempt(Player p) {
        boolean exempt = p.hasPermission(settings().exemptPermission);
        UUID uuid = p.getUniqueId();
        async(() -> {
            storage.exempt(uuid, exempt);
            return null;
        }, null, null);
    }

    /** Pre-login, on the login thread: the ban check, the record, and the mute to cache. */
    public String checkLogin(UUID uuid, String name, String ip) {
        long now = System.currentTimeMillis();
        try {
            Object[] r = blocking(() -> {
                storage.seen(uuid, name, ip, now);
                // An address ban already on a shared address (made before these were refused) is not honoured.
                Optional<Punishment> ban = storage.inForce(Type.BAN, uuid, publicAddress(ip) ? ip : null, now);
                Optional<Punishment> mute = storage.inForce(Type.MUTE, uuid, null, now);
                return new Object[]{ban, mute};
            });
            @SuppressWarnings("unchecked") Optional<Punishment> ban = (Optional<Punishment>) r[0];
            @SuppressWarnings("unchecked") Optional<Punishment> mute = (Optional<Punishment>) r[1];
            mute.ifPresentOrElse(m -> mutes.put(uuid, m), () -> mutes.remove(uuid));
            addressBanned.remove(uuid);
            if (ban.isEmpty()) return null;
            // Banned only by address: whether they are exempt is asked on join.
            if (!ban.get().target().equals(uuid)) {
                addressBanned.put(uuid, ban.get());
                return null;
            }
            return Msg.MM.serialize(banScreen(ban.get()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not check " + name + " for bans; letting them in", e);
            return null;
        }
    }

    /** After join: warnings they missed while offline. */
    public void deliverWarnings(Player player) {
        async(() -> storage.unseenWarnings(player.getUniqueId()), list -> {
            if (!player.isOnline() || list.isEmpty()) return;
            List<Punishment> pending = new ArrayList<>(list);
            if (settings().popup && plugin.dialogs() != null) {
                plugin.dialogs().warned(player, pending.get(pending.size() - 1), -1);
                // The popup shows the newest; the older ones go to chat and count as read.
                for (int i = 0; i < pending.size() - 1; i++) {
                    Platform.send(player, msg().component("target.warn", vars(pending.get(i)).text("count", "?")));
                    markSeen(pending.get(i).id());
                }
            } else {
                for (Punishment p : pending) {
                    Platform.send(player, msg().component("target.warn", vars(p).text("count", "?")));
                    markSeen(p.id());
                }
            }
        }, null);
    }

    /** The popup's button: marks a warning read, when it is the player's own. */
    public void acknowledge(Player player, long id) {
        async(() -> {
            Optional<Punishment> p = storage.get(id);
            if (p.isPresent() && p.get().target().equals(player.getUniqueId())) storage.markSeen(id);
            return null;
        }, null, null);
    }

    public void markSeen(long id) {
        async(() -> {
            storage.markSeen(id);
            return null;
        }, null, null);
    }

    public Punishment muteOf(UUID uuid) {
        Punishment m = mutes.get(uuid);
        if (m != null && !m.inForce(System.currentTimeMillis())) {
            mutes.remove(uuid);
            return null;
        }
        return m;
    }
}
