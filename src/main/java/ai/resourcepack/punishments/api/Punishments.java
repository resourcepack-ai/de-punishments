package ai.resourcepack.punishments.api;

/**
 * The way in for another plugin: {@code Punishments.api()}.
 *
 * <p>Add {@code depend: [Punishments]} (or {@code softdepend}, and check
 * {@link #isEnabled()} first) to your plugin.yml so it is there before you ask.
 */
public final class Punishments {

    private static volatile PunishmentsAPI api;

    private Punishments() {}

    /** The API. Throws when Punishments is not enabled. */
    public static PunishmentsAPI api() {
        PunishmentsAPI current = api;
        if (current == null) {
            throw new IllegalStateException("Punishments is not enabled. Add depend: [Punishments] to your plugin.yml.");
        }
        return current;
    }

    /** Whether Punishments is running, for a plugin that only soft-depends on it. */
    public static boolean isEnabled() {
        return api != null;
    }

    /** For Punishments itself. */
    public static void set(PunishmentsAPI punishments) {
        api = punishments;
    }
}
