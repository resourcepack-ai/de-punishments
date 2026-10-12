package ai.resourcepack.verdict.event;

import ai.resourcepack.verdict.Punishment;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A punishment is on the record and has been carried out. Main thread. */
public final class PunishmentEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Punishment punishment;
    private final CommandSender actor;

    public PunishmentEvent(Punishment punishment, CommandSender actor) {
        this.punishment = punishment;
        this.actor = actor;
    }

    public Punishment getPunishment() { return punishment; }
    public CommandSender getActor() { return actor; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
