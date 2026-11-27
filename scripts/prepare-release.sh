#!/usr/bin/env bash
set -euo pipefail
# Bash xtrace prints expanded arguments, including the keystore password.
if [[ $- == *x* ]]; then
  set +x
  printf 'Warning: shell xtrace is disabled to protect signing credentials.\n' >&2
fi
umask 077

usage() {
  cat >&2 <<'EOF'
Usage:
  prepare-release.sh RUN_ID COMMIT_SHA OUTPUT_DIRECTORY
  prepare-release.sh --unsigned-apk APK COMMIT_SHA OUTPUT_DIRECTORY

Production mode downloads the unsigned release APK from a successful Android CI
run on main, signs it locally and writes a verified release bundle. The
--unsigned-apk mode is test-only: it refuses commits on origin/main and existing
remote tags, marks release.json with "testOnly": true, and does not print a
publish command.

Required environment:
  LINKSONDA_KEYSTORE       PKCS12 keystore outside the repository
  LINKSONDA_SIGNER_SHA256  expected signer certificate SHA-256 digest
  LINKSONDA_KEY_ALIAS      key alias (default: linksonda)
  ANDROID_HOME or ANDROID_BUILD_TOOLS
EOF
}

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

read_badging() {
  local badging=$1 package_line
  package_line=$(sed -n '/^package: /{p;q;}' <<< "$badging")
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$package_line")" \
    "$(sed -n "s/.* versionCode='\([0-9]*\)'.*/\1/p" <<< "$package_line")" \
    "$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<< "$package_line")" \
    "$(awk -F"'" '/^(minSdkVersion|sdkVersion):/ {print $2; exit}' <<< "$badging")" \
    "$(awk -F"'" '/^targetSdkVersion:/ {print $2; exit}' <<< "$badging")"
}

if [[ $# == 4 && $1 == "--unsigned-apk" ]]; then
  mode=local
  unsigned_input=$2
  commit=$3
  output_arg=$4
elif [[ $# == 3 && $1 =~ ^[0-9]+$ && $2 =~ ^[0-9a-f]{40}$ ]]; then
  mode=ci
  run_id=$1
  commit=$2
  output_arg=$3
else
  usage
  exit 2
fi

[[ $commit =~ ^[0-9a-f]{40}$ ]] || fail "COMMIT_SHA must be a full lowercase commit SHA"

repo=chuck852/LinkSonda
application_id=io.github.chuck852.linksonda
key_alias=${LINKSONDA_KEY_ALIAS:-linksonda}
expected_signer=${LINKSONDA_SIGNER_SHA256:-}
build_tools=${ANDROID_BUILD_TOOLS:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_BUILD_TOOLS}/build-tools/36.0.0}
apksigner="$build_tools/apksigner"
aapt2="$build_tools/aapt2"

for command in git gh jq sha256sum tar unzip realpath mktemp stat sed grep awk tr date; do
  require_command "$command"
done
[[ -x "$apksigner" ]] || fail "apksigner not found at $apksigner"
[[ -x "$aapt2" ]] || fail "aapt2 not found at $aapt2"

[[ -n ${LINKSONDA_KEYSTORE:-} ]] || fail "LINKSONDA_KEYSTORE is not set"
keystore=$(realpath -- "$LINKSONDA_KEYSTORE")
[[ -f "$keystore" ]] || fail "Keystore not found: $keystore"
repo_root=$(git rev-parse --show-toplevel)
case "$keystore" in
  "$repo_root"/*) fail "Keystore must be outside the repository" ;;
esac
keystore_permissions=$(stat -c '%a' -- "$keystore")
if (( (0$keystore_permissions & 077) != 0 )); then
  fail "Keystore permissions must not allow group or other access (chmod 600)"
fi

expected_signer=$(tr -d ':' <<< "$expected_signer" | tr '[:upper:]' '[:lower:]')
[[ $expected_signer =~ ^[0-9a-f]{64}$ ]] ||
  fail "LINKSONDA_SIGNER_SHA256 must be a full SHA-256 digest"

output=$(realpath -m -- "$output_arg")
[[ ! -e "$output" ]] || fail "Output already exists: $output"
case "$output" in
  "$repo_root"/*) fail "Output must be outside the repository" ;;
esac
mkdir -p -- "$(dirname "$output")"
stage=$(mktemp -d -- "$(dirname "$output")/.linksonda-release.XXXXXXXX")
trap 'rm -rf -- "$stage"' EXIT
bundle="$stage/bundle"
mkdir -- "$bundle"

git cat-file -e "$commit^{commit}" 2>/dev/null || fail "Commit not found: $commit"
git fetch origin main
if [[ $mode == ci ]]; then
  git merge-base --is-ancestor "$commit" origin/main ||
    fail "Commit must be on origin/main"
else
  git merge-base --is-ancestor "$commit" HEAD ||
    fail "Commit must be reachable from HEAD"
  if git merge-base --is-ancestor "$commit" origin/main; then
    fail "--unsigned-apk is test-only; commits on origin/main require CI mode"
  fi
  printf 'Warning: --unsigned-apk is test-only; CI provenance is not available.\n' >&2
fi

git fetch --tags origin

git show "$commit:version.properties" > "$stage/version.properties" ||
  fail "version.properties not found at $commit"
version_code=$(sed -n 's/^versionCode=//p' "$stage/version.properties")
version_name=$(sed -n 's/^versionName=//p' "$stage/version.properties")
[[ $version_code =~ ^[1-9][0-9]*$ ]] ||
  fail "version.properties must define a positive integer versionCode"
[[ $version_name =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] ||
  fail "version.properties has an unsafe versionName"
tag="v$version_name"

tag_ref="refs/tags/$tag"
git rev-parse -q --verify "$tag_ref" >/dev/null || fail "Tag not found: $tag"
[[ $(git cat-file -t "$tag_ref") == tag ]] || fail "Tag must be annotated: $tag"
tag_commit=$(git rev-parse "$tag_ref^{commit}")
[[ $tag_commit == "$commit" ]] || fail "Tag $tag does not point to $commit"
remote_refs=$(git ls-remote --tags origin "$tag_ref" "$tag_ref^{}")
if [[ $mode == ci ]]; then
  remote_commit=$(awk -v ref="$tag_ref^{}" '$2 == ref {print $1}' <<< "$remote_refs")
  [[ $remote_commit == "$commit" ]] || fail "Push annotated tag $tag to origin first"
elif [[ -n $remote_refs ]]; then
  fail "Test mode cannot use tag $tag because it already exists on origin"
fi

notes_path="docs/releases/$tag.md"
git show "$commit:$notes_path" > "$stage/release-notes.md" ||
  fail "Release notes not found: $notes_path"
[[ -s "$stage/release-notes.md" ]] || fail "Release notes are empty: $notes_path"

# Reject any existing release (including a draft) for this tag and establish the
# highest published versionCode. Legacy previews have no release.json, so their
# published APK is inspected instead.
max_code=0
max_tag=
functional_releases=0
metadata_dir="$stage/metadata"
legacy_dir="$stage/legacy"
mkdir -- "$metadata_dir" "$legacy_dir"
releases_tsv=$(gh api --paginate "repos/$repo/releases?per_page=100" --jq '
  .[] | [.tag_name, (.draft|tostring), (any(.assets[]; .name == "release.json")|tostring),
  ([.assets[] | select(.name|endswith(".apk")) | .name][0] // "")] | @tsv
') || fail "Cannot read published releases"
while IFS=$'\t' read -r release_tag draft has_metadata apk_name; do
  [[ -n $release_tag ]] || continue
  [[ $release_tag != "$tag" ]] || fail "Release $tag already exists"
  if [[ $draft == true ]]; then
    continue
  fi

  if [[ $has_metadata == true ]]; then
    release_dir=$(mktemp -d -- "$metadata_dir/release.XXXXXXXX")
    gh release download "$release_tag" --repo "$repo" --pattern release.json \
      --dir "$release_dir" >/dev/null
    metadata=$(cat "$release_dir/release.json")
    jq -e --arg tag "$release_tag" --arg app "$application_id" '
      .schemaVersion == 1 and .tag == $tag and .applicationId == $app and
      (.versionName | type) == "string" and
      (.versionCode | type) == "number" and
      (.versionCode | floor) == .versionCode and
      .versionCode > 0 and .versionCode < 2147483648 and
      (.minSdk | type) == "number" and
      (.minSdk | floor) == .minSdk and .minSdk > 0 and
      (.apk.size | type) == "number" and
      (.apk.size | floor) == .apk.size and .apk.size > 0 and
      (.testOnly // false) == false and
      (.channel == "stable" or .channel == "preview") and
      (.apk.name | test("^[A-Za-z0-9._-]+$")) and
      (.apk.sha256 | test("^[0-9a-f]{64}$")) and
      (.signerCertificateSha256 | test("^[0-9a-f]{64}$"))
    ' <<< "$metadata" >/dev/null || fail "Incomplete release.json in $release_tag"
    release_code=$(jq -er '.versionCode' <<< "$metadata") ||
      fail "Invalid release.json in $release_tag"
    release_name=$(jq -er '.versionName' <<< "$metadata") ||
      fail "Invalid release.json in $release_tag"
    release_signer=$(jq -er '.signerCertificateSha256' <<< "$metadata")
    release_apk_name=$(jq -er '.apk.name' <<< "$metadata")
    [[ $release_tag == "v$release_name" ]] ||
      fail "release.json versionName does not match tag $release_tag"

    prior_dir=$(mktemp -d -- "$metadata_dir/apk.XXXXXXXX")
    gh release download "$release_tag" --repo "$repo" --pattern "$release_apk_name" \
      --dir "$prior_dir" >/dev/null
    prior_apk="$prior_dir/$release_apk_name"
    [[ -f $prior_apk ]] || fail "Published APK is missing from $release_tag"
    prior_certs=$("$apksigner" verify --print-certs "$prior_apk" 2>&1) ||
      fail "Published APK failed signature verification: $release_tag"
    [[ $(grep -c '^Signer #[0-9][0-9]* certificate DN: ' <<< "$prior_certs") == 1 ]] ||
      fail "Published APK must have exactly one signer: $release_tag"
    prior_signer=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$prior_certs" |
      tr -d ':' | tr '[:upper:]' '[:lower:]')
    [[ $prior_signer == "$release_signer" ]] ||
      fail "Published APK signer does not match release.json: $release_tag"
    if [[ $mode == ci && $prior_signer != "$expected_signer" ]]; then
      fail "Signing key changed: $release_tag was signed with a different key; refusing to break update continuity"
    fi
    functional_releases=$((functional_releases + 1))
  elif [[ -n $apk_name ]]; then
    release_dir=$(mktemp -d -- "$legacy_dir/release.XXXXXXXX")
    gh release download "$release_tag" --repo "$repo" --pattern "$apk_name" \
      --dir "$release_dir" >/dev/null
    legacy_apk="$release_dir/$apk_name"
    legacy_badging=$("$aapt2" dump badging "$legacy_apk")
    IFS=$'\t' read -r legacy_pkg release_code _ _ _ <<< "$(read_badging "$legacy_badging")" || true
    [[ $legacy_pkg == "$application_id" ]] ||
      fail "Published APK $release_tag has the wrong applicationId"
    [[ $release_code =~ ^[1-9][0-9]*$ ]] ||
      fail "Cannot read versionCode from published APK $release_tag"
  else
    continue
  fi

  if (( release_code > max_code )); then
    max_code=$release_code
    max_tag=$release_tag
  fi
done <<< "$releases_tsv"

(( version_code > max_code )) ||
  fail "versionCode $version_code must be greater than published maximum $max_code${max_tag:+ ($max_tag)}"
printf 'Functional releases checked for signer continuity: %s\n' "$functional_releases" >&2

unsigned_sha=
if [[ $mode == ci ]]; then
  gh api "repos/$repo/actions/runs/$run_id" | jq -e --arg sha "$commit" --arg repo "$repo" '
    .head_sha == $sha and .repository.full_name == $repo and
    .head_repository.full_name == $repo and .head_branch == "main" and
    .path == ".github/workflows/android-ci.yml" and .conclusion == "success" and
    (.event == "push" or .event == "workflow_dispatch")
  ' >/dev/null || fail "CI run $run_id is not a successful main build of $commit"

  artifact="$stage/artifact"
  gh run download "$run_id" --repo "$repo" --name LinkSonda-release-unsigned \
    --dir "$artifact"
  unsigned="$artifact/LinkSonda-release-unsigned.apk"
  checksums="$artifact/SHA256SUMS"
  [[ -f $unsigned ]] || fail "Unsigned release APK is missing from CI artifact"
  [[ -f $checksums ]] || fail "SHA256SUMS is missing from CI artifact"
  mapfile -t checksum_lines < "$checksums"
  [[ ${#checksum_lines[@]} == 1 &&
     ${checksum_lines[0]} =~ ^[0-9a-f]{64}[[:space:]]+LinkSonda-release-unsigned\.apk$ ]] ||
    fail "Unexpected SHA256SUMS in CI artifact"
  (cd "$artifact" && sha256sum --check --strict SHA256SUMS >/dev/null) ||
    fail "Unsigned release APK does not match its CI checksum"
  unsigned_sha=$(cut -d' ' -f1 <<< "${checksum_lines[0]}")
else
  unsigned=$(realpath -- "$unsigned_input")
  [[ -f $unsigned ]] || fail "Unsigned APK not found: $unsigned"
  unsigned_sha=$(sha256sum -- "$unsigned" | cut -d' ' -f1)
fi

if "$apksigner" verify "$unsigned" >/dev/null 2>&1; then
  fail "APK is already signed"
fi
if unzip -Z1 "$unsigned" | grep -Eiq '^META-INF/.*\.(SF|RSA|DSA|EC)$'; then
  fail "APK contains JAR signature files"
fi

unsigned_badging=$("$aapt2" dump badging "$unsigned")
IFS=$'\t' read -r app_package apk_code apk_name app_min_sdk app_target_sdk \
  <<< "$(read_badging "$unsigned_badging")" || true
[[ $app_package == "$application_id" ]] || fail "APK has the wrong applicationId"
[[ $apk_code == "$version_code" ]] || fail "APK versionCode does not match version.properties"
[[ $apk_name == "$version_name" ]] || fail "APK versionName does not match version.properties"
[[ $app_min_sdk =~ ^[1-9][0-9]*$ ]] || fail "Cannot read APK minSdk"
if grep -qx 'application-debuggable' <<< "$unsigned_badging"; then
  fail "Refusing to sign a debuggable APK"
fi

signed="$stage/LinkSonda-$version_name.apk"
set +x
if [[ -t 0 ]]; then
  read -r -s -p 'Keystore password: ' keystore_password
else
  IFS= read -r -s keystore_password || true
fi
printf '\n' >&2
[[ -n ${keystore_password:-} ]] || fail "Keystore password cannot be empty"
# apksigner reads the KeyStore password and then the key password from stdin.
# The password never appears in argv, the environment, or a temporary file.
printf '%s\n%s\n' "$keystore_password" "$keystore_password" |
  "$apksigner" sign --ks "$keystore" --ks-key-alias "$key_alias" \
    --ks-pass stdin --key-pass stdin --v4-signing-enabled false \
    --out "$signed" "$unsigned"
keystore_password=

certificates=$("$apksigner" verify --verbose --print-certs "$signed" 2>&1) ||
  fail "Signed APK failed apksigner verification"
[[ $(grep -c '^Signer #[0-9][0-9]* certificate DN: ' <<< "$certificates") == 1 ]] ||
  fail "Signed APK must have exactly one signer"
signer_sha=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$certificates" |
  tr -d ':' | tr '[:upper:]' '[:lower:]')
[[ $signer_sha == "$expected_signer" ]] ||
  fail "Signer certificate does not match LINKSONDA_SIGNER_SHA256"
grep -Eq '^Verified using v[23] scheme .*: true$' <<< "$certificates" ||
  fail "Signed APK lacks an APK Signature Scheme v2/v3 signature"

signed_badging=$("$aapt2" dump badging "$signed")
IFS=$'\t' read -r signed_package signed_code signed_name signed_min_sdk signed_target_sdk \
  <<< "$(read_badging "$signed_badging")" || true
[[ $signed_package == "$app_package" && $signed_code == "$apk_code" &&
   $signed_name == "$apk_name" && $signed_min_sdk == "$app_min_sdk" &&
   $signed_target_sdk == "$app_target_sdk" ]] ||
  fail "Signing changed APK metadata"
if grep -qx 'application-debuggable' <<< "$signed_badging"; then
  fail "Signed APK is debuggable"
fi

channel=stable
if [[ $version_name == *-* ]]; then
  channel=preview
fi
test_only=false
if [[ $mode == local ]]; then
  test_only=true
fi
generated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
apk_sha=$(sha256sum -- "$signed" | cut -d' ' -f1)
apk_size=$(stat -c '%s' -- "$signed")
jq -n --arg tag "$tag" --arg commit "$commit" --arg app "$application_id" \
  --arg name "$version_name" --arg channel "$channel" --argjson code "$version_code" \
  --argjson minSdk "$signed_min_sdk" --arg apkName "LinkSonda-$version_name.apk" \
  --argjson apkSize "$apk_size" --arg apkSha "$apk_sha" --arg signer "$signer_sha" \
  --arg generatedAt "$generated_at" --argjson testOnly "$test_only" '
    {
      schemaVersion: 1,
      tag: $tag,
      commit: $commit,
      applicationId: $app,
      versionName: $name,
      versionCode: $code,
      channel: $channel,
      minSdk: $minSdk,
      apk: {name: $apkName, size: $apkSize, sha256: $apkSha},
      signerCertificateSha256: $signer,
      generatedAt: $generatedAt
    } + (if $testOnly then {testOnly: true} else {} end)
  ' > "$bundle/release.json"
jq -e --argjson code "$version_code" --argjson minSdk "$signed_min_sdk" \
  --arg sha "$apk_sha" --arg signer "$signer_sha" --argjson testOnly "$test_only" '
    .schemaVersion == 1 and .versionCode == $code and .minSdk == $minSdk and
    .apk.sha256 == $sha and .apk.size > 0 and .signerCertificateSha256 == $signer and
    (.testOnly // false) == $testOnly
  ' "$bundle/release.json" >/dev/null || fail "Generated release.json is inconsistent"

notices=(LICENSE NOTICE THIRD_PARTY_NOTICES.md)
mapfile -t third_party < <(git ls-tree -r --name-only "$commit" -- third_party)
[[ ${#third_party[@]} -gt 0 ]] || fail "Third-party license files not found at $commit"
notices_dir="$stage/notices"
mkdir -- "$notices_dir"
for notice in "${notices[@]}" "${third_party[@]}"; do
  git cat-file -e "$commit:$notice" 2>/dev/null || fail "Missing license file: $notice"
  mkdir -p -- "$notices_dir/$(dirname "$notice")"
  git show "$commit:$notice" > "$notices_dir/$notice"
done
tar -czf "$bundle/LinkSonda-license-notices.tar.gz" -C "$notices_dir" -- \
  "${notices[@]}" "${third_party[@]}"

mv -- "$signed" "$bundle/LinkSonda-$version_name.apk"
cp -- "$stage/release-notes.md" "$bundle/release-notes.md"
(
  cd "$bundle"
  sha256sum -- "LinkSonda-$version_name.apk" release.json \
    LinkSonda-license-notices.tar.gz > SHA256SUMS
  sha256sum --check --strict SHA256SUMS >/dev/null
)

mv -T --no-clobber -- "$bundle" "$output"
test ! -d "$bundle" || fail "Output already exists: $output"

printf 'Prepared LinkSonda %s (versionCode %s, %s) from commit %s\n' \
  "$version_name" "$version_code" "$channel" "$commit"
printf 'Unsigned APK SHA-256: %s\n' "$unsigned_sha"
printf 'Signed APK SHA-256:   %s\n' "$apk_sha"
printf 'Signer SHA-256:       %s\n' "$signer_sha"
printf 'Published maximum:    versionCode %s%s\n' "$max_code" "${max_tag:+ ($max_tag)}"
printf 'Bundle:               %s\n' "$output"
if [[ $mode == local ]]; then
  printf '\nTEST ONLY: this bundle has "testOnly": true and must not be published.\n' >&2
  exit 0
fi
printf '\nCreate a draft release after reviewing the bundle:\n'
printf 'gh release create %q --repo %q --verify-tag --draft' "$tag" "$repo"
if [[ $channel == preview ]]; then
  printf ' --prerelease'
fi
printf ' --title "LinkSonda %s" --notes-file %q \\\n' "$version_name" "$output/release-notes.md"
printf '  %q %q %q %q\n' \
  "$output/LinkSonda-$version_name.apk" "$output/release.json" \
  "$output/SHA256SUMS" "$output/LinkSonda-license-notices.tar.gz"
