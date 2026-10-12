package ai.resourcepack.punishments.core;

import ai.resourcepack.punishments.PunishmentsPlugin;
import ai.resourcepack.punishments.api.Punishment;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** config.yml, read once per load into plain values. */
public final class Settings {

    public record Reason(String id, String label, long mute, long tempban) {}

    public record Length(String id, String label) {}

    public record Limit(String permission, long mute, long tempban, boolean permanentBan) {}

    public record CommandSpec(boolean enabled, String name, List<String> aliases) {}

    public final FileConfiguration cfg;
    public final Map<String, Reason> reasons = new LinkedHashMap<>();
    public final List<Length> lengths = new ArrayList<>();
    public final List<Limit> limits = new ArrayList<>();
    public final TreeMap<Integer, String> escalation = new TreeMap<>();
    public final Set<String> blockedCommands;
    public final String reasonFormat;
    public final String defaultReason;
    public final boolean requireReason;
    public final long defaultMute;
    public final long defaultTempban;
    public final long warningLifetime;
    public final boolean popup;
    public final boolean blockSigns;
    public final boolean blockBooks;
    public final String exemptPermission;
    public final boolean allowSelf;
    public final DateTimeFormatter dates;

    public Settings(FileConfiguration cfg) {
        this.cfg = cfg;
        ConfigurationSection r = cfg.getConfigurationSection("reasons");
        if (r != null) {
            for (String id : r.getKeys(false)) {
                ConfigurationSection s = r.getConfigurationSection(id);
                if (s == null) continue;
                reasons.put(id.toLowerCase(Locale.ROOT), new Reason(id.toLowerCase(Locale.ROOT), s.getString("label", id),
                        length(s.getString("mute")), length(s.getString("tempban"))));
            }
        }
        for (Map<?, ?> m : cfg.getMapList("lengths")) {
            Object id = m.get("id");
            if (id == null) continue;
            Object label = m.get("label");
            lengths.add(new Length(String.valueOf(id), label == null ? String.valueOf(id) : String.valueOf(label)));
        }
        ConfigurationSection l = cfg.getConfigurationSection("limits");
        if (l != null) {
            for (String key : l.getKeys(false)) {
                ConfigurationSection s = l.getConfigurationSection(key);
                if (s == null) continue;
                limits.add(new Limit(s.getString("permission", "punishments.limit." + key),
                        length(s.getString("mute", "perm")), length(s.getString("tempban", "perm")), s.getBoolean("permanent-ban", true)));
            }
        }
        for (Map<?, ?> m : cfg.getMapList("warnings.escalation")) {
            Object count = m.get("count");
            Object command = m.get("command");
            if (count == null || command == null) continue;
            try {
                escalation.put(Integer.parseInt(String.valueOf(count)), String.valueOf(command));
            } catch (NumberFormatException ignored) {
                // a count that is not a number does nothing
            }
        }
        blockedCommands = cfg.getStringList("mute.blocked-commands").stream()
                .map(s -> s.toLowerCase(Locale.ROOT).replaceFirst("^/", "")).collect(Collectors.toSet());
        reasonFormat = cfg.getString("reason-format", "{preset} ({details})");
        defaultReason = cfg.getString("default-reason", "No reason given");
        requireReason = cfg.getBoolean("require-reason", false);
        defaultMute = orElse(length(cfg.getString("default-length.mute", "1h")), 3_600_000L);
        defaultTempban = orElse(length(cfg.getString("default-length.tempban", "1d")), 86_400_000L);
        warningLifetime = length(cfg.getString("warnings.expire-after", "0"));
        popup = cfg.getBoolean("warnings.popup", true);
        blockSigns = cfg.getBoolean("mute.block-signs", true);
        blockBooks = cfg.getBoolean("mute.block-books", false);
        exemptPermission = cfg.getString("protection.exempt-permission", "punishments.exempt");
        allowSelf = cfg.getBoolean("protection.allow-self", false);
        DateTimeFormatter f;
        try {
            f = DateTimeFormatter.ofPattern(cfg.getString("date-format", "d MMM yyyy, HH:mm"), Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            f = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
        }
        dates = f.withZone(ZoneId.systemDefault());
    }

    private static long orElse(long v, long fallback) {
        return v == 0 ? fallback : v;
    }

    /** A length from config: millis, PERMANENT, or 0 for "not set". */
    public static long length(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        return Durations.parse(raw.trim());
    }

    public CommandSpec command(String key) {
        ConfigurationSection s = cfg.getConfigurationSection("commands." + key);
        if (s == null) return new CommandSpec(true, key, List.of());
        return new CommandSpec(s.getBoolean("enabled", true), s.getString("name", key).toLowerCase(Locale.ROOT).replaceFirst("^/", ""),
                s.getStringList("aliases").stream().map(a -> a.toLowerCase(Locale.ROOT).replaceFirst("^/", "")).toList());
    }

    /** everyone | staff | none for a kind of announcement. */
    public String broadcast(String kind) {
        return cfg.getString("broadcast." + kind, "everyone").toLowerCase(Locale.ROOT);
    }

    /**
     * The most generous limit that applies to the sender, or null for no cap
     * (console, or a player holding none of the limit permissions).
     */
    public Limit limitFor(CommandSender sender) {
        if (!(sender instanceof Player p)) return null;
        Limit best = null;
        for (Limit l : limits) {
            if (!p.hasPermission(l.permission())) continue;
            if (best == null) {
                best = l;
                continue;
            }
            best = new Limit(best.permission(), max(best.mute(), l.mute()), max(best.tempban(), l.tempban()),
                    best.permanentBan() || l.permanentBan());
        }
        return best;
    }

    private static long max(long a, long b) {
        if (a == Durations.PERMANENT || b == Durations.PERMANENT) return Durations.PERMANENT;
        return Math.max(a, b);
    }
}
