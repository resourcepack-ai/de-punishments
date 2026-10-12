package ai.resourcepack.punishments.core;

import ai.resourcepack.punishments.PunishmentsPlugin;
import ai.resourcepack.punishments.api.Punishment;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import ai.resourcepack.punishments.core.platform.Platform;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * messages.yml. Values handed in through {@link Vars#text} are escaped, so a
 * reason somebody typed is printed as typed; {@link Vars#raw} is for pieces
 * that are themselves from messages.yml.
 */
public final class Msg {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlConfiguration yml;
    private final YamlConfiguration defaults;

    public Msg(YamlConfiguration yml, YamlConfiguration defaults) {
        this.yml = yml;
        this.defaults = defaults;
    }

    public static final class Vars {
        public final Map<String, String> map = new LinkedHashMap<>();

        public Vars text(String key, Object value) {
            map.put(key, MM.escapeTags(value == null ? "" : String.valueOf(value)));
            return this;
        }

        public Vars raw(String key, String value) {
            map.put(key, value == null ? "" : value);
            return this;
        }
    }

    public static Vars vars() {
        return new Vars();
    }

    /** The template at a path, from messages.yml, else from the copy inside the jar. */
    public String raw(String path) {
        String v = yml.getString(path);
        if (v == null) v = defaults.getString(path);
        return v == null ? path : v;
    }

    public String fill(String template, Vars vars) {
        String out = template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.map.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    public Component component(String path, Vars vars) {
        return MM.deserialize(fill(raw(path), vars));
    }

    /** With the prefix in front: what staff are told. */
    public void tell(CommandSender to, String path, Vars vars) {
        Platform.send(to, MM.deserialize(raw("prefix") + fill(raw(path), vars)));
    }

    public void tellRaw(CommandSender to, String miniMessage) {
        Platform.send(to, MM.deserialize(raw("prefix") + miniMessage));
    }

    /** Without the prefix: what a punished player is told. */
    public void send(CommandSender to, String path, Vars vars) {
        Platform.send(to, component(path, vars));
    }

    public String type(Punishment p) {
        return raw("types." + p.kind());
    }
}
