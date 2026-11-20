# UltraCore

Modular optimization suite for **Paper / Purpur**, built for a 1 GB (1024 MB) server. Java 21, Maven, Paper API (no NMS).
14 independent modules implement the 20 requested optimizations; an adaptive controller tightens or relaxes them
based on TPS, MSPT, heap, GC overhead, chunks and entities.

> **Build/test status (please read).** The project was written in an environment without Maven, without access to the
> Paper repository and without a Minecraft server, so it has **not been compiled against the Paper API or run**.
> The sources were only checked to *parse* with javac. Expect to fix small API/signature mismatches on the first
> `mvn package` (set `paper.version` in `pom.xml` to your exact version), then run the benchmark section below before
> trusting it in production. Nothing here is a placeholder, but nothing has been measured either, so no performance
> numbers are claimed.

## Install

1. Install JDK 21 and Maven 3.9+.
2. `mvn clean package` → `target/UltraCore-1.0.0.jar`.
3. Drop the jar into `plugins/`, start the server once, edit `plugins/UltraCore/config.yml`, run `/ultracore reload`.

Targets Paper 1.21.x (Purpur works because it is a Paper fork). Every Paper-only call is feature-detected at startup;
if one is missing the affected module logs a message and disables itself instead of crashing the server.

### Recommended launch flags for 1 GB of RAM
Leave headroom for metaspace, threads and the OS: use ~768 MB heap, not 1024.

    java -Xms768M -Xmx768M -XX:+UseG1GC -XX:MaxGCPauseMillis=150 -XX:+ParallelRefProcEnabled \
         -XX:G1HeapRegionSize=4M -XX:+UseStringDeduplication -jar paper.jar nogui

Do not add `-XX:+DisableExplicitGC` if you want the idle-GC feature.

## Commands and permissions
| Command | Permission | What it does |
|---|---|---|
| `/ultracore` `status` | `ultracore.status` | TPS, MSPT, RAM used/committed/max, loaded chunks, entities, generation queue, pressure level, active modules |
| `reload` | `ultracore.reload` | Restores every temporary change, reloads config, restarts modules |
| `optimize` | `ultracore.optimize` | One-shot pass of every active module |
| `chunks` | `ultracore.chunks` | Per-world chunk/tile-entity counts, distances, load/unload stats |
| `memory` | `ultracore.memory` | Heap, old-gen occupancy, GC overhead, GC totals |
| `entities` | `ultracore.entities` | Per-world entity counts and top types, entity/mob/item stats |
| `generation [status\|pregen <chunks> [world]\|stop\|pause\|resume]` | `ultracore.generation` | Generation queue and pregeneration |

All need `ultracore.command` as well (default: op). `ultracore.bypass.blockentity` exempts a player from placement limits.

## What implements each of the 20 items
| # | Item | Module | Implementation |
|---|---|---|---|
| 1 | Minimize RAM | MemoryOptimizer + whole code base | One shared chunk scanner and one shared player tracker instead of per-module tasks; primitive `long` keyed maps (`LongLongMap`); reusable buffers; no streams in hot paths; per-cycle snapshots released immediately |
| 2 | Minimize CPU | CpuOptimizer | AFK players get a reduced simulation distance, restored on movement |
| 3 | GC | GarbageCollectionOptimizer | Measures GC overhead (feeds the adaptive level), warns, idle-only `System.gc()`, JVM flag audit |
| 4 | Reduce allocation | (code style) + GC module | Allocation-free hot paths (packed keys, reused buffers); see MemoryOptimizer javadoc |
| 5 | Prevent leaks | MemoryOptimizer | Every map is cleared on quit/world unload/disable; periodic safety-net prune |
| 6 | Entity ticking | EntityOptimizer + MobOptimizer | Fewer entities (caps, far despawn) and paused AI for far mobs. Paper has no API to skip an entity's tick directly; use Paper's own `entity-activation-range` (spigot.yml) for that |
| 7 | Mob AI | MobOptimizer | `Mob#setAware(false)` beyond a radius, restored when players approach; protected mobs excluded |
| 8 | Spawning | MobOptimizer | Natural spawns cancelled above per-chunk caps or too far from players; shrinks under pressure |
| 9 | Despawning | EntityOptimizer | Despawnable mobs far from all players removed sooner |
| 10 | Item merging | ItemOptimizer | Radius merge, vanilla-like age/pickup-delay rules, bounded scan |
| 11 | Item cleanup | ItemOptimizer | Per-chunk limit (oldest first), optional max age, protected materials/named/enchanted/shulkers |
| 12 | Hoppers | HopperOptimizer | Pressure-adaptive transfer interval; vanilla-identical at normal pressure; nothing lost |
| 13 | Redstone | RedstoneOptimizer | Hotspot detection (never changes redstone by default); optional SUPPRESS brake |
| 14 | Block entities | BlockEntityOptimizer | Per-chunk placement limits and hotspot log |
| 15 | Random ticks | RandomTickOptimizer | Lowers `randomTickSpeed` under pressure, restores after |
| 16 | Chunk loading | ChunkLoadingOptimizer | Adaptive view/simulation distance, fast-travel throttle |
| 17 | Chunk unloading | ChunkUnloadingOptimizer | `unloadChunkRequest` for idle chunks with grace period; honours tickets/force-load |
| 18 | Plugin-caused loads | ChunkLoadingOptimizer | UltraCore never loads chunks; orphan-load counter and optional caller attribution |
| 19 | Generation | ChunkGenerationOptimizer | Queue with priorities, dedupe, limits, auto-pause, border check, cleanup on quit/unload, throughput stats, pregen |
| 20 | Saving | ChunkSavingOptimizer | Observes saves; optional staggered managed autosave with fail-safes |

## Honest limitations (design decisions you should know)
* **Vanilla/Paper-driven generation** (a player simply walking) cannot be rerouted through any supported Paper API. The
  generation queue governs requests made through UltraCore (pregen, prefetch). Natural generation is shaped indirectly by
  the view-distance and fast-travel controls.
* **Hoppers and redstone**: Paper's own settings (`hopper.*`, `misc.redstone-implementation: ALTERNATE_CURRENT`) give larger
  static gains. UltraCore's modules add load shedding on top and deliberately don't duplicate them.
* **Chunk saving**: there is no per-chunk save API, so the safe levers are world-level. The vanilla settings
  `max-auto-save-chunks-per-tick` and `delay-chunk-unloads-by` in `config/paper-world-defaults.yml` are the main tools and
  are not duplicated.
* **Managed autosave (off by default).** It switches Bukkit autosave off per world and performs `world.save()` itself.
  Chunks still save on unload, saves are fail-safed, and there is a final save on disable/reload, but a hard JVM crash between
  saves loses up to `interval-seconds` of changes (the same exposure as a long vanilla autosave interval). Test on a copy of
  your world and keep backups before enabling.
* **Behavior changes** are limited to what each module documents: mob/animal caps remove surplus mobs, item cap removes
  surplus items, pressure lowers random tick speed and distances. Every module can be disabled individually, and
  every temporary change is reverted on reload and shutdown.
* Pressure inputs: TPS uses Paper's 1-minute average (stable, slightly slow); heap uses old-generation occupancy.

## Configuration
`config.yml` is fully commented. Each module has `enabled`, limits and intervals; thresholds for the adaptive system are under
`adaptive`. Start with defaults on a 1 GB server, then tune with `/ultracore status` while players are online.

## Benchmarking instructions
Install [spark](https://spark.lucko.me) next to UltraCore. Use the same world copy, the same JVM flags, and restart between runs.

1. **Baseline**: set every module to `enabled: false`, restart, run each scenario for 5 minutes.
2. **Per module**: enable one module, `/ultracore reload`, repeat the scenarios, record the deltas.
3. **All modules**: enable everything and repeat.

Scenarios: idle (0 players); 1, 5, 10 players (bots such as mineflayer or real players walking apart); heavy entities
(`/summon` 300+ mobs in a few chunks, drop 2000 items in a pen); heavy redstone (a few fast clocks); several players
exploring/flying with elytra over unexplored terrain; a large generation queue (`/ultracore generation pregen 100`);
low memory (start with `-Xmx512M`).

Record, per run: `/spark tps` (TPS/MSPT), `/spark healthreport` (CPU, heap, GC), `/spark gc` (GC pauses),
`/ultracore status`, and for generation `/ultracore generation` (avg ms per chunk, chunks per minute). Template:

| Scenario | Modules | TPS | MSPT p95 | Heap used (MB) | CPU % | GC time % | Chunks/min generated |
|---|---|---|---|---|---|---|---|

An optimization is worth keeping only if its row beats the baseline in the scenario it targets and does not regress the others.
