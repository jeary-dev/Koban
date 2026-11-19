# Setting up the Discord bot

Koban's bot runs inside the plugin. It only makes outgoing connections to Discord, so there are no
ports to open and no web server, and it works on shared hosting. It takes about ten minutes. When
you're done, `/koban setup` checks every step and tells you what's missing.

## 1. Create the bot

1. Open the [Discord Developer Portal](https://discord.com/developers/applications) and click
   **New Application**. The name is the bot's name in your server (e.g. "Koban").
2. Go to **Bot** and click **Reset Token**, then copy the token. Discord shows it once.
   - Anyone who has the token controls the bot. Don't paste it in Discord, screenshots or a public
     repository. If it leaks, reset it here; the old token stops working immediately.
3. Still on **Bot**, under **Privileged Gateway Intents**:
   - Leave everything **off** unless you want thread messages copied into reports as notes.
   - If you do, turn on **Message Content Intent** and set `discord.sync-notes: true` (step 5).
     Without the intent turned on here, Discord refuses the connection and Koban tells you so.

## 2. Invite it

Use the invite link `/koban setup` prints once the token is in your config. It already contains
the right permissions. To build it by hand, go to **OAuth2 → URL Generator** and pick the scopes
`bot` and `applications.commands`, plus these permissions:

| Permission | Why |
|---|---|
| View Channel, Read Message History | See the reports channel and find older threads |
| Send Messages, Send Messages in Threads | Post reports and log lines ("Steve claimed this") |
| Create Public Threads, Manage Threads | One thread per report; archive closed ones, rename them |
| Embed Links | The report card |
| Add Reactions | 📝 under a thread message that became a note |
| Manage Channels *(forum only, optional)* | Create the forum tags (Open, Claimed, Cheating …) |

## 3. Make the reports channel

A **forum channel** works best. Every report is a post with tags for its status and reasons, and
staff can filter by tag. A normal **text channel** also works; each report then becomes a message
with a thread under it.

Make the channel private to staff. Discord's own channel permissions decide who can *see*
reports. Koban's roles (step 5) decide who can *act* on them.

If you don't give the bot Manage Channels, create these tags yourself (names must match, case
doesn't matter): `Open`, `Claimed`, `Resolved`, `Dismissed`, plus the plain name of every reason in
reasons.yml (`Cheating`, `Harassment` …). Status tag names come from the `discord.status-*` keys
in messages.yml. A forum holds at most 20 tags, so with more than 16 reasons the last ones get no
tag. Koban warns you about that when it loads.

## 4. Copy the IDs

In Discord: **Settings → Advanced → Developer Mode**. Then:

- **Server ID**: right-click the server icon → *Copy Server ID*.
- **Channel ID**: right-click the reports channel → *Copy Channel ID*.
- **Role IDs**: Server Settings → Roles → right-click a role → *Copy Role ID*.

## 5. Fill in config.yml

```yaml
discord:
  enabled: true
  token: 'paste-the-token-here'
  guild-id: '123456789012345678'
  channel-id: '234567890123456789'
  create-tags: true
  sync-notes: false
  roles:
    admin: ['345678901234567890']     # Admins: everything
    resolve: ['456789012345678901']   # Moderators
    claim: ['456789012345678901']
    punish: ['456789012345678901']
    note: ['456789012345678901']
    view: ['567890123456789012']      # Helpers: can read, can't act
    override: []
```

Put IDs in quotes; YAML can round very large numbers.

**Keeping the token out of the file.** Leave `token: ''` and set the environment variable
`KOBAN_DISCORD_TOKEN` for the server process instead. That suits hosting panels and Docker, and it
keeps the token out of any config you share or back up. The variable wins over the file.

What each role grants:

| Role key | Allows |
|---|---|
| `view` | `/reports open`, `/reports lookup` (every other key includes it) |
| `claim` | Claim and unclaim |
| `resolve` | Resolve, dismiss, reopen |
| `punish` | Run a punishment from actions.yml |
| `note` | Thread messages become report notes (with `sync-notes`) |
| `override` | Act on reports someone else claimed |
| `admin` | All of the above |

Roles are checked on every click, so taking a role away takes effect immediately. Nobody can use
the buttons until at least one role is set; Koban warns about that when it loads.

## 6. Reload and check

Run `/koban reload` (or restart), then `/koban setup`:

```
交 Koban · Discord setup check
  ✔ A token is set (never shown)
  ✔ Connected as Koban to My Server
  ✔ Reports go to the forum #reports
  ✔ The bot has every permission it needs there
  ✔ All forum tags exist
  ✔ Roles: view: Helper, claim: Moderator, …
  • Thread messages aren't copied as notes (discord.sync-notes: false)
  • Invite link with the right permissions: https://discord.com/oauth2/…
```

Each ✘ line says what to fix. Reports keep working in game the whole time; if Discord is down or
misconfigured, threads catch up as soon as the bot connects.

## Using it

**In a report thread**
- **Claim / Unclaim**: one button that toggles.
- **Resolve**: opens a form with an outcome (warned, kicked, banned …) and an optional note.
- **Dismiss**: with an optional note.
- **Reopen**: shown on closed reports.
- **Punish** menu: pick a punishment from actions.yml. You get a preview of the exact command,
  and only you can confirm it. It runs as the server console and is recorded in the audit log.
- With `sync-notes`, anything staff with the `note` role write in the thread becomes a report note
  (marked with 📝). Notes added in game are posted in the thread.

**Slash commands**
- `/reports open`: the reports waiting for a decision.
- `/reports lookup <player>`: a player's report history.
- `/link <code>`: link your Discord account to your Minecraft account. Get the code in game with
  `/koban link`. Once linked, what you do on Discord shows your Minecraft name in game, and the
  reports you claimed in game count as yours on Discord.

**Closed reports** are archived, so the forum's active list shows only what's waiting. Any
change (reopening, a new reporter, a note) brings the thread back.

## Troubleshooting

| `/koban setup` says | Fix |
|---|---|
| The bot token was rejected | Reset the token in the Developer Portal and paste the new one |
| Message Content intent isn't granted | Turn it on in the Developer Portal (Bot → Privileged Gateway Intents) or set `sync-notes: false` |
| The bot isn't in the server with guild-id … | Invite it with the link `/koban setup` prints, or check `guild-id` |
| There's no channel … the bot can see | Copy the channel ID again (it must be in the `guild-id` server), and let the bot's role see that channel |
| Channel … is a voice/category/… | Use a forum channel or a text channel |
| Missing permissions: … | Re-invite with the link, or give the bot those permissions on the channel |
| Missing forum tags | Give the bot Manage Channels, or create the tags yourself |
| These role IDs don't exist | Copy the role IDs again (Copy *Role* ID, not User ID) |
