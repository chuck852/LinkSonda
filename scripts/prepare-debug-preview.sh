#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 4 || ! $1 =~ ^[0-9]+$ || ! $2 =~ ^[0-9a-f]{40}$ || ! $3 =~ ^[0-9a-f]{64}$ ]]; then
  printf 'Usage: %s RUN_ID COMMIT_SHA APK_SHA256 OUTPUT_DIRECTORY\n' "$0" >&2
  exit 2
fi

run_id=$1
commit=$2
checksum=$3
output=$(realpath -m -- "$4")
repo=chuck852/LinkSonda
build_tools=${ANDROID_BUILD_TOOLS:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_BUILD_TOOLS}/build-tools/36.0.0}

git fetch origin main
git merge-base --is-ancestor "$commit" origin/main
gh api "repos/$repo/actions/runs/$run_id" | jq -e --arg sha "$commit" --arg repo "$repo" '
  .head_sha == $sha and .repository.full_name == $repo and
  .head_repository.full_name == $repo and .head_branch == "main" and
  .path == ".github/workflows/android-ci.yml" and .conclusion == "success" and
  (.event == "push" or .event == "workflow_dispatch")
' >/dev/null

# Use a sibling temporary directory so the verified bundle can be moved into place.
stage=$(mktemp -d -- "$(dirname "$output")/.linksonda-preview.XXXXXXXX")
trap 'rm -rf -- "$stage"' EXIT
artifact="$stage/artifact"
bundle="$stage/bundle"
mkdir -- "$bundle"
gh run download "$run_id" --repo "$repo" --name LinkSonda-debug --dir "$artifact"

cp -- "$artifact/app-debug.apk" "$bundle/LinkSonda-debug.apk"
printf '%s  LinkSonda-debug.apk\n' "$checksum" > "$bundle/SHA256SUMS"
(cd "$bundle" && sha256sum --check SHA256SUMS)

certificates=$("$build_tools/apksigner" verify --print-certs "$bundle/LinkSonda-debug.apk")
badging=$("$build_tools/aapt2" dump badging "$bundle/LinkSonda-debug.apk")
if ! grep -Eq 'certificate DN: .*CN=Android Debug(,|$)' <<< "$certificates" ||
   ! grep -qx 'application-debuggable' <<< "$badging" ||
   ! grep -q "^package: name='io.github.chuck852.linksonda' " <<< "$badging" ||
   grep -Eq "^uses-permission[^:]*: name='android.permission.INTERNET'" <<< "$badging"; then
  printf 'The APK does not match the offline LinkSonda debug preview.\n' >&2
  exit 1
fi

notices=(LICENSE NOTICE THIRD_PARTY_NOTICES.md)
notice_paths=$(git ls-tree -r --name-only "$commit" -- third_party)
test -n "$notice_paths"
mapfile -t third_party <<< "$notice_paths"
notices+=("${third_party[@]}")
for notice in "${notices[@]}"; do
  git show "$commit:$notice" | cmp - "$artifact/$notice"
done
tar -czf "$bundle/LinkSonda-license-notices.tar.gz" -C "$artifact" -- "${notices[@]}"
mv -T --no-clobber -- "$bundle" "$output"
test ! -d "$bundle" || { printf 'Output already exists: %s\n' "$output" >&2; exit 1; }
printf 'Prepared preview from commit %s (Android CI run %s) in %s\n' "$commit" "$run_id" "$output"
