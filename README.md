# Verdict

Warn, mute, kick, temp-ban and ban: from a panel, or from a command.

`/punish Steve` opens a dialog with Steve's head, whether he is online, what
he is serving now and how many warnings count against him; a reason and a
length to pick, details to type, a Silent switch; and a button for each
punishment the staff member is allowed to give. Bans ask first. History is a
paged dialog with a Revoke button on anything still in force. A warned player
gets a dialog they have to acknowledge, on the spot or the next time they join.

Every button gives exactly what the matching command gives, so the commands
are never second-class: they work on every client, from console, from other
plugins, and without RP Engine installed at all.

The screens are drawn in Studio and ship inside the jar as an RP Engine
content folder (`content/verdict/`), installed into the engine on first start
like every addon's content. The panel's Reason, Length and Silent controls are
each staff member's own RP Engine settings, so they stay where they were left.

Needs **Paper 1.21.7+**. The panel needs **RP Engine** and a 1.21.6+ client
(1.21.9+ to see players' heads); the commands need nothing.

## Commands

| Command | Permission |
|---|---|
| `/punish [player]` | `verdict.punish` (no name: pick somebody) |
| `/warn <player> [-s] [reason]` | `verdict.warn` |
| `/unwarn <player>` | `verdict.unwarn` (the newest warning) |
| `/mute <player> [length] [-s] [reason]` | `verdict.mute` |
| `/unmute <player>` | `verdict.unmute` |
| `/kick <player> [-s] [reason]` | `verdict.kick` |
| `/tempban <player> <length> [-s] [-ip] [reason]` | `verdict.tempban` |
| `/ban <player> [length] [-s] [-ip] [reason]` | `verdict.ban` (`-ip`: `verdict.ban.ip`) |
| `/unban <player>` | `verdict.unban` |
| `/history <player>` | `verdict.history` |
| `/verdict reload` · `/verdict revoke <id>` | `verdict.admin` · `verdict.revoke` |

- **Lengths**: `30m`, `2h`, `7d`, `2w`, `1mo`, `1y`, combined (`1d12h`), or
  `perm`. Leave it out for the reason's default.
- **Reasons**: `#hacking` is the preset of that name (its label, and its
  default lengths); anything after it is details: `/tempban Steve #hacking fly`.
- **`-s`** is silent: only staff with `verdict.notify` hear about it.
- **`-ip`** also bans the player's address. A local or private address
  (loopback, `10.x`, `192.168.x`, `fc00::/7`, link-local) is refused: behind a
  proxy or on a LAN it is everybody's. `verdict.exempt` players are never
  turned away by an address ban.
- Offline players work for everything but kick.
- `verdict.staff` is all of it; `verdict.exempt` can't be punished. An offline
  player counts as exempt if they held it when they were last online.
- A warning that reaches an escalation step runs that step's command only if
  the warner could have run it themselves; otherwise they are told so.

Every name and alias is in `config.yml`, and any command can be turned off.
Verdict takes `/ban`, `/kick` and the rest over from vanilla and other plugins.

## Customising

- `config.yml`: storage (SQLite, or MySQL/MariaDB for a network), command names,
  reason presets and their lengths, the lengths the panel offers, caps per staff
  rank (`limits:`), warning expiry and escalation (3 warnings → a mute, 5 → a
  temp-ban, or whatever you write), the commands a mute blocks, who hears about
  what.
- `messages.yml`: every word, in MiniMessage, including the kick and ban
  screens.
- The screens' pictures are fixed; every value drawn on them (names, counts,
  dates, reasons, the confirmation's words) comes from `messages.yml`.

`/verdict reload` reads both. Changing `storage:` needs a restart.

## Changing the screens

They are Dialog-style documents in `dialogs-src/dialogs.json`, Studio's own
format. Edit them, then rebuild the content folder with Studio's exporter:

```bash
cd studio
node scripts/export-dialog-content.mjs ../rpe-addons/verdict/dialogs-src/dialogs.json   ../rpe-addons/verdict/src/main/resources/content/verdict --namespace verdict --previews ../rpe-addons/verdict/build/previews
```

Keep `content/verdict/pack.yml` (the exporter clears the folder) and bump the
plugin version, or servers that installed this version keep the old files.
The panel's buttons run `/verdict act <warn|mute|kick|tempban|ban|unmute|unban> <player>`;
its controls set the RP Engine settings `verdict_reason`, `verdict_length` and
`verdict_silent`, whose values must stay the reason keys and lengths in
`config.yml`.

## For developers

`PunishmentPreEvent` (cancellable, reason changeable), `PunishmentEvent`
(done) and `PunishmentRevokeEvent`, all on the main thread.
