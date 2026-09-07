# Rift Arena

A small top-down arena brawler in the spirit of League of Legends — written as **one HTML file**
with embedded JavaScript and CSS. No build step, no dependencies, no external assets.

Open `index.html` in any modern browser and play.

## Multiplayer

Multiplayer here means **same-screen (local) multiplayer**: two people share one keyboard,
and the remaining slots are filled by bots. There is no server component — everything runs
in the page.

| Mode | Teams |
| --- | --- |
| 1 Player | You + an AI ally vs 2 AI (2v2) |
| 2 Players — Co-op | Player 1 + Player 2 vs 2 AI (2v2) |
| 2 Players — Duel | Player 1 vs Player 2 (1v1) |

First team to the chosen kill count (8 / 15 / 25) wins. Fallen champions respawn at their
base after 5 seconds.

## Controls

**Player 1**

| Input | Action |
| --- | --- |
| `W` `A` `S` `D` | Move |
| Hold right mouse button | Click-to-move (walks to the cursor, steering around walls) |
| Left mouse button | Basic attack, aimed at the cursor (hold to auto-repeat) |
| `Q` or `Space` | Special ability |
| `Esc` | Pause · `M` while paused returns to the menu |

**Player 2** (shares the keyboard, no mouse)

| Input | Action |
| --- | --- |
| Arrow keys | Move |
| `,` | Basic attack |
| `.` | Special ability |

Player 2's attacks auto-aim at the nearest enemy, so both players can play comfortably on one
keyboard.

## Champions

| Champion | Role | Basic attack | Special ability |
| --- | --- | --- | --- |
| **Kael**, Blademaster | Fighter · 660 HP | *Crescent Slash* — wide melee arc, 36 dmg | *Blink Strike* — dash forward, cleaving everyone crossed (75 dmg) |
| **Sylva**, Ranger | Marksman · 500 HP | *Piercing Arrow* — fast long-range shot, 27 dmg | *Arrow Volley* — a fan of seven arrows, 23 dmg each |
| **Ignis**, Pyromancer | Mage · 470 HP | *Emberbolt* — slow bolt with splash damage, 32 dmg | *Meteor* — telegraphed blast at the cursor, 95 dmg + slow |
| **Thorne**, Guardian | Tank · 880 HP | *Hammer Smash* — short cone with knockback, 34 dmg | *Bulwark* — 180 shield, haste, and a knockback nova (45 dmg) |

Every champion has a cooldown on both its basic attack and its ability; the HUD card shows a
radial sweep for each, and a matching ring is drawn around the champion in the arena.

## Arena rules

- Walls block movement **and** projectiles; melee arcs still need line of sight through range.
- Champions push each other apart softly, so nobody gets stuck inside a teammate.
- A golden **Relic** spawns in mid lane every 20 seconds and heals 230 HP plus a short haste buff.
- Freshly respawned champions are damage-immune for a moment, which ends the instant they attack.

## Implementation notes

Everything lives in `index.html`:

- **Rendering** — a single 1360×760 `<canvas>`, scaled by CSS to fit the window; particles,
  floating damage numbers, telegraphs and screen shake are drawn per frame.
- **Collision** — circle-vs-AABB resolution for champions (with axis sliding along walls),
  sub-stepped projectile sweeps so fast shots cannot tunnel through walls, and circle-circle
  separation between units.
- **Bots** — pick a target, hold their champion's preferred range, strafe, lead their shots for
  slow projectiles, back off and go for the Relic when low, step out of meteor telegraphs, and
  probe angles around obstacles when the direct path is blocked.
- **HUD** — DOM overlay (scores, kill feed, player cards with conic-gradient cooldown sweeps)
  layered over the canvas so text stays crisp at any window size.

Tested in headless Chromium: menus, all three modes, both control schemes, pause/resume,
match-end and rematch flow run without console errors.
