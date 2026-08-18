# Engine API methods — architecture and how to extend it

This package implements the [Engine API](https://github.com/ethereum/execution-apis/tree/main/src/engine)
(`engine_*` JSON-RPC methods). It follows a strict versioning pattern, described here so that a new
version of a method — or a brand-new method series — is always added the same way. Read this before
changing anything in this package, in the related parameter/result classes, or in their tests.

## Architecture

Every series in this package follows the same pattern below, whether or not it has multiple
versions. Series with more than one version (`engine_forkchoiceUpdatedV*`, `engine_newPayloadV*`,
`engine_getPayloadV*`, `engine_getPayloadBodiesBy*`) are a **sealed class hierarchy mirroring the
specification**: version N extends version N−1 and overrides only what its spec version adds or
changes. Single-version series (`engine_exchangeCapabilities`, `engine_exchangeTransitionConfiguration`,
`engine_getClientVersionV1`, `engine_getBlobsV*`) are a plain, non-sealed
`ExecutionEngineJsonRpcMethod` subclass built with a `(null, null)` fork window (or, for
`engine_getBlobsV*`, a real fork window, since those methods only activate from a given hardfork).

- `EngineForkchoiceUpdatedV1 permits EngineForkchoiceUpdatedV2`, `... V3 permits
  EngineForkchoiceUpdatedV4`; `EngineNewPayloadV1 permits EngineNewPayloadV2`, `... V4 permits
  EngineNewPayloadV5`; `EngineGetPayloadV1 permits EngineGetPayloadV2`, `... V5 permits
  EngineGetPayloadV6`; `EngineGetPayloadBodiesByHashV1 permits EngineGetPayloadBodiesByHashV2` and
  `EngineGetPayloadBodiesByRangeV1 permits EngineGetPayloadBodiesByRangeV2`; and the latest version
  of each is `final`. Future multi-version series follow the same shape.
- All versions extend `ExecutionEngineJsonRpcMethod`, which owns the fork-window validation
  (`minSupportedFork` / `firstUnsupportedFork` constructor arguments, `validateForkSupported`,
  see also `ForkSupportHelper`). Concrete versions never check fork timestamps themselves.
- Engine methods execute concurrently by default. The `engine_forkchoiceUpdatedV*` and
  `engine_newPayloadV*` series are the exception: the Engine API spec requires them to be
  processed in the order they were received, so `EngineForkchoiceUpdatedV1` and
  `EngineNewPayloadV1` extend `OrderedExecutionJsonRpcMethod` instead of
  `ExecutionEngineJsonRpcMethod` directly — a compile-time choice, not a runtime flag, so a new
  ordered series must extend `OrderedExecutionJsonRpcMethod` from its V1 class onward.
  `OrderedExecutionJsonRpcMethod` runs calls through a single-threaded `WorkerExecutor` created
  from the engine consensus API's existing Vertx instance (the same one backing `EngineQosTimer`),
  rather than a dedicated Vertx instance just for ordering. That instance is passed as an explicit
  constructor argument rather than through `ConstructorArguments`, since FCU and newPayload are the
  only series that need it.
- Every series takes a single `ExecutionEngineJsonRpcMethod.ConstructorArguments` record (built via
  the generated `ConstructorArgumentsBuilder`) plus `(minSupportedFork, firstUnsupportedFork)`,
  instead of a bespoke positional argument list per series — this is what lets `VersionScheduler`
  build every multi-version series through one shared factory shape (see below), and what lets a
  single-version series be built with a one-line `new EngineFooV1(constructorArguments, from, to)`.
  `ConstructorArguments` carries every field any series needs; mark a field `@Nullable` if only some
  series read it (e.g. `mergeCoordinator` is absent pre-merge; `clientVersion`/`commit` are
  `engine_getClientVersionV1`-only; `transactionPool` is `engine_getBlobsV*`-only) — and extend it
  (and its builder) when adding a series that needs a field it doesn't have yet.
- The JSON data structures relevant to multi-version series are sealed hierarchies too, mirroring the
  spec versions: request parameters in `..internal.parameters` (`ExecutionPayloadV1..V4`,
  `NewPayloadRequestParametersV1..V3`, `ForkchoiceStateV1`, `PayloadAttributesV1..V4`), results in
  `..internal.results` (`PayloadStatusV1`, `ForkchoiceUpdatedResultV1`,
  `EngineGetPayloadResultV1..V6`, `ExecutionPayloadBodiesV1..V2`). Result classes reuse the
  request-side payload hierarchy rather than re-declaring header fields:
  `EngineGetPayloadResultV1` wraps an `ExecutionPayloadV1` via `@JsonValue`.
- A version class overrides narrow, protected hooks of its parent (e.g. `createResponse`,
  `createExecutionPayload`, `validateParameters`, `validatePayloadAttributes`) — it never
  re-implements the request flow.

### Registration and scheduling

`org.hyperledger.besu.ethereum.api.jsonrpc.methods.ExecutionEngineJsonRpcMethods` declares, per
multi-version series, which version is active in which fork window via the `VersionScheduler` DSL,
using constructor references (not reflection — see `VersionScheduler.EngineMethodFactory`):

```java
VersionScheduler.startsFromBeginningUntil(EngineGetPayloadV1::new, SHANGHAI)
    .thenAlsoFromBeginning(EngineGetPayloadV2::new)
    .thenFrom(CANCUN, EngineGetPayloadV3::new)
    .thenFrom(AMSTERDAM, EngineGetPayloadV6::new)
    .build(constructorArguments);
```

`EngineForkchoiceUpdatedV*` and `EngineNewPayloadV*` follow the same DSL, but since their
constructors take an extra `Vertx` argument (see `OrderedExecutionJsonRpcMethod` above), their
factories are lambdas capturing `consensusEngineServer` instead of bare constructor references:
`(ca, from, to) -> new EngineForkchoiceUpdatedV1<>(ca, consensusEngineServer, from, to)`.

Not every series is a version-supersedes-version chain: in `engine_getPayloadBodiesBy*` V2 only adds
an optional field, so V1 and V2 coexist permanently, with no fork window on either — use
`VersionScheduler.alwaysActive(EngineGetPayloadBodiesByHashV1::new, EngineGetPayloadBodiesByHashV2::new)`
for series like this instead of `startsFromBeginningUntil`/`thenFrom`.

The scheduler instantiates each version with the right `(minSupportedFork, firstUnsupportedFork)`
pair derived from the chain. Single-version series aren't registered through `VersionScheduler` at
all — `ExecutionEngineJsonRpcMethods.create()` just calls their constructor directly (e.g. `new
EngineGetBlobsV1(constructorArguments, CANCUN, OSAKA)`), since there's no chain to build. Method
names live in the `RpcMethod` enum; `engine_exchangeCapabilities` derives the advertised capability
list automatically from every `RpcMethod` entry starting with `engine_`, so there is no separate
capabilities list to maintain.

## Test pattern (src/test, same package)

Tests are layered exactly like the production classes: `EngineForkchoiceUpdatedV4Test extends
EngineForkchoiceUpdatedV3Test extends ... V1Test`, so **every version class runs all the tests of
the previous versions plus its own**.

- The V1 test class owns the generic scenarios, written against protected hooks:
  `createMethodInstance()`, `getMinSupportedTimestamp()` / `getMaxSupportedTimestamp()`,
  payload/attribute builders, fixture customizers, and result-assertion hooks such as
  `assertPayloadResult(Object)` that each version extends with
  `super.assertPayloadResult(result); ...` plus its own checks.
- A version test class contains only: the `createMethodInstance()` override, the method-name test
  override, hook overrides, and tests for behavior introduced in that version.
- A scenario that stops applying at some version is guarded with
  `assumeTrue(someCapabilityHook())` on a boolean/Optional hook the later version overrides —
  **never** `@Disabled` and never an empty test override.
- Fork milestones in unit tests are the fake ones defined by `AbstractScheduledApiTest`
  (Paris=10, Shanghai=20, Cancun=30, Prague=50, Osaka=60, Amsterdam=70, ...).

Acceptance tests are fixture-driven, one directory per fork:
`acceptance-tests/tests/src/acceptanceTest/resources/jsonrpc/engine/<fork>/` containing a
`genesis.json` and `test-cases/` with JSON request/response pairs (see also the
`*AcceptanceTestHelper` classes under `acceptance-tests/.../acceptance/ethereum/`).

## Checklist: add version N+1 to an existing series

Use the commits that introduced the current latest version as the exemplar
(`git log --oneline -- <path to latest version class>`), then:

1. Un-`final` (or extend `permits`) the current latest method class; add
   `EngineFooVN+1 extends EngineFooVN` (`final`), overriding `getName()` and only the hooks the
   spec changes. The compiler enforces the rest of the chain.
2. If the payload/attributes/result shape changes, extend the corresponding sealed hierarchy in
   `..internal.parameters` / `..internal.results` the same way (update `permits` on the parent).
3. Add `ENGINE_FOO_VN+1("engine_fooVN+1")` to `RpcMethod` (this also advertises it via
   `engine_exchangeCapabilities`).
4. Extend the series' `VersionScheduler` chain in `ExecutionEngineJsonRpcMethods` with
   `.thenFrom(<ACTIVATION_FORK>, EngineFooVN+1::new)`.
5. Add `EngineFooVN+1Test extends EngineFooVNTest`: override `createMethodInstance()`, the
   method-name test, the fork-window hooks, and any builder/assertion hooks; add tests only for
   the new behavior. All inherited tests must pass unmodified.
6. Add/extend the acceptance-test fixtures for the activation fork.
7. Update `CHANGELOG.md`.

## Checklist: add a brand-new method series

1. Create `EngineBarV1 extends ExecutionEngineJsonRpcMethod` (sealed once V2 exists, otherwise
   plain), taking `ConstructorArguments` plus the fork window in its constructor; add its
   parameter/result classes as (future-sealed) hierarchies from the start if it takes/returns
   structured data.
2. Register it in `RpcMethod` and in `ExecutionEngineJsonRpcMethods`: via `VersionScheduler`
   (`startsFrom(<FORK>, EngineBarV1::new)` or `alwaysActive(...)`) if more versions are expected, or
   a direct `new EngineBarV1(constructorArguments, from, to)` call if it's a single-version series.
   Extend `ConstructorArguments` (and its builder) first if it needs a field the record doesn't
   carry yet — mark it `@Nullable` if no other series reads it.
3. Create `EngineBarV1Test` with all scenarios written against protected hooks from day one, so
   `EngineBarV2Test` can be layered on top later if a V2 is ever added.
4. Add acceptance-test fixtures and a `CHANGELOG.md` entry.

## Definition of done

```
./gradlew :ethereum:api:test --tests "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.*"
./gradlew :ethereum:api:spotlessApply
```

Both must pass, with no `@Disabled` tests introduced, before the change is complete.
