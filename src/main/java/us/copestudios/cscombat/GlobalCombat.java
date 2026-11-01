package us.copestudios.cscombat;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * "Global combat": PvP on the whole server only at certain times (schedule) or when staff turn it on (manual).
 *
 * <p>Same settings as 1.0.2. New: when the schedule switches PvP on or off, everyone is told
 * ({@code announce-schedule}), and when PvP switches off all combat tags end, so nobody stays stuck
 * unable to /spawn.</p>
 */
final class GlobalCombat {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("H:mm");

    private final CSCombat plugin;
    /** Last state seen by the timer, to notice schedule changes. Null = not checked yet. */
    private Boolean lastActive;

    GlobalCombat(CSCombat plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    boolean enabled() {
        return config().getBoolean("global-combat.enabled", false);
    }

    String mode() {
        return config().getString("global-combat.mode", "manual").toLowerCase(java.util.Locale.ROOT);
    }

    boolean manualState() {
        return config().getBoolean("global-combat.manual-state", true);
    }

    List<String> ranges() {
        return config().getStringList("global-combat.time-ranges");
    }

    /** Is PvP allowed right now? (Always true when global combat is turned off in the config.) */
    boolean active() {
        if (!enabled()) {
            return true;
        }
        return switch (mode()) {
            case "manual" -> manualState();
            case "schedule" -> withinSchedule();
            default -> true;
        };
    }

    /** /cscombat toggle (manual mode): flips and saves manual-state. Returns the new state. */
    boolean toggle() {
        boolean state = !manualState();
        config().set("global-combat.manual-state", state);
        plugin.saveConfig();
        lastActive = state;
        changed(state);
        return state;
    }

    private LocalTime now() {
        String zone = config().getString("global-combat.timezone", "");
        if (zone != null && !zone.isBlank()) {
            try {
                return LocalTime.now(ZoneId.of(zone.trim()));
            } catch (DateTimeException ignored) {
                plugin.getLogger().warning("global-combat.timezone '" + zone + "' is not a valid time zone, using server time.");
            }
        }
        return LocalTime.now();
    }

    private boolean withinSchedule() {
        LocalTime now = now();
        for (String range : ranges()) {
            String[] times = range.split("-");
            if (times.length != 2) {
                plugin.getLogger().warning("Invalid time range (use HH:mm-HH:mm): " + range);
                continue;
            }
            try {
                LocalTime start = LocalTime.parse(times[0].trim(), HH_MM);
                LocalTime end = LocalTime.parse(times[1].trim(), HH_MM);
                boolean inside = start.isBefore(end)
                        ? !now.isBefore(start) && now.isBefore(end)
                        : !now.isBefore(start) || now.isBefore(end); // crosses midnight, e.g. 22:00-02:00
                if (inside) {
                    return true;
                }
            } catch (DateTimeException error) {
                plugin.getLogger().warning("Invalid time range (use HH:mm-HH:mm): " + range);
            }
        }
        return false;
    }

    /** Timer (every second): announces schedule changes. */
    void tick() {
        boolean active = active();
        if (lastActive == null) {
            lastActive = active;
            return;
        }
        if (active != lastActive) {
            lastActive = active;
            if (config().getBoolean("global-combat.announce-schedule", true)) {
                changed(active);
            } else if (!active) {
                plugin.tags().clear(false);
            }
        }
    }

    /** Resets the change detector (after /cscombat reload). */
    void reset() {
        lastActive = null;
    }

    private void changed(boolean active) {
        plugin.messages().broadcast(active ? "global-combat-enabled" : "global-combat-disabled");
        if (!active) {
            plugin.tags().clear(false);
        }
    }
}
