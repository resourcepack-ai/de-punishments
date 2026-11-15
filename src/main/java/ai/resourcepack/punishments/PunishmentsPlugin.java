package ai.resourcepack.punishments;

import ai.resourcepack.punishments.core.*;
import ai.resourcepack.punishments.core.dialog.DialogHook;
import ai.resourcepack.punishments.core.dialog.Dialogs;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Punishments: warn, mute, kick, temp-ban and ban, from a dialog or a command.
 * The panel needs Dialog Engine (its dialogs ship in this jar); the commands
 * need nothing.
 */
public final class PunishmentsPlugin extends JavaPlugin {

    private Settings settings;
    private Msg msg;
    private Service service;
    private Commands commands;
    private Dialogs dialogs;
    private ai.resourcepack.common.update.UpdateCheck updates;

    /** The SpigotMC resource the update check asks about (listings/punishments/release.json has it too). */
    static final int SPIGOT_RESOURCE = 139570;
    /** The first Dialog Engine with what the panel needs: installDrawn and setting. */
    static final String DIALOG_ENGINE_MIN = "0.4.1";

    @Override
    public void onLoad() {
        // Asked before the worlds load, so the answer is back for the startup banner.
        if (SPIGOT_RESOURCE > 0) {
            ai.resourcepack.common.update.UpdateCheck.early(this, SPIGOT_RESOURCE);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (updates != null) updates.stop();
        updates = SPIGOT_RESOURCE > 0 ? ai.resourcepack.common.update.UpdateCheck.spigot(this, SPIGOT_RESOURCE)
                .name("Punishments")
                .permission("punishments.update")
                .enabledWhen(() -> getConfig().getBoolean("update-check", true))
                .player((player, update) -> msg.tellRaw(player, "Punishments <white>" + update.latest()
                        + "</white> is out (you have " + update.running() + "). <u><click:open_url:'" + update.url()
                        + "'><hover:show_text:'" + update.url() + "'>Get it on SpigotMC</hover></click></u>")) : null;
        ai.resourcepack.common.banner.StartupBanner banner = ai.resourcepack.common.banner.StartupBanner.begin(this)
                .name("Punishments")
                .addonOf("Dialog Engine")
                .tagline("Warn, mute, kick and ban from a panel.")
                .docs("https://resourcepack.ai/docs/punishments");
        if (updates != null) banner.updates(updates);
        if (!new File(getDataFolder(), "messages.yml").exists()) saveResource("messages.yml", false);
        loadFiles();

        Storage storage = new Storage(getConfig().getConfigurationSection("storage") == null
                ? new YamlConfiguration() : getConfig().getConfigurationSection("storage"), getDataFolder());
        try {
            storage.open();
        } catch (Exception e) {
            getLogger().severe("Could not open the punishment database: " + e.getMessage());
            getLogger().severe("Punishments is disabled. Fix storage: in config.yml and restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service = new Service(this, storage);
        ai.resourcepack.punishments.api.Punishments.set(new ai.resourcepack.punishments.core.Api(service));
        commands = new Commands(this);
        commands.register();
        getServer().getPluginManager().registerEvents(new Listeners(this), this);

        Plugin engine = getServer().getPluginManager().getPlugin("DialogEngine");
        if (engine != null && engine.isEnabled()) {
            try {
                dialogs = DialogHook.attach(this);
                banner.ok("Panel", "/punish opens it");
            } catch (LinkageError | RuntimeException e) {
                getLogger().warning("Dialog Engine is installed but could not be used (" + e + "); commands only."
                        + " The panel needs Dialog Engine " + DIALOG_ENGINE_MIN + " or newer.");
                dialogs = null;
            }
        } else {
            banner.ok("Panel", "Commands only. Install Dialog Engine for the panel");
        }

        if (updates != null) updates.start();
        banner.print();
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (commands != null) commands.unregister();
        ai.resourcepack.punishments.api.Punishments.set(null);
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

    public void reloadEverything() {
        loadFiles();
        commands.register();
    }

    public Settings settings() {
        return settings;
    }

    public Msg msg() {
        return msg;
    }

    public Service service() {
        return service;
    }

    public Dialogs dialogs() {
        return dialogs;
    }
}
