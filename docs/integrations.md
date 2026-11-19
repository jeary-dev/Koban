# Integrations

Koban needs no other plugin. Everything here is optional.

## Punishment plugins (actions.yml)

Punishments are command templates that run as the server console, from the Discord menu under each
report or with `/reports punish <id> <action>`. Staff see the exact command before confirming it.
Every run, and every failure, goes in the audit log.

Use the plugin's namespace (`litebans:ban` rather than `ban`), so the command runs in the plugin
you meant even when two plugins register the same name. `/help <plugin>` lists the names a plugin
registers.

### Placeholders

| Placeholder | Value |
|---|---|
| `{player}` | the reported player's name. A name that isn't a valid Minecraft name is refused; for Bedrock players through Floodgate, add their prefix to `allowed-name-prefixes` |
| `{uuid}` | their UUID |
| `{reason}` | the report's reason, e.g. `Cheating` (or `reason-fallback`) |
| `{category}` | the reason key, e.g. `cheating` |
| `{report}` | the report number |
| `{staff}` | who chose the punishment |
| `{server}` | `server-name` from config.yml |

`{reason}`, `{category}`, `{staff}` and `{server}` are cleaned before they're inserted. Only
letters, digits, spaces and `. , ! ? ' ( ) # : - _` are kept, so nothing anyone typed can add
an argument, a flag or a second command.

`resolve: true` resolves the report (action taken) once the command has run.

### LiteBans

```yaml
templates:
  warn:
    label: Warn
    emoji: '⚠️'
    command: 'litebans:warn {player} {reason} (report #{report})'
    resolve: true
  mute-1h:
    label: Mute for 1 hour
    emoji: '🔇'
    command: 'litebans:tempmute {player} 1h {reason} (report #{report})'
    resolve: true
  tempban-1d:
    label: Ban for 1 day
    emoji: '⏳'
    command: 'litebans:tempban {player} 1d {reason} (report #{report})'
    resolve: true
  ban:
    label: Ban
    emoji: '🔨'
    command: 'litebans:ban {player} {reason} (report #{report})'
    resolve: true
```

LiteBans records console punishments as made by "Console". To keep the moderator's name in
LiteBans' history too, put `{staff}` in the reason: `{reason} (report #{report}, by {staff})`.

### AdvancedBan

```yaml
templates:
  warn:
    label: Warn
    command: 'advancedban:warn {player} {reason} (report #{report})'
    resolve: true
  mute-1h:
    label: Mute for 1 hour
    command: 'advancedban:tempmute {player} 1h {reason} (report #{report})'
    resolve: true
  tempban-1d:
    label: Ban for 1 day
    command: 'advancedban:tempban {player} 1d {reason} (report #{report})'
    resolve: true
  ban:
    label: Ban
    command: 'advancedban:ban {player} {reason} (report #{report})'
    resolve: true
```

AdvancedBan also accepts layouts in place of a reason (`@layoutname`). A layout name has no
special characters, so it can be written straight into the command.

### EssentialsX

```yaml
templates:
  mute-1h:
    label: Mute for 1 hour
    command: 'essentials:mute {player} 1h {reason}'
    resolve: true
  tempban-1d:
    label: Ban for 1 day
    command: 'essentials:tempban {player} 1d {reason} (report #{report})'
    resolve: true
  ban:
    label: Ban
    command: 'essentials:ban {player} {reason} (report #{report})'
    resolve: true
  jail:
    label: Jail for 30 minutes
    command: 'essentials:jail {player} jail 30m'
    resolve: true
```

### Vanilla (no plugin)

The default actions.yml uses `minecraft:tell`, `minecraft:kick` and `minecraft:ban`. Vanilla has no
temporary ban, so the default `tempban-1d` template needs one of the plugins above. Remove it if
you don't have one.

## Vanish plugins

Koban reads the `vanished` metadata that SuperVanish, PremiumVanish, EssentialsX, CMI and
VanishNoPacket all set, so there's nothing to configure:

- Vanished players don't appear in `/report` tab completion, and can't be reported by players who
  can't see them. To those players, a report against them gets the same answer as a name that
  isn't online.
- Vanished staff are never listed as nearby witnesses in a report's evidence.
- Staff who can see vanished players (by the vanish plugin's own rules) can still report them.

## PlaceholderAPI

When PlaceholderAPI is installed, Koban registers its placeholders. You don't need to download an
eCloud expansion.

| Placeholder | Value |
|---|---|
| `%koban_open%` | reports nobody has claimed yet |
| `%koban_claimed%` | reports being handled |
| `%koban_active%` | open + claimed |
| `%koban_resolved%` | resolved reports, ever |
| `%koban_dismissed%` | dismissed reports, ever |
| `%koban_my_claimed%` | reports the player has claimed right now |
| `%koban_against%` | reports ever made against the player |
| `%koban_made%` | reports the player has made (joining an existing report counts) |
| `%koban_linked%` | `true` when the player linked a Discord account |
| `%koban_discord%` | `online` or `offline`: the bot's connection |

PlaceholderAPI asks for values on the main thread, as often as every tick for a scoreboard.
Every answer comes from memory and never waits on the database:

- Counts are refreshed in the background within about a second of any change, and once a minute.
- A player's own numbers load the first time they're requested. That first request is empty, and
  the next one has the value. After a report changes, the old value is shown once more while the
  new one loads.

A staff scoreboard line, for example: `Reports: %koban_open% open · %koban_my_claimed% mine`.

## Your own plugin (events)

Koban fires Bukkit events (package `dev.jeary.koban.api.event`) on the main thread, whether a
change was made in game or on Discord:

| Event | Cancellable | When |
|---|---|---|
| `ReportCreateEvent` | yes | A player files a report (`mergeInto` is set when it joins an open one) |
| `ReportClaimEvent` | yes | Staff claim a report or take over a claim |
| `ReportResolveEvent` | yes | Staff resolve or dismiss a report |
| `ReportUpdateEvent` | no | Anything was stored: a new reporter, claim, release, close, reopen, note |

Cancelling stops the change before anything is stored. A cancelled report doesn't start the
reporter's cooldown. Add `softdepend: [Koban]` to your plugin.yml and listen as usual:

```kotlin
import dev.jeary.koban.api.event.ReportCreateEvent

@EventHandler
fun onReport(event: ReportCreateEvent) {
    if (event.reported.name == "Notch") event.isCancelled = true
}
```
