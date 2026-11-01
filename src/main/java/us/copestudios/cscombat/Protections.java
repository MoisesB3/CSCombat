package us.copestudios.cscombat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import us.copestudios.cscombat.hook.GriefPreventionHook;
import us.copestudios.cscombat.hook.WorldGuardHook;

/**
 * Asks the protection plugins (WorldGuard, GriefPrevention, ProtectionStones) whether a hit is allowed, and
 * spots Citizens NPCs.
 *
 * <p>Each plugin is only asked when it's installed AND enabled in config.yml "integrations". A hook that throws
 * (e.g. a different plugin version) is switched off with one warning instead of spamming the console on every hit.</p>
 */
final class Protections {

    private final CSCombat plugin;
    private boolean worldGuard;
    private boolean griefPrevention;
    private boolean protectionStones;

    Protections(CSCombat plugin) {
        this.plugin = plugin;
        detect();
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private static boolean installed(String name) {
        return Bukkit.getPluginManager().isPluginEnabled(name);
    }

    /** Re-reads which hooks are on (start + reload). */
    void detect() {
        worldGuard = installed("WorldGuard") && config().getBoolean("integrations.worldguard.enabled", true);
        griefPrevention = installed("GriefPrevention") && config().getBoolean("integrations.griefprevention.enabled", true);
        protectionStones = installed("ProtectionStones") && installed("WorldGuard")
                && config().getBoolean("integrations.protectionstones.enabled", true);
    }

    String summary() {
        StringBuilder text = new StringBuilder();
        if (worldGuard) {
            text.append("WorldGuard ");
        }
        if (griefPrevention) {
            text.append("GriefPrevention ");
        }
        if (protectionStones) {
            text.append("ProtectionStones ");
        }
        if (citizensChecks()) {
            text.append("Citizens ");
        }
        return text.length() == 0 ? "none" : text.toString().trim();
    }

    private boolean citizensChecks() {
        return installed("Citizens") && config().getBoolean("integrations.citizens.enabled", true)
                && config().getBoolean("integrations.citizens.prevent-npc-tag", true);
    }

    /**
     * True if the entity is a Citizens NPC (Citizens puts "NPC" metadata on them - no Citizens code needed).
     */
    boolean isNpc(Entity entity) {
        return citizensChecks() && entity.hasMetadata("NPC");
    }

    /** False = cancel the hit. */
    boolean allows(Player attacker, Player victim) {
        if (worldGuard) {
            try {
                WorldGuardHook.Result result = WorldGuardHook.check(attacker, victim,
                        config().getBoolean("integrations.worldguard.custom-flags.allow-combat", true),
                        config().getBoolean("integrations.worldguard.custom-flags.prevent-combat", true),
                        config().getBoolean("integrations.worldguard.respect-pvp-flag", true));
                if (result == WorldGuardHook.Result.DENY) {
                    return false;
                }
            } catch (Throwable error) {
                worldGuard = false;
                plugin.getLogger().warning("WorldGuard check failed, turning that hook off until /csreload: " + error);
            }
        }
        if (griefPrevention) {
            try {
                if (GriefPreventionHook.blocks(attacker, victim,
                        config().getBoolean("integrations.griefprevention.prevent-in-claims", true),
                        config().getBoolean("integrations.griefprevention.prevent-for-claim-owners", false))) {
                    return false;
                }
            } catch (Throwable error) {
                griefPrevention = false;
                plugin.getLogger().warning("GriefPrevention check failed, turning that hook off until /csreload: " + error);
            }
        }
        if (protectionStones
                && config().getBoolean("integrations.protectionstones.prevent-in-regions", true)) {
            try {
                if (WorldGuardHook.inProtectionStonesRegion(victim.getLocation())) {
                    return false;
                }
            } catch (Throwable error) {
                protectionStones = false;
                plugin.getLogger().warning("ProtectionStones check failed, turning that hook off until /csreload: " + error);
            }
        }
        return true;
    }
}
