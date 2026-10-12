package ai.resourcepack.punishments.api.event;

import ai.resourcepack.punishments.api.Punishment.Type;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A punishment is about to be recorded. Cancel it to stop it; change the
 * reason if you like. Fired on the main thread, before the target is looked up.
 */
public final class PunishmentPreEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender actor;
    private final Type type;
    private final String targetName;
    private final long length;
    private final boolean silent;
    private String reason;
    private boolean cancelled;

    public PunishmentPreEvent(CommandSender actor, Type type, String targetName, String reason, long length, boolean silent) {
        this.actor = actor;
        this.type = type;
        this.targetName = targetName;
        this.reason = reason;
        this.length = length;
        this.silent = silent;
    }

    public CommandSender getActor() { return actor; }
    public Type getType() { return type; }
    /** As typed: the player may be offline, and is not looked up yet. */
    public String getTargetName() { return targetName; }
    /** Millis, or -1 for permanent (and for warnings and kicks, which have none). */
    public long getLength() { return length; }
    public boolean isSilent() { return silent; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
