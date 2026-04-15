## personal-polish-connected-suite-state-leak-followup

- The remaining rerun flake was still caused by cross-test persistence owners surviving between instrumentation classes, not by reopened product regressions.
- Several Compose-only `androidTest` suites (`HostLibraryFlowsInstrumentedTest`, `IdentityPasswordFlowsInstrumentedTest`, `SnippetLibraryFlowsInstrumentedTest`, `SnippetHistoryStateCoherenceInstrumentedTest`, `SessionRealConnectionFlowsInstrumentedTest`) were only resetting persistence in `@Before`, so tracked `AppContainer` / Room / DataStore instances from a previous test class could stay alive until a later suite hit relaunch-heavy cleanup.
- Added `TestPersistenceResetRule` so those suites now reset persistence in both `before()` and `after()`.
- The app-shell / real-fixture rule-based suites also now call `resetTestPersistenceState(context)` in `after()` instead of only `application.resetDefaultContainerForTesting()`, which ensures tracked non-application containers are closed and backing DB/DataStore files are deleted between full-suite tests.
- Verification that mattered for this feature:
  - `./gradlew testDebugUnitTest --max-workers=4`
  - `./gradlew lintDebug --max-workers=4`
  - targeted `:app:connectedDebugAndroidTest` checks for the previously migrating host/session/snippet cases
  - two consecutive successful `./gradlew connectedDebugAndroidTest --max-workers=1` runs
