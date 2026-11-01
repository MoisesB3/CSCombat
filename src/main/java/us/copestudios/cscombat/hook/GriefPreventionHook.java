package us.copestudios.cscombat.hook;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.entity.Player;

/**
 * Everything that touches GriefPrevention. Only loaded when GriefPrevention is installed.
 */
public final class GriefPreventionHook {

    private GriefPreventionHook() {
    }

    /**
     * True if the hit should be blocked because of a claim at the victim's location.
     *
     * @param inClaims    block all PvP inside claims
     * @param claimOwners block PvP when the attacker or victim owns the claim
     */
    public static boolean blocks(Player attacker, Player victim, boolean inClaims, boolean claimOwners) {
        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(victim.getLocation(), false, null);
        if (claim == null) {
            return false;
        }
        if (inClaims) {
            return true;
        }
        // Admin claims have no owner (ownerID == null) - 1.0.2 crashed on those.
        return claimOwners && claim.ownerID != null
                && (claim.ownerID.equals(victim.getUniqueId()) || claim.ownerID.equals(attacker.getUniqueId()));
    }
}
