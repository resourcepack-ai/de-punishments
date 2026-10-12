package ai.resourcepack.punishments.core;

import ai.resourcepack.punishments.PunishmentsPlugin;
import ai.resourcepack.punishments.api.Punishment;

import ai.resourcepack.punishments.core.platform.Platform;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

/** Where punishments bite: logging in, talking, and the commands a mute covers. */
public final class Listeners implements Listener {

    private final PunishmentsPlugin plugin;

    public Listeners(PunishmentsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String ip = event.getAddress() == null ? null : event.getAddress().getHostAddress();
        String screen = plugin.service().checkLogin(event.getUniqueId(), event.getName(), ip);
        if (screen != null) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Platform.legacy(Msg.MM.deserialize(screen)));
    }

    /** An address ban, held back at pre-login until the player's permissions can be asked. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinBanned(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Punishment ban = plugin.service().addressBanned.remove(p.getUniqueId());
        if (ban == null || p.hasPermission(plugin.settings().exemptPermission)) return;
        event.setJoinMessage(null);
        Platform.kick(p, plugin.service().banScreen(ban));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.service().rememberExempt(p);
        // A moment after joining, so a popup is not lost in the loading screen.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.service().deliverWarnings(p);
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().rememberExempt(event.getPlayer());
        plugin.service().mutes.remove(event.getPlayer().getUniqueId());
        plugin.service().addressBanned.remove(event.getPlayer().getUniqueId());
        if (plugin.dialogs() != null) plugin.dialogs().forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // Paper's own chat event is not Spigot's; this one is both servers'
    public void onChat(AsyncPlayerChatEvent event) {
        Punishment mute = plugin.service().muteOf(event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        tellMuted(event.getPlayer(), mute);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Punishment mute = plugin.service().muteOf(event.getPlayer().getUniqueId());
        if (mute == null) return;
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        String bare = colon >= 0 ? label.substring(colon + 1) : label;
        var blocked = plugin.settings().blockedCommands;
        boolean block = blocked.contains(bare);
        if (!block) {
            // An alias of a blocked command is blocked too: /tell is /msg on most servers.
            Command c = Platform.commandMap().getCommand(label);
            if (c != null) {
                block = blocked.contains(c.getName().toLowerCase(Locale.ROOT))
                        || c.getAliases().stream().anyMatch(a -> blocked.contains(a.toLowerCase(Locale.ROOT)));
            }
        }
        if (!block) return;
        event.setCancelled(true);
        tellMuted(event.getPlayer(), mute);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!plugin.settings().blockSigns) return;
        Punishment mute = plugin.service().muteOf(event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        tellMuted(event.getPlayer(), mute);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        if (!plugin.settings().blockBooks) return;
        Punishment mute = plugin.service().muteOf(event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        tellMuted(event.getPlayer(), mute);
    }

    private void tellMuted(Player p, Punishment mute) {
        Msg.Vars v = plugin.service().vars(mute);
        plugin.msg().send(p, mute.permanent() ? "muted-permanent" : "muted", v);
    }
}
