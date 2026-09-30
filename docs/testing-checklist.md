# Testing checklist

Everything in `docs/` that is written but still needs testing, gathered in one place (2026-09-29). Each item links to the
doc that has the details. Issue IDs (D1, C8, ...) are from [pattern-crafting.md](pattern-crafting.md) §8.

Use spark for every performance item and write down before/after numbers. Scale target: ~50k pipes, with routing well
under one 50 ms tick.

---

## 1. Item transport rewrite (clumps)

Phase 1 compiles and passes its unit tests (`TravelPathTest`, `ClumpBreakTest`), but **it has never been run in game**.
Details: [item-transport-rewrite.md](item-transport-rewrite.md).

**Delivery**
- [ ] Items reach chests along a line of routed pipes.
- [ ] Items reach chests along a corridor of basic transport pipes that has branches.
- [ ] Items follow the corridor the router picked at branches of plain pipes instead of random-walking. Check that
      this is acceptable in real builds.
- [ ] Firewall filters, one-way pipes, ISC and system entrances still work (they fall back to the old simulation or
      route each item).
- [ ] BC/TD pipes inside a corridor don't break anything (their support is being dropped, so they only need to not
      break).
- [ ] Speed upgrades still shorten the trip and power is still used (current code; later, transport controllers set the
      speed).
- [ ] Gather window: items sent a few ticks apart from the same junction to the same destination join one clump, and
      the late joiner arriving up to `itemClumpGatherTicks` early causes no problems.

**Client animation**
- [ ] The animation follows the corridor pipe by pipe.
- [ ] Up to 3 stacks are drawn per clump.
- [ ] No flicker or snapping at junctions (the next hop packet has to arrive about when the client copy reaches the
      junction).
- [ ] A player who walks into range while a clump is in flight: note what they see (today only the next hop). Open
      question whether that's good enough.
- [ ] Packets only go to players in the same dimension within 64 blocks of either end of the corridor.

**Breaking pipes under a clump in flight**
- [ ] Pipe behind the clump: the clump still arrives.
- [ ] The pipe the clump is in right now: its items drop there.
- [ ] Pipe ahead of the clump (or the target junction): the clump turns back to its source junction and is routed
      around the gap.
- [ ] Source junction gone or unloaded while turning back: items drop where the clump is.
- [ ] A clump that is already turning back and meets a second break: drops its items, doesn't bounce again.
- [ ] While the corridor is flagged broken, new items wait in the pipe's retry buffer instead of teleporting through the
      gap, and resume once the corridor is re-scanned (or after 100 ticks).
- [ ] Crafters re-request the items that were dropped.

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
- [ ] **Load lag (§10 known issues):** loading a large network no longer freezes the client or server (the router
      managers use a position index now). Profile it with spark on the client and the server.
- [ ] Confirm the ranking of TPS causes in lag-investigation §1 with spark (it was found by reading the code only).
- [ ] If spark still shows a per-pipe floor, check the follow-ups at the end of §4 (BC tile part, `isDirty`, ticking
      empty transport pipes, periodic corridor re-scan).

The pipe rendering fixes (§3) are written now, see section 8. The particle fixes (§2) aren't. For now,
`enableParticleFX=false` in the server config turns all LP particles and laser packets off (section 8).

## 3. Pattern crafting: fixes to verify in game

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

## 4. Pattern crafting: issues that need an in-game check

These are marked **P** (plausible) in pattern-crafting.md §8. Reproduce them to confirm or close them.

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

## 5. Pattern crafting: known issues from the user (§10)

Reproduce these; they're still open.

- [ ] Items get stuck when requesting crafts for machines in blocking mode, even when the machine is empty (may be C8
      or C1/C2).
- [ ] Items aren't dropped from the crafting pipe when it's broken.
- [ ] Items get teleported to satellite pipes instead of routed (satellites insert straight into their machine).
- [ ] Excess fluids are voided when the network has no storage for them. Expected: halt, never void.

## 6. MUI migration and legacy removal

Details: [pattern-crafting.md](pattern-crafting.md) §7 and §8.7.

**Pattern crafting pipe and handheld pattern (shared editor)**
- [ ] Pipe GUI: edit patterns, assign satellites, cancel, return inputs, change blocking mode. All changes reach the
      server and survive a reload.
- [ ] Handheld pattern GUI: edits the live held stack, the held slot is locked while open.
- [ ] NEI recipe transfer into the pipe GUI and the handheld GUI.
- [ ] Legacy wrench on the pattern pipe and pattern satellites opens the MUI (after the security check).

**Pattern crafting table**
- [ ] Slots and progress sync; shift-click (MUI merges) marks the table dirty and triggers a recipe check.
- [ ] The 4 upgrade slots take speed upgrades; old saves with 3 slots load into the first 3.

**Satellites**
- [ ] `PipeSatelliteMui`: id, next free id, and name for pattern satellites. A duplicate name gets a suffix that syncs
      back to the client.
- [ ] Plain LP satellites still open `GuiSatellitePipe` with the legacy wrench.
- [ ] Memory chip: FAVORITES and APPLY_LAST_TO_RECIPE modes; clicking a satellite with a renamed chip renames it.

**Old saves**
- [ ] Worlds with in-progress orders from before the legacy removal still load (`satelliteDeliveries` entries are lost
      on purpose; check nothing else breaks).

**Other MUIs**
- [ ] G11: `PipeFluidSupplierMk2Mui` and `ModuleProviderMuiDynamic` edits reach the server (no `allowC2S()` yet, so
      expected to fail until fixed).
- [ ] G12: shift-click into pipe upgrade slots (`ItemIdentifierInventory`) — expected to fail until fixed.
- [ ] Upgrade side GUI: styling and gap sizes (§10 minors).

## 7. General

- [ ] Dedicated server starts and runs pattern crafting, the MUIs and clump transport without client-class crashes
      (§8.8 found no crash path by reading the code).
- [ ] Without NEI installed: `PatternFluidStack` doesn't throw `NoClassDefFoundError` (G9, expected to fail until
      fixed).

## 8. Pipe rendering (2026-09-30)

The pipe body is baked into chunk geometry in the world renderer (solid pass only) instead of being drawn by the TESR
every frame. The TESR returns early for pipes with nothing dynamic to draw. Tested so far: stable FPS with thousands of
pipes in view.

**Looks the same as before**
- [ ] Pipe shapes for every connection count: straight, corners, T-junctions, crosses, supports, and mounts next to
      solid blocks.
- [ ] Routed/status side textures, the inactive (unpowered) corners, and the per-pipe center icon.
- [ ] Fluid pipes: inner glass plates and side texture plates.
- [ ] Sides stretched to a neighbour's block bounds (e.g. a pipe next to a slab).
- [ ] Lighting matches: one brightness value per block, same as the old TESR.
- [ ] Request table still renders (it was already baked).
- [ ] Inventory and held pipe items are unchanged (`LogisticsNewPipeItemRenderer` wasn't touched).

**Updates**
- [ ] Status textures change when routing or power changes (chunk rebuild from `afterStateUpdated`).
- [ ] Placing or removing a neighbour block updates mounts and connections.
- [ ] Toggling "Use New Renderer" switches between the old and new models.
- [ ] With Angelica: no crashes or corruption (CCL's render state isn't thread-safe; it should run on the main thread).

**TESR early return**
- [ ] Items in transit, buffered items, fluids and pipe signs still render.
- [ ] BuildCraft wires and gates with dynamic parts still render.

**Settings**
- [ ] The settings tab shows only "Use New Renderer" and "Max. Distance for Pipe Content". "Pipe render distance",
      "fallback renderer" and "VBO renderer" are gone.
- [ ] Old player config files still load. Client and server both need the new build (the config packet changed).

**Particles**
- [ ] `enableParticleFX=false` on the server: no `ParticleFX` or `PowerPacketLaser` packets are sent. Measure mspt
      against `true`.

## 9. Routing to full or missing inventories (2026-09-30)

`ModuleItemSink` (basic pipes and chassis item sinks) caches room in its target inventory for 20 ticks. It stops
advertising itself when there's no room or no inventory, and marks an item as full when an insert fails. Basic pipes
now reroute arriving items they can't take. Tested so far: 20k pipes with millions of items ran at 60 FPS but 1000+
mspt before these changes.

**Full inventories**
- [ ] A full chest behind a basic pipe or chassis item sink stops getting items. They go to other sinks with room.
- [ ] Items that bounce off a full chest go to another router, not back into the same chest.
- [ ] After a chest is emptied, its sink starts accepting again within about 40 ticks.
- [ ] A full default route still takes top-ups for stacks that aren't full.
- [ ] A filtered sink stops advertising only the filter items it has no room for.
- [ ] Special inventories (barrels, drawers, AE, DSU): a default route keeps working, and a full one rejects items.
- [ ] Basic pipe touching several inventories: no item loops (only the first inventory it finds is checked for room).
- [ ] Spark: mspt in the 20k-pipe world with all storage full, before vs. after.

**Basic pipe with no inventory**
- [ ] It isn't chosen as a destination, including as a default route.
- [ ] It becomes a destination again within about 40 ticks after a chest is attached.
- [ ] Items already in flight to it are rerouted to another sink with room.
- [ ] With no destination anywhere, they wait in the pipe's buffer, retry every 2 s, and drop after about 10 s.
- [ ] Losing network power while items are in flight: they reroute and eventually drop instead of being delivered
      (same as chassis). Check that this is acceptable.

**Joining networks (B18, B19 in [bug-list.md](bug-list.md))**
- [ ] Join a powered and an unpowered island with one pipe: every pipe in the unpowered island shows as powered and
      routes. Try it several times, and with larger islands.
- [ ] B19 repro: several small chests on basic pipes. Once the first chest fills, the rest of the clump goes to the
      next chest instead of staying in the pipe buffer.
- [ ] Spark: no noticeable routing cost from recomputing "unreachable" routes after graph changes (they used to be
      served stale).
- [ ] B20: extractor + small filtered chests + a default route. Items that bounce off a full chest go to another
      filtered chest with room, not the default route. The extractor sends partial stacks when a chest has less
      than a stack of room left.
- [ ] Items in transit don't leak: after the network is idle, `countOnRoute` is back to 0 for every sink (e.g. sinks
      don't stay "full" with nothing in flight).
- [ ] B21: 16x16 grid of basic pipes with chests, extract into it until chests fill. Bounced items find another chest
      instead of dropping.
- [ ] B21 fallback: with every filtered chest full, items that run out of retries go to a default route with room.
      They only drop when no default route has room either.
