# Historical Operational Gates

This directory contains historical operational gate scripts preserved for technical reference and provenance.

## Status: HISTORICAL / UNSUPPORTED

- `production_gate.ps1`: Historical PowerShell mirror of the production gate.
  - This script is **NOT** the current supported operational gate.
  - The current canonical release verification gate is **`scripts/production_gate.sh`**.
  - Preserved solely for historical provenance.
  - Must **NOT** be used to infer current Windows platform gate support.
  - Windows environments run verification using `scripts/production_gate.sh` via Git Bash / WSL or direct `./gradlew :app:testDebugUnitTest`.
