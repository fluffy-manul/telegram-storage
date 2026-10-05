# Closed-storage retention

Every instance registers a JVM shutdown hook whose callback captures the storage.
Previously, the hook stayed registered after successful explicit close, retaining the
storage, its bot and its decoded index until JVM exit. The change stores the hook handle
and unregisters it only after the existing persistence and executor shutdown complete.
If JVM shutdown has already begun, removing a hook throws `IllegalStateException`; that
case is ignored because the JVM is already running its shutdown hooks.

The index upload, description update, response checks and executor ordering are unchanged.
There is no new index snapshot, value cache, skipped write, or synchronization policy.

## Reproduction

Use JDK 23 and the Gradle 8.12.1 wrapper. The baseline harness commit has unchanged
production source from upstream `0bfa8bc544042f3253bc0ed4db7ebe7076653a75`.

```sh
git worktree add ../telegram-storage-baseline 1481cf6
cd ../telegram-storage-baseline
./gradlew benchmarkClosedStorageRetention -Pentries=10000 -Pcycles=5
./gradlew benchmarkClosedStorageRetention -Pentries=100000 -Pcycles=5

cd ../telegram-storage
./gradlew build
./gradlew benchmarkClosedStorageRetention -Pentries=10000 -Pcycles=5
./gradlew benchmarkClosedStorageRetention -Pentries=100000 -Pcycles=5
```

Each run uses a fresh JVM and five independent instances with freshly decoded indexes.
All API calls use a loopback HTTP fixture and a dummy token. Each successful close is
checked to make exactly one document upload and one description update. The production
rate limiter is active and the mock server adds no simulated latency. After closing all
instances, the probe makes five GC requests with 100 ms between them, then examines weak
references and reports the JVM heap usage. No timed performance claims are made, so JIT
warmup is not a before/after latency variable.

## Observations

Measured 2026-10-05 on Linux x86_64, Eclipse Temurin 23.0.2+7, Gradle 8.12.1.
One separate JVM run per implementation and workload:

| Entries per instance | Closed instances | Baseline retained instances / entries | Changed retained instances / entries |
| ---: | ---: | ---: | ---: |
| 10,000 | 5 | 5 / 50,000 | 0 / 0 |
| 100,000 | 5 | 5 / 500,000 | 0 / 0 |

Reported whole-JVM heap usage after the GC requests:

| Entries per instance | Baseline (bytes) | Changed (bytes) |
| ---: | ---: | ---: |
| 10,000 | 13,924,288 | 6,251,424 |
| 100,000 | 83,646,624 | 6,255,272 |

These heap totals include the fixture, HTTP clients, JVM/library state and measurement
noise. They are not an isolated per-entry allocation measurement. GC requests are best
effort; observed cleared weak references show collectability in these runs, not a promise
that every JVM will collect an instance immediately after close. There is no automatic
GC-based pass/fail assertion in the unit tests.

## Regression coverage and limits

Eighteen local tests pass: eight basic persistence/mutable-key tests, seven consistency
and failure tests, and three lifecycle tests. They verify unconditional close publication,
last-close-wins behavior across instances, close/reopen, acknowledgement waiting, failed
upload retry via `set`, existing failed-close behavior, and real child-JVM shutdown.
Four destructive live integration tests require nonempty `BOT_TOKEN` and `CHANNEL_NAME`
and were skipped. Existing credentialed CI continues to enable them.

Unclosed instances must remain reachable through their hooks until JVM shutdown. Failed
close still has the upstream limitations: it sets the closed flag before persistence,
so a later close does not retry, and failure can leave the executor alive. This focused
change does not alter those semantics or add distributed coordination. No Telegram
accounts were contacted and no live-service performance result is claimed.

The cloud environment used `JAVA_HOME=/workspace/tools/jdk-23.0.2+7`,
`GRADLE_USER_HOME=/workspace/.gradle`, and
`-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts` for its trusted certificates.
These are local environment settings, not repository requirements.
