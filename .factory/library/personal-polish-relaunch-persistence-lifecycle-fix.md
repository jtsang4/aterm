## personal-polish-relaunch-persistence-lifecycle-fix

- The repeat-use failures are reproducible on the assigned validator surface:
  - `SessionSshFixtureInstrumentedTest#favorite_repeat_use_flow_reconnects_and_runs_saved_snippet_without_any_login_or_sync_gate`
  - `SnippetRealFixtureExecutionInstrumentedTest#active_session_target_refreshes_with_live_session_changes_reuses_that_session_and_suppresses_duplicate_confirm_runs`
  - `#latest_saved_content_history_relaunch_and_deletion_stay_coherent_on_real_fixture_surface`
  - `#successful_runs_show_recent_history_newest_first_with_target_context`
- The failing transcript reports show real payload proof in the terminal (`ACTIVE_SESSION_REUSE_PROOF`, `LATEST_HISTORY_CONTENT_PROOF`, etc.), but snippet execution history still remains empty.
- Emulator logcat repeatedly shows the deeper lifecycle/persistence symptom during relaunch:
  - `A SQLiteConnection object for database '/data/user/0/io.github.jtsang4.aterm/databases/aterm.db' was leaked`
  - `file unlinked while open: /data/data/io.github.jtsang4.aterm/databases/aterm.db`
- That strongly suggests the relaunch/runtime path is shadowing persistence with a stale open Room connection while the on-disk database gets deleted/recreated, matching the mission precondition about copied DB snapshots showing zero rows after relaunch.
- I tried tightening container cleanup in `AtermApplication.replaceAppContainerForTesting` so old `AppContainer` instances close immediately instead of only on suite teardown. This reduces one obvious leak source but does **not** restore the failing repeat-use history flows by itself.
- The next worker should continue at the lifecycle boundary: audit every relaunch path that swaps `AppContainer` / `AtermApp` / `MainActivity` state, ensure old Room/DataStore holders are closed before DB deletion or replacement, and only then rerun the repeat-use snippet/session instrumentation.
- New evidence from targeted reruns:
  - `SnippetRealFixtureExecutionInstrumentedTest#active_session_target_refreshes_with_live_session_changes_reuses_that_session_and_suppresses_duplicate_confirm_runs`
  - `#successful_runs_show_recent_history_newest_first_with_target_context`
  - `#latest_saved_content_history_relaunch_and_deletion_stay_coherent_on_real_fixture_surface`
  - `SessionSshFixtureInstrumentedTest#favorite_repeat_use_flow_reconnects_and_runs_saved_snippet_without_any_login_or_sync_gate`
    still fail even when run in isolation.
- The generated Android test XML now shows the transcript tail already contains the dispatched snippet output (`ACTIVE_SESSION_REUSE_PROOF`, etc.) while history remains empty. That means the current blocker is **not only** DB rows disappearing after relaunch; snippet success/history persistence is still failing even when remote payload proof visibly lands in the transcript.
- I also confirmed the SQLite leak symptom still exists in logcat during instrumented teardown (`SQLiteConnection ... leaked`, `file unlinked while open`) because many tests still create raw `AppContainer.create(context)` instances outside `AtermApplication` ownership and then delete `aterm.db`. So the lifecycle leak is real, but it does **not** explain the isolated snippet-history failures by itself.
- Best next step:
  1. keep the `AtermApplication` container-closing change,
  2. add deterministic close/ownership for every raw test-created `AppContainer` before DB deletion in the relaunch-heavy android tests,
  3. then debug `feature/snippets` execution confirmation / `markExecuted()` on the real fixture path, because payload proof is present but recent history still never records.
- Follow-up from the next lifecycle pass:
  - `AppContainer` now tracks every context-backed instance created via `AppContainer.create(...)`, closes idempotently, and exposes `closeAllTrackedContainersForTesting(clearPersistentState = true)` so test resets can tear down even raw test-created containers instead of only the application-owned override/default containers.
  - `AtermApplication.resetDefaultContainerForTesting()` and `clearPersistentStateForTesting()` now route through that tracked-container shutdown, so relaunch-heavy instrumentation no longer depends on every test remembering to wire containers back through `AtermApplication` first.
  - Android tests that previously called `context.deleteDatabase("aterm.db")` / deleted the `datastore` directory directly now share `resetTestPersistenceState(context)`, which first asks the application/tracked containers to clear+close persistence owners before deleting files. This closes the remaining obvious raw-test ownership gap on the relaunch-heavy test surface.
  - DataStore ownership was also part of the leak gap: `createUserPreferencesDataStore(...)` now returns a managed wrapper with shared-instance reference counting plus explicit `clear()` and `close()` hooks, and `buildLocalDataFoundation(...)` clears/closes that managed preferences store alongside Room teardown.
  - Added a repository test proving two callers can share the same managed preferences DataStore, one caller can close without breaking the other, and `clear()` resets persisted theme/font/last-viewed preferences back to defaults.
  - Validation results after this fix:
    - `./gradlew testDebugUnitTest --max-workers=4` ✅
    - `./gradlew lintDebug --max-workers=4` ✅
    - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.jtsang4.aterm.SessionSshFixtureInstrumentedTest#favorite_repeat_use_flow_reconnects_and_runs_saved_snippet_without_any_login_or_sync_gate,io.github.jtsang4.aterm.SnippetRealFixtureExecutionInstrumentedTest --max-workers=1` ❌
  - The targeted instrumentation failure mode is unchanged but sharper now: transcript/logcat still shows real payload proof (`FAVORITE_REPEAT_SNIPPET_OK`, `ACTIVE_SESSION_REUSE_PROOF`, `LATEST_HISTORY_CONTENT_PROOF`, `RECENT_HISTORY_ALPHA/BETA`), while snippet execution history remains empty. A fresh rerun with emulator logcat capture also showed **no** `SQLiteConnection ... leaked` or `file unlinked while open: .../aterm.db` matches, which suggests the lifecycle leak gap is reduced/closed enough to move the blocker squarely to the snippet history persistence path.
