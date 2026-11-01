package us.copestudios.cscombat;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import us.copestudios.copecore.CopeCore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All of CSCombat's events: hits, blocked commands, combat logging, deaths and joins.
 */
final class CombatListener implements Listener {

    /** Don't repeat "you can't attack" messages more than once per this many ms (1.0.2 sent one per hit). */
    private static final long NOTICE_COOLDOWN = 2000;

    private final CSCombat plugin;
    /** Players being killed for combat logging right now (their death event is handled specially). */
    private final Set<UUID> punishing = new HashSet<>();
    /** Players that were kicked (their quit is not a combat log unless death.punish-kicked). */
    private final Set<UUID> kicked = new HashSet<>();
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    CombatListener(CSCombat plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    // =====================================================================
    //  Hits
    // =====================================================================

    /** The player behind a hit: the player themselves, or whoever shot the projectile. */
    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /** True if CSCombat should leave this player alone (world list, shared states like in-duel). */
    private boolean ignored(Player player) {
        if (config().getStringList("combat.disabled-worlds").stream()
                .anyMatch(world -> world.equalsIgnoreCase(player.getWorld().getName()))) {
            return true;
        }
        for (String state : config().getStringList("combat.ignore-states")) {
            if (CopeCore.states().has(player, state)) {
                return true;
            }
        }
        return false;
    }

    private void notice(Player player, String key, String... pairs) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        plugin.messages().sendRaw(player, key, pairs);
    }

    // HIGHEST + ignoreCancelled (like 1.0.2): hits that other plugins already blocked never tag anyone.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null) {
            return; // mobs, TNT... (combat.mob-tagging is kept in the config for later, like 1.0.2)
        }
        if (attacker.equals(victim) && config().getBoolean("combat.prevent-self-tag", true)) {
            return; // own arrow / ender pearl
        }
        // Citizens NPCs: never tagged and never tag anyone - but the hit itself is NOT cancelled any more
        // (1.0.2 cancelled it, so practice bots and NPC fights couldn't be hit at all).
        if (plugin.protections().isNpc(victim) || plugin.protections().isNpc(attacker)) {
            return;
        }
        if (ignored(attacker) || ignored(victim)) {
            return;
        }

        // Server-wide PvP off (global-combat)
        if (!plugin.global().active()) {
            event.setCancelled(true);
            notice(attacker, "global-combat-inactive");
            return;
        }

        // New player protection
        NewPlayerProtection protection = plugin.protection();
        if (protection.isProtected(victim)) {
            event.setCancelled(true);
            notice(attacker, "protected-target", "time", String.valueOf(protection.remainingSeconds(victim)),
                    "player", victim.getName());
            return;
        }
        if (protection.isProtected(attacker)) {
            if (config().getBoolean("new-player-protection.end-on-attack", false)) {
                protection.end(attacker, true);
            } else {
                event.setCancelled(true);
                notice(attacker, "attacker-protected", "time", String.valueOf(protection.remainingSeconds(attacker)));
                return;
            }
        }

        // WorldGuard / GriefPrevention / ProtectionStones
        if (!plugin.protections().allows(attacker, victim)) {
            event.setCancelled(true);
            return;
        }

        // Which hits tag (same rules as 1.0.2: player-damage = every hit from a player counts)
        boolean melee = event.getDamager() instanceof Player;
        boolean tag = config().getBoolean("combat.tag-actions.player-damage", true)
                || (melee && config().getBoolean("combat.tag-actions.player-hit", true))
                || (!melee && config().getBoolean("combat.tag-actions.projectile-hit", true));
        if (tag) {
            plugin.tags().tag(attacker, victim);
            plugin.tags().tag(victim, attacker);
        }
    }

    // =====================================================================
    //  Commands
    // =====================================================================

    /** "/Essentials:Home x" -> "home". */
    private static String commandName(String text) {
        String first = text.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (first.startsWith("/")) {
            first = first.substring(1);
        }
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(colon + 1) : first;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!plugin.tags().isTagged(player)) {
            return;
        }
        if (config().getBoolean("combat.bypass-with-permission", true) && player.hasPermission("cscombat.bypass")) {
            return;
        }
        // Fixed: "/essentials:home" and "/minecraft:tp" used to get around the block list.
        String command = commandName(event.getMessage());
        if (command.equals("cscombat") || command.equals("combat") || command.equals("cb")) {
            return; // /cscombat status always works
        }
        for (String allowed : config().getStringList("combat.allowed-commands")) {
            if (command.equals(commandName(allowed))) {
                return;
            }
        }
        for (String blocked : config().getStringList("combat.blocked-commands")) {
            if (blocked.trim().equals("*") || command.equals(commandName(blocked))) {
                event.setCancelled(true);
                plugin.messages().sendRaw(player, "command-blocked",
                        "time", String.valueOf(plugin.tags().remainingSeconds(player)), "command", "/" + command);
                return;
            }
        }
    }

    // =====================================================================
    //  Join / quit / death
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.protection().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        kicked.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean wasKicked = kicked.remove(player.getUniqueId());
        lastNotice.remove(player.getUniqueId());
        plugin.protection().onQuit(player);

        if (!plugin.tags().isTagged(player)) {
            plugin.tags().untag(player, false);
            return;
        }
        if (wasKicked && !config().getBoolean("death.punish-kicked", false)) {
            plugin.tags().untag(player, false);
            return;
        }
        if (player.hasPermission("cscombat.bypass.logout")) {
            plugin.tags().untag(player, false);
            return;
        }
        punish(player);
    }

    /** Combat log: lightning, broadcast, then the player dies (the death event below decides the drops). */
    private void punish(Player player) {
        String opponent = plugin.tags().opponentName(player);
        plugin.tags().untag(player, false);
        if (config().getBoolean("death.lightning-effect", true)) {
            player.getWorld().strikeLightningEffect(player.getLocation());
        }
        if (config().getBoolean("death.broadcast-death", true)) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(player)) {
                    plugin.messages().sendRaw(online, "combat-logout-death", "player", player.getName(), "opponent", opponent);
                }
            }
            plugin.messages().sendRaw(plugin.getServer().getConsoleSender(), "combat-logout-death",
                    "player", player.getName(), "opponent", opponent);
        }
        plugin.getLogger().info(player.getName() + " logged out in combat" + (opponent.isEmpty() ? "" : " (with " + opponent + ")") + ".");
        punishing.add(player.getUniqueId());
        try {
            player.setHealth(0);
        } finally {
            punishing.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (punishing.contains(player.getUniqueId())) {
            applyLogoutDrops(event, player);
            return;
        }
        if (config().getBoolean("combat.untag-on-death", true)) {
            plugin.tags().untag(player, false); // 1.0.2 kept dead players tagged after respawning
        }
    }

    /**
     * death.drop-items / drop-exp, using the normal death (so grave and death-chest plugins see the items).
     * 1.0.2 dropped everything by hand before killing the player.
     */
    private void applyLogoutDrops(PlayerDeathEvent event, Player player) {
        if (config().getBoolean("death.drop-items", true)) {
            if (event.getKeepInventory()) { // keepInventory gamerule: still drop for combat loggers, like 1.0.2
                event.setKeepInventory(false);
                event.getDrops().clear();
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        event.getDrops().add(item);
                    }
                }
            }
        } else {
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
        if (config().getBoolean("death.drop-exp", true)) {
            event.setKeepLevel(false);
            event.setNewLevel(0);
            event.setNewExp(0);
            event.setNewTotalExp(0);
            event.setDroppedExp(totalExperience(player));
        } else {
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        if (config().getBoolean("death.broadcast-death", true)) {
            event.setDeathMessage(null); // our own message was already sent
        }
    }

    /** All the experience a player has (same formula as 1.0.2). */
    private static int totalExperience(Player player) {
        int level = player.getLevel();
        int exp = Math.round(player.getExp() * player.getExpToLevel());
        if (level <= 15) {
            exp += level * level + 6 * level;
        } else if (level <= 30) {
            exp += (int) (2.5 * level * level - 40.5 * level + 360);
        } else {
            exp += (int) (4.5 * level * level - 162.5 * level + 2220);
        }
        return exp;
    }
}
