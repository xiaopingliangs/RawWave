# RawWave Project Agent Rules

Read `AI_HANDOFF.md` first for the current build, verification status, and next step.

## Build artifact rules (mandatory)

1. **Fixed delivery folder:** all user-facing APKs and ZIPs go to `D:\RawWave-APK\`. Build intermediates may stay in `dist/<runId>/`, but the final deliverable must be copied to the fixed folder.
2. **Device prefix first (English):** file names must start with `Phone_`, `Watch_`, or `Both_`.
   - `Phone_RawWave_<versionName>_<runId>.apk`
   - `Watch_RawWave_<versionName>_<runId>.apk`
   - `Both_RawWave_<versionName>_<runId>.zip`
   - Do not use the Chinese prefixes `手机_` / `手表_` / `双端_` for new deliverables; they are historical only.
3. Never use `RawWave-mobile-*` / `RawWave-wear-*` for new deliverables; those are legacy names.
4. For each run, also write `build-<runId>.json` and `SHA256SUMS-<runId>.txt` in `D:\RawWave-APK\`.
5. Update the current release note and `AI_HANDOFF.md` with the fixed paths and hashes.

Full details: `RELEASE_CONVENTIONS.md`.

## UI / size rules

- Read `.opencode/skills/rawwave-ui/SKILL.md` before UI work.
- Current UI direction: size-first plain Android Views (`:designsystem`), no Compose/Material/AppCompat runtime unless a measured spike proves watch ≤10 MB and phone ≤12 MB.
- Replace old screens; do not add a bridge screen that only opens the old UI and adds a tap.
- Diagnostics stay behind the developer/data console; first-level screens must be consumer-readable.
