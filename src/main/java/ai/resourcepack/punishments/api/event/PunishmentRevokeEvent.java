package ai.resourcepack.punishments.api.event;

import ai.resourcepack.punishments.api.Punishment.Type;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** A mute, ban or warning was lifted (unmute, unban, unwarn, or revoke by id). Main thread. */
public final class PunishmentRevokeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Type type;
    private final UUID target;
    private final String targetName;
    private final CommandSender actor;

    public PunishmentRevokeEvent(Type type, UUID target, String targetName, CommandSender actor) {
        this.type = type;
        this.target = target;
        this.targetName = targetName;
        this.actor = actor;
    }

    public Type getType() { return type; }
    public UUID getTarget() { return target; }
    public String getTargetName() { return targetName; }
    public CommandSender getActor() { return actor; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
