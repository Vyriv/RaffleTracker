# RaffleTracker

A Hypixel SkyBlock mod that tracks Year 500 Raffle tasks.

## Screenshots

![RaffleTracker Easy Tasks](assets/RT_Easy.png) ![RaffleTracker Medium Tasks](assets/RT_Medium.png) ![RaffleTracker Hard Tasks](assets/RT_Hard.png)

## Features

- Displays raffle tasks that arent completed
- Click `Easy`, `Medium`, or `Hard` to switch task tabs.
- Hover a task row to see what the challenge is.
- use /rt move to drag or resize it

## Commands

| Command | Description |
|---|---|
| `/rt` | Show RaffleTracker command help |
| `/rt move` | Open the position screen to drag and resize the HUD |
| `/rt toggle` | Hide or show the tracker UI without disabling task tracking |
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

- Fabric Loader 0.19.2+ for 1.21.11, or 0.19.3+ for 26.1.2
- Fabric API
