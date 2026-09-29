# Logistics Pipes Rework — TODO

Compiled from the project board. Difficulty is an estimate of implementation effort, not priority.

The core design choices are in [docs/rework-design-decisions.md](../docs/rework-design-decisions.md). Items that come from that doc,
or that it changes, are marked _(design doc)_; where an item here disagrees with the doc, the doc wins.

**Legend:** 🟢 easy (localized change / content) · 🟡 medium (new GUI, block or module, contained logic) · 🟠 hard (touches routing, request or crafting core) · 🔴 very hard (new subsystem or cross-cutting rewrite)

---

## Upgrade Rework

- [ ] Remove unused/useless upgrades. Decided _(design doc)_:
  - [ ] 🟡 Remove sneaky upgrades; the insertion side is set with a GT screwdriver
  - [ ] 🟡 Remove side-block (disconnection) upgrades; sides are blocked with a GT crowbar
  - [ ] 🟢 Remove the advanced satellite upgrade; its behaviour becomes the default
- [ ] 🟡 Speed upgrade rework _(design doc)_: no longer affects item travel speed. It raises the extraction speed and
  stack size of extractor and provider modules and speeds up the pattern crafting table

Upgrades after rework:
- [ ] 🟠 Buffer upgrade (new) _(design doc)_: supplier and crafting pipes hold 1 requested set inside the pipe and send it
  without travel time. Replaces the pattern pipe's always-on buffering and is also the prestock: without it, pipes
  re-request items every time
- [ ] 🟡 Crafting upgrade (new) _(design doc)_: lets suppliers place crafting requests (today they do it by default)
- [ ] 🟡 Crafting monitor upgrade _(design doc)_: goes on supplier pipes, request pipes and the request table. With it you
  can inspect the whole request tree; without it you only see that crafts were queued. The limit on concurrent monitored
  requests is supplier 1, request pipe mk1 2, mk2 4, request table 6
- [ ] NBT upgrade - filter by nbt (_design doc_: also unlocks the NBT option in crafting pipes)
- [ ] Damage upgrade - filter by damage values
- [ ] OreDict upgrade - filter by ore dict names (_design doc_: also unlocks the OreDict option in crafting pipes)
- [ ] Placement rules upgrade - configure from which slot to extract/insert
- [x] ~~Prestock upgrade - request items and keep them prestocked in internal buffer~~ _(design doc: merged into the
  Buffer upgrade)_
- [ ] CC control upgrade - computercraft/open computer integration? (I guess)

## Crafting

- [x] 🟢 Crafting monitoring upgrade — install without pipe controller - deprecated, crafting monitor enabled by default
  _(design doc reopens this: without the upgrade the monitor only shows that crafts were queued; the crafting monitor
  upgrade adds the full tree, with request limits, see Upgrade Rework)_

- [ ] 🟢 Allow players to open crafting pipes without wrenches
- [ ] 🟢 Request pipe & request table — add input field for requested items
- [ ] 🟢 Replace request buttons with number input fields in request table/pipes
- [ ] 🟡 Request Table
- [ ] 🟡 Improved crafting monitor
- [ ] 🟡 Supplier crafting monitor
- [ ] 🟡 Crafting request cancellation
- [ ] 🟡 NEI recipe support
- [ ] 🟡 Increase the 127 limit for crafting items
- [x] 🟠 Pattern Crafting Pipe
- [x] 🟠 Blocking and smart blocking support for pattern crafting pipes
- [x] 🟠 Item Crafting Module
- [x] 🟠 Process Crafting Module
- [x] 🟠 Item byproducts
- [x] 🟠 Satellite rework
- [x] 🟠 NBT / OreDict support
- [ ] 🔴 Better request handler
- [ ] 🟡 Remove the old crafting modules (`ModuleCrafter`, legacy crafting pipe and its GUI) _(design doc)_: the pattern
  crafting pipe and patterns replace them

## Fluids & Fluid Crafting

- [ ] 🟢 Logistics Fluid Request — add input field for requested liquids
- [ ] 🟢 View fluids in different quantities (mB, buckets, multiples of 144, …)
- [ ] 🟡 Fluid NEI recipe support
- [ ] 🟡 Logistics Fluid Request pipe mk2
- [ ] 🟡 Fix: logistics pipes cannot supply multiple liquids into one input
- [ ] 🟠 Fluid byproducts
- [ ] 🟠 Fluid crafting for pattern crafting pipes without needing satellites
- [ ] 🔴 Logistics Fluid Chassis and fluid modules
- [ ] 🔴 Fluid crafting

## Supplier

- [ ] 🟡 Item Supplier
- [ ] 🟡 Fluid Supplier
- [ ] 🟡 NBT / OreDict / damage support for supplier pipe (possible upgrade?)
- [ ] 🟠 Supplier reservation for requested crafts, so nothing can steal the ingredients

## Chassis, Modules & Upgrades

- [ ] 🟢 Increase the number of upgrade slots for modules to 4
- [ ] 🟢 Remove the "upgrade module upgrade" for chassis — make chassis upgradable by default
  _(design doc: chassis will have upgrades; still undecided whether they stay per module or are shared by the whole
  chassis)_
- [ ] 🟢 Allow item sink modules to be exchanged into different types in crafting table / assembler / chisel
- [ ] 🟢 Item Speed Upgrade _(design doc: replaced by the speed upgrade rework; travel speed comes from transport
  controller blocks)_
- [ ] 🟡 Logical Extractor upgrade
- [ ] 🟡 Remove disconnection upgrades — allow disconnection via a GT tool
- [ ] 🟡 ItemSink priority
- [ ] 🟡 Fuzzy damage split
- [ ] 🟡 Priority for providers and basic pipes
- [ ] 🟡 Rethink modules
  - [ ] 🟡 Remove duplicate modules that only add upgraded features (extractor tiers, item sink variants) and replace them
    with upgrades _(design doc)_
  - [ ] 🔴 Fluid modules (new) that work like fluid pipes, for the fluid chassis _(design doc)_

## UI / UX & Quality of Life

- [ ] 🟢 Add tooltips to items and modules
- [ ] 🟢 Allow HUD glasses to be worn in the baubles slot
- [ ] 🟢 Add "T" search functionality for request items
- [ ] 🟢 Reduce particle animations to prevent lag, add an option to disable them completely
- [ ] 🟡 Better GUI for successful / unsuccessful requests
- [ ] Connect/disconect pipes with wrench
- [ ] Enable/disable sneaky behavior with screwdriver
- [ ] 🟠 Rewrite every GUI with ModularUI _(design doc)_, adding controls for the features in the design doc where the old
  GUIs lack them. Status: [.claude/modularUI-docs/migration-status.md](../.claude/modularUI-docs/migration-status.md)
- [ ] 🟢 Upgrades insertable without a pipe controller through the side upgrade GUI, at most 4 _(design doc)_

## Pipes & Transport

_(design doc)_
- [ ] 🔴 Item transport rewrite: no traveling item entities, items teleport pipe to pipe in clumps. Phase 1 is in code,
  not tested in game: [docs/item-transport-rewrite.md](../docs/item-transport-rewrite.md)
- [ ] 🟠 Only LP pipes route and carry items: drop BuildCraft / Thermal Dynamics / other pipe support from routing and transport
- [ ] 🟡 GregTech pipes as buffers: they can take items from the LP network and push items into it, but aren't part of routing
- [ ] 🟠 Transport controller blocks _(design doc)_: several tiers, like the logistics power junction, that set item travel
  speed; the last tier makes transport instant (the big performance win). Replaces speed upgrades on travel
- [ ] 🟡 Tesseracts / special pipe connections: keep the explicit link for compatibility; in GTNH most likely treated as
  chests (buffer endpoints) _(design doc)_

## Bugs & Performance

- [ ] 🟢 Torch can be mounted on a crafting (any?) pipe by using a wrench on the bottom face of the torch
- [ ] 🟡 World owner (LAN server hosted from the player's game instance) render config overrides other clients' config
- [ ] 🟠 On a large LP network, adding any new pipe except transport ones causes short-term but severe lag
- [ ] 🟠 TPS lag from many items in pipes, see [docs/lag-investigation.md](../docs/lag-investigation.md) §1 (the transport rewrite)
- [ ] 🟡 FPS lag from particles, see [docs/lag-investigation.md](../docs/lag-investigation.md) §2
- [ ] 🟠 FPS lag near many pipes (pipe bodies drawn every frame), see [docs/lag-investigation.md](../docs/lag-investigation.md) §3

## Balance & Documentation

- [ ] 🟢 Chassis rebalance
- [ ] 🟢 Recipes rebalancing
- [ ] 🟢 Update questbook tab to better explain the different pipes — information about LP is very scarce
- [ ] 🟡 Write GuildeNH Logistics Pipes guide / documentation

## Texturing

- [ ] New modernized icons for modules
- [ ] New modernized icons for upgrades

## Integrations & Large Features

- [ ] 🟠 Re-add and upgrade the Inv. System Connector pipe _(design doc: a major feature, linking two networks through any
  buffer: chests, ender chests, pipelines, chest minecarts, cargo rockets; for cleanroom automation and outposts)_
- [ ] 🟠 Re-add energy transportation to LP
- [ ] 🔴 AE2 connectivity

## Debug / Legacy

- [ ] 🟢 Legacy Wrench item _(design doc)_: debugging only, not craftable. Opens the legacy GUIs that still exist in case
  the new implementation breaks something; it does not bring back deleted ones

## Done

- [x] Crafting pattern mk1 item
- [x] Crafting pattern mk2
- [x] Crafting pattern mk3
- [x] Crafting overflow policy
