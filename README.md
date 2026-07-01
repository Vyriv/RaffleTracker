# RaffleTracker

A Hypixel SkyBlock mod that tracks Year 500 Incredible Raffle tasks.

## Screenshots

![RaffleTracker Easy Tasks](assets/RT_Easy.png)

![RaffleTracker Medium Tasks](assets/RT_Medium.png)

![RaffleTracker Hard Tasks](assets/RT_Hard.png)

## Features

- **Raffle task HUD** - displays your scanned raffle tasks directly on your screen
- **Automatic task scanning** - open the Year 500 Incredible Raffle Box task UI and the mod reads the task item names and lore
- **Completion detection** - detects raffle completion chat messages and temporarily removes completed tasks from the HUD
- **Reset timer tracking** - reads the raffle task reset timer from the Raffle Tasks paper and expires the cached task list when the timer ends
- **New-task prompt** - shows `OPEN RAFFLE FOR NEW TASKS` after a reset until you scan the raffle UI again
- **Difficulty tabs** - click `Easy`, `Medium`, or `Hard` to show one difficulty at a time
- **Dynamic tab cleanup** - completed difficulties disappear once all tasks in that difficulty are done
- **Hover task details** - hover a task while a cursor is available to see its scanned objective text
- **Moveable and resizable HUD** - use `/rt move` to drag the tracker, resize it with the scroll wheel, or use `+`, `-`, and `0`
- **HUD visibility toggle** - use `/rt toggle` to hide or show the tracker UI while task tracking keeps running
- **Standalone mod** - ships separately from VyAddons and DRT

## Commands

| Command | Description |
|---|---|
| `/rt` | Show RaffleTracker command help |
| `/rt move` | Open the position screen to drag and resize the HUD |
| `/rt movetebox` | Alias for `/rt move` |
| `/rt toggle` | Hide or show the tracker UI without disabling task tracking |
| `/rt rescan` | Force-scan the currently open raffle task UI |
| `/rt clear` | Clear cached raffle tasks and reset timer state |

You can also use `/raffletracker` with the same subcommands.

You can also click the HUD directly while a cursor is available:

- Click `Easy`, `Medium`, or `Hard` to switch task tabs.
- Hover a task row to see the scanned task details.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.11 or 26.1.2
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop the matching `RaffleTracker` jar into your `mods/` folder

## Requirements

- Minecraft 1.21.11 or 26.1.2
- Fabric Loader 0.19.2+ for 1.21.11, or 0.19.3+ for 26.1.2
- Fabric API
- Java 21+ for 1.21.11, or Java 25+ for 26.1.2
