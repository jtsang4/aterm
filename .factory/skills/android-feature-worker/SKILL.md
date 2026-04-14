---
name: android-feature-worker
description: Build Android feature slices for hosts, identities, snippets, settings, and local persistence workflows.
---

# android-feature-worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use this worker for host, identity, snippet, settings, Room/DataStore-backed UI flows, and other local-only Android features that do not primarily revolve around SSH transport internals or terminal IO.

## Required Skills

None.

## Work Procedure

1. Read `mission.md`, `AGENTS.md`, and the assigned feature in `features.json` from the mission directory provided in worker bootstrap, then read the relevant repo library docs before editing code. Do not assume those mission files live at repo root on resumed missions.
2. Run `./.factory/init.sh`, then inspect the current tests and surrounding code to match project conventions.
3. Write failing tests first (red): unit tests for business rules/repositories and Compose/instrumentation tests for the specific user flow you are changing.
4. Implement the feature with local-only behavior. Preserve the mission rules: no login/sync/team code paths, no plaintext secret storage, and no ambiguous host/identity/snippet targeting.
5. Run the fastest focused validators during iteration. Before handoff, run the meaningful repo validators plus the affected instrumentation/UI tests. If you added or modified `androidTest` or other emulator-facing UI coverage, you must run the affected `connectedDebugAndroidTest` suite unless the emulator surface is unavailable, in which case the blocker must be called out explicitly in the handoff.
6. Manually verify the touched surfaces on emulator when the feature is user-facing. Prefer realistic flows rather than internal-only checks.
7. If Droid-Shield flags obviously synthetic fixture values as secrets, replace them with even more explicit non-secret placeholders and, if needed, make non-functional formatting adjustments that preserve behavior. Never swap in real-looking credentials, host keys, or private-key material just to satisfy the scanner.
8. Ensure any recent-history, favorite, or persistence behavior you changed survives app relaunch when relevant.
9. Stop any processes you started and write a precise handoff that names the flows you tested and what remained undone.

## Example Handoff

```json
{
  "salientSummary": "Implemented host CRUD + search with reusable identity linking and verified the editor, delete confirmation, and host-auth recovery path on emulator-5554.",
  "whatWasImplemented": "Added the host library list, empty state, create/edit/delete flows, search by label/address/username, duplicate-label disambiguation in list rows, and host-auth recovery actions that deep-link into identity creation/import/relink when the selected auth mode has no compatible identity.",
  "whatWasLeftUndone": "Recent-host surfacing is intentionally left for the later repeat-use polish feature; current work records only the foundational host CRUD/search behaviors.",
  "verification": {
    "commandsRun": [
      {
        "command": "JAVA_HOME=/root/.local/share/aterm-jdk-17 ANDROID_SDK_ROOT=/root/Android/Sdk PATH=/root/.local/share/aterm-jdk-17/bin:/root/Android/Sdk/cmdline-tools/latest/bin:/root/Android/Sdk/platform-tools:/root/Android/Sdk/emulator:$PATH ./gradlew testDebugUnitTest --max-workers=4",
        "exitCode": 0,
        "observation": "Host repository and editor state tests passed."
      },
      {
        "command": "JAVA_HOME=/root/.local/share/aterm-jdk-17 ANDROID_SDK_ROOT=/root/Android/Sdk PATH=/root/.local/share/aterm-jdk-17/bin:/root/Android/Sdk/cmdline-tools/latest/bin:/root/Android/Sdk/platform-tools:/root/Android/Sdk/emulator:$PATH ./gradlew connectedDebugAndroidTest --max-workers=1",
        "exitCode": 0,
        "observation": "Host create/edit/delete/search instrumentation suite passed on emulator-5554."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Created a key-auth host without any key identities available, used the inline recovery CTA to generate/select an identity, then saved the host.",
        "observed": "The flow returned to the host form with the new identity selected and the host saved successfully."
      },
      {
        "action": "Deleted a host that referenced a reusable identity and reopened the identity library.",
        "observed": "The identity remained intact and selectable for other hosts."
      }
    ]
  },
  "tests": {
    "added": [
      {
        "file": "feature/hosts/src/androidTest/.../HostEditorFlowTest.kt",
        "cases": [
          {
            "name": "missing key identity exposes inline recovery path",
            "verifies": "A host form with no compatible identity does not dead-end the user."
          },
          {
            "name": "delete host keeps reusable identity intact",
            "verifies": "Host deletion does not remove linked identity records."
          }
        ]
      }
    ]
  },
  "discoveredIssues": []
}
```

## When to Return to Orchestrator

- The feature requires SSH-session or terminal infrastructure that does not exist yet
- The intended behavior conflicts with the documented host/identity/snippet data model or security boundary
- A required validation path depends on environment capabilities that `./.factory/init.sh` and `.factory/services.yaml` cannot restore
