package us.copestudios.cscombat;

import org.bukkit.configuration.file.YamlConfiguration;
import us.copestudios.copecore.config.ConfigFile;
import us.copestudios.copecore.text.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Moves 1.0.2's texts out of config.yml into messages.yml (runs once, on the first start of 2.0.0).
 *
 * <p>Only texts you CHANGED are copied (texts still at the 1.0.2 default get the new 2.0.0 default instead).
 * Your &amp;#RRGGBB colors and {time}/{player} placeholders keep working as they are.</p>
 */
final class LegacyMigration {

    /** config.yml path -> { messages.yml key, 1.0.2 default text }. */
    private static final Map<String, String[]> TEXTS = new LinkedHashMap<>();

    static {
        add("prefix", "prefix", "&#FF5555[CSCombat]&#FFFFFF ");
        add("new-player-protection.protected-message", "protected-target",
                "&#FF5555You cannot attack this player! They have new player protection for {time} more seconds.");
        add("actionbar.combat-message", "actionbar", "&#FF5555⚔ Combat: {time}s");
        add("messages.enter-combat", "enter-combat", "&#FF5555⚔ You are now in combat for {time} seconds!");
        add("messages.exit-combat", "exit-combat", "&#55FF55✓ You are no longer in combat.");
        add("messages.already-in-combat", null, "&#FF5555You are still in combat for {time} seconds!");
        add("messages.combat-logout-death", "combat-logout-death", "&#FF5555{player} logged out during combat and died!");
        add("messages.command-blocked", "command-blocked", "&#FF5555You cannot use commands while in combat! ({time}s remaining)");
        add("messages.global-combat-enabled", "global-combat-enabled", "&#55FF55⚔ Global combat has been enabled!");
        add("messages.global-combat-disabled", "global-combat-disabled", "&#FF5555⚔ Global combat has been disabled!");
        add("messages.global-combat-inactive", "global-combat-inactive", "&#FFAA00Combat is currently disabled on this server.");
        add("messages.new-player-protected", "new-player-protected", "&#55FF55You have {time} seconds of new player protection!");
        add("messages.protection-expired", "protection-expired", "&#FFAA00Your new player protection has expired.");
        add("messages.reload-success", "reload-success", "&#55FF55Configuration reloaded successfully!");
        add("messages.reload-error", "reload-error", "&#FF5555Error reloading configuration. Check console for details.");
        add("messages.combat-status", "combat-status", "&#FFAA00Combat Status: {status}");
        add("messages.time-remaining", "time-remaining", "&#FFAA00Time Remaining: {time} seconds");
        add("messages.not-in-combat", "not-in-combat", "&#55FF55You are not in combat.");
        add("messages.help-header", "generic.help-header", "&#FF5555========== CSCombat Help ==========");
        add("messages.help-footer", "generic.help-footer", "&#FF5555==================================");
        add("messages.no-permission", "generic.no-permission", "&#FF5555You do not have permission to use this command!");
        add("messages.player-not-found", "generic.player-not-found", "&#FF5555Player not found!");
        add("messages.invalid-usage", "generic.usage", "&#FF5555Invalid usage! Use: {usage}");
        add("messages.player-only", "generic.players-only", "&#FF5555This command can only be used by players!");
    }

    private static void add(String oldPath, String newKey, String oldDefault) {
        TEXTS.put(oldPath, new String[]{newKey, oldDefault});
    }

    private LegacyMigration() {
    }

    static void run(CSCombat plugin, ConfigFile config, Messages messages) {
        YamlConfiguration yaml = config.get();
        int moved = 0;
        boolean touched = false;
        for (Map.Entry<String, String[]> entry : TEXTS.entrySet()) {
            String old = yaml.getString(entry.getKey());
            if (old == null) {
                continue;
            }
            String newKey = entry.getValue()[0];
            if (newKey != null && !old.equals(entry.getValue()[1])) {
                messages.file().get().set(newKey, old);
                moved++;
            }
            yaml.set(entry.getKey(), null);
            touched = true;
        }
        if (yaml.isConfigurationSection("messages") && yaml.getConfigurationSection("messages").getKeys(false).isEmpty()) {
            yaml.set("messages", null);
        }
        if (!touched) {
            return;
        }
        config.save();
        if (moved > 0) {
            messages.file().save();
            messages.reload();
            plugin.getLogger().info("Moved " + moved + " of your texts from config.yml to messages.yml.");
        }
    }
}
