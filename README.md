# Punishments

Warn, mute, kick, temp-ban and ban: from a panel, or from a command.

`/punish Steve` opens a dialog with Steve's head, whether he is online, what
he is serving now and how many warnings count against him; a reason, a length
and a Silent switch; and a button for each punishment. Bans ask first, and
take typed details. History is a list with a Revoke button on each entry. A
warned player gets a dialog they have to acknowledge, on the spot or the next
time they join.

Every button gives exactly what the matching command gives, so the commands
are never second-class: they work on every client, from console, from other
plugins, and without Dialog Engine installed at all.

The screens are drawn in Studio and ship inside the jar as Dialog Engine drawn
dialogs (`drawn/punishments/`), installed into Dialog Engine on first start
(`installDrawn`). The panel's Reason, Length and Silent controls are each staff
member's own Dialog Engine settings, so they stay where they were left.

Runs on **Spigot or Paper 1.21.7+**, one jar. The panel needs **Dialog Engine
0.4.0+** and a 1.21.6+ client (1.21.9+ to see players' heads); the commands
need nothing.

## Commands

| Command | Permission |
|---|---|
| `/punish [player]` | `punishments.punish` (no name: pick somebody) |
| `/warn <player> [-s] [reason]` | `punishments.warn` |
| `/unwarn <player>` | `punishments.unwarn` (the newest warning) |
| `/mute <player> [length] [-s] [reason]` | `punishments.mute` |
| `/unmute <player>` | `punishments.unmute` |
| `/kick <player> [-s] [reason]` | `punishments.kick` |
| `/tempban <player> <length> [-s] [-ip] [reason]` | `punishments.tempban` |
| `/ban <player> [length] [-s] [-ip] [reason]` | `punishments.ban` (`-ip`: `punishments.ban.ip`) |
| `/unban <player>` | `punishments.unban` |
| `/history <player>` | `punishments.history` |
| `/punishments reload` · `/punishments revoke <id>` | `punishments.admin` · `punishments.revoke` |

- **Lengths**: `30m`, `2h`, `7d`, `2w`, `1mo`, `1y`, combined (`1d12h`), or
  `perm`. Leave it out for the reason's default.
- **Reasons**: `#hacking` is the preset of that name (its label, and its
  default lengths); anything after it is details: `/tempban Steve #hacking fly`.
- **`-s`** is silent: only staff with `punishments.notify` hear about it.
- **`-ip`** also bans the player's address. A local or private address
  (loopback, `10.x`, `192.168.x`, `fc00::/7`, link-local) is refused: behind a
  proxy or on a LAN it is everybody's. `punishments.exempt` players are never
  turned away by an address ban.
- Offline players work for everything but kick.
- `punishments.staff` is all of it; `punishments.exempt` can't be punished. An
  offline player counts as exempt if they held it when they were last online.
- A warning that reaches an escalation step runs that step's command only if
  the warner could have run it themselves; otherwise they are told so.

Every name and alias is in `config.yml`, and any command can be turned off.
Punishments takes `/ban`, `/kick` and the rest over from vanilla and other
plugins.

## Customising

- `config.yml`: storage (SQLite, or MySQL/MariaDB for a network), command names,
  reason presets and their lengths, caps per staff rank (`limits:`), warning
  expiry and escalation, the commands a mute blocks, who hears about what, the
  update check.
- `messages.yml`: every word, in MiniMessage, including the kick and ban
  screens and every value drawn on the panels.
- The screens themselves: `/de edit punishments:panel` (or `confirm`,
  `history`, `players`, `warned`) opens one in Dialog Engine's browser editor.
  An edited screen is kept when Punishments updates.

`/punishments reload` reads both files. Changing `storage:` needs a restart.

## Changing the shipped screens

They are Dialog-style documents in `dialogs-src/dialogs.json`, Studio's own
format. Edit them, then have Studio draw them into the jar's resources:

```bash
node tools/make-dialogs.mjs
```

Bump the version afterwards, or servers that installed this version keep the
old screens. The panel's buttons run `/punishments act <warn|mute|kick|tempban|ban|unmute|unban> <player>`;
its controls set the settings `punish_reason`, `punish_length` and
`punish_silent`, whose values must stay the reason keys and lengths in
`config.yml`.

## Building

```bash
cd ../../dialog-engine && ./gradlew build   # the jar this compiles against
cd ../de-addons/punishments && ./gradlew build
```

`build/libs/Punishments-<version>.jar`. `DIALOG_ENGINE_JAR` points the build
at another Dialog Engine jar.

## For developers

`PunishmentPreEvent` (cancellable, reason changeable), `PunishmentEvent`
(done) and `PunishmentRevokeEvent`, all on the main thread, in
`ai.resourcepack.punishments.api.event`.
