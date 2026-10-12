package ai.resourcepack.punishments.core.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything that differs between Spigot and Paper, in one place. The plugin
 * compiles against spigot-api, which Paper also implements, so the rest of the
 * code is the same on both.
 *
 * <p>Text is Adventure throughout (shaded and relocated, so it is ours on both
 * servers) and leaves as BungeeCord chat for a player and as a legacy string
 * for the console, a kick screen and a refused login, which is how the Bukkit
 * API spells those.
 */
public final class Platform {

    private Platform() {}

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    public static void send(CommandSender to, Component message) {
        if (to instanceof Player p) {
            p.spigot().sendMessage(BungeeComponentSerializer.get().serialize(message));
        } else {
            to.sendMessage(LEGACY.serialize(message));
        }
    }

    public static String legacy(Component text) {
        return LEGACY.serialize(text);
    }

    public static void kick(Player player, Component screen) {
        player.kickPlayer(LEGACY.serialize(screen));
    }

    /** The server's command map. Paper has {@code Bukkit.getCommandMap()}; both servers have CraftServer's. */
    public static CommandMap commandMap() {
        try {
            return (CommandMap) Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reach the command map", e);
        }
    }

    /** The command map's own table: Paper's getter, or Spigot's protected field. */
    @SuppressWarnings("unchecked")
    public static Map<String, Command> knownCommands(CommandMap map) {
        try {
            return (Map<String, Command>) map.getClass().getMethod("getKnownCommands").invoke(map);
        } catch (ReflectiveOperationException ignored) {
            // Spigot: no getter, only the field
        }
        for (Class<?> c = map.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("knownCommands");
                f.setAccessible(true);
                return (Map<String, Command>) f.get(map);
            } catch (ReflectiveOperationException ignored) {
                // up a class
            }
        }
        return new HashMap<>();
    }

    /**
     * Somebody who has played here, by name, without asking Mojang: Paper's
     * {@code getOfflinePlayerIfCached}, else the server's list of everyone who
     * has joined. Null when neither knows the name.
     */
    public static OfflinePlayer cachedOffline(String name) {
        try {
            return (OfflinePlayer) Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class).invoke(null, name);
        } catch (ReflectiveOperationException ignored) {
            // Spigot
        }
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }
}
