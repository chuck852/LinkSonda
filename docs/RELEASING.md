# Publishing a technical preview

[GitHub Releases](https://github.com/chuck852/LinkSonda/releases) is the distribution channel. Android CI builds and tests each change. Maintainers select one successful `main` build and publish it after separate owner acceptance.

## Prepare a candidate

Use a successful Android CI run from a push to `main` or a manual dispatch from `main`. Record the run ID, full commit SHA and APK SHA-256 printed in the **Build debug APK** step. The CI artifact expires after 14 days. If it expires, select and validate a new build, including its new checksum and signing certificate.

Use Bash, Git, an authenticated GitHub CLI, jq, GNU coreutils, tar, JDK 25 and the Android SDK Build-Tools. Set `ANDROID_BUILD_TOOLS` to the Build-Tools directory if you need to override the default under `ANDROID_HOME`. Use a new output directory outside the checkout.

```bash
./scripts/prepare-debug-preview.sh "$run_id" "$commit" "$apk_sha256" "$bundle"
```

The script verifies the commit and CI run, checks the APK digest, debug signer and permissions, and packages the license notices from the same commit. For the v0.2.0 preview it requires `POST_NOTIFICATIONS` and rejects `INTERNET`. Install and launch the staged APK on Android and review the exact files and release notes before asking for acceptance. The [v0.2.0 notes](releases/v0.2.0.md) describe the current candidate.

## Publish the accepted candidate

After the owner accepts the commit, APK checksum and notes, tag that commit. Create a draft pre-release and verify its three assets before publishing.

```bash
repo=chuck852/LinkSonda
tag=v0.2.0
git tag -a "$tag" "$commit" -m "Developer Preview $tag"
git push origin "refs/tags/$tag"
gh release create "$tag" --repo "$repo" --verify-tag --draft --prerelease \
  --title "Developer Preview $tag" --notes-file "docs/releases/$tag.md" \
  "$bundle/LinkSonda-debug.apk" "$bundle/SHA256SUMS" "$bundle/LinkSonda-license-notices.tar.gz"
gh release view "$tag" --repo "$repo"
```

Download the draft assets and verify the checksum and contents. Publish through the GitHub Releases UI or run `gh release edit "$tag" --repo "$repo" --draft=false`. Download the public APK, recheck its digest and confirm installation and launch. Permanent signing for functional releases is tracked separately.
