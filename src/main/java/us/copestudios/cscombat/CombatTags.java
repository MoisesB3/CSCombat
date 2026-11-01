package us.copestudios.cscombat;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import us.copestudios.copecore.CopeCore;
import us.copestudios.copecore.player.PlayerStates;
import us.copestudios.copecore.util.Sounds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is in combat, until when, and with whom.
 *
 * <p>Every tag is also mirrored into CopeCore's shared {@code combat} state, so the other Cope plugins see it
 * without depending on CSCombat (CZRTP refuses to teleport tagged players, etc.).</p>
 *
 * <p>All methods run on the main thread (events and the timer task).</p>
 */
final class CombatTags {

    /** One tag: when it ends and the last player who hit / was hit by this player. */
    private record Tag(long endsAt, UUID opponent) {
    }

    private final CSCombat plugin;
    private final Map<UUID, Tag> tags = new ConcurrentHashMap<>();

    CombatTags(CSCombat plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    /** combat.tag-duration in milliseconds. */
    private long durationMillis() {
        return Math.max(1, config().getInt("combat.tag-duration", 15)) * 1000L;
    }

    /**
     * Tags (or re-tags) a player. The first tag sends "enter-combat" and plays the enter sound;
     * later hits only restart the timer, like 1.0.2.
     */
    void tag(Player player, Player opponent) {
        boolean wasTagged = isTagged(player);
        long duration = durationMillis();
        tags.put(player.getUniqueId(), new Tag(System.currentTimeMillis() + duration,
                opponent == null ? null : opponent.getUniqueId()));
        CopeCore.states().set(player, PlayerStates.COMBAT, duration);
        if (!wasTagged) {
            plugin.messages().sendRaw(player, "enter-combat", "time", String.valueOf(duration / 1000));
            playSound(player, "sounds.enter-combat");
            plugin.debug(player.getName() + " entered combat" + (opponent == null ? "" : " with " + opponent.getName()));
        }
    }

    /** Ends a tag. {@code notify} = send "exit-combat" and play the exit sound. */
    void untag(Player player, boolean notify) {
        if (tags.remove(player.getUniqueId()) == null) {
            return;
        }
        CopeCore.states().remove(player, PlayerStates.COMBAT);
        if (notify) {
            plugin.messages().sendRaw(player, "exit-combat");
            playSound(player, "sounds.exit-combat");
        }
        plugin.debug(player.getName() + " left combat");
    }

    boolean isTagged(Player player) {
        Tag tag = tags.get(player.getUniqueId());
        return tag != null && tag.endsAt() > System.currentTimeMillis();
    }

    /** Seconds left, rounded UP (so the action bar never shows "0s" while still tagged). */
    int remainingSeconds(Player player) {
        Tag tag = tags.get(player.getUniqueId());
        if (tag == null) {
            return 0;
        }
        long left = tag.endsAt() - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** Name of the last opponent, or "" if none. */
    String opponentName(Player player) {
        Tag tag = tags.get(player.getUniqueId());
        if (tag == null || tag.opponent() == null) {
            return "";
        }
        OfflinePlayer opponent = Bukkit.getOfflinePlayer(tag.opponent());
        return opponent.getName() == null ? "" : opponent.getName();
    }

    /** Online players that are tagged right now. */
    List<Player> taggedPlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : tags.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && isTagged(player)) {
                list.add(player);
            }
        }
        return list;
    }

    /** Ends every tag (silently). Used on disable and when global combat switches off. */
    void clear(boolean notify) {
        for (UUID id : new ArrayList<>(tags.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                untag(player, notify);
            }
        }
        tags.clear();
    }

    /**
     * Called by the timer: ends expired tags and refreshes action bars.
     *
     * <p>1.0.2 removed entries from the map while iterating it (ConcurrentModificationException in the
     * console every time a tag ended). This copies the keys first.</p>
     */
    void tick(boolean updateActionBar) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Tag> entry : new ArrayList<>(tags.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                tags.remove(entry.getKey()); // offline: nothing to notify
                continue;
            }
            if (entry.getValue().endsAt() <= now) {
                untag(player, true);
            } else if (updateActionBar && config().getBoolean("actionbar.enabled", true)) {
                plugin.messages().actionBar(player, "actionbar",
                        "time", String.valueOf(remainingSeconds(player)),
                        "opponent", opponentName(player));
            }
        }
    }

    /** sounds.enabled + sound name + sounds.volume/pitch, played through CopeCore (old enum names work). */
    private void playSound(Player player, String path) {
        if (!config().getBoolean("sounds.enabled", true)) {
            return;
        }
        String name = config().getString(path, "");
        if (name == null || name.isBlank()) {
            return;
        }
        Sounds.play(player, name.trim() + ":" + config().getDouble("sounds.volume", 0.5)
                + ":" + config().getDouble("sounds.pitch", 1.0));
    }
}
