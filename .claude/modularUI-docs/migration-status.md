# Module GUI migration status (ModularUI2)

Determined from actual GUI wiring, not filenames: a module is "migrated" only if it
implements `logisticspipes.api.IMUICompatibleModule`. Both `ChassisGui.java` (in-pipe)
and `ItemModule.java` (in-hand) check `module instanceof IMUICompatibleModule` — if
true they call the module's `getPipeGui()`/`getHandGui()` (new `*MuiDynamic` class in
`gui/modularUI/dynamicModules/`); if false they fall back to the legacy
`getPipeGuiProvider()`/`getInHandGuiProvider()` path, which opens an old
`gui/modules/Gui*.java` class (extends `ModuleBaseGui`).

All the legacy `Gui*.java` classes listed below are still live code paths, not dead
leftovers — each is actively constructed from its module's provider methods.

## Status

| Module | Migrated | GUI in use                                                                                                                                                                                                 |
|---|---|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ModuleItemSink` | ✅ | `dynamicModules/ModuleItemSinkMuiDynamic.java`                                                                                                                                                             |
| `ModuleProvider` | ✅ | `dynamicModules/ModuleProviderMuiDynamic.java`                                                                                                                                                             |
| `ModuleActiveSupplier` | ✅ | `dynamicModules/ModuleActiveSupplierMuiDynamic.java`                                                                                                                                                       |
| `ModulePassiveSupplier` | ✅ | `dynamicModules/ModulePassiveSupplierMuiDynamic.java`                                                                                                                                                      |
| `ModuleElectricManager` | ✅ | `dynamicModules/ModuleElectricManagerMuiDynamic.java`                                                                                                                                                      |
| `ModuleTerminus` | ✅ | `dynamicModules/ModuleTerminusMuiDynamic.java`                                                                                                                                                             |
| `ModuleEnchantmentSinkMK2` | ✅ | `dynamicModules/ModuleEnchantmentSinkMK2MuiDynamic.java`                                                                                                                                                   |
| `ModuleApiaristAnalyser` (Bee Analyzer) | ✅ | `dynamicModules/ModuleBeeAnalyzerMuiDynamic.java`                                                                                                                                                          |
| `ModuleApiaristSink` | ❌ | `gui/modules/GuiApiaristSink.java`                                                                                                                                                                         |
| `ModuleOreDictItemSink` | ❌ | `gui/modules/GuiOreDictItemSink.java`                                                                                                                                                                      |
| `ModuleModBasedItemSink` ("String Based Item Sink") | ✅ | `dynamicModules/ModuleModBasedItemSinkMuiDynamic.java`                                                                                                                                                     |
| `ModuleCreativeTabBasedItemSink` | ❌ | same `GuiStringBasedItemSink` provider                                                                                                                                                                     |
| `ModuleTypeFilterItemSink` | ❌ | same `GuiStringBasedItemSink` provider                                                                                                                                                                     |
| `ModuleThaumicAspectSink` | ❌ | `gui/modules/GuiThaumicAspectSink.java`                                                                                                                                                                    |
| `ModuleAdvancedExtractor` (+ MK2/MK3 subclasses) | ❌ | `gui/modules/GuiAdvancedExtractor.java`                                                                                                                                                                    |
| `ModuleExtractor` (+ Mk2/Mk3 subclasses) | ❌ | `gui/modules/GuiExtractor.java`                                                                                                                                                                            |
| `ModuleCCBasedQuickSort` | ❌ | `gui/modules/GuiCCBasedQuickSort.java`                                                                                                                                                                     |
| `ModuleFluidSupplier` | ❌ | `gui/modules/GuiFluidSupplier.java`                                                                                                                                                                        |
| `ModuleCrafter` (Crafting) | ⚠️ n/a | `gui/GuiCraftingPipe.java` — depricated, will be replaced by Pattern Crafting pipe, a `ModuleCraftingMuiDynamic` class exists in `dynamicModules/` but has zero callers; orphaned migration stub           |
| `LogisticsSimpleFilterModule` (abstract base) | ⚠️ n/a | `gui/modules/GuiSimpleFilter.java` — relevant only if a future concrete subclass fails to override it; all current subclasses (Terminus, PassiveSupplier, EnchantmentSinkMK2) do override and are migrated |

_Last verified: 2026-08-06._

## Effect of the rework design (2026-09-29)

Per [docs/rework-design-decisions.md](../../docs/rework-design-decisions.md), every GUI moves to ModularUI, but some of the
modules above are deprecated rather than migrated:
- Deprecated modules keep their legacy GUI and get no MUI. They and their GUIs are deleted one major pack version after
  the rework ships (rework in 2.10 → deleted in 2.11), see "Compatibility with old bases" in
  `docs/rework-design-decisions.md`. Don't delete those legacy GUIs before then.
- `ModuleCrafter` is deprecated, because crafting modules are replaced by the pattern crafting pipe.
- Modules that only duplicate another one with more features become upgrades. The design doc names the extractors and
  item sinks as examples.
  - Likely candidates: `ModuleExtractor` Mk2/Mk3 and `ModuleAdvancedExtractor` MK2/MK3.
  - Possibly also the item sink variants such as `ModuleOreDictItemSink`, since the OreDict filter becomes an upgrade.
  - The final list is settled under "Rethink modules" in `docs/roadmap.md` 2c. Wait for it before migrating any of these GUIs.
- Fluid modules (new) for the fluid chassis will need MUI GUIs from the start.
