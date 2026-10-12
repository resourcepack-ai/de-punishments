package ai.resourcepack.verdict;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Verdict: warn, mute, kick, temp-ban and ban, from a dialog or a command.
 * The panel needs RP Engine (its dialogs ship in this jar); the commands need nothing.
 */
public final class VerdictPlugin extends JavaPlugin {

    private Settings settings;
    private Msg msg;
    private Service service;
    private Commands commands;
    private Dialogs dialogs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) saveResource("messages.yml", false);
        loadFiles();

        Storage storage = new Storage(getConfig().getConfigurationSection("storage") == null
                ? new YamlConfiguration() : getConfig().getConfigurationSection("storage"), getDataFolder());
        try {
            storage.open();
        } catch (Exception e) {
            getLogger().severe("Could not open the punishment database: " + e.getMessage());
            getLogger().severe("Verdict is disabled. Fix storage: in config.yml and restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service = new Service(this, storage);
        commands = new Commands(this);
        commands.register();
        getServer().getPluginManager().registerEvents(new Listeners(this), this);

        Plugin engine = getServer().getPluginManager().getPlugin("RPEngine");
        if (engine != null && engine.isEnabled()) {
            try {
                dialogs = EngineHook.attach(this, engine);
            } catch (LinkageError | RuntimeException e) {
                getLogger().warning("RP Engine is installed but could not be used (" + e + "); commands only.");
                dialogs = null;
            }
        } else {
            getLogger().info("RP Engine is not installed: commands only. Install it for the punishment panel.");
        }
    }

    @Override
    public void onDisable() {
        if (commands != null) commands.unregister();
        if (service != null) service.shutdown();
    }

    private void loadFiles() {
        reloadConfig();
        settings = new Settings(getConfig());
        // A permission nobody declared is granted to every op. A cap or an
        // exemption must be given on purpose, so declare them off for everyone.
        var pm = getServer().getPluginManager();
        java.util.List<String> offByDefault = new java.util.ArrayList<>();
        for (Settings.Limit l : settings.limits) offByDefault.add(l.permission());
        offByDefault.add(settings.exemptPermission);
        for (String node : offByDefault) {
            if (pm.getPermission(node) == null) {
                pm.addPermission(new org.bukkit.permissions.Permission(node, org.bukkit.permissions.PermissionDefault.FALSE));
            }
        }
        YamlConfiguration defaults = new YamlConfiguration();
        var in = getResource("messages.yml");
        if (in != null) defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        msg = new Msg(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml")), defaults);
    }

    void reloadEverything() {
        loadFiles();
        commands.register();
    }

    Settings settings() {
        return settings;
    }

    Msg msg() {
        return msg;
    }

    Service service() {
        return service;
    }

    Dialogs dialogs() {
        return dialogs;
    }
}
