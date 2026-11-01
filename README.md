# CSCombat 2.0.0

Combat tagging, combat-log punishment, new player protection and scheduled PvP.
Built on **CopeCore** (API 2). Runs on Spigot, Paper, Purpur and Arclight,
Minecraft **1.20.1 and newer** (Java 17+).

## Install / update from 1.0.2

1. Install **CopeCore** first (CSCombat won't start without it).
2. Delete `CSCombat-1.0.2.jar`, drop in `CSCombat-2.0.0.jar`, restart.
3. Done. Your `config.yml` keeps working. Texts you had changed (in `messages:`,
   plus `prefix`, `protected-message` and the action bar text) are moved to
   `messages.yml` automatically, colors and `{time}`/`{player}` included.

Optional: WorldGuard, GriefPrevention, ProtectionStones, Citizens. Each one is
only used when it's installed.

## What it does

- **Combat tag.** A hit tags both players for `combat.tag-duration` seconds
  (every hit restarts it). Tagged players see a timer in the action bar and
  can't use `blocked-commands`.
- **Combat logging.** Leaving while tagged kills the player: lightning,
  broadcast, items and experience dropped (configurable in `death:`).
- **New player protection.** First-time players can't hit or be hit for
  `protection-time` seconds.
- **Global combat.** Optional: PvP only at set times (`schedule`) or when staff
  turn it on (`manual`, `/cscombat toggle`).
- **Shared with other Cope plugins.** The tag is CopeCore's `combat` state, so
  CZRTP, Cope Bosses and the rest know who's fighting.

## Fixed from 1.0.2

| Problem in 1.0.2 | 2.0.0 |
|---|---|
| Console error (ConcurrentModificationException) every time a tag ran out | fixed |
| WorldGuard flags `allow-combat` / `prevent-combat` never existed (registered too late) | registered at startup, they work |
| With ProtectionStones installed, PvP was blocked in **every** WorldGuard region (arenas too) | only ProtectionStones regions |
| Hitting a Citizens NPC was cancelled (practice bots couldn't be hit) | NPCs just don't tag |
| `/essentials:home`, `/minecraft:tp` got around blocked commands | caught |
| Normal players couldn't use `/cscombat status` (whole command needed admin) | status is for everyone |
| Dead players stayed tagged after respawning | tag ends on death (`untag-on-death`) |
| New player protection was lost on relog | saved in data.yml, only online time counts |
| "Protection expired" only showed when someone hit you | shown right when it ends |
| "You can't attack" message on every single hit | once per 2 seconds |
| Admin claims (GriefPrevention) could throw errors | handled |
| Schedule times exactly on the start minute didn't count | they do |

## New options (config.yml)

| Option | Default | What |
|---|---|---|
| `combat.untag-on-death` | true | dying ends the tag |
| `combat.disabled-worlds` | [] | worlds CSCombat ignores |
| `combat.ignore-states` | [in-duel] | CopeCore states that make CSCombat ignore a player |
| `new-player-protection.count-offline-time` | false | does protection run out while offline? |
| `new-player-protection.end-on-attack` | false | attacking ends your protection |
| `global-combat.timezone` | '' | schedule time zone, e.g. `America/New_York` |
| `global-combat.announce-schedule` | true | announce when the schedule turns PvP on/off |
| `death.punish-kicked` | false | kill players kicked while tagged |

When global combat switches off, all tags end so nobody is stuck unable to use
commands.

## Commands

| Command | Permission | What |
|---|---|---|
| `/cscombat` (`/combat`, `/cb`) | - | help (only shows what you can use) |
| `/cscombat status` | - | your combat / protection / global status |
| `/cscombat protection [end]` | - | your new player protection, or end it early |
| `/cscombat toggle` | `cscombat.toggle` | global combat on/off (manual mode) |
| `/cscombat time` | `cscombat.admin` | global combat schedule |
| `/cscombat list` | `cscombat.admin` | everyone in combat right now |
| `/cscombat untag <player>` | `cscombat.admin` | end someone's tag |
| `/cscombat protect <player> [seconds\|off]` | `cscombat.admin` | give / remove protection |
| `/cscombat reload`, `/csreload` | `cscombat.reload` | reload config and messages |

## Permissions

| Permission | Default | What |
|---|---|---|
| `cscombat.admin` | op | admin commands (includes reload and toggle) |
| `cscombat.reload` | op | reload |
| `cscombat.toggle` | op | /cscombat toggle |
| `cscombat.bypass` | op | use blocked commands in combat |
| `cscombat.bypass.logout` | **nobody** | logging out in combat doesn't kill you (new) |
| `cscombat.newplayer.bypass` | op | never get new player protection |

## WorldGuard flags

```
/rg flag <region> prevent-combat allow   -> no PvP in the region
/rg flag <region> allow-combat deny      -> no PvP in the region
/rg flag <region> allow-combat allow     -> CSCombat skips its pvp-flag check there
```

## Placeholders (PlaceholderAPI, through CopeCore)

| Placeholder | Value |
|---|---|
| `%cope_combat_in_combat%` | true / false |
| `%cope_combat_time%` | seconds left in combat |
| `%cope_combat_opponent%` | last player you fought |
| `%cope_combat_protected%` | true / false (new player protection) |
| `%cope_combat_protection_time%` | protection seconds left |
| `%cope_combat_global%` | is PvP allowed server-wide right now |

## Building

```
mvn clean package
```

The jar is `target/CSCombat-2.0.0.jar`. CopeCore must be installed in your local
Maven repo (`mvn install` in the CopeCore project); `<copecore.version>` in
`pom.xml` must match your CopeCore build.
