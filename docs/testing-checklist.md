# Testing checklist

Everything in `docs/` that is written but still needs testing, gathered in one place. Each item links to the doc that
has the details. Issue IDs (D1, C8, ...) are from [pattern-crafting.md](pattern-crafting.md) §8, bug IDs (B22, ...) from
[bug-list.md](bug-list.md). Passed items are removed; what failed went to the bug list, with a retest line here.

Use spark for every performance item and write down before/after numbers. Scale target: ~50k pipes, with routing well
under one 50 ms tick.

**Last pass: 2026-09-30, dedicated server.** Delivery, client animation, most of the pipe-break rules, pipe rendering,
full/missing inventory routing, joining networks, the pattern crafting table and satellite MUIs all passed. Blocker:
**B25**, opening the pattern crafting pipe GUI crashes the dedicated server (fine in singleplayer). Every item marked
_(B25)_ waits for that fix.

---

## 1. Item transport rewrite (clumps)

Details: [item-transport-rewrite.md](item-transport-rewrite.md).

**Delivery and animation**
- [ ] Packets only go to players in the same dimension within 64 blocks of either end of the corridor.

**Breaking pipes under a clump in flight**
- [ ] B22: breaking the plain transport pipe a clump is in drops its items (works for routed pipes only).
- [ ] B23: a clump that turns back at a break is routed on right away, and goes to a default route when there's no
      path. New items sent into a broken corridor take the new path or a default route instead of waiting in the
      retry buffer.
- [ ] Crafters re-request the items that were dropped. _(B25)_

**Save / unload**
- [ ] Save and reload with clumps in flight: the items arrive after the reload (restored clumps are pathless, wait ≥20
      ticks and are routed again).
- [ ] Unload the target chunk mid-flight: the items arrive once it loads again.
- [ ] Unload the source chunk right after a departure: nothing joins the clump that is now on disk.
- [ ] Breaking a pipe that holds restored clumps drops them and calls `itemWasLost` (crafters re-request).

**Config and performance**
- [ ] `itemClumpTransport=false` restores the old behaviour.
- [ ] Spark: server tick time with a large number of items in flight, clumps vs. `itemClumpTransport=false`.
- [ ] Busy destination with many items in transit: `countOnRoute` / in-transit bookkeeping no longer shows up
      (`InTransitTracker` replaced the O(n) queue).

## 2. Lag fixes

Details: [lag-investigation.md](lag-investigation.md), and the known issues in [pattern-crafting.md](pattern-crafting.md) §10.

- [ ] **Idle pipe cost (§4):** `LPConstants.DEBUG` now reads `logisticspipes.enableDebug`. Measure the per-pipe cost
      with debug off. It was 20–50 µs per pipe per tick; check it is now well under that, including plain transport pipes.
- [ ] Debug off really is off in a normal run: `/lp debug` and `PowerJunctionCheatPacket` are refused, no
      `TOOLTIP_INFO`.
- [ ] Debug on with `-Dlogisticspipes.enableDebug=true` still works for a debug session.
- [ ] **Power view reuse (§4):** pipes still show powered/unpowered correctly, and update when a power junction is
      added, removed, runs dry or gets reconnected.
- [ ] **Load lag (B10):** loading a large network no longer freezes the client or server (the router managers use a
      position index now). Profile it with spark on the client and the server.
- [ ] Confirm the ranking of TPS causes in lag-investigation §1 with spark (it was found by reading the code only).
- [ ] If spark still shows a per-pipe floor, check the follow-ups at the end of §4 (BC tile part, `isDirty`, ticking
      empty transport pipes, periodic corridor re-scan).

The particle fixes (§2) aren't written yet. For now, `enableParticleFX=false` in the server config turns all LP
particles and laser packets off (section 7).

## 3. Pattern crafting: fixes to verify in game _(B25)_

Details: [pattern-crafting.md](pattern-crafting.md) §8.

**Exploits (should all be closed now)**
- [ ] S1: the crafting table has no update packet any more. Slots and progress sync through MUI.
- [ ] S2: `RequestTableSetCursorPacket` sent to the server does nothing.
- [ ] S3: pattern pipe GUI can't be opened or used from a distance or without security access. Satellite renaming
      requires `canConfigurePipe`.
- [ ] S4: request table packets only act on the table the player has open; client coordinates/dimension are ignored.
- [ ] S6: `CraftingRequestDebugRequest` only works for ops (or the integrated-server owner).
- [ ] S7: pattern items can't be put into pattern editor slots. Still check the import size caps.

**Dupes / item loss**
- [ ] D1: a machine that fits only part of a set: no duplication, the rest of the set is finished before any other push.
      Same with local + satellite targets, and with several satellites.
- [ ] D1 / C9: reload while a set is partly inserted (`pendingDispatch` isn't persisted). See what happens to the rest
      of the set.
- [ ] D2: sided inventories (e.g. GT machines, furnace top/side) get items on the correct face, with and without a sneaky
      upgrade.
- [ ] D3: two different fluids into a machine with one tank: no dupe, no void.
- [ ] D4: stacks over 127 (buffers, requested, lost queue, orders, pattern entries) survive save/reload. Old saves
      without `lpCount` still load.
- [ ] D6: BLOCKING mode with a clamped short insert becomes a pending set, no dupe.

**Issues that need an in-game check** (marked **P** in §8; reproduce to confirm or close)
- [ ] D7: non-sided target re-extracts unconsumed ingredients (catalysts, containers) or player items from a shared chest.
- [ ] D8: removing or swapping a pattern with buffered ingredients voids them, or the new pattern inherits them.
- [ ] D9: same-pipe intermediate not fully accepted locally → parent craft waits forever.
- [ ] C8: BLOCKING/SMART never unlocks when the target holds permanent contents (circuits, molds, fuel).
- [ ] C9: satellite broken or unloaded during a batch blocks the whole pipe; batch lost on reload.
- [ ] C14: extra promised as a plain item isn't removed when registered as a DictResource with flags.
- [ ] C15: in OFF mode, passing items get sunk (e.g. ingot↔block patterns) and unrequested crafts happen.
- [ ] C16: reload re-requests ingredients that are still in transit → surplus in the buffer.
- [ ] C17: overflow extras double-booked by sibling EXTRA promises.
- [ ] C18: same-pipe order finished early stays staged forever.
- [ ] C21: pattern crafting table with an ambiguous recipe deadlocks on an unexpected output.
- [ ] C23: crafting pattern with a filled container (milk bucket, cells) turns into a fluid ingredient.
- [ ] C24: editing a pattern mid-craft stalls the order and holds reservations.
- [ ] C27: cancel, then a new order on the same slot → old arrivals become surplus.
- [ ] G2: request table with storage upgrades: client/server slot count mismatch (IOOBE).
- [ ] G4: MUI opens on a different pattern slot on client and server.

**Known issues from the user (B11–B14)**, still open, reproduce them:
- [ ] B11: items get stuck when requesting crafts for machines in blocking mode, even when the machine is empty (may be
      C8 or C1/C2).
- [ ] B12: items aren't dropped from the crafting pipe when it's broken.
- [ ] B13: items get teleported to satellite pipes instead of routed (satellites insert straight into their machine).
- [ ] B14: excess fluids are voided when the network has no storage for them. Expected: halt, never void.

## 4. MUI migration and legacy removal

Details: [pattern-crafting.md](pattern-crafting.md) §7 and §8.7.

**Pattern crafting pipe and handheld pattern (shared editor)**
- [ ] B25: the pipe GUI opens on a dedicated server without crashing.
- [ ] Pipe GUI: edit patterns, assign satellites, cancel, return inputs, change blocking mode. All changes reach the
      server and survive a reload. _(B25)_
- [ ] Handheld pattern GUI: edits the live held stack, the held slot is locked while open.
- [ ] NEI recipe transfer into the pipe GUI and the handheld GUI.

**Old saves**
- [ ] Worlds with in-progress orders from before the legacy removal still load (`satelliteDeliveries` entries are lost
      on purpose; check nothing else breaks).

**Other MUIs** (expected to fail until fixed)
- [ ] B15 / G11: `PipeFluidSupplierMk2Mui` edits reach the server without errors (the provider module already works).
- [ ] B16 / G12: shift-click into the pattern crafting pipe's upgrade slots (already works in the chassis).
- [ ] B26: upgrade side GUI gaps and styling.

## 5. General

- [ ] Dedicated server runs pattern crafting, the MUIs and clump transport without client-class crashes. Clump
      transport and the other MUIs are fine; pattern crafting is blocked by B25.
- [ ] B17 / G9: without NEI installed, `PatternFluidStack` doesn't throw `NoClassDefFoundError` (expected to fail
      until fixed).

## 6. Pipe rendering

The pipe body is baked into chunk geometry (solid pass only) and the TESR returns early for pipes with nothing dynamic
to draw. Looks, updates, TESR early return and Angelica all passed on 2026-09-30.

- [ ] Toggling "Use New Renderer" switches between the old and new models.
- [ ] The settings tab shows only "Use New Renderer" and "Max. Distance for Pipe Content". "Pipe render distance",
      "fallback renderer" and "VBO renderer" are gone.
- [ ] B7: a neighbour that changes shape without changing the connection state (e.g. one non-solid block replaced by
      another with different bounds) updates the stretched side.

## 7. Particles

- [ ] `enableParticleFX=false` on the server: no `ParticleFX` or `PowerPacketLaser` packets are sent. Measure mspt
      against `true`.

## 8. Routing to full or missing inventories

`ModuleItemSink` caches room in its target inventory for 20 ticks and stops advertising itself when there's no room or
no inventory. Full inventories, basic pipes with no inventory, joining networks and B18–B21 all passed on 2026-09-30.

- [ ] B24: a full AE interface stops being advertised (drawers and barrels already do).
- [ ] B2: a basic pipe with its connections set by shift+wrench only pushes into the chosen inventory (once that's
      built).
- [ ] Items in transit don't leak: after the network is idle, `countOnRoute` is back to 0 for every sink (e.g. sinks
      don't stay "full" with nothing in flight).
- [ ] Spark: mspt in the 20k-pipe world with all storage full, before vs. after.
- [ ] Spark: no noticeable routing cost from recomputing "unreachable" routes after graph changes (they used to be
      served stale).
