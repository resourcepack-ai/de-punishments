package ai.resourcepack.punishments.core;

import ai.resourcepack.punishments.api.Punishment;
import ai.resourcepack.punishments.api.Punishment.Type;
import ai.resourcepack.punishments.api.PunishmentsAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@link PunishmentsAPI}, over the service the commands use. Internal. */
public final class Api implements PunishmentsAPI {

    private final Service service;

    public Api(Service service) {
        this.service = service;
    }

    @Override
    public Optional<Punishment> mute(Player player) {
        return player == null ? Optional.empty() : Optional.ofNullable(service.muteOf(player.getUniqueId()));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> activeMute(UUID player) {
        if (player == null) return CompletableFuture.completedFuture(Optional.empty());
        return service.ask(() -> service.storage.inForce(Type.MUTE, player, null, System.currentTimeMillis()));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> activeBan(UUID player) {
        if (player == null) return CompletableFuture.completedFuture(Optional.empty());
        return service.ask(() -> service.storage.inForce(Type.BAN, player, null, System.currentTimeMillis()));
    }

    @Override
    public CompletableFuture<List<Punishment>> history(UUID player, int limit) {
        if (player == null) return CompletableFuture.completedFuture(List.of());
        int most = Math.max(1, Math.min(500, limit));
        return service.ask(() -> List.copyOf(service.storage.history(player, most)));
    }

    @Override
    public CompletableFuture<Integer> warnings(UUID player) {
        if (player == null) return CompletableFuture.completedFuture(0);
        long lifetime = service.settings().warningLifetime;
        return service.ask(() -> service.storage.warnings(player, lifetime > 0 ? System.currentTimeMillis() - lifetime : 0));
    }

    @Override
    public void punish(CommandSender actor, Type type, String target, String reason, long length, boolean silent) {
        if (type == null || target == null || target.isBlank()) return;
        CommandSender by = actor == null ? Bukkit.getConsoleSender() : actor;
        // Zero is "the configured default", which the service reads as no length given.
        Long asked = length == 0 ? null : Long.valueOf(length < 0 ? Durations.PERMANENT : length);
        service.punish(new Service.Request(by, type, target.trim(), asked, silent, false, reason, false));
    }

    @Override
    public void revoke(CommandSender actor, Type type, String target) {
        if (type == null || type == Type.KICK || target == null || target.isBlank()) return;
        service.lift(actor == null ? Bukkit.getConsoleSender() : actor, type, target.trim());
    }
}
