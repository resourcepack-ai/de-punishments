package ai.resourcepack.punishments.core.dialog;

import ai.resourcepack.punishments.PunishmentsPlugin;
import ai.resourcepack.punishments.api.Punishment;
import ai.resourcepack.dialogs.api.DialogEngineAPI;
import ai.resourcepack.punishments.core.*;

import ai.resourcepack.punishments.api.Punishment.Type;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punishments' screens, drawn in Studio and shipped in the jar as Dialog
 * Engine drawn dialogs ({@code drawn/punishments/}), and with {@link DialogHook}
 * the only code that talks to Dialog Engine. Without it this is never
 * constructed and Punishments is commands only.
 *
 * <p>The pictures are fixed; what changes per player is filled by Dialog
 * Engine from the values handed to {@code show}: names, counts, and a list's
 * rows ({@code hist_3_title}, {@code hist_count}). The panel's Reason, Length
 * and Silent controls are the staff member's own Dialog Engine settings
 * ({@code punish_reason}, {@code punish_length}, {@code punish_silent}), which
 * a click sets and Dialog Engine keeps on the player; the panel's buttons run
 * {@code /punishments act ...}, which reads them.
 */
public final class Dialogs {

    public static final String NAMESPACE = "punishments";
    static final int HISTORY_ROWS = 12;
    static final int PLAYER_ROWS = 6;

    /** A ban waiting on its confirmation, per staff member. */
    public record Pending(Type type, String target, Long length, boolean silent, String reasonInput, boolean temp,
                   String reasonLabel, String lengthLabel) {}

    /** Which paged list a staff member last opened, so Next and Previous know what to turn. */
    private record Paging(String list, String target, int page) {}

    private final PunishmentsPlugin plugin;
    private final DialogEngineAPI engine;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Paging> paging = new ConcurrentHashMap<>();
    private final Map<UUID, List<Punishment>> histories = new ConcurrentHashMap<>();

    Dialogs(PunishmentsPlugin plugin, DialogEngineAPI engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    public void forget(Player p) {
        pending.remove(p.getUniqueId());
        paging.remove(p.getUniqueId());
        histories.remove(p.getUniqueId());
    }

    private boolean show(Player viewer, String id, Map<String, String> values) {
        boolean shown = engine.open(viewer, NAMESPACE + ":" + id, values);
        if (!shown) plugin.msg().tell(viewer, "errors.no-dialogs", null);
        return shown;
    }

    public void close(Player viewer) {
        engine.close(viewer);
    }

    /**
     * A list's rows as the values a drawn dialog reads: {@code name_3} is row
     * three's main value (its {@code ""} field), {@code name_3_title} another
     * field, and {@code name_count} how many there are, which is what makes a
     * row past the end empty rather than unknown.
     */
    private static void rows(Map<String, String> values, String list, List<Map<String, String>> rows) {
        values.put(list + "_count", String.valueOf(rows.size()));
        for (int i = 0; i < rows.size(); i++) {
            for (Map.Entry<String, String> field : rows.get(i).entrySet()) {
                values.put(list + "_" + (i + 1) + (field.getKey().isEmpty() ? "" : "_" + field.getKey()), field.getValue());
            }
        }
    }

    // ---------------------------------------------------------------- the panel

    public void panel(Player viewer, Service.Summary s) {
        paging.remove(viewer.getUniqueId());
        Msg msg = plugin.msg();
        var dates = plugin.settings().dates;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("target", s.target().name());
        v.put("presence_dot", s.online() ? "online" : "offline");
        v.put("presence", s.online() ? msg.raw("panel.online")
                : s.record() == null ? msg.raw("panel.never-joined")
                : msg.fill(msg.raw("panel.last-seen"), Msg.vars().raw("ago", ago(s.record().lastSeen()))));
        v.put("warnings", String.valueOf(s.warnings()));
        v.put("total", String.valueOf(s.total()));
        v.put("mute_state", state(s.mute()));
        v.put("ban_state", state(s.ban()));
        // Unmute and Unban are one-row lists: shown only when there is something to lift.
        rows(v, "unmute", s.mute().isPresent() ? List.of(Map.of("", "1")) : List.of());
        rows(v, "unban", s.ban().isPresent() ? List.of(Map.of("", "1")) : List.of());
        show(viewer, "panel", v);
    }

    /** "3d", "5h", "12m": the largest whole unit since then, which is all the panel has room for. */
    static String ago(long then) {
        long s = Math.max(0, (System.currentTimeMillis() - then) / 1000);
        if (s < 60) return "1m";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        if (s < 86400L * 365) return (s / 86400) + "d";
        return (s / (86400L * 365)) + "y";
    }

    private String state(Optional<Punishment> p) {
        Msg msg = plugin.msg();
        if (p.isEmpty()) return msg.raw("panel.no");
        Punishment x = p.get();
        if (x.permanent()) return msg.raw("panel.permanently");
        return msg.fill(msg.raw("panel.left"), Msg.vars().raw("left",
                Durations.format(Math.max(0, x.expires() - System.currentTimeMillis()), msg.raw("permanent"))));
    }

    /** Opens the panel about somebody again, after an action on it. Behind the action on the database thread, so it shows the result. */
    public void refreshPanel(Player viewer, String target) {
        plugin.service().summary(viewer, target, summary -> {
            if (viewer.isOnline()) panel(viewer, summary);
        });
    }

    // ---------------------------------------------------------------- what the controls say

    /** The staff member's chosen reason, as a preset key of config.yml. */
    public String reasonKey(Player p) {
        String raw = engine.setting(p, "punish_reason").orElse("hacking");
        Map<String, Settings.Reason> reasons = plugin.settings().reasons;
        if (reasons.containsKey(raw)) return raw;
        String dashed = raw.replace('_', '-');
        if (reasons.containsKey(dashed)) return dashed;
        return reasons.isEmpty() ? raw : reasons.keySet().iterator().next();
    }

    /** The chosen length: null for the reason's default, PERMANENT, or millis. */
    public Long length(Player p) {
        String raw = engine.setting(p, "punish_length").orElse("default");
        if (raw.equals("default") || raw.equals("auto")) return null;
        long ms = Durations.parse(raw);
        return ms == 0 ? null : ms;
    }

    public boolean silent(Player p) {
        return engine.setting(p, "punish_silent").map(v -> v.equals("on")).orElse(false);
    }

    // ---------------------------------------------------------------- confirmation

    public void confirm(Player viewer, Pending p) {
        pending.put(viewer.getUniqueId(), p);
        Msg msg = plugin.msg();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("target", p.target());
        boolean permanent = p.length() != null && p.length() == Durations.PERMANENT;
        v.put("confirm_title", msg.fill(msg.raw(permanent ? "confirm.title-ban" : "confirm.title-tempban"), Msg.vars().raw("target", p.target())));
        v.put("confirm_line", msg.fill(msg.raw(permanent ? "confirm.line-ban" : "confirm.line-tempban"),
                Msg.vars().raw("length", p.lengthLabel())));
        v.put("confirm_reason", p.reasonLabel());
        v.put("confirm_length", p.lengthLabel());
        v.put("confirm_audience", msg.raw(p.silent() ? "confirm.audience-staff" : "confirm.audience-everyone"));
        show(viewer, "confirm", v);
    }

    public Pending takePending(Player viewer) {
        return pending.remove(viewer.getUniqueId());
    }

    // ---------------------------------------------------------------- history

    public void history(Player viewer, Service.Summary s, List<Punishment> rows) {
        histories.put(viewer.getUniqueId(), rows);
        Paging old = paging.get(viewer.getUniqueId());
        int page = old != null && old.list().equals("hist") && old.target().equalsIgnoreCase(s.target().name()) ? old.page() : 1;
        paging.put(viewer.getUniqueId(), new Paging("hist", s.target().name(), page));
        showHistory(viewer, s.target().name(), s.total(), s.warnings());
    }

    private void showHistory(Player viewer, String target, int total, int warnings) {
        Msg msg = plugin.msg();
        List<Punishment> rows = histories.getOrDefault(viewer.getUniqueId(), List.of());
        int pages = Math.max(1, (rows.size() + HISTORY_ROWS - 1) / HISTORY_ROWS);
        Paging pg = paging.get(viewer.getUniqueId());
        int page = Math.max(1, Math.min(pages, pg == null ? 1 : pg.page()));
        paging.put(viewer.getUniqueId(), new Paging("hist", target, page));
        long now = System.currentTimeMillis();
        List<Map<String, String>> list = new ArrayList<>();
        for (Punishment p : rows.subList((page - 1) * HISTORY_ROWS, Math.min(rows.size(), page * HISTORY_ROWS))) {
            Msg.Vars vars = plugin.service().vars(p);
            Map<String, String> row = new LinkedHashMap<>();
            // Staff type reasons; tags in one are shown as typed, so take them out.
            row.put("", net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(p.reason()));
            row.put("id", String.valueOf(p.id()));
            String status = msg.fill(msg.raw(Service.statusKey(p, now) + "-plain"), Msg.vars().raw("by", p.revokedBy()));
            row.put("title", "#" + p.id() + " " + msg.type(p) + (status.isEmpty() ? "" : " · " + status));
            boolean timed = p.type() == Type.MUTE || p.type() == Type.BAN;
            row.put("meta", msg.raw("history.by") + " " + p.actorName() + " · "
                    + plugin.settings().dates.format(Instant.ofEpochMilli(p.created()))
                    + (timed ? " · " + vars.map.get("length") : ""));
            list.add(row);
        }
        Map<String, String> v = new LinkedHashMap<>();
        rows(v, "hist", list);
        v.put("target", target);
        v.put("history_summary", rows.isEmpty() ? msg.raw("history.clean")
                : msg.fill(msg.raw("history.summary"), Msg.vars().raw("count", String.valueOf(total)).raw("warnings", String.valueOf(warnings))));
        v.put("history_page", msg.fill(msg.raw("history.page"), Msg.vars().raw("page", String.valueOf(page)).raw("pages", String.valueOf(pages))));
        show(viewer, "history", v);
    }

    // ---------------------------------------------------------------- picking somebody

    public void players(Player viewer) {
        Paging old = paging.get(viewer.getUniqueId());
        int page = old != null && old.list().equals("plist") ? old.page() : 1;
        paging.put(viewer.getUniqueId(), new Paging("plist", "", page));
        showPlayers(viewer);
    }

    private void showPlayers(Player viewer) {
        List<Player> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(viewer) && viewer.canSee(p)) online.add(p);
        }
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (online.size() + PLAYER_ROWS - 1) / PLAYER_ROWS);
        Paging pg = paging.get(viewer.getUniqueId());
        int page = Math.max(1, Math.min(pages, pg == null ? 1 : pg.page()));
        paging.put(viewer.getUniqueId(), new Paging("plist", "", page));
        List<String> names = new ArrayList<>();
        for (Player p : online.subList((page - 1) * PLAYER_ROWS, Math.min(online.size(), page * PLAYER_ROWS))) names.add(p.getName());
        Msg msg = plugin.msg();
        Map<String, String> v = new LinkedHashMap<>();
        rows(v, "plist", names.stream().map(n -> Map.of("", n)).toList());
        v.put("plist_note", online.isEmpty() ? msg.raw("players.nobody") : "");
        // Paging only when there is more than one page: Previous and Next are one-row lists.
        v.put("plist_page", pages <= 1 ? ""
                : msg.fill(msg.raw("history.page"), Msg.vars().raw("page", String.valueOf(page)).raw("pages", String.valueOf(pages))));
        rows(v, "pprev", page > 1 ? List.of(Map.of("", "1")) : List.of());
        rows(v, "pnext", page < pages ? List.of(Map.of("", "1")) : List.of());
        show(viewer, "players", v);
    }

    /** Next or Previous on whichever list the staff member has open. */
    public void turn(Player viewer, int by) {
        Paging pg = paging.get(viewer.getUniqueId());
        if (pg == null) return;
        paging.put(viewer.getUniqueId(), new Paging(pg.list(), pg.target(), Math.max(1, pg.page() + by)));
        if (pg.list().equals("plist")) {
            showPlayers(viewer);
        } else {
            plugin.service().summary(viewer, pg.target(), s -> showHistory(viewer, s.target().name(), s.total(), s.warnings()));
        }
    }

    /** After a revoke from the history: read it again and show the same page. */
    public void refreshHistory(Player viewer) {
        Paging pg = paging.get(viewer.getUniqueId());
        if (pg == null || !pg.list().equals("hist")) return;
        plugin.service().summary(viewer, pg.target(), s -> plugin.service().history(viewer, s.target().name(), rows -> {
            if (viewer.isOnline()) history(viewer, s, rows);
        }));
    }

    // ---------------------------------------------------------------- the warning popup

    /** The popup a warned player has to acknowledge. {@code count} -1 when unknown. */
    public void warned(Player target, Punishment p, int count) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(p.id()));
        v.put("reason", p.reason());
        v.put("actor", p.actorName());
        v.put("date", plugin.settings().dates.format(Instant.ofEpochMilli(p.created())));
        v.put("count", count < 0 ? "?" : String.valueOf(count));
        engine.open(target, NAMESPACE + ":warned", v);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
