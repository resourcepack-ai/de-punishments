package ai.resourcepack.punishments.core.dialog;

import ai.resourcepack.dialogs.api.DialogEngine;
import ai.resourcepack.dialogs.api.DialogEngineAPI;
import ai.resourcepack.punishments.PunishmentsPlugin;

/**
 * Hands Punishments' screens to Dialog Engine: the jar's
 * {@code drawn/punishments/} is installed into Dialog Engine's drawn folder
 * when this version has not installed it yet (Dialog Engine reads them and
 * keeps any the server owner has edited). Kept apart from the plugin class so
 * Dialog Engine's classes are only touched when it is there.
 */
public final class DialogHook {

    private DialogHook() {}

    public static Dialogs attach(PunishmentsPlugin plugin) throws LinkageError {
        DialogEngineAPI api = DialogEngine.api();
        if (api == null) throw new IllegalStateException("Dialog Engine is not ready");
        api.installDrawn(plugin, Dialogs.NAMESPACE);
        for (String screen : new String[] {"panel", "confirm", "history", "players", "warned"}) {
            if (!api.ids().contains(Dialogs.NAMESPACE + ":" + screen)) {
                plugin.getLogger().warning("Dialog Engine has no " + Dialogs.NAMESPACE + ":" + screen + "; the console above says why.");
            }
        }
        plugin.getLogger().info("Dialog Engine found: /punish opens the panel.");
        return new Dialogs(plugin, api);
    }
}
