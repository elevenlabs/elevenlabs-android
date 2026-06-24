# Release

Create a new release of the ElevenLabs Android SDK and publish it to Maven Central.

## Arguments

The user may provide a version number (for example, `0.9.2`). If not provided:
1. Run `git describe --tags --abbrev=0` to get the latest tag.
2. Inspect unreleased changes with `git log --oneline --decorate <latest-tag>..main`.
3. Pick the next semantic version from the changes:
   - Patch for fixes and dependency bumps.
   - Minor for new SDK features.
   - Major for breaking public API changes.
4. Confirm the version with the user before proceeding.

## Version location

The version string must be updated in exactly this file:

| File | Pattern |
|------|---------|
| `elevenlabs-sdk/build.gradle.kts` | `version = "X.Y.Z"` |

This value is used for:
- Maven coordinates: `io.elevenlabs:elevenlabs-android:X.Y.Z`
- `BuildConfig.SDK_VERSION`, sent as the SDK version in token requests

Do not update `example-app`'s `versionName`; it is the sample app version, not the SDK release version. The README intentionally uses `<latest>` instead of a pinned SDK version.

## Publishing setup

Publishing is handled by `.github/workflows/publish.yml` when a GitHub Release is published. The workflow:
1. Checks out the repository.
2. Uses JDK 17.
3. Runs `./gradlew elevenlabs-sdk:build`.
4. Runs `./gradlew elevenlabs-sdk:test`.
5. Runs `./gradlew elevenlabs-sdk:publishToMavenCentral`.
6. Uploads SDK artifacts.

The Maven Central publish step requires these repository secrets:
- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_IN_MEMORY_KEY`
- `SIGNING_KEY_ID`
- `SIGNING_PASSWORD`

Do not publish manually with `publishToMavenCentral` unless the user explicitly asks and the required signing/Maven Central credentials are available. The normal publishing path is the GitHub Release workflow.

## What previous releases show

- Recent release PRs such as `v0.9.1`, `v0.9.0`, `v0.7.3`, `v0.7.2`, and `v0.7.1` only changed `elevenlabs-sdk/build.gradle.kts`.
- Feature releases may include the version bump in the feature PR itself, as `v0.8.0` did.
- Publishing is triggered by publishing the GitHub Release for the `vX.Y.Z` tag; historical `Publish SDK` workflow runs for releases have succeeded from that event.
- No follow-up commit is normally required after publishing. Follow-up commits after tags are new unreleased fixes/features for the next release.

## Steps

1. **Verify clean state**: Run `git status` on the `main` branch. Abort if there are uncommitted changes or if not on `main`.

2. **Update main**:
   ```bash
   git pull origin main
   ```

3. **Determine release contents**:
   ```bash
   latest_tag=$(git describe --tags --abbrev=0)
   git log --oneline --decorate "$latest_tag"..main
   gh release view "$latest_tag"
   ```
   Use this to confirm the next version and release scope.

4. **Update the SDK version**: Edit `elevenlabs-sdk/build.gradle.kts` and replace the existing `version = "..."` with `version = "X.Y.Z"`.

5. **Build and test**:
   ```bash
   ./gradlew elevenlabs-sdk:build
   ./gradlew elevenlabs-sdk:test
   ```
   If the change is broad or affects the example app, also run:
   ```bash
   ./gradlew build
   ./gradlew test
   ```

6. **Optional local publishing check**: If signing credentials are available locally, run:
   ```bash
   ./gradlew elevenlabs-sdk:publishToMavenLocal
   ```

7. **Commit**: Stage only the version file and commit:
   ```bash
   git add elevenlabs-sdk/build.gradle.kts
   git commit -m "chore: bump version to X.Y.Z"
   ```

8. **Search for the old version**:
   ```bash
   rg "OLD_VERSION"
   ```
   Verify any remaining references are intentional.

9. **Confirm with user**: Before pushing, show the user a summary:
   - Version being released.
   - Commits included since the previous tag.
   - The fact that publishing a GitHub Release will trigger Maven Central publication.
   - The exact commands that will push `main`, create/push the tag, and publish the release.

10. **Push and tag**:
    ```bash
    git push origin main
    git tag vX.Y.Z
    git push origin vX.Y.Z
    ```

11. **Create the GitHub Release**:
    ```bash
    gh release create vX.Y.Z --title "vX.Y.Z" --generate-notes
    ```
    This publishes the release immediately and triggers the `Publish SDK` workflow.

12. **Monitor publishing**:
    ```bash
    gh run list --workflow="Publish SDK" --limit 5
    gh run watch <run-id>
    ```
    If the workflow fails:
    ```bash
    gh run view <run-id> --log-failed
    ```

13. **Verify Maven Central**: After the workflow succeeds, check the artifact:
    ```bash
    curl -fsSL "https://repo1.maven.org/maven2/io/elevenlabs/elevenlabs-android/X.Y.Z/elevenlabs-android-X.Y.Z.pom"
    ```
    Maven Central propagation can lag briefly, so retry before treating a missing artifact as a failed publish.

14. **Report**: Share the GitHub Release URL, publish workflow run URL, and Maven coordinate:
    ```
    io.elevenlabs:elevenlabs-android:X.Y.Z
    ```
