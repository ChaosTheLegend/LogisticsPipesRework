# Logistics Pipes Rework — TODO

Compiled from the project board. Difficulty is an estimate of implementation effort, not priority.

**Legend:** 🟢 easy (localized change / content) · 🟡 medium (new GUI, block or module, contained logic) · 🟠 hard (touches routing, request or crafting core) · 🔴 very hard (new subsystem or cross-cutting rewrite)

---

## Upgrade Rework

- [ ] Remove unused/useless upgrades

Upgrades after rework:
- [ ] NBT upgrade - filter by nbt
- [ ] Damage upgrade - filter by damage values
- [ ] OreDict upgrade - filter by ore dict names
- [ ] Placement rules upgrade - configure from which slot to extract/insert
- [ ] Prestock upgrade - request items and keep them prestocked in internal buffer
- [ ] CC control upgrade - computercraft/open computer integration? (I guess)

## Crafting

- [x] 🟢 Crafting monitoring upgrade — install without pipe controller - deprecated, crafting monitor enabled by default

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
- [ ] 🟢 Allow item sink modules to be exchanged into different types in crafting table / assembler / chisel
- [ ] 🟢 Item Speed Upgrade
- [ ] 🟡 Logical Extractor upgrade
- [ ] 🟡 Remove disconnection upgrades — allow disconnection via a GT tool
- [ ] 🟡 ItemSink priority
- [ ] 🟡 Fuzzy damage split
- [ ] 🟡 Priority for providers and basic pipes
- [ ] 🟡 Rethink modules

## UI / UX & Quality of Life

- [ ] 🟢 Add tooltips to items and modules
- [ ] 🟢 Allow HUD glasses to be worn in the baubles slot
- [ ] 🟢 Add "T" search functionality for request items
- [ ] 🟢 Reduce particle animations to prevent lag, add an option to disable them completely
- [ ] 🟡 Better GUI for successful / unsuccessful requests
- [ ] Connect/disconect pipes with wrench
- [ ] Enable/disable sneaky behavior with screwdriver

## Bugs & Performance

- [ ] 🟢 Torch can be mounted on a crafting (any?) pipe by using a wrench on the bottom face of the torch
- [ ] 🟡 World owner (LAN server hosted from the player's game instance) render config overrides other clients' config
- [ ] 🟠 On a large LP network, adding any new pipe except transport ones causes short-term but severe lag

## Balance & Documentation

- [ ] 🟢 Chassis rebalance
- [ ] 🟢 Recipes rebalancing
- [ ] 🟢 Update questbook tab to better explain the different pipes — information about LP is very scarce
- [ ] 🟡 Write GuildeNH Logistics Pipes guide / documentation

## Texturing

- [ ] New modernized icons for modules
- [ ] New modernized icons for upgrades

## Integrations & Large Features

- [ ] 🟡 Re-add Inv. System Connector pipe
- [ ] 🟠 Re-add energy transportation to LP
- [ ] 🔴 AE2 connectivity

## Done

- [x] Crafting pattern mk1 item
- [x] Crafting pattern mk2
- [x] Crafting pattern mk3
- [x] Crafting overflow policy
