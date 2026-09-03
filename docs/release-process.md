# Keepix Release Process

## Versioning

| Field | Scheme |
|---|---|
| `versionCode` | Integer, monotonically increasing (1, 2, 3...) |
| `versionName` | SemVer: `MAJOR.MINOR.PATCH` (e.g., `1.0.0`, `1.0.1`, `1.1.0`) |

**Current:** `versionCode = 1`, `versionName = "1.0.0"`

## Build Artifact

```bash
./gradlew clean bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

## Signing

- **Keystore:** `~/keystores/keepix-release.jks` (alias `keepix-release`, validity 25y)
- **Secrets** in `~/.gradle/gradle.properties` (user-home, outside the repo — the
  repo's own `gradle.properties` is tracked and must never hold them):
  - `KEEPIX_KEYSTORE_FILE`
  - `KEEPIX_KEYSTORE_PASSWORD`
  - `KEEPIX_KEY_ALIAS`
  - `KEEPIX_KEY_PASSWORD`

## Play Console Rollout

| Track | Audience | Duration | Action |
|---|---|---|---|
| Internal Test | You (1 tester) | 1 day | Smoke test signed AAB end-to-end |
| Production (Staged) | 5% → 25% → 100% | 24–72h per stage | Monitor ANR/crash rate in Play Console → "Halt rollout" if regression |

**To advance stages:** Play Console → Release dashboard → "Edit release" → Increase percentage.

## Hotfix Flow

1. Fix bug on `master`
2. `versionCode += 1`, `versionName = "1.0.1"` (patch bump)
3. `./gradlew clean bundleRelease`
4. Upload new AAB to **same staged rollout** (Play Console replaces artifact)
5. Resume rollout

## Post-Launch Checklist

- [ ] Privacy policy URL correct in Data Safety form
- [ ] Content Rating = Everyone (IARC)
- [ ] Store listing: icon, 3 screenshots, copy from `docs/store-listing/`
- [ ] Internal test passes on ≥2 devices/API levels
- [ ] Production staged at 5%