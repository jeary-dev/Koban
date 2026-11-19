<div align="center">

# 交番 Koban

**Player reports and moderation for Minecraft, with a Discord bot built in.**

Spigot & Paper 1.20.6 – 26.x · Java 21 · Kotlin · SQLite or MySQL/MariaDB · JDA 6

[![build](https://github.com/jeary-dev/Koban/actions/workflows/build.yml/badge.svg)](https://github.com/jeary-dev/Koban/actions/workflows/build.yml)
[![license: MIT](https://img.shields.io/badge/license-MIT-c94f72.svg)](LICENSE)

<!-- Screenshot: the Discord forum with a few report posts and their status tags. -->
<!-- <img src="docs/images/discord-forum.png" alt="…" width="860"> -->

</div>

A koban (交番) is the small neighbourhood police box on a Japanese street corner. Koban gives your
server one: players report someone in a few clicks, and the evidence is captured at that moment.
Staff handle the report in game or from a Discord thread, and the reporter hears back when it's
done.

There is one report per player, not one per complaint. When five people report the same cheater,
staff see one report with five reporters and a rising priority, not five tickets to close one by one.

## Contents

- [Features](#features)
- [Installing](#installing)
- [Commands and permissions](#commands-and-permissions)
- [Configuration](#configuration)
- [Discord](#discord)
- [PlaceholderAPI and other plugins](#placeholderapi-and-other-plugins)
- [Compatibility](#compatibility)
- [Building from source](#building-from-source)
- [Architecture](#architecture)

## Features

**Reporting** (`/report <player> [reason] [details]`)
- A menu of reasons (configurable icons, names and descriptions), then an optional chat prompt:
  "describe what happened", which the reporter can skip or cancel.
- `/report Steve cheating flying around spawn` does it all in one line, with tab completion.
- Evidence is captured automatically when the report is made:
  - both players' recent chat
  - the reported player's recent commands (off by default; passwords are never recorded)
  - location, health, game mode, ping, client brand and TPS
  - who was nearby as a witness
- Players who just left can still be reported, for a configurable few minutes.

**Fair to staff, fair to players**
- **Merging:** a report against a player who already has an open one joins it. Every reporter is
  listed and the priority rises with the count (Normal → Raised → Urgent).
- **Anti-abuse:** a cooldown between reports, a cap on open reports per reporter, no
  self-reports, and an exempt permission for staff.
- **Feedback:** reporters are told when their report is handled. If they're offline, they're told
  the next time they join.

**For staff** (`/reports`)
- A report list filtered by status, reason or player, sorted with the most reported first.
- Each report has its own view showing the evidence, the reporters and the notes. From it, staff can:
  - claim, resolve (action taken or no action) and dismiss, each with an optional note
  - reopen, teleport to the spot, and see the player's report history
- Every action is also a command (`/reports claim 12`) with console support.
- Live alerts with a sound, and a summary of open reports when staff join.

**Discord** (optional, runs inside the plugin)
- A forum post per report with tags for status and reasons. Closed reports are archived.
- Buttons: Claim, Resolve (an outcome plus a note), Dismiss, Reopen.
- A punishment menu with a preview and confirmation that runs a command from actions.yml.
- Role-based permissions per action, checked on every click.
- Two-way notes, `/reports open`, `/reports lookup`, and account linking.
- If Discord is down or misconfigured, nothing is lost: threads catch up when the bot reconnects.

**For server owners**
- `/koban reload` checks every file before applying anything. If a file is broken, it names the
  file, the setting and the problem, and keeps the old settings.
- `/koban setup` checks the Discord setup step by step and prints an invite link with the right
  permissions.
- An audit log of every action (`audit_log` table): who, what, when, and whether it was done in
  game, on Discord or from the console.
- Cancellable Bukkit events and PlaceholderAPI placeholders.
- Every message is in messages.yml (MiniMessage), and every menu layout is in menus.yml.

<!-- Screenshots to add (docs/images/):
     1. report-reasons.png   the /report reason menu
     2. staff-list.png       /reports with a few reports
     3. staff-view.png       a report's detail view with evidence
     4. discord-thread.png   a report thread with its embed and buttons
     5. discord-resolve.png  the Resolve form
     6. discord-punish.png   the punishment preview and confirmation -->

## Installing

1. Download `Koban-<version>.jar` from [Releases](https://github.com/jeary-dev/Koban/releases).
   Each release has a `.sha256` file; check it with `sha256sum -c Koban-<version>.jar.sha256`
   (Linux/macOS) or `Get-FileHash Koban-<version>.jar` (Windows).
2. Put it in `plugins/` and start the server. On the first start the server downloads Koban's
   libraries (Kotlin, JDA, the database drivers) from Maven Central. This needs internet access
   once, and takes a minute.
3. Edit `plugins/Koban/config.yml` and `reasons.yml`, then run `/koban reload`.
4. Optional: [set up the Discord bot](docs/discord-setup.md).

Koban uses SQLite out of the box: a file in the plugin folder, with nothing to set up. To share
reports between servers on a network, point every server at the same MySQL or MariaDB database.

## Commands and permissions

| Command | Permission | |
|---|---|---|
| `/report <player> [reason] [details]` | `koban.report` (everyone) | Report a player |
| `/reports` | `koban.staff` | The report list |
| `/reports view <id>` | `koban.staff` | One report |
| `/reports history <player>` | `koban.history` | A player's reports |
| `/reports claim <id>` · `unclaim <id>` | `koban.claim` | Take or release a report |
| `/reports resolve <id> <action\|no-action> [note]` | `koban.resolve` | Close with an outcome |
| `/reports dismiss <id> [note]` · `reopen <id>` | `koban.resolve` | |
| `/reports note <id> <text>` | `koban.note` | Add a note |
| `/reports punish <id> <action>` | `koban.punish` | Run a punishment from actions.yml |
| `/reports tp <id>` | `koban.teleport` | Go to where the report was made |
| `/koban link` · `unlink` | `koban.link` (everyone) | Link a Discord account |
| `/koban reload` · `setup` · `version` | `koban.admin` | |

| Permission | Default | |
|---|---|---|
| `koban.moderator` | op | Everything a moderator needs: staff, claim, resolve, note, teleport, history, punish, bypass.cooldown |
| `koban.admin` | op | `koban.moderator` plus override, bypass.limit and the `/koban` admin commands |
| `koban.notify` | op | Live alerts and the join summary (included in `koban.staff`) |
| `koban.override` | op | Act on reports someone else claimed |
| `koban.exempt` | false | Can't be reported |
| `koban.bypass.cooldown` · `koban.bypass.limit` | op | Skip the cooldown / the open-report cap |

Commands a player has no permission for are hidden from them entirely, including tab completion.

## Configuration

| File | What's in it |
|---|---|
| `config.yml` | storage, cooldowns and limits, evidence, notifications, priority levels, Discord |
| `reasons.yml` | the report reasons |
| `actions.yml` | punishment templates ([examples for LiteBans, AdvancedBan, EssentialsX](docs/integrations.md#punishment-plugins-actionsyml)) |
| `menus.yml` | every menu's layout, items and titles |
| `messages.yml` | every message, in MiniMessage |

Durations are written `30s`, `15m`, `12h`, `7d` or combined (`1h30m`). Some highlights:

```yaml
reports:
  cooldown: 60s
  max-open-per-reporter: 3
  merge-window: 30m        # a new report joins an open one filed within this window
  ask-details: true

evidence:
  chat:
    lines: 10
  nearby-radius: 32
  commands:
    enabled: false         # privacy first: off until you turn it on

priority:
  urgent:
    name: Urgent
    reporters: 4           # 4 or more reporters
    color: '#b33a55'       # the Discord embed's colour
```

A reason in reasons.yml:

```yaml
categories:
  cheating:
    name: '<#f3d1d9>Cheating'
    icon: DIAMOND_SWORD
    description:
      - '<#c0a0a8>Hacked clients, x-ray, fly,'
      - '<#c0a0a8>kill aura, auto-clickers.'
```

## Discord

<!-- Screenshot: docs/images/discord-thread.png, a report thread with the embed and buttons. -->

The bot only makes outgoing connections, so there are no ports to open and it works on shared
hosting. It needs no privileged intent unless you want thread messages copied in as notes. The
token can come from the `KOBAN_DISCORD_TOKEN` environment variable rather than a file, and Koban
never logs or shows it.

**[Step-by-step setup guide →](docs/discord-setup.md)**

## PlaceholderAPI and other plugins

`%koban_open%`, `%koban_claimed%`, `%koban_active%`, `%koban_resolved%`, `%koban_dismissed%`,
`%koban_my_claimed%`, `%koban_against%`, `%koban_made%`, `%koban_linked%`, `%koban_discord%`.
Every value comes from memory, so they're safe on scoreboards.

Vanish plugins (SuperVanish, PremiumVanish, EssentialsX, CMI) work without setup. Vanished staff
can't be reported by players who can't see them, and are never listed as witnesses.

Details, punishment templates and the event API are in **[docs/integrations.md](docs/integrations.md)**.

## Compatibility

One jar runs on Spigot and Paper from 1.20.6 to the newest release. It uses only public API (no
NMS), checks for features at runtime rather than by version number, and compiles against both the
oldest and the newest Spigot API in CI.

Tested on real servers:

| Server | Java | |
|---|---|---|
| Paper 1.21.11 (build 132) | 21 | in game with test clients, plus a console smoke test |
| Spigot 1.21.11 | 21 | in game with test clients, plus a console smoke test |
| Paper 26.3 (build 256) | 25 | console smoke test |
| Spigot 26.3 | 25 | console smoke test |

On Paper, text goes through Paper's own Adventure. On Spigot it goes through the BungeeCord chat API.
`/koban debug text` shows which path is in use and checks it round-trips.

## Building from source

```bash
./mvnw package            # jar in target/, runs the tests
./mvnw -Papi-latest compile   # also compile against the newest Spigot API (needs JDK 25)
```

The MySQL tests use Testcontainers and run when Docker is available; otherwise they're skipped.

## Architecture

```
domain/     reports, merging, permissions, evidence, outcomes: plain Kotlin, no Bukkit, unit tested
service/    ReportService: every change is one transaction plus an audit entry, then listeners
storage/    SQLite/MySQL through HikariCP, versioned migrations, async on Dispatchers.IO
game/       the report flow, staff actions, notifications, punishment runner
menu/       inventory menus built from menus.yml
discord/    the JDA bot: thread sync (dirty flag + worker queue), buttons, modals, /setup checks
platform/   Paper vs Spigot text, evidence capture, the main-thread dispatcher
api/        Bukkit events
```

A few decisions:

- **Every state change is a transition.** Claim, resolve, dismiss and reopen are checked by one
  state machine in `domain/`. Each is stored with a version number, so two staff clicking at once
  can't both win.
- **Discord is eventually consistent.** A change marks the report "dirty" in the same transaction.
  A single worker redraws dirty reports and clears the flag only if nothing changed in between.
  A minute-long sweep catches anything missed while Discord was down.
- **JDA never touches Bukkit.** Discord callbacks go through the same service as in-game actions.
  Anything that needs the server, such as dispatching a punishment command, hops to the main
  thread.

## License

[MIT](LICENSE)
