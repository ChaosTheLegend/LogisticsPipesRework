# Logistics Pipes Rework — Roadmap

Derived from [rework.md](rework.md). Every open item there is assigned to exactly one phase below.
Difficulty legend is the same: 🟢 easy · 🟡 medium · 🟠 hard · 🔴 very hard.

```
Phase 1: Crafting system ──┬──► Phase 2: Upgrades & modules ──┐
                           │                                  ├──► Phase 4: QoL, balance, polish
                           └──► Phase 3: Block UI & requests ─┘
Bugs & performance: parallel track, pick up any time
```

Phases 2 and 3 both depend on Phase 1 (request handler API) but not on each other, so they can overlap.

---

## Phase 1 — Crafting system

**Goal:** a request handler that can carry everything else: large amounts, cancellation, reservations and fluids.

Already done (baseline): pattern crafting pipe + blocking/smart blocking, item & process crafting modules,
item byproducts, satellite rework, NBT/OreDict crafting, patterns mk1–mk3, overflow policy, crafting monitor enabled by default.

### 1a. Request core (start here, everything else builds on it)
- [ ] 🔴 Better request handler
- [ ] 🟡 Increase the 127 limit for crafting items
- [ ] 🟡 Crafting request cancellation (backend: unwind reservations, promises and in-flight items; the UI button comes in Phase 3)

### 1b. Suppliers
- [ ] 🟡 Item Supplier
- [ ] 🟡 Fluid Supplier
- [ ] 🟠 Supplier reservation for requested crafts, so nothing can steal the ingredients

### 1c. Fluid crafting
- [ ] 🟡 Fix: logistics pipes cannot supply multiple liquids into one input
- [ ] 🟠 Fluid crafting for pattern crafting pipes without needing satellites
- [ ] 🟠 Fluid byproducts
- [ ] 🔴 Fluid crafting (the general case; the module-based part waits for the fluid chassis in Phase 2)

**Done when:** large multi-step item and fluid requests resolve, can be cancelled cleanly, and reserved ingredients stay reserved.

---

## Phase 2 — Upgrades & modules

**Goal:** a small, clear set of upgrades, chassis that are upgradable by default, and tools in place of the connection upgrades.

### 2a. Cleanup
- [ ] Remove unused/useless upgrades
- [ ] 🟢 Remove the "upgrade module upgrade"; make chassis upgradable by default
- [ ] 🟢 Increase the number of upgrade slots for modules to 4
- [ ] 🟡 Remove disconnection upgrades; allow disconnection with a GT tool
- [ ] Connect/disconnect pipes with a wrench
- [ ] Enable/disable sneaky behavior with a screwdriver

### 2b. New upgrade set
- [ ] NBT upgrade: filter by NBT
- [ ] Damage upgrade: filter by damage values (includes 🟡 fuzzy damage split)
- [ ] OreDict upgrade: filter by ore dict names
- [ ] Placement rules upgrade: choose which slot to extract from / insert into
- [ ] 🟢 Item Speed Upgrade
- [ ] 🟡 Logical Extractor upgrade
- [ ] Prestock upgrade: request items and keep them prestocked in an internal buffer (needs the Phase 1 request handler)
- [ ] CC control upgrade: ComputerCraft / OpenComputers integration
- [ ] 🟡 NBT / OreDict / damage support for the supplier pipe (reuse the filter upgrades above)

### 2c. Modules & routing priority
- [ ] 🟡 Rethink modules (do this first in 2c; it decides the scope of the rest)
- [ ] 🟡 ItemSink priority
- [ ] 🟡 Priority for providers and basic pipes
- [ ] 🟢 Allow item sink modules to be converted between types in crafting table / assembler / chisel
- [ ] 🔴 Logistics Fluid Chassis and fluid modules (unblocks module-based fluid crafting from 1c)

**Done when:** the upgrade list is final, every upgrade/module has a single clear purpose, and no connection behavior needs an upgrade item.

---

## Phase 3 — Block UI & request table

**Goal:** a modern request workflow: type the amount, search, see what happened, cancel.

### 3a. Request inputs
- [ ] 🟢 Request pipe & request table: input field for requested items
- [ ] 🟢 Replace request buttons with number input fields
- [ ] 🟢 Logistics Fluid Request: input field for requested liquids
- [ ] 🟢 "T" search for request items
- [ ] 🟢 View fluids in different units (mB, buckets, multiples of 144, …)

### 3b. Request table & pipes
- [ ] 🟡 Request Table
- [ ] 🟡 Logistics Fluid Request pipe mk2
- [ ] 🟡 Better GUI for successful / unsuccessful requests
- [ ] 🟡 NEI recipe support
- [ ] 🟡 Fluid NEI recipe support

### 3c. Crafting monitors & block access
- [ ] 🟡 Improved crafting monitor (with a cancel button hooked to the 1a backend)
- [ ] 🟡 Supplier crafting monitor
- [ ] 🟢 Allow players to open crafting pipes without wrenches

**Done when:** a player can search, request an exact amount of an item or fluid, follow it in the monitor, and cancel it without a wrench or button spam.

---

## Phase 4 — QoL, rebalance, GUI tweaks & other

### 4a. QoL & GUI tweaks
- [ ] 🟢 Add tooltips to items and modules
- [ ] 🟢 Allow HUD glasses in the baubles slot
- [ ] 🟢 Reduce particle animations; add an option to disable them

### 4b. Balance
- [ ] 🟢 Chassis rebalance (after 2a, since chassis are now upgradable by default)
- [ ] 🟢 Recipe rebalance (after Phases 2–3 settle the final item list)

### 4c. Textures
- [ ] New modernized icons for modules (can start once 2c is final)
- [ ] New modernized icons for upgrades (can start once 2b is final)

### 4d. Documentation
- [ ] 🟢 Update the questbook tab to explain the different pipes
- [ ] 🟡 Write the GTNH Logistics Pipes guide / documentation

**Done when:** the rework is balanced, documented and ready to release.

---

## Parallel track — Bugs & performance

Not tied to a phase. Pick these up whenever there's room.

- [ ] 🟢 Torch can be mounted on a crafting (any?) pipe by using a wrench on the bottom face of the torch
- [ ] 🟡 LAN host's render config overrides other clients' config
- [ ] 🟠 Large network: adding any non-transport pipe causes short but severe lag (routing table rebuild; worth doing before or during Phase 1, since the request handler will stress routing more)

## After the rework — Integrations & large features

Out of scope for the four phases above. Revisit once Phase 4 ships.

- [ ] 🟡 Re-add Inv. System Connector pipe
- [ ] 🟠 Re-add energy transportation to LP
- [ ] 🔴 AE2 connectivity
