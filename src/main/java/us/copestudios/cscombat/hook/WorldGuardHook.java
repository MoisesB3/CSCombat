package us.copestudios.cscombat.hook;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Everything that touches WorldGuard. This class is only loaded when WorldGuard is installed, so servers
 * without it never see a "class not found" error.
 *
 * <h2>Custom flags</h2>
 * <ul>
 *   <li>{@code allow-combat}: deny = no PvP in the region; allow = CSCombat skips its own
 *       {@code pvp} flag check here (WorldGuard itself still blocks PvP where {@code pvp} is deny).</li>
 *   <li>{@code prevent-combat}: allow = no PvP in the region.</li>
 * </ul>
 * <p>Fixed from 1.0.2: WorldGuard only accepts new flags while the server is LOADING plugins. 1.0.2 registered
 * them later, so they never existed (the console said "Failed to register WorldGuard flags"). They are now
 * registered in onLoad and work: {@code /rg flag <region> prevent-combat allow}.</p>
 */
public final class WorldGuardHook {

    /** ProtectionStones names its regions like "ps120x64y-35z". */
    private static final Pattern PROTECTION_STONES_ID = Pattern.compile("ps-?\\d+x-?\\d+y-?\\d+z");

    private static StateFlag allowCombat;
    private static StateFlag preventCombat;

    private WorldGuardHook() {
    }

    /** What WorldGuard says about a hit. */
    public enum Result {
        /** Nothing special: continue with the other checks. */
        PASS,
        /** allow-combat is "allow" here: PvP is allowed, skip the WorldGuard PvP flag check. */
        ALLOW,
        /** Blocked by prevent-combat, allow-combat deny, or the pvp flag. */
        DENY
    }

    /** Called from CSCombat.onLoad. */
    public static void registerFlags(Logger logger) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        allowCombat = register(registry, "allow-combat", logger);
        preventCombat = register(registry, "prevent-combat", logger);
    }

    private static StateFlag register(FlagRegistry registry, String name, Logger logger) {
        try {
            StateFlag flag = new StateFlag(name, false);
            registry.register(flag);
            return flag;
        } catch (FlagConflictException | IllegalStateException error) {
            Flag<?> existing = registry.get(name);
            if (existing instanceof StateFlag state) {
                return state; // another plugin made the same flag: share it
            }
            logger.warning("Could not register the WorldGuard flag '" + name + "': " + error.getMessage());
            return null;
        }
    }

    private static ApplicableRegionSet regionsAt(Location location) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .getApplicableRegions(BukkitAdapter.adapt(location));
    }

    /**
     * Checks the custom flags and (optionally) WorldGuard's own pvp flag at the victim's location.
     */
    public static Result check(Player attacker, Player victim, boolean useAllowFlag, boolean usePreventFlag,
                               boolean respectPvpFlag) {
        ApplicableRegionSet regions = regionsAt(victim.getLocation());
        if (usePreventFlag && preventCombat != null && regions.testState(null, preventCombat)) {
            return Result.DENY;
        }
        if (useAllowFlag && allowCombat != null) {
            StateFlag.State state = regions.queryState(null, allowCombat);
            if (state == StateFlag.State.DENY) {
                return Result.DENY;
            }
            if (state == StateFlag.State.ALLOW) {
                return Result.ALLOW;
            }
        }
        if (respectPvpFlag) {
            LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(attacker);
            if (!regions.testState(local, Flags.PVP)) {
                return Result.DENY;
            }
        }
        return Result.PASS;
    }

    /**
     * True if the location is inside a ProtectionStones region.
     *
     * <p>Fixed from 1.0.2: it blocked PvP inside ANY WorldGuard region when ProtectionStones was installed
     * (including arena regions). Now only ProtectionStones' own regions count.</p>
     */
    public static boolean inProtectionStonesRegion(Location location) {
        for (ProtectedRegion region : regionsAt(location).getRegions()) {
            if (PROTECTION_STONES_ID.matcher(region.getId().toLowerCase(Locale.ROOT)).matches()) {
                return true;
            }
        }
        return false;
    }
}
