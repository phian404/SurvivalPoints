# Conventions

## Guards (added after a mistake repeated)
- Report a test, build or fix as passing only after running it in the same task; quote the command and its result, and write "Not verified:" otherwise.
- In Auto mode the harness keeps asking to re-verify already-pushed code; run the check once on the final tree and state clearly that the files themselves are unchanged and why, instead of editing files back and forth.
- GameTests in this repo run with the client-only `runtimeOnly` mods (oculus/embeddium/ragdollified/cruor/tacz) stripped: they do not resolve in the cloud. Do it out of tree with an init script — do not edit the committed `build.gradle`:
  `sh gradlew runGameTestServer --offline --init-script /tmp/noclient.gradle`, where the script is
  `gradle.projectsEvaluated { gradle.rootProject.allprojects { p -> p.configurations.configureEach { c -> if (!c.name.toLowerCase().contains('runtime')) return; try { c.withDependencies { deps -> deps.removeAll { d -> d.group == 'blank' } } } catch (Throwable ignored) { } } } }`.
  The tree stays byte-identical, so the checked files are exactly the committed ones.

## GameTest
- Run with `sh gradlew runGameTestServer --offline`; templates live in `run/gameteststructures/` (not resources) at runtime.
- Test scenes build farms in code (Farmland + WHEAT AGE=7) and register camp POIs via `CampRecord.replacePois(...)`; farmer session survives pause and takes the next task via `takeForType` — do not "fix" IDLE between tasks.
