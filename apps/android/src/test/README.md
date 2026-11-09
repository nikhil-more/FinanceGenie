# JVM tests

`domain/` contains JVM financial-model, exact-money, validation and calculation
tests. `GoldenFixtureTest` reads the shared JSON in `fixtures/ledger/` through a
Gradle-supplied system property, without an Android runtime.

`privacy/` tests the in-memory lock/session policy, including cold start, timeout
boundaries, rotation, cancellation and monotonic-clock regression. No device
credential is handled or mocked by the production app.

Run `./gradlew :apps:android:testDebugUnitTest`. Room, manual-entry, navigation,
privacy-flag and activity-recreation checks live under `src/androidTest/` and use
the user's phone with isolated test storage. Run the repository's
`scripts/verify_storage_on_device.py --ui` driver for storage/UI suites and explicit
fresh-process probes. Actual Android authentication and TalkBack remain manual.
