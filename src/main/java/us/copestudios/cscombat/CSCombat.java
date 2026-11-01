package us.copestudios.cscombat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import us.copestudios.copecore.CopeCore;
import us.copestudios.copecore.command.CommandContext;
import us.copestudios.copecore.command.CommandFailure;
import us.copestudios.copecore.command.CopeCommand;
import us.copestudios.copecore.config.ConfigFile;
import us.copestudios.copecore.scheduler.Tasks;
import us.copestudios.copecore.text.Messages;
import us.copestudios.cscombat.hook.WorldGuardHook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSCombat: combat tagging, combat-log punishment, new player protection and server-wide PvP times.
 *
 * <h2>What it does (same as 1.0.2)</h2>
 * <ul>
 *   <li>Hitting a player tags both players for {@code combat.tag-duration} seconds. Tagged players can't use
 *       blocked commands and see a timer in the action bar.</li>
 *   <li>Logging out while tagged kills the player (lightning, broadcast, drops).</li>
 *   <li>First-time players get {@code protection-time} seconds where they can't hit or be hit.</li>
 *   <li>Optional "global combat": PvP only at scheduled times, or when staff toggle it on.</li>
 *   <li>Respects WorldGuard, GriefPrevention, ProtectionStones and Citizens.</li>
 * </ul>
 *
 * <h2>Built on CopeCore</h2>
 * <p>Tags are shared through CopeCore's {@code combat} state, so CZRTP, Cope Bosses and the others know who is in
 * combat. Texts live in messages.yml (old ones were moved automatically). Placeholders: {@code %cope_combat_...%}.</p>
 */
public final class CSCombat extends JavaPlugin {

    private static final int REQUIRED_CORE_API = 2;
    private static final String ADMIN = "cscombat.admin";

    private ConfigFile config;
    private Messages messages;
    private CombatTags tags;
    private NewPlayerProtection protection;
    private GlobalCombat global;
    private Protections protections;
    private BukkitTask timer;

    // =====================================================================
    //  Start / stop
    // =====================================================================

    /** WorldGuard only accepts new flags while plugins are LOADING, so they're registered here. */
    @Override
    public void onLoad() {
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                WorldGuardHook.registerFlags(getLogger());
            } catch (Throwable error) {
                getLogger().warning("Could not register the WorldGuard flags: " + error);
            }
        }
    }

    @Override
    public void onEnable() {
        if (!CopeCore.requireApi(this, REQUIRED_CORE_API)) {
            return;
        }
        config = new ConfigFile(this, "config.yml");
        messages = CopeCore.messages(this);
        LegacyMigration.run(this, config, messages);

        tags = new CombatTags(this);
        protection = new NewPlayerProtection(this);
        global = new GlobalCombat(this);
        protections = new Protections(this);

        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        registerCommands();
        registerPlaceholders();
        startTimer();

        getLogger().info("CSCombat " + getDescription().getVersion() + " enabled - tag " + config.get().getInt("combat.tag-duration", 15)
                + "s, hooks: " + protections.summary() + ".");
    }

    @Override
    public void onDisable() {
        if (timer != null) {
            timer.cancel();
        }
        if (protection != null) {
            protection.saveAll();
        }
        if (tags != null) {
            tags.clear(false);
        }
    }

    /** Timer every 5 ticks: ends tags/protection on time; action bar every actionbar.update-interval ticks. */
    private void startTimer() {
        if (timer != null) {
            timer.cancel();
        }
        int interval = Math.max(5, config.get().getInt("actionbar.update-interval", 20));
        int[] ticks = {0};
        timer = Tasks.timer(this, 5, 5, () -> {
            ticks[0] += 5;
            boolean actionBar = ticks[0] % interval < 5;
            tags.tick(actionBar);
            if (ticks[0] % 20 == 0) {
                protection.tick();
                global.tick();
            }
        });
    }

    /** /csreload and /cscombat reload. */
    void reloadAll() {
        config.reload();
        messages.reload();
        protections.detect();
        global.reset();
        startTimer();
    }

    // =====================================================================
    //  Shared parts
    // =====================================================================

    @Override
    public FileConfiguration getConfig() {
        return config.get();
    }

    @Override
    public void saveConfig() {
        config.save();
    }

    @Override
    public void reloadConfig() {
        config.reload();
    }

    Messages messages() {
        return messages;
    }

    CombatTags tags() {
        return tags;
    }

    NewPlayerProtection protection() {
        return protection;
    }

    GlobalCombat global() {
        return global;
    }

    Protections protections() {
        return protections;
    }

    /** debug.enabled / debug.log-combat-events (1.0.2 settings). */
    void debug(String line) {
        if (config.get().getBoolean("debug.enabled", false) || config.get().getBoolean("debug.log-combat-events", false)) {
            getLogger().info("[debug] " + line);
        }
    }

    private String onOff(boolean on) {
        return messages.format(on ? "word-enabled" : "word-disabled");
    }

    // =====================================================================
    //  Commands
    // =====================================================================

    private void registerCommands() {
        // /cscombat (/combat, /cb). 1.0.2 needed cscombat.admin for the whole command, so normal players
        // couldn't even use "status". Now status and protection are for everyone.
        // "/cscombat" alone shows the help page (only lists what the player may use), like 1.0.2.
        CopeCommand command = new CopeCommand(this, "cscombat", messages);
        command.sub("status").playerOnly().descriptionKey("help.status").description("your combat status")
                .executes(this::status);
        command.sub("protection").playerOnly().usage("[end]").descriptionKey("help.protection")
                .description("your new player protection")
                .tab(ctx -> List.of("end"))
                .executes(this::protectionCommand);
        command.sub("toggle").permission("cscombat.toggle").descriptionKey("help.toggle")
                .description("turn server-wide PvP on/off (manual mode)")
                .executes(this::toggle);
        command.sub("time").permission(ADMIN).descriptionKey("help.time").description("global combat schedule")
                .executes(this::time);
        command.sub("list").permission(ADMIN).descriptionKey("help.list").description("players in combat")
                .executes(this::list);
        command.sub("untag").permission(ADMIN).usage("<player>").minArgs(1).descriptionKey("help.untag")
                .description("end a player's combat tag")
                .tab(ctx -> online())
                .executes(ctx -> {
                    Player target = ctx.onlinePlayer(0);
                    if (!tags.isTagged(target)) {
                        throw new CommandFailure("not-tagged", "player", target.getName());
                    }
                    tags.untag(target, true);
                    ctx.reply("untagged", "player", target.getName());
                });
        command.sub("protect").permission(ADMIN).usage("<player> [seconds|off]").minArgs(1)
                .descriptionKey("help.protect").description("give or remove new player protection")
                .tab(ctx -> ctx.size() <= 1 ? online() : List.of("300", "off"))
                .executes(ctx -> {
                    Player target = ctx.onlinePlayer(0);
                    if (ctx.argOr(1, "").equalsIgnoreCase("off")) {
                        protection.end(target, true);
                        ctx.reply("protection-removed", "player", target.getName());
                        return;
                    }
                    int seconds = ctx.has(1) ? ctx.integer(1, 1, 86_400)
                            : config.get().getInt("new-player-protection.protection-time", 300);
                    protection.give(target, seconds);
                    ctx.reply("protection-given", "player", target.getName(), "time", String.valueOf(seconds));
                });
        command.sub("reload").permission("cscombat.reload").descriptionKey("help.reload").description("reload the files")
                .executes(this::reload);
        command.register();

        // /csreload (1.0.2)
        CopeCommand reload = new CopeCommand(this, "csreload", messages);
        reload.defaultAction(ctx -> {
            if (!ctx.sender().hasPermission("cscombat.reload")) {
                throw new CommandFailure("generic.no-permission");
            }
            reload(ctx);
        });
        reload.register();
    }

    private void reload(CommandContext ctx) {
        long start = System.currentTimeMillis();
        try {
            reloadAll();
            ctx.reply("reload-success", "ms", String.valueOf(System.currentTimeMillis() - start));
            getLogger().info(ctx.sender().getName() + " reloaded CSCombat. Hooks: " + protections.summary());
        } catch (RuntimeException error) {
            getLogger().severe("Error reloading CSCombat: " + error);
            ctx.reply("reload-error");
        }
    }

    private void status(CommandContext ctx) {
        Player player = ctx.player();
        if (tags.isTagged(player)) {
            ctx.reply("combat-status", "status", messages.format("status-in-combat"));
            ctx.reply("time-remaining", "time", String.valueOf(tags.remainingSeconds(player)),
                    "opponent", tags.opponentName(player));
        } else {
            ctx.reply("not-in-combat");
        }
        if (protection.isProtected(player)) {
            ctx.reply("status-protection", "time", String.valueOf(protection.remainingSeconds(player)));
        }
        ctx.reply("status-global", "state", onOff(global.active()));
    }

    private void protectionCommand(CommandContext ctx) {
        Player player = ctx.player();
        if (!protection.isProtected(player)) {
            throw new CommandFailure("protection-none");
        }
        if (ctx.argOr(0, "").equalsIgnoreCase("end")) {
            protection.end(player, false);
            ctx.reply("protection-ended");
            return;
        }
        ctx.reply("status-protection", "time", String.valueOf(protection.remainingSeconds(player)));
        ctx.reply("protection-end-hint");
    }

    private void toggle(CommandContext ctx) {
        if (!global.enabled()) {
            throw new CommandFailure("global-off-in-config");
        }
        if (!global.mode().equals("manual")) {
            throw new CommandFailure("global-not-manual");
        }
        global.toggle(); // broadcasts global-combat-enabled / disabled
    }

    private void time(CommandContext ctx) {
        if (!global.enabled()) {
            throw new CommandFailure("global-off-in-config");
        }
        ctx.reply("time-mode", "mode", global.mode());
        if (global.mode().equals("manual")) {
            ctx.reply("time-manual", "state", onOff(global.manualState()));
            return;
        }
        ctx.reply("time-schedule-header");
        for (String range : global.ranges()) {
            messages.sendRaw(ctx.sender(), "time-schedule-line", "range", range);
        }
        ctx.reply("time-current", "state", onOff(global.active()));
    }

    private void list(CommandContext ctx) {
        List<Player> tagged = tags.taggedPlayers();
        if (tagged.isEmpty()) {
            ctx.reply("list-empty");
            return;
        }
        ctx.reply("list-header", "count", String.valueOf(tagged.size()));
        for (Player player : tagged) {
            messages.sendRaw(ctx.sender(), "list-line", "player", player.getName(),
                    "time", String.valueOf(tags.remainingSeconds(player)), "opponent", tags.opponentName(player));
        }
    }

    private static List<String> online() {
        List<String> names = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names;
    }

    // =====================================================================
    //  Placeholders: %cope_combat_<key>%
    // =====================================================================

    private void registerPlaceholders() {
        CopeCore.placeholders().register(this, "combat", (offline, key) -> {
            if (key.equalsIgnoreCase("global")) {
                return String.valueOf(global.active());
            }
            Player player = offline == null ? null : offline.getPlayer();
            if (player == null) {
                return null;
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put("in_combat", String.valueOf(tags.isTagged(player)));
            values.put("time", String.valueOf(tags.remainingSeconds(player)));
            values.put("opponent", tags.opponentName(player));
            values.put("protected", String.valueOf(protection.isProtected(player)));
            values.put("protection_time", String.valueOf(protection.remainingSeconds(player)));
            return values.get(key.toLowerCase(java.util.Locale.ROOT));
        });
    }
}
