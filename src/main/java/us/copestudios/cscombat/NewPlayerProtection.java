package us.copestudios.cscombat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import us.copestudios.copecore.config.ConfigFile;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * New player protection: players joining for the first time can't hit or be hit for a while.
 *
 * <h2>Fixed from 1.0.2</h2>
 * <ul>
 *   <li>Protection was only in memory, so a new player who relogged lost it. It's now saved in data.yml.</li>
 *   <li>By default only time spent ONLINE counts ({@code count-offline-time: false}), so "5 minutes" really
 *       means 5 minutes of playing.</li>
 *   <li>"Your protection has expired" was only sent the next time someone hit the player. Now it's sent
 *       right when it ends.</li>
 * </ul>
 */
final class NewPlayerProtection {

    private static final String BYPASS = "cscombat.newplayer.bypass";

    private final CSCombat plugin;
    private final ConfigFile data;
    /** Online protected players -> end time (ms). */
    private final Map<UUID, Long> endsAt = new ConcurrentHashMap<>();

    NewPlayerProtection(CSCombat plugin) {
        this.plugin = plugin;
        this.data = new ConfigFile(plugin, "data.yml", false);
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private boolean enabled() {
        return config().getBoolean("new-player-protection.enabled", true)
                && config().getInt("new-player-protection.protection-time", 300) > 0;
    }

    private boolean countOffline() {
        return config().getBoolean("new-player-protection.count-offline-time", false);
    }

    /** Join: start protection for first-time players, resume it for returning protected ones. */
    void onJoin(Player player) {
        String path = "protection." + player.getUniqueId();
        if (!player.hasPlayedBefore()) {
            if (!enabled() || player.hasPermission(BYPASS)) {
                return;
            }
            int seconds = config().getInt("new-player-protection.protection-time", 300);
            endsAt.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
            plugin.messages().sendRaw(player, "new-player-protected", "time", String.valueOf(seconds));
            return;
        }
        if (!data.get().contains(path)) {
            return;
        }
        long stored = data.get().getLong(path);
        data.get().set(path, null);
        data.save();
        // Stored as "milliseconds left" (online time) or as an end time (offline time counts).
        long end = countOffline() ? stored : System.currentTimeMillis() + stored;
        if (enabled() && end > System.currentTimeMillis()) {
            endsAt.put(player.getUniqueId(), end);
            plugin.messages().sendRaw(player, "new-player-protected", "time", String.valueOf(remainingSeconds(player)));
        }
    }

    /** Quit: save what's left so a relog doesn't remove it. */
    void onQuit(Player player) {
        Long end = endsAt.remove(player.getUniqueId());
        if (end == null || end <= System.currentTimeMillis()) {
            return;
        }
        data.get().set("protection." + player.getUniqueId(),
                countOffline() ? end : end - System.currentTimeMillis());
        data.save();
    }

    /** Saves everyone still protected (server stop / reload). */
    void saveAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            onQuit(player);
        }
    }

    /** True if the player is protected right now (players with cscombat.newplayer.bypass never are). */
    boolean isProtected(Player player) {
        Long end = endsAt.get(player.getUniqueId());
        return end != null && end > System.currentTimeMillis() && enabled() && !player.hasPermission(BYPASS);
    }

    int remainingSeconds(Player player) {
        Long end = endsAt.get(player.getUniqueId());
        if (end == null) {
            return 0;
        }
        long left = end - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** Ends protection early. {@code notify} = send "protection-expired". */
    boolean end(Player player, boolean notify) {
        if (endsAt.remove(player.getUniqueId()) == null) {
            return false;
        }
        if (notify) {
            plugin.messages().sendRaw(player, "protection-expired");
        }
        return true;
    }

    /** Gives protection for some seconds (admin command). */
    void give(Player player, int seconds) {
        endsAt.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        plugin.messages().sendRaw(player, "new-player-protected", "time", String.valueOf(seconds));
    }

    /** Timer: tells players the moment their protection ends. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : new ArrayList<>(endsAt.entrySet())) {
            if (entry.getValue() <= now) {
                Player player = Bukkit.getPlayer(entry.getKey());
                endsAt.remove(entry.getKey());
                if (player != null) {
                    plugin.messages().sendRaw(player, "protection-expired");
                }
            }
        }
    }

    /** How many offline players still have protection saved (for /cscombat info). */
    int savedCount() {
        ConfigurationSection section = data.get().getConfigurationSection("protection");
        return section == null ? 0 : section.getKeys(false).size();
    }
}
