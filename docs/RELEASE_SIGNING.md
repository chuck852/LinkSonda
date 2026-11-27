# Release signing key

Official LinkSonda APKs are signed on the owner's Fedora machine. The signing key is never stored in Git, CI, a GitHub secret or an artifact. Android CI only produces an unsigned release APK.

This is the owner's step-by-step runbook. Do not create the production key until the owner has approved the key location, password manager entry and both offline backups. A temporary test key may be used to rehearse the process, but it must never sign a published release.

## 0. Fedora prerequisites

Use a trusted Fedora account and a checkout you control. The examples use JDK 25 and Android SDK Build-Tools 36.0.0.

```bash
java -version
keytool -help >/dev/null
export ANDROID_HOME="$HOME/Android/Sdk"
export APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
test -x "$APKSIGNER"
```

`age` is optional; install it with `sudo dnf install age` if you choose it for encrypted backups. `gpg` is normally already installed.

Never run the signing script with `bash -x` or with `set -x`: expanded arguments can contain the password. `prepare-release.sh` disables xtrace at startup as a second line of defence, but do not rely on that alone. Keep the password in a password manager and enter it only at the interactive prompt.

## 1. Create the production key

Create the key outside every checkout. `keytool` prompts for the password; do not pass it as an argument.

```bash
umask 077
mkdir -p "$HOME/.secure"
chmod 700 "$HOME/.secure"

keytool -genkeypair \
  -keystore "$HOME/.secure/linksonda-release.p12" \
  -storetype PKCS12 \
  -alias linksonda \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=LinkSonda, O=LinkSonda" \
  -v

chmod 600 "$HOME/.secure/linksonda-release.p12"
```

Use a long, randomly generated password stored in the owner's password manager. Do not reuse another password and do not put it in a command argument, repository file, chat or log. The interactive prompt reads it into a temporary shell variable only for the immediate `apksigner` pipeline.

Record the certificate fingerprint. The fingerprint is public; the key and password are not.

```bash
keytool -list -v \
  -keystore "$HOME/.secure/linksonda-release.p12" \
  -alias linksonda |
  sed -n 's/^[[:space:]]*SHA256: //p'
```

Export the fingerprint for the release environment:

```bash
export LINKSONDA_KEYSTORE="$HOME/.secure/linksonda-release.p12"
export LINKSONDA_KEY_ALIAS=linksonda
export LINKSONDA_SIGNER_SHA256="<SHA-256 printed above>"
```

## 2. Create two encrypted offline backups

Make two encrypted copies and store them in two separate physical locations. Do not keep the backup passphrase with either copy and do not sync the keystore to cloud storage.

```bash
umask 077
age -p -o "$HOME/linksonda-release.p12.age" \
  "$HOME/.secure/linksonda-release.p12"
```

If you use GnuPG instead:

```bash
gpg -c --cipher-algo AES256 \
  --output "$HOME/linksonda-release.p12.gpg" \
  "$HOME/.secure/linksonda-release.p12"
```

Copy each encrypted file to separate offline media. Keep the backup passphrase in the password manager, separate from the media.

Do not delete the original key or either backup until both restores have been tested.

## 3. Verify a restore with a test signature

A matching certificate fingerprint alone does not prove that the restored private key can sign. Restore one backup on a clean temporary directory, compare the fingerprint, then sign a disposable APK and verify the signer.

```bash
restore_dir=$(mktemp -d)
chmod 700 "$restore_dir"

age -d -o "$restore_dir/restored.p12" \
  "$HOME/linksonda-release.p12.age"
chmod 600 "$restore_dir/restored.p12"
```

For a GnuPG backup, replace the `age -d` command with:

```bash
gpg -d --output "$restore_dir/restored.p12" \
  "$HOME/linksonda-release.p12.gpg"
```

Compare the restored fingerprint:

```bash
keytool -list -v \
  -keystore "$restore_dir/restored.p12" \
  -alias linksonda |
  sed -n 's/^[[:space:]]*SHA256: //p'
```

The output must equal `LINKSONDA_SIGNER_SHA256`. Now sign a disposable APK. Build an unsigned APK first if needed:

```bash
./gradlew assembleRelease
```

Then run:

```bash
unsigned="$PWD/app/build/outputs/apk/release/app-release-unsigned.apk"
cp "$unsigned" "$restore_dir/test-unsigned.apk"

set +x
read -r -s -p 'Restored keystore password: ' restore_password
printf '\n' >&2
printf '%s\n%s\n' "$restore_password" "$restore_password" |
  "$APKSIGNER" sign \
    --ks "$restore_dir/restored.p12" \
    --ks-key-alias linksonda \
    --ks-pass stdin --key-pass stdin \
    --v4-signing-enabled false \
    --out "$restore_dir/test-signed.apk" \
    "$restore_dir/test-unsigned.apk"
unset restore_password

"$APKSIGNER" verify --print-certs "$restore_dir/test-signed.apk" |
  sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
```

The signing verification must print the same SHA-256 fingerprint. If either the certificate or the test signature differs, stop and restore from the second backup.

Clean up the disposable files:

```bash
rm -rf "$restore_dir"
```

Repeat the restore test with the second backup before trusting either copy.

## 4. Sign the first official APK

Prerequisites: the commit is accepted, its Android CI run is successful, `version.properties` contains a versionCode higher than every published release, release notes exist at `docs/releases/v<versionName>.md`, and the annotated tag `v<versionName>` is pushed to the same commit.

```bash
export LINKSONDA_KEYSTORE="$HOME/.secure/linksonda-release.p12"
export LINKSONDA_KEY_ALIAS=linksonda
export LINKSONDA_SIGNER_SHA256="<SHA-256 from keytool>"
export ANDROID_HOME="$HOME/Android/Sdk"

./scripts/prepare-release.sh RUN_ID COMMIT_SHA OUTPUT_DIRECTORY
```

The script downloads the unsigned APK from the successful `main` CI run, verifies its provenance and checksum, signs it locally, checks the signer certificate, writes `release.json`, and builds a checksummed bundle. Enter the keystore password at the prompt. The password is read from stdin and is never passed in argv, an environment variable or a temporary file.

The script also checks signing continuity. Every previous published release that has `release.json` must use the same signer certificate as `LINKSONDA_SIGNER_SHA256`; the script downloads that release's APK and verifies its actual signature. Legacy debug previews without `release.json` are excluded. If this check fails, stop: a different key would break in-place updates.

`SHA256SUMS` covers only files that are published as release assets:

- `LinkSonda-<versionName>.apk`
- `release.json`
- `LinkSonda-license-notices.tar.gz`

`release-notes.md` is copied into the bundle for the release body, but it is not an asset and is not listed in `SHA256SUMS`.

Create the draft release with the command printed by the script, inspect every asset, then publish. The first release-signed APK cannot update a debug-signed preview in place: uninstall the old app or use a clean device. Uninstalling removes local settings.

Before publishing, verify the bundle:

```bash
cd OUTPUT_DIRECTORY
sha256sum -c SHA256SUMS
```

After creating the draft, download its assets again and repeat the checksum and certificate checks. Do not publish if any listed asset is missing.

## 5. Test-only mode

`prepare-release.sh --unsigned-apk` is only for local testing and recovery rehearsal. It refuses a commit on `origin/main` and a tag that already exists on `origin`, marks `release.json` with `"testOnly": true`, and never prints a publish command. Test bundles must not be uploaded to GitHub Releases.

Official releases always use the CI-artifact mode shown in section 4.

## 6. Recovery

- **Lost machine:** restore the keystore from an offline copy, verify the fingerprint and the test signature described in section 3, then continue.
- **Lost key:** existing installations cannot be updated. Users must uninstall the app, losing local data, and install a build signed with the new key. Keep the backups current.
- **Suspected compromise:** stop publishing, rotate the key and publish a security notice. Treat the old key as unusable for updates.

Never paste the keystore password or its contents into a chat, issue, pull request, CI log or shell command.
