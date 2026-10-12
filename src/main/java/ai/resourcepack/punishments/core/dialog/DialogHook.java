package ai.resourcepack.verdict;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.AddonContent;
import org.bukkit.plugin.Plugin;

/**
 * Installs Verdict's dialogs into RP Engine, the way every addon here ships
 * its content: the jar's content/verdict/ is copied into the engine's content
 * folder when this version has not installed it yet, and the engine reloads
 * once to build it into its pack. Kept apart from VerdictPlugin so the
 * engine's classes are only touched when the engine is there.
 */
final class EngineHook {

    private EngineHook() {}

    static Dialogs attach(VerdictPlugin plugin, Plugin found) throws LinkageError {
        if (!(found instanceof RPEnginePlugin engine)) {
            throw new IllegalStateException("RPEngine is not the RP Engine this was built for");
        }
        boolean changed;
        try {
            changed = AddonContent.install(plugin, engine, Dialogs.NAMESPACE);
        } catch (Exception e) {
            throw new IllegalStateException("could not install the dialogs: " + e.getMessage(), e);
        }
        if (changed) {
            plugin.getLogger().info("Installed the punishment dialogs into RP Engine; reloading it once.");
            engine.reload();
        }
        plugin.getLogger().info("RP Engine found: /punish opens the panel.");
        return new Dialogs(plugin, engine);
    }
}
