This file is made as a guide for the rework

## Pipes

- The only pipes that can be used for LP routing and transport are the ones from log. pipes mod, buildcraft, thermal, gregtech and other pipes Won't be supported for routing, altho GregTech pipes can be used as buffers and can accept items from LP net and send them into the net
- Pipes no longer spawn traveling item entities, instead they simulate them by teleporting items from pipe to pipe
- Pipe speed no longer controlled by upgrades, speed upgrades are gonna be reworked to instead affect extraction speed/crafting table speed

## Upgrades
- Sneaky upgrades - remove. The sneaky behaviour can be changed with GT screwdriver
- Side block upgrades - remove. Side blocks can be changed using GT crowbar
- Speed upgrades - no longer increase item travel speed, increase extraction speed/strack size for extractor/provider modules, speeds up the pattern crafting table
- Buffer upgrade (new) - allows the pipe to hold 1 set of requested items inside itself, this skips travel time and allows the pipe to transfer items instantly when needed, applied to supplier and crafting pipes
- Nbt filter upgrade (new) - allows to filter items by nbt (enables option in crafting pipes)
- Ore dict. filter upgrade (new) - allows to filter items by ore dict. (enables option in crafting pipes)
- Adv. satellite upgrade - remove, works by default
- Crafting monitor upgrade - can now be applied to request pipes, request table and supplier pipes, allows players to watch pending crafting requests, the number of concurrent requests are limited per pipe: supplier - 1 crafting request, request pipe mk1 - 2 requests, request pipe mk2 - 4 requests, request table - 6
- Crafting upgrade (new) - allows suppliers to place crafting requests (currently this behaviour is enabled by default)
- opaque upgrade - remove, add client side config option to disable item rendering, which will just disable client router for item animations, since server already teleport items

## Modules
- Many modules (extractor, itemsink) are duplicates of each other with upgraded functionality, remove duplicate modules, replace them with upgrades
- Crafting modules - remove, they will be replaced by pattern crafting pipe and crafting pattern respectively
- Fluid modules (new) - modules that will work exactly like fluid pipes, they will work with fluid chassis

## GUI

- All GUIs are to be rewritten with modularUI, for the sake of extendability, better interaction and better packet handling
- Old guis for the pipes often lack controls for features outlined in this doc, they should be added when needed
- Upgrades should be insertable without pipe controller using the side upgrade gui, upgrade storage capped at 4
- Chassis upgrades are still undecided

## New pipes

- Logistics fluid chassis - same as regular chassis, but support fluid modules
- Pattern crafting pipe - new driver for autocrafting, the old crafting system is going to be deprecated
- Inv. system connector pipe - to be re-enabled and upgraded. this is the major advantage of LP allowing to connect 2 networks using *ANY* intermediate buffer (chests, ender chests, long distance pipelines, *chest minecarts*, *CARGO ROCKETS* <- wild one, and more), can be used to automate cleanroom and connect LP networks from different outposts
- Extractor pipe - same as extractor module (why don't we have this yet?)

## Debug/Legacy

Legacy Wrench item - made for the purpose of accessing legacy gui and debugging, in case new implementation breaks something

## Clarifications (2026-09-29)

Answers to open questions raised while aligning the other docs with this one.

- **Legacy Wrench** - for debugging only, nothing else. It isn't craftable. It does **not** bring back deleted legacy
  GUIs; it only opens the ones that still exist.
- **Crafting monitor** - without the upgrade you only see that crafts were queued (no breakdown). With the crafting
  monitor upgrade you can inspect the whole request tree. The per-pipe request limits above apply.
- **Prestock** - is the buffer upgrade. Without it, pipes re-request items every time, which is slow.
- **Chassis upgrades** - chassis will have upgrades. Still undecided: keep per-module upgrades, or share one set of
  upgrades across the whole chassis.
- **Travel speed** - adjustable, but not through speed upgrades. Special **transport controller blocks**, similar to the
  logistics power junction, come in several tiers. The final tier makes item transport instant, which should massively
  improve performance.
- **Tesseracts / special pipe connections** - keep the explicit link for compatibility. In GTNH they will *most likely*
  be treated as chests (a buffer endpoint, like GT pipes), not as part of a route.
