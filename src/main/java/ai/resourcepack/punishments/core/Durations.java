package ai.resourcepack.verdict;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "30m", "1h30m", "7d", "2w", "1mo", "1y", "perm". Millis in, words out.
 * {@link #PERMANENT} is -1: a punishment that never runs out.
 */
public final class Durations {

    public static final long PERMANENT = -1;

    private static final Pattern PART = Pattern.compile("(\\d+)(mo|y|w|d|h|m|s)");
    private static final Pattern WHOLE = Pattern.compile("((\\d+)(mo|y|w|d|h|m|s))+");

    private Durations() {}

    /** True when the token reads as a duration (including perm). */
    public static boolean isDuration(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return isPermanent(t) || WHOLE.matcher(t).matches();
    }

    public static boolean isPermanent(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("perm") || t.equals("permanent") || t.equals("forever") || t.equals("never");
    }

    /** Millis, {@link #PERMANENT}, or 0 when it is not a duration. */
    public static long parse(String token) {
        String t = token.toLowerCase(Locale.ROOT).trim();
        if (isPermanent(t)) return PERMANENT;
        if (!WHOLE.matcher(t).matches()) return 0;
        Matcher m = PART.matcher(t);
        long total = 0;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            total += n * switch (m.group(2)) {
                case "y" -> 365L * 86_400_000L;
                case "mo" -> 30L * 86_400_000L;
                case "w" -> 7L * 86_400_000L;
                case "d" -> 86_400_000L;
                case "h" -> 3_600_000L;
                case "m" -> 60_000L;
                default -> 1_000L;
            };
        }
        return total;
    }

    /** "2d 3h", "45m", "Permanent" (the word comes from messages.yml). */
    public static String format(long millis, String permanentWord) {
        if (millis < 0) return permanentWord;
        long s = Math.max(1, millis / 1000);
        long y = s / (365L * 86400); s %= 365L * 86400;
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder out = new StringBuilder();
        if (y > 0) out.append(y).append("y ");
        if (d > 0) out.append(d).append("d ");
        if (h > 0 && y == 0) out.append(h).append("h ");
        if (m > 0 && y == 0 && d == 0) out.append(m).append("m ");
        if (out.isEmpty()) out.append(s).append("s ");
        return out.toString().trim();
    }
}
