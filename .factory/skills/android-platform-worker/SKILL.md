---
name: android-platform-worker
description: Bootstrap and evolve the Android project/toolchain, app shell, and shared platform infrastructure.
---

# android-platform-worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use this worker for Android bootstrap, Gradle/build setup, project/module scaffolding, app shell/navigation, test harness setup, DI wiring, and other shared platform changes that unblock later feature workers.

## Required Skills

None.

## Work Procedure

1. Read `mission.md`, `AGENTS.md`, `.factory/services.yaml`, and relevant `.factory/library/*.md` files before changing anything.
2. Run `./.factory/init.sh` first. If bootstrap tooling is broken, fix only the repo-controlled setup needed for this feature; if the environment cannot be restored within mission boundaries, return to the orchestrator.
3. For any code-bearing platform change, add or update the smallest failing test/smoke check first (red). For greenfield bootstrap, create the missing scaffold/test harness and demonstrate an initially failing or absent command before making it pass.
4. Implement the platform change in the minimal repo surface that future workers can build on. Keep generated boilerplate lean and aligned with mission boundaries.
5. Run the fastest relevant validators during iteration. Before handoff, run the repo baseline validators from `.factory/services.yaml` that are meaningful for the current state (`test`, `lint`, `build`; if the app is runnable, add `instrumentation` or an equivalent smoke check).
6. If you touched the app shell or navigation, manually boot the emulator and verify the affected surfaces render without crashes.
7. Stop any emulator or long-running processes you started. Do not leave orphaned Gradle daemons, watch tasks, or emulator instances you launched for ad hoc checks.
8. Write a thorough handoff with concrete commands, outputs, and any bootstrap caveats future workers need.

## Example Handoff

```json
{
  "salientSummary": "Bootstrapped the Android project, added Gradle wrapper + app shell navigation, and proved the repo can build and launch on emulator-5554 after running ./.factory/init.sh.",
  "whatWasImplemented": "Created the initial multi-module Android project scaffold, wired Compose navigation for hosts/identities/sessions/snippets/settings, added baseline unit and instrumentation test harnesses, and updated local bootstrap files so JDK/SDK/AVD setup is reproducible from the repo.",
  "whatWasLeftUndone": "Manual validation of SSH-specific flows is deferred to later SSH/terminal features because no connection stack exists yet.",
  "verification": {
    "commandsRun": [
      {
        "command": "./.factory/init.sh",
        "exitCode": 0,
        "observation": "Installed/verified JDK, Android SDK packages, and created AVD atermApi35 idempotently."
      },
      {
        "command": "JAVA_HOME=/root/.local/share/aterm-jdk-17 ANDROID_SDK_ROOT=/root/Android/Sdk PATH=/root/.local/share/aterm-jdk-17/bin:/root/Android/Sdk/cmdline-tools/latest/bin:/root/Android/Sdk/platform-tools:/root/Android/Sdk/emulator:$PATH ./gradlew testDebugUnitTest --max-workers=4",
        "exitCode": 0,
        "observation": "Baseline JVM tests passed."
      },
      {
        "command": "JAVA_HOME=/root/.local/share/aterm-jdk-17 ANDROID_SDK_ROOT=/root/Android/Sdk PATH=/root/.local/share/aterm-jdk-17/bin:/root/Android/Sdk/cmdline-tools/latest/bin:/root/Android/Sdk/platform-tools:/root/Android/Sdk/emulator:$PATH ./gradlew assembleDebug --max-workers=4",
        "exitCode": 0,
        "observation": "Debug build assembled successfully."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Booted emulator-5554, installed the debug app, and launched the app shell.",
        "observed": "The app opened to the local-only shell and rendered placeholder navigation for hosts, identities, sessions, snippets, and settings without crashing."
      }
    ]
  },
  "tests": {
    "added": [
      {
        "file": "app/src/test/java/.../AppShellSmokeTest.kt",
        "cases": [
          {
            "name": "app shell exposes expected top-level destinations",
            "verifies": "The greenfield scaffold renders the mission's in-scope navigation entry points."
          }
        ]
      }
    ]
  },
  "discoveredIssues": []
}
```

## When to Return to Orchestrator

- JDK/Android SDK/emulator bootstrap cannot be restored within repo-controlled setup
- The chosen Android stack/library decisions appear incompatible with the mission and need architectural re-evaluation
- A platform change would require breaking the documented mission boundaries or introducing out-of-scope cloud/account functionality
