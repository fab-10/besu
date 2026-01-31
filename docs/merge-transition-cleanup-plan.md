# Merge Transition Code Cleanup Plan

**Created**: 2026-01-31
**Status**: Planning Phase - On Hold
**Related Issue**: https://github.com/hyperledger/besu/issues/2897

## Executive Summary

The Merge (Ethereum's transition from PoW to PoS) completed in September 2022. All major public networks (mainnet, sepolia, holesky) have been running exclusively on Proof of Stake for years. This document outlines a plan to remove the transition-specific code that is no longer needed, simplifying the codebase by approximately 2,000 lines.

## Table of Contents

1. [Background](#background)
2. [Current State Analysis](#current-state-analysis)
3. [Components to Remove](#components-to-remove)
4. [Components to Keep](#components-to-keep)
5. [Implementation Plan](#implementation-plan)
6. [Testing Strategy](#testing-strategy)
7. [How to Resume This Work](#how-to-resume-this-work)

---

## Background

### What is the Merge Transition Code?

The merge transition code handles the runtime switch from Proof of Work to Proof of Stake when a network reaches its configured Terminal Total Difficulty (TTD). It includes:

- Dual protocol schedules (pre-merge PoW validation + post-merge PoS validation)
- Mining coordinator orchestration (PoW mining + PoS block proposal)
- Consensus context switching
- Peer selection optimization during transition
- Special backward sync handling for reorgs near TTD

### Why Remove It?

1. **All networks have transitioned**: Mainnet (Sept 2022), Sepolia, and Holesky are all post-merge
2. **Code complexity**: Transition code adds ~2,000 lines and cognitive overhead
3. **TODO from 2022**: `BesuController.java:361` has a TODO to switch to vanilla MergeBesuControllerBuilder
4. **Maintenance burden**: Every change must consider dual-mode behavior
5. **Working alternative exists**: Checkpoint sync already bypasses transition code

### Key Discovery

The codebase **already has a working post-merge-only path**:
- When using `SyncMode.CHECKPOINT` with a PoS checkpoint block, Besu uses `MergeBesuControllerBuilder` directly
- The existing `MergeProtocolSchedule` can handle historical PoW blocks by checking `block.difficulty > 0`
- This means full historical sync capability can be maintained WITHOUT runtime transition detection

---

## Current State Analysis

### Network Status (All Post-Merge)

| Network | TTD | Transition Date | Status |
|---------|-----|-----------------|--------|
| Mainnet | 58750000000000000000000 | Sept 15, 2022 | Finalized |
| Sepolia | 17000000000000000 | June 20, 2022 | Finalized |
| Holesky | 0 | Genesis | PoS from genesis |

### Code Entry Point

Located in `/home/fdifabio/repo/besu/app/src/main/java/org/hyperledger/besu/controller/BesuController.java:358-369`:

```java
if (configOptions.getTerminalTotalDifficulty().isPresent()) {
  if (syncMode == SyncMode.CHECKPOINT && isCheckpointPoSBlock(configOptions)) {
    // Already bypasses transition code
    return new MergeBesuControllerBuilder().genesisConfig(genesisConfig);
  } else {
    // TODO: Should be changed to vanilla MergeBesuControllerBuilder after successful transition
    // Issue: https://github.com/hyperledger/besu/issues/2897
    return new TransitionBesuControllerBuilder(builder, new MergeBesuControllerBuilder())
        .genesisConfig(genesisConfig);
  }
}
```

### Transition* Classes Location

All located in `/home/fdifabio/repo/besu/consensus/merge/src/main/java/org/hyperledger/besu/consensus/merge/`:

1. **TransitionUtils.java** - Generic switching utility
2. **TransitionContext.java** - Consensus context wrapper
3. **TransitionProtocolSchedule.java** - Protocol schedule router
4. **TransitionBackwardSyncContext.java** - Backward sync handler
5. **TransitionBestPeerComparator.java** - Peer selection during transition
6. **TransitionCoordinator.java** (in blockcreation/) - Mining coordinator orchestrator

Plus: **TransitionBesuControllerBuilder.java** in `/home/fdifabio/repo/besu/app/src/main/java/org/hyperledger/besu/controller/`

---

## Components to Remove

### Phase 1: Delete Transition Classes (~1,500 lines)

#### Primary Classes
```
/app/src/main/java/org/hyperledger/besu/controller/
├── TransitionBesuControllerBuilder.java [DELETE]

/consensus/merge/src/main/java/org/hyperledger/besu/consensus/merge/
├── TransitionUtils.java [DELETE]
├── TransitionContext.java [DELETE]
├── TransitionProtocolSchedule.java [DELETE]
├── TransitionBackwardSyncContext.java [DELETE]
├── TransitionBestPeerComparator.java [DELETE]
└── blockcreation/
    └── TransitionCoordinator.java [DELETE]
```

#### Test Classes
```
/consensus/merge/src/test/java/org/hyperledger/besu/consensus/merge/
├── TransitionProtocolScheduleTest.java [DELETE]
├── TransitionBestPeerComparatorTest.java [DELETE]
└── TransitionUtilsTest.java [DELETE]

/app/src/test/java/org/hyperledger/besu/controller/
└── TransitionControllerBuilderTest.java [DELETE]
```

### Phase 2: Simplify Validation Rules (~200 lines)

These classes currently check `shouldUsePostMergeRules()` to conditionally apply. Make them always apply:

```
/consensus/merge/src/main/java/org/hyperledger/besu/consensus/merge/headervalidationrules/
├── MergeConsensusRule.java
│   └── Remove shouldUsePostMergeRules() method
│   └── Make rules always active
├── IncrementalTimestampRule.java
│   └── Always enforce incremental timestamps
├── NoDifficultyRule.java
│   └── Always enforce difficulty = 0
└── NoNonceRule.java
    └── Always enforce nonce = 0
```

### Phase 3: Update Controller Builder (~50 lines)

```
/app/src/main/java/org/hyperledger/besu/controller/BesuController.java:358-369

BEFORE:
if (configOptions.getTerminalTotalDifficulty().isPresent()) {
  if (syncMode == SyncMode.CHECKPOINT && isCheckpointPoSBlock(configOptions)) {
    return new MergeBesuControllerBuilder().genesisConfig(genesisConfig);
  } else {
    return new TransitionBesuControllerBuilder(builder, new MergeBesuControllerBuilder())
        .genesisConfig(genesisConfig);
  }
}

AFTER:
if (configOptions.getTerminalTotalDifficulty().isPresent()) {
  return new MergeBesuControllerBuilder().genesisConfig(genesisConfig);
}
```

### Phase 4: Update Integration Tests (~200 lines)

Remove or update tests that specifically test transition behavior:
- Tests simulating the TTD crossing
- Tests validating transition coordinator switching
- Tests for reorg handling during transition

---

## Components to Keep

### MergeBesuControllerBuilder ✅
The post-merge controller builder. This becomes the only builder for merge-capable networks.

### MergeProtocolSchedule ✅
Already handles historical PoW blocks via difficulty check:
```java
// Lines 95-99 in MergeProtocolSchedule.java
@Override
public ProtocolSpec getByBlockHeader(final ProcessableBlockHeader blockHeader) {
  return blockHeader.getDifficulty().greaterThan(Difficulty.ZERO)
      ? preMergeProtocolSchedule.getByBlockHeader(blockHeader)
      : postMergeProtocolSchedule.getByBlockHeader(blockHeader);
}
```

This means **historical sync still works** - blocks with difficulty > 0 use PoW validation, blocks with difficulty = 0 use PoS validation. No runtime transition detection needed.

### PostMergeContext ✅
Implementation of `MergeContext` interface. Maintains:
- Finalized block tracking
- Safe block tracking
- Payload management
- Terminal PoW block reference (historical)

### MergeCoordinator ✅
Handles post-merge block creation via Engine API:
- Payload preparation (`engine_forkchoiceUpdatedV*`)
- Block proposal
- Forkchoice updates

### MergeContext Interface ✅
Core interface for merge-related state. Methods to consider:

| Method | Keep? | Reason |
|--------|-------|--------|
| `getTerminalTotalDifficulty()` | ✅ Yes | Needed for `engine_exchangeTransitionConfigurationV1` |
| `isPostMerge()` | ⚠️ Maybe | Could become always true, or removed |
| `getFinalized()` | ✅ Yes | Core PoS functionality |
| `getSafeBlock()` | ✅ Yes | Core PoS functionality |
| `getTerminalPoWBlock()` | ✅ Yes | Historical reference |
| `isPostMergeAtGenesis()` | ✅ Yes | Distinguishes Holesky-style networks |

### Engine API ✅
All Engine API methods remain:
- `engine_exchangeTransitionConfigurationV1` - Still validates CL/EL agreement on TTD
- `engine_forkchoiceUpdatedV*` - Core PoS block proposal
- `engine_newPayloadV*` - Core PoS block validation
- All other Engine API methods

### BackwardSyncContext ✅
Standard backward sync remains. Special transition handling removed.

---

## Implementation Plan

### Recommended: Three-Phase Rollout

#### **Phase 1: Deprecation (Release N)**

**Goal**: Soft deprecation with escape hatch

**Changes**:
1. Add CLI flag: `--merge-transition-support=<boolean>` (default: `false`)
2. Add startup warning when transition code is used
3. Update documentation to recommend checkpoint sync
4. No code deletion yet

**Implementation**:
```java
// BesuController.java
if (configOptions.getTerminalTotalDifficulty().isPresent()) {
  boolean useTransitionSupport = // CLI flag value

  if (useTransitionSupport) {
    LOG.warn("Merge transition support is deprecated and will be removed in the next release. "
           + "Consider using --sync-mode=CHECKPOINT for new nodes.");
    return new TransitionBesuControllerBuilder(builder, new MergeBesuControllerBuilder())
        .genesisConfig(genesisConfig);
  } else {
    return new MergeBesuControllerBuilder().genesisConfig(genesisConfig);
  }
}
```

**Benefits**:
- Safe rollout
- Catches any unexpected edge cases
- Users can temporarily re-enable if needed
- Validates that MergeBesuControllerBuilder works for all scenarios

**Duration**: 1 release cycle (~3 months)

---

#### **Phase 2: Removal (Release N+1)**

**Goal**: Complete removal of transition code

**Changes**:
1. Delete all Transition* classes (see "Components to Remove")
2. Update BesuController to always use MergeBesuControllerBuilder
3. Simplify validation rules (no more conditional logic)
4. Remove transition-related tests
5. Update documentation

**Testing Requirements**:
- Full sync from checkpoint (mainnet, sepolia, holesky)
- Historical sync from genesis (if supported)
- Backward sync across PoW/PoS boundary
- Engine API functionality
- Network transition scenarios (dev networks)

**Benefits**:
- ~2,000 lines of code removed
- Simplified architecture
- Reduced maintenance burden
- Clearer code for new contributors

---

#### **Phase 3: Documentation Update (Ongoing)**

**Goal**: Clear communication and developer guidance

**Changes**:
1. Update README and getting started guides
2. Document checkpoint sync requirement for mainnet
3. Update architecture documentation
4. Create migration guide for operators
5. Add release notes explaining the change

**Key Messages**:
- "Mainnet sync now requires checkpoint sync (recommended since 2022)"
- "Historical sync still works via difficulty-based protocol selection"
- "No impact on post-merge operation"
- "Private networks: must use checkpoint sync if joining post-TTD"

---

### Alternative: Immediate Removal

**When to consider**: If team has high confidence and wants faster cleanup

**Pros**:
- Faster cleanup
- Less process overhead

**Cons**:
- Higher risk if edge cases exist
- No fallback for unexpected issues

**Recommendation**: Stick with three-phase rollout for safety

---

## Testing Strategy

### Phase 1 Testing (Deprecation)

1. **Smoke Tests**: Verify nodes start and sync with transition support disabled
2. **Mainnet**: Full checkpoint sync from recent checkpoint
3. **Sepolia/Holesky**: Full checkpoint sync
4. **Backward Sync**: Sync backwards across TTD boundary
5. **Engine API**: All Engine API methods function correctly

### Phase 2 Testing (Removal)

#### Unit Tests
- [ ] Test MergeBesuControllerBuilder creation
- [ ] Test MergeProtocolSchedule difficulty-based switching
- [ ] Test validation rules always enforce PoS constraints
- [ ] Test PostMergeContext state management

#### Integration Tests
- [ ] Checkpoint sync mainnet from recent finalized block
- [ ] Checkpoint sync sepolia from recent finalized block
- [ ] Checkpoint sync holesky (PoS from genesis)
- [ ] Backward sync from current head to 100 blocks before TTD
- [ ] Engine API: forkchoice updates
- [ ] Engine API: payload preparation
- [ ] Engine API: new payload validation
- [ ] Engine API: transition configuration exchange

#### Manual Testing Scenarios
- [ ] Fresh node sync mainnet via checkpoint
- [ ] Fresh node sync sepolia via checkpoint
- [ ] Fresh node sync holesky
- [ ] Restart node during sync
- [ ] Node comes back after being offline for days
- [ ] Network reorg handling (post-TTD blocks only)

#### Edge Cases
- [ ] Node with no finalized block set
- [ ] Backward sync to pre-TTD blocks
- [ ] Blocks with difficulty > 0 (historical PoW blocks)
- [ ] Blocks with difficulty = 0 (PoS blocks)
- [ ] Engine API with incorrect TTD configuration

### Regression Testing

Ensure no functionality is broken:
- [ ] Block validation (PoW and PoS blocks)
- [ ] State root calculation
- [ ] Transaction execution
- [ ] Receipt generation
- [ ] Log filtering
- [ ] JSON-RPC APIs
- [ ] GraphQL APIs
- [ ] Metrics and monitoring
- [ ] Tracing APIs

---

## Risks and Mitigations

### Risk 1: Private Networks Mid-Transition

**Risk**: A private network might still be transitioning or planning to transition.

**Likelihood**: Very low (The Merge was in 2022)

**Mitigation**:
- Phase 1 deprecation provides warning and escape hatch
- Document that networks must use checkpoint sync if joining post-TTD
- Keep TTD configuration support for historical context

### Risk 2: Historical Sync Breaks

**Risk**: Removing transition code could break syncing from genesis.

**Likelihood**: Low (MergeProtocolSchedule handles this)

**Mitigation**:
- MergeProtocolSchedule.getByBlockHeader() checks block.difficulty
- Blocks with difficulty > 0 use PoW validation (pre-merge schedule)
- Blocks with difficulty = 0 use PoS validation (post-merge schedule)
- Test syncing across TTD boundary during Phase 1

### Risk 3: Unknown Dependencies

**Risk**: Some code path depends on transition logic in unexpected ways.

**Likelihood**: Low to medium

**Mitigation**:
- Phase 1 provides discovery period
- Comprehensive integration testing
- Monitor community feedback during deprecation period

### Risk 4: Checkpoint Sync Not Available

**Risk**: Users unable to sync because no checkpoint available.

**Likelihood**: Very low (many checkpoint sources exist)

**Mitigation**:
- Document checkpoint sources (Infura, Alchemy, etc.)
- Besu already defaults to checkpoint sync for mainnet
- Community provides checkpoint data

---

## How to Resume This Work

### Prerequisites

1. **Team Alignment**: Get team consensus on the approach (three-phase recommended)
2. **Issue Tracking**: Create/update GitHub issue #2897
3. **Timeline**: Decide on release target for Phase 1
4. **Testing Resources**: Ensure infrastructure for integration testing

### Step-by-Step Resume Process

#### 1. Review This Document
```bash
cd /home/fdifabio/repo/besu
cat docs/merge-transition-cleanup-plan.md
```

#### 2. Create GitHub Issue/PR
- Reference issue #2897
- Link this planning document
- Get maintainer approval

#### 3. Start Phase 1 Implementation

**Create Feature Branch**:
```bash
git checkout -b feature/deprecate-merge-transition
```

**Add CLI Flag** (`BesuCommand.java`):
```java
@Option(
  names = {"--merge-transition-support"},
  description = "Enable merge transition support (DEPRECATED, default: false)",
  defaultValue = "false",
  arity = "1"
)
private Boolean mergeTransitionSupport = false;
```

**Update BesuController** (`BesuController.java:358-369`):
```java
if (configOptions.getTerminalTotalDifficulty().isPresent()) {
  if (mergeTransitionSupport) {
    LOG.warn("Merge transition support is deprecated and will be removed in the next release. " +
             "All major networks have completed The Merge. Consider using --sync-mode=CHECKPOINT.");
    return new TransitionBesuControllerBuilder(builder, new MergeBesuControllerBuilder())
        .genesisConfig(genesisConfig);
  } else {
    LOG.info("Using post-merge controller builder");
    return new MergeBesuControllerBuilder().genesisConfig(genesisConfig);
  }
}
```

**Add Tests**:
```java
// Test default behavior (transition support disabled)
// Test with flag enabled (transition support still works)
// Test warning message appears
```

#### 4. Testing Checklist

Before opening PR:
- [ ] Mainnet checkpoint sync works
- [ ] Sepolia checkpoint sync works
- [ ] Holesky checkpoint sync works
- [ ] All unit tests pass
- [ ] Integration tests pass
- [ ] Startup warning appears when flag is used
- [ ] Documentation updated

#### 5. Open PR

**PR Title**: "Deprecate merge transition support"

**PR Description Template**:
```markdown
## Description
Deprecates merge transition support code as The Merge completed in 2022. This is Phase 1 of a cleanup plan.

## Related Issue
Fixes #2897

## Changes
- Added `--merge-transition-support` CLI flag (default: false)
- Added deprecation warning when transition code is used
- Updated documentation to recommend checkpoint sync
- No code deletion in this phase (safe rollout)

## Testing
- [x] Mainnet checkpoint sync
- [x] Sepolia checkpoint sync
- [x] Holesky checkpoint sync
- [x] All tests pass

## Next Steps
Phase 2 (next release): Remove all Transition* classes

## Documentation
See docs/merge-transition-cleanup-plan.md for full plan
```

#### 6. Monitor Phase 1 (1 Release Cycle)

- Watch for community feedback
- Monitor issue reports
- Gather data on usage of `--merge-transition-support` flag
- Confirm no edge cases discovered

#### 7. Proceed to Phase 2

If Phase 1 is successful:
- Create new branch: `feature/remove-merge-transition`
- Follow "Components to Remove" section
- Delete all Transition* classes
- Update validation rules
- Update tests
- Open Phase 2 PR

---

## Quick Reference Commands

### Find Transition Class Usages
```bash
cd /home/fdifabio/repo/besu

# Find all references to Transition classes
grep -r "TransitionBesuControllerBuilder" --include="*.java"
grep -r "TransitionProtocolSchedule" --include="*.java"
grep -r "TransitionContext" --include="*.java"
grep -r "TransitionCoordinator" --include="*.java"
grep -r "TransitionBackwardSyncContext" --include="*.java"
grep -r "TransitionBestPeerComparator" --include="*.java"
grep -r "TransitionUtils" --include="*.java"
```

### Run Relevant Tests
```bash
# Unit tests
./gradlew :consensus:merge:test
./gradlew :besu:test --tests BesuControllerTest

# Integration tests
./gradlew :acceptance-tests:test --tests MergeAcceptanceTest
```

### Check TODO Comments
```bash
grep -r "TODO.*merge.*transition" --include="*.java"
```

---

## Contact and Questions

**Document Owner**: Analysis performed by Claude Code on 2026-01-31

**To Resume**:
1. Review this document thoroughly
2. Create/update GitHub issue #2897
3. Follow "Step-by-Step Resume Process" above
4. Reference the two exploration agent reports (IDs: a3eba39, a428ed1)

**Key Decision Points**:
- [ ] Approve three-phase approach vs immediate removal
- [ ] Set timeline for Phase 1 release
- [ ] Determine acceptance criteria for moving to Phase 2
- [ ] Decide on historical sync support policy

---

## Appendix: File Locations

### Transition Classes
```
/home/fdifabio/repo/besu/app/src/main/java/org/hyperledger/besu/controller/
  └── TransitionBesuControllerBuilder.java

/home/fdifabio/repo/besu/consensus/merge/src/main/java/org/hyperledger/besu/consensus/merge/
  ├── TransitionUtils.java
  ├── TransitionContext.java
  ├── TransitionProtocolSchedule.java
  ├── TransitionBackwardSyncContext.java
  ├── TransitionBestPeerComparator.java
  └── blockcreation/
      └── TransitionCoordinator.java
```

### Keep Classes (Post-Merge Only)
```
/home/fdifabio/repo/besu/consensus/merge/src/main/java/org/hyperledger/besu/consensus/merge/
  ├── MergeContext.java (interface)
  ├── PostMergeContext.java (implementation)
  ├── MergeProtocolSchedule.java (difficulty-based switching)
  └── blockcreation/
      └── MergeCoordinator.java (PoS block creation)
```

### Key Entry Point
```
/home/fdifabio/repo/besu/app/src/main/java/org/hyperledger/besu/controller/
  └── BesuController.java:358-369 (where TransitionBesuControllerBuilder is instantiated)
```

---

**End of Document**