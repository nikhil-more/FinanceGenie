# Offline performance measurements

The reference is the user's A063 running Android 15 / API 35. Measurements use a
separate `com.financegenie.android.benchmark` application, signed locally and based
on the release build, with debugging disabled and shell profiling enabled.
Production/development databases and privacy preferences are never cleared or
used as benchmark fixtures. The current release configuration has no R8 shrinking;
the benchmark deliberately matches that baseline rather than claiming future
distribution-build performance.

The benchmark source set supplies its own Application/container, fixed November
2026 clock and synthetic-only `performance-fixture-ledger.db`, using the production
profile-store lifetime. A same-signature permission
protects its setup activity. These classes, the setup route and synthetic data
builder are excluded from debug/release APKs. The separate benchmark driver is
also signed with the local debug key. No bank, user credential or notification
content is accessed.

## Dataset and method

- 10,000 committed synthetic events across June–November 2026, three accounts,
  twenty named categories plus Uncategorized, and one overall November budget.
  The mix includes income, transfers, unlinked confirmed refunds, adjustments
  and expenses, including refund-only categories. November has 1,652 events.
- The default driver runs twenty process-cold launches. It force-stops only the
  benchmark app, verifies its process/window is absent, and starts the timer before
  the explicit Activity launch. A unique launch ID and signature-protected signal
  identify the new process after `FullyDrawnReporter` and a committed hardware
  frame. The driver then verifies 1,652 events, ₹948.75 spending and foreground
  identity before stopping the confirmation timer. Empty/loading screens cannot
  substitute for a loaded result.
- `launchToConfirmedOverviewMs` includes process creation, rendering, broadcast
  delivery and UIAutomator verification. The diagnostic
  `launchToCommittedFrameMs` ends at the app's frame-commit callback, using the
  same boot-relative `SystemClock.elapsedRealtimeNanos()` origin. Frame commit
  means submission to the swap chain, not guaranteed physical presentation. These
  metrics are named separately from AndroidX TTID/TTFD. The two-second usable
  Overview target is assessed on the later, conservative confirmation endpoint.
- The method requires API 29+ and hardware rendering. Filesystem/SQLite caches are
  warm and compilation retains the installed state; this is process-cold startup,
  not cold disk cache or controlled AOT compilation. No clocks, root access or
  global device settings are changed.
- In-process instrumentation on the same non-debuggable app measures thirty
  revision-checked reads of the warm snapshot, monthly summaries including a full
  read, and 50-row bounded searches. Each summary uses a fresh repository instance,
  so SQLite/OS caches are warm but no in-memory snapshot is reused for that read.
  This keeps the summary measurement distinct from the snapshot-cache hit time.
- Thirty one-paise expense drafts are prepared outside measurement. Save timing starts
  with the actual Save click and ends after the expected Overview metric is
  rendered and Compose reports idle. This includes test synchronization/polling
  overhead. End state is checked for 10,030 events, avoiding successful-looking
  timings for missing writes.
  Thirty budget edits then measure Save to the exact updated limit and remaining
  amount. The final persisted limit is ₹5,030 at revision 31. These samples are
  reported separately from expense-save timings.
- P50/P95 use the nearest-rank method. Sample arrays are retained in aggregate
  reports. Process PSS is sampled between operations; the maximum sample is a
  baseline rather than a continuously measured absolute memory peak. APK bytes
  and SHA-256 identify the measured target artifact.

## Measurement findings during implementation

Macrobenchmark 1.3.4 produced only thirteen valid startup samples from twenty
requested iterations. Inspection of the app's own traces found that the missing
samples contained a truncated process name. That library version matched only
the full package name. Version 1.4.1 includes the
[AndroidX process-name matcher](https://android.googlesource.com/platform/frameworks/support/+/3ecf191783372810d8cbe9dededeea09dde6ae07/benchmark/benchmark-traceprocessor/src/commonMain/kotlin/androidx/benchmark/traceprocessor/TraceProcessor.kt)
that also recognizes the last fifteen characters when Perfetto has only the
Linux scheduler name. Its published benchmark artifacts support compile SDK 34
and AGP 8.1.1 or newer, so this correction needs no SDK/toolchain upgrade. The
default readiness runner still requires twenty verified cold launches. Incomplete
trace captures do not establish an AndroidX startup target result.

The first 1.4.1 run restored twenty frame groups but only fourteen startup results.
The incomplete traces ended before `reportFullyDrawn`. Accessibility text/idle
checks alone were insufficient to keep capture alive through that frame. The
fully-drawn/frame-commit barrier makes all twenty launches verify their loaded
frames, but trace extraction still returns only fourteen startup results on this
phone. The underlying trace issue remains unresolved. Standard TTID/TTFD is
therefore **INCOMPLETE**, not a passed twenty-run measurement.

The separate direct readiness measurement provides twenty end-to-end samples
without relying on that extraction. It includes every launch and adds confirmation
overhead rather than adjusting or filling missing trace timings. The original
`OfflineStartupBenchmark` remains callable from instrumentation/Android Studio for
diagnosis. Its reviewed [frame/trace diagnostic](performance-trace-diagnostic.json)
identifies the earlier artifact, sample counts and limitations; its frame summaries
describe captured samples rather than complete presentation coverage.

The initial implementation measured 445 ms P95 from Save to the updated screen.
Avoiding repeated reads through a revision-checked snapshot cache reduced an
intermediate measurement to 309 ms P95. Both exceed the proposed 300 ms target.
These are diagnostic results from earlier builds, not final acceptance results;
final measurements and any remaining failures belong in the verification log.

## Recorded baseline — 9 November 2026

The [reviewed aggregate report](performance-baseline.json) contains all twenty
startup samples and thirty samples per operation, with hashes for the target,
driver and test APKs. On the A063 / API 35:

| Measurement | P95 | Proposed target | Outcome |
|---|---:|---:|---|
| Launch to UI-confirmed Overview | 2,278.64 ms | ≤2,000 ms | Above target |
| Launch to committed frame | 632.66 ms | Diagnostic only | Buffer submission, not presentation |
| Monthly summary including uncached snapshot read | 187.30 ms | ≤300 ms | Pass |
| Bounded 50-row search | 2.99 ms | ≤500 ms | Pass |
| Expense Save to visible updated value | 278.76 ms | ≤300 ms | Pass |
| Revision-checked warm snapshot read | 1.79 ms | Baseline only | Recorded |

The confirmed endpoint includes broadcast delivery, process checks and UIAutomator
verification. The committed-frame result does not replace the failed confirmation
target or establish a twenty-run TTFD result. This historical run left startup
confirmation and budget-save timing open. The two-second target has not been
relaxed. Expense Save improved from the initial 445 ms measurement while
retaining exact financial validation and atomic writes.

The measured APK is 7,163,426 bytes (6.83 MiB); maximum sampled process PSS is
268,819 KiB (262.5 MiB). The separate incomplete trace diagnostic records 505 frame
samples, with CPU-duration P95 74.96 ms and overrun P95 130.89 ms. Those are captured
sample summaries from its separately hashed earlier artifact, not a complete
per-launch frame/presentation guarantee or a universal smoothness pass.

After measurement, one benchmark-owned diagnostic trace was retained in `/tmp`,
and the three synthetic benchmark packages were uninstalled from the phone.
FinanceGenie Dev was reopened with its ledger/preferences retained.

## Gate A measurements — 10 November 2026

The [current reviewed report](performance-gate-a.json) retains twenty startup
samples and thirty samples for every operation, including budget edits.

| Measurement | P95 | Target | Outcome |
|---|---:|---:|---|
| Launch to UI-confirmed Overview | 775.52 ms | ≤2,000 ms | Pass |
| Launch to committed frame | 699.46 ms | Diagnostic only | Recorded |
| Monthly summary including uncached snapshot read | 185.79 ms | ≤300 ms | Pass |
| Bounded 50-row search | 2.74 ms | ≤500 ms | Pass |
| Expense Save to visible updated value | 296.89 ms | ≤300 ms | Pass |
| Budget Save to visible updated limit/remaining | 314.52 ms | ≤300 ms initially | User accepted ~315 ms for individual use |

The startup driver now sets UIAutomator's generic idle timeout to zero for its
measurements and restores it afterward. Nonce, new PID, committed-frame, exact
visible-value and foreground checks still determine readiness. The difference
from 2,278.64 ms corrects automation overhead; it does not establish an application
startup speedup. Standard AndroidX TTID/TTFD remains incomplete at fourteen of
twenty and is retained as a separate diagnostic, never filled with inferred values.

The original 300 ms budget target remains false in the report. On 10 November the
user explicitly accepted the measured 314.52 ms baseline for this personal app.
No extra calculation cache was adopted. The expense/startup targets remain intact;
revisit a material regression from this budget baseline.

The target APK is 7,429,034 bytes (7.09 MiB). Maximum sampled process PSS is
527,626 KiB (515.3 MiB), compared with the earlier 268,819 KiB. This is a longer
instrumented workload that also performs thirty budget edits and keeps the
profile store active; it is not a controlled production-only memory comparison.
The app's financial calculation path is unchanged. Retain both observations and
investigate memory again if normal use shows pressure or a comparable workload
regresses. Neither sampled PSS nor incomplete frame traces establish a universal
memory or smoothness guarantee.

The report hashes identify the measured APKs. Later test-only assertions that the
matched expense/budget text is displayed are not retroactive measurements of a
different test APK.
The later Gate A search-entry fix gives the text field an immediate Compose value
instead of feeding it the background report's previous selection. It changes no
financial calculation or report requests. The recorded APK hashes identify the
baseline before that input fix; they are not measurements of a later candidate.

## Build and run

Use the repository's JDK 17 / SDK 34 toolchain and an authorized, unlocked physical
phone. Benchmark libraries require first-time public dependency downloads.

```bash
./gradlew :apps:android:assembleBenchmark :benchmarks:assembleBenchmark
./gradlew -PfinancegenieTestBuildType=benchmark :apps:android:assembleBenchmarkAndroidTest
python3 scripts/benchmark_on_device.py
```

`--serial` selects a phone privately when several are connected. `--output` defaults
to `/tmp/financegenie-step6-performance`; choose a fresh output directory for each
completed run so an older report cannot be confused with a failed rerun. The
runner installs only the separate
benchmark target/driver/test APKs, prepares their synthetic fixture, runs direct
readiness then query/save instrumentation, and writes a sanitized `summary.json`. It reports
each proposed timing target separately: measurement completion is not a target pass.

Raw traces and full library metadata stay in benchmark-owned phone storage or
temporary/ignored build output; they are never committed. Only reviewed aggregate
timings, model/API, conditions and artifact hashes may enter repository evidence.
The normal FinanceGenie Dev app remains installed with its own data intact.

CI compiles benchmark artifacts; physical measurements are a separate device
check. Actual results, failed targets, limitations and any optimization must be
recorded in `verification-log.md` and the progress tracker before claiming a pass.

References used for this setup:

- [Android Macrobenchmark setup and build configuration](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Startup/full-display and frame metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [Compose fully-drawn reporting](https://developer.android.com/topic/performance/issues/launch-time)
- [FullyDrawnReporter completion listeners](https://developer.android.com/reference/kotlin/androidx/activity/FullyDrawnReporter)
- [Hardware frame-commit callbacks](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))
- [Boot-relative monotonic clock](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtimeNanos())
- [Benchmark instrumentation arguments](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args)
