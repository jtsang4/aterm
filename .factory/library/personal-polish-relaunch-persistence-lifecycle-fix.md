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
