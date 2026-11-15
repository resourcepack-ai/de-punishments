package ai.resourcepack.punishments.api;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What another plugin can ask Punishments, and ask it to do. Get it with
 * {@link Punishments#api()}.
 *
 * <p>Everything about an offline player is in the database, so those answers
 * come back as a {@link CompletableFuture}, completed on the main thread. Only
 * {@link #mute(Player)} answers at once, because chat cannot wait.
 */
public interface PunishmentsAPI {

    /**
     * The mute in force on an online player, or empty. Answers at once, from
     * memory: the question a chat plugin asks on every message.
     */
    Optional<Punishment> mute(Player player);

    /** Whether an online player is muted right now. */
    default boolean isMuted(Player player) {
        return mute(player).isPresent();
    }

    /** The mute in force on anybody, online or not. */
    CompletableFuture<Optional<Punishment>> activeMute(UUID player);

    /** The ban in force on anybody. Bans by address are not looked at: this is the player's own. */
    CompletableFuture<Optional<Punishment>> activeBan(UUID player);

    /**
     * Somebody's punishments, newest first, lifted and run-out ones included:
     * {@link Punishment#inForce} tells them apart.
     *
     * @param limit how many at most, 1 to 500
     */
    CompletableFuture<List<Punishment>> history(UUID player, int limit);

    /**
     * How many warnings count against somebody now: the ones not lifted and
     * not older than {@code warnings.expire-after} in config.yml, which is the
     * number an escalation is decided by.
     */
    CompletableFuture<Integer> warnings(UUID player);

    /**
     * Punishes somebody, exactly as if {@code actor} had typed the command:
     * the same reason presets ({@code #hacking}), limits, exemptions, events,
     * broadcast and escalation, and {@code actor} is told the outcome in chat.
     * The one thing not asked is the command's own permission, since your
     * plugin has already decided.
     *
     * @param actor  who is punishing; null for the console
     * @param target their name, online or not
     * @param reason the reason, or null for the configured default
     * @param length how long, in milliseconds, for a mute or a ban:
     *               {@link Punishment#NEVER} for ever, 0 for the configured
     *               default (a ban's is for ever). Ignored for a warning and a kick
     * @param silent whether to skip the broadcast
     */
    void punish(CommandSender actor, Punishment.Type type, String target, String reason, long length, boolean silent);

    /**
     * Lifts somebody's mute or ban, or their latest warning, as
     * {@code /unmute}, {@code /unban} and {@code /unwarn} do. {@code actor} is
     * told the outcome; null for the console. A kick has nothing to lift.
     */
    void revoke(CommandSender actor, Punishment.Type type, String target);
}
