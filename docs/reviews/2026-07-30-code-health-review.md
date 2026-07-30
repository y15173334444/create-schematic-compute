# Code Health Review — v1.2.4.1

**Date**: 2026-07-30
**Scope**: 95 Java source files, focused on Sable compatibility layer
**Context**: Previous AI agent broke all Sable block running states due to not understanding the Sable physics engine API.

---

## Summary / 总览

| Severity | Count | Impact |
|----------|-------|--------|
| Critical | 2 | Blocks completely dead on Sable structures |
| High | 4 | Data corruption, runtime exceptions, state loss |
| Medium | 6 | Performance, maintainability, debuggability |
| Low | 2 | Edge cases |

---

## Critical

### 1. SensorBlockEntitySable: Missing `savedLevel` recovery

**File**: `compat/SensorBlockEntitySable.java:31`
**Category**: correctness
**Failure**: When placed on a Sable sub-level, Sable may set `level` to null on the BE. `SensorBlockEntitySable.sable$physicsTick()` checks `if (level == null || level.isClientSide()) return;` — without any recovery. The sensor silently skips ALL physics ticks. `rawVelX/Y/Z` and `cachedSubWorld*` are never updated, and `tick()` outputs zero attitude/velocity forever.

**Root cause**: `ControlSeatBlockEntitySable` and `RadarBlockEntitySable` both have a `savedLevel` field and recovery logic (`if (this.level == null) this.level = savedLevel`). `SensorBlockEntitySable` has neither `savedLevel` nor `onLoad()`/`setLevel()` overrides.

**Fix**: Add `savedLevel`, override `onLoad()`/`setLevel()`, add recovery at top of `sable$physicsTick()`.

### 2. Duplicated `resolveSubLevel()` across 3 Sable compat classes

**Files**: `ControlSeatBlockEntitySable.java:186-199`, `RadarBlockEntitySable.java:94-108`, `SensorBlockEntitySable.java:139-153`
**Category**: maintainability
**Failure**: Identical 14-line `resolveSubLevel()` method copied 3 times. If Sable API changes, all 3 need updating. Already divergent: `RadarBlockEntitySable` nulls `cachedSubLevel` in `onLoad()`/`setLevel()`, the other two don't — stale sub-level references could leak.

**Fix**: Extract to shared static helper method.

---

## High

### 3. `rawVelX/Y/Z` written from physics thread, read from game thread, no volatile

**Files**: `blocks/ControlSeatBlockEntity.java:46`, `blocks/SensorBlockEntity.java:20`
**Category**: threading
**Failure**: `rawVelX/Y/Z` (package-private `double`) are written in `sable$physicsTick()` (physics thread) and read in `tick()` (game thread) for acceleration calculation: `accelX = (rawVelX - prevRawVelX) / 0.05`. Without `volatile`, the game thread may read stale cached values, producing random acceleration spikes. Inconsistent — `cachedSubWorld*` fields ARE volatile.

**Fix**: Add `volatile` to `rawVelX`, `rawVelY`, `rawVelZ`.

### 4. `SensorBlockEntity.getSublevelOrientation()` uses wrong Sable classpath

**File**: `blocks/SensorBlockEntity.java:77`
**Category**: correctness
**Failure**: Uses `Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevel")` — note `.api.sublevel`. All other reflection code (`SablePacketHelper`, `RadarBlockEntity`) uses `dev.ryanhcode.sable.sublevel.SubLevel`. If Sable removed the `api.sublevel` package alias, this cast fails silently and `getSublevelOrientation()` always returns null.

**Fix**: Align classpath with other usages.

### 5. `initialSubYaw` not persisted to NBT

**File**: `compat/ControlSeatBlockEntitySable.java:27`
**Category**: state-loss
**Failure**: `initialSubYaw` is set on first `sable$physicsTick` to compute relative rotation. On chunk unload/reload, it resets to NaN. If the sub-level has rotated between save and load, `ATTITUDE_YAW` and `FORWARD_YAW` outputs jump by the accumulated rotation delta.

**Fix**: Save `initialSubYaw` in NBT via `saveTypeSpecific`/`loadTypeSpecific` hooks.

### 6. `ControlSeatEntity` hard-imports `dev.ryanhcode.sable.Sable`

**File**: `entity/ControlSeatEntity.java:3`
**Category**: robustness
**Failure**: Direct `import dev.ryanhcode.sable.Sable` without reflection guard. If Sable jar is absent from classpath at load time, `NoClassDefFoundError` crashes the entity registration. (Mitigated by NeoForge's lazy class loading, but fragile.)

**Fix**: Guard with reflection or move Sable-specific logic to a compat class.

---

## Medium

### 7. Three independent Sable reflection initialization paths

**Files**: `network/SablePacketHelper.java:30-71`, `blocks/SensorBlockEntity.java:71-81`, `blocks/RadarBlockEntity.java:351-367`
**Category**: maintainability
**Failure**: Three separate reflection init methods, each caching different method handles. If Sable changes internal API, 3 locations need updating. Already divergent: `SensorBlockEntity` uses `api.sublevel.SubLevel`, others use `sublevel.SubLevel`.

**Fix**: Consolidate into a single `SableReflection` utility class.

### 8. `SablePacketHelper.findSubLevel()` O(n) scan

**File**: `network/SablePacketHelper.java:73-89`
**Category**: performance
**Failure**: Iterates ALL sub-levels, calling `getBlockEntity(pos)` (triggers chunk lookups) for each. In large Sable structures with many sub-levels, this is expensive and called during radar scanning.

**Fix**: Use the chunk-based plot lookup (same as `resolveSubLevel()`) instead of iterating all sub-levels.

### 9. Widespread silent exception swallowing

**Files**: All 4 compat classes, plus `SablePacketHelper.java`
**Category**: debuggability
**Failure**: 30+ `catch (Exception ignored) {}` blocks silently discard errors. Many are on critical paths (world position computation, attitude calculation). When Sable compatibility breaks, there's zero diagnostic output — the block just silently stops working.

**Fix**: Add `LOGGER.warn(...)` to critical-path catch blocks. Distinguish between "Sable not installed" (expected) and "Sable installed but operation failed" (unexpected).

### 10. `SablePacketHelper.subTransformCache` uses `System.identityHashCode` as key

**File**: `network/SablePacketHelper.java:206-208`
**Category**: correctness
**Failure**: `System.identityHashCode()` is not guaranteed unique — hash collisions are possible (though rare). If a sub-level object is GC'd and its identity hash reused, the cache returns stale transform data.

**Fix**: Use a different stable identifier (e.g., sub-level's origin position as composite key) or use `WeakHashMap` pattern.

### 11. `RadarBlockEntity` debug logs evaluate arguments every tick

**File**: `blocks/RadarBlockEntity.java:258-293`
**Category**: performance
**Failure**: Multiple `LOGGER.debug(...)` calls with concatenated strings — arguments are evaluated even when debug level is disabled. On Sable structures, this runs every tick (20Hz).

**Fix**: Guard with `if (LOGGER.isDebugEnabled())` or use SLF4J parameterized messages.

### 12. `RadarBlockEntity.tryBootstrapSableCache()` duplicates Sable reflection

**File**: `blocks/RadarBlockEntity.java:136-199`
**Category**: maintainability
**Failure**: The bootstrap method re-implements sub-level pose extraction using raw reflection (bounding box check, position/orientation extraction) that partially duplicates `SablePacketHelper.getOrComputeSubTransform()`. If either changes, the other stays stale.

**Fix**: Use shared reflection utility.

---

## Low

### 13. `RuntimeState` loaded during editor-open without protection

**File**: `blocks/SyncedGraphBlockEntity.java:436-439`
**Category**: state-management
**Failure**: When graph editor is open, `loadAdditional()` skips `graph = NodeGraph.load(...)` to avoid value bounce-back, but `runtimeState` is still overwritten — pidState values being actively displayed in the editor can be overwritten by server sync.

**Fix**: Guard `runtimeState` loading with the same `editorOpen` check, or merge instead of replace.

### 14. `cachedSubLevel` uses `volatile` without atomicity guarantee

**Files**: All 3 Sable compat subclasses
**Category**: threading
**Failure**: `volatile SubLevel cachedSubLevel` is checked-then-set in `resolveSubLevel()`. Two concurrent callers (e.g., `sable$getLoadingDependencies()` and `sable$getConnectionDependencies()`) could both see null and both compute — harmless duplicate work, but avoidable with `AtomicReference`.

**Fix**: Use `AtomicReference<SubLevel>` or accept the benign race.

---

## Architecture Notes / 架构备注

### Sable compat layer design

The current design uses:
- Factory methods (`ControlSeatBlockEntity::create`, etc.) that check `ModList.get().isLoaded("sable")` and reflectively instantiate `*Sable` subclasses
- Subclasses extend the base BE and implement `BlockEntitySubLevelActor`
- `SablePacketHelper` for Sable-aware network operations (scan, range check)

**Strengths**: Clean separation — no Sable code in base classes (mostly). The `::create` factory pattern avoids classloading issues.

**Weaknesses**: The base classes still contain Sable-specific code paths:
- `ControlSeatBlockEntity.tick()` checks `inputMode == 1` (seems unrelated to Sable)
- `SensorBlockEntity.getSublevelOrientation()` exists in base class with Sable reflection
- `RadarBlockEntity.tryBootstrapSableCache()` duplicates reflection logic

For v1.3, consider moving all Sable-specific code into the compat subclasses.

### Threading model

Two threads touch BE state:
1. **Game thread** (20Hz): `tick()`, NBT save/load, network packet handling
2. **Physics thread** (Sable): `sable$physicsTick()`

Current approach uses `volatile` on some fields but not all. The acceleration calculation in `tick()` reads `rawVel*` twice (once directly, once via `prevRawVel`) — between those reads, `sable$physicsTick` could update the value on the physics thread.

**Recommendation**: For v1.3, consider a double-buffered approach: `sable$physicsTick` writes to a "pending" struct, `tick()` atomically swaps it. Eliminates all cross-thread coherence concerns.

---

## Fix Plan / 修复计划

All fixes targeted for v1.2.4.1 patch:

| # | Fix | Priority | Effort |
|---|-----|----------|--------|
| 1 | SensorBlockEntitySable savedLevel | Critical | Small |
| 2 | Extract resolveSubLevel() | Critical | Small |
| 3 | volatile rawVel* | High | Trivial |
| 4 | Fix SensorBlockEntity Sable classpath | High | Trivial |
| 5 | Persist initialSubYaw to NBT | High | Medium |
| 6 | Guard ControlSeatEntity Sable import | High | Small |
| 7 | Unified Sable reflection | Medium | Medium |
| 8 | findSubLevel() performance | Medium | Small |
| 9 | Logging in exception handlers | Medium | Small |
| 10 | identityHashCode cache key | Medium | Small |
| 11 | Guard debug logs | Medium | Trivial |
| 12 | Remove radar bootstrap reflection duplicate | Medium | Medium |
| 13 | RuntimeState editor-open protection | Low | Small |
| 14 | cachedSubLevel AtomicReference | Low | Trivial |

🤖 Generated with [Claude Code](https://claude.com/claude-code)
