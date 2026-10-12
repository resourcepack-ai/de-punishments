package ai.resourcepack.punishments.api;

import ai.resourcepack.punishments.core.Durations;

import java.util.Locale;
import java.util.UUID;

/**
 * One row of the record. A temp-ban is a {@link Type#BAN} with an expiry;
 * a kick and a warning are never "active" in the sense of being enforced,
 * though a warning counts toward escalation until it is revoked or old.
 */
public record Punishment(
        long id,
        Type type,
        UUID target,
        String targetName,
        String ip,
        String reason,
        UUID actor,
        String actorName,
        long created,
        long expires,
        boolean active,
        String revokedBy,
        long revokedAt,
        boolean silent,
        boolean seen) {

    public enum Type {
        WARN, MUTE, KICK, BAN;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code expires} for one that never runs out. */
    public static final long NEVER = -1;

    public boolean permanent() {
        return expires == NEVER;
    }

    /** In force right now: not revoked and not run out. */
    public boolean inForce(long now) {
        return active && (expires == NEVER || expires > now);
    }

    /** The command-word for this row: tempban for a ban that runs out. */
    public String kind() {
        if (type == Type.BAN && !permanent()) return "tempban";
        if (type == Type.MUTE && !permanent()) return "tempmute";
        return type.key();
    }
}
