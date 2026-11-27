# Release signing key

Official LinkSonda APKs are signed on the owner's machine. The signing key is never stored in Git, CI, a GitHub secret or an artifact. The Android CI only produces an unsigned release APK.

This page is the owner's short runbook. Do not create the production key until the owner has approved the key location, password manager entry and both offline backups. A temporary test key may be used to rehearse the process, but it must never sign a published release.

## Create the production key

Create the key on a trusted machine, outside every checkout, with a password generated and stored in a password manager. The command prompts for the password; do not pass it as an argument.

```bash
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

The keystore must stay outside the repository. `prepare-release.sh` refuses a keystore inside the checkout or readable by group or others.

## Back up and verify the key

1. Make two encrypted offline copies with a tool such as `age -p` or `gpg -c`. Store them in two separate physical locations. Do not keep the password with either copy and do not sync the keystore to cloud storage.
2. Restore one copy into a new temporary directory on a clean machine. Decrypt it, then compare its certificate fingerprint with the original.
3. Record the SHA-256 fingerprint in the release environment and in the owner's records. The fingerprint is public; the key and password are not.

```bash
keytool -list -v -keystore "$LINKSONDA_KEYSTORE" -alias linksonda |
  sed -n 's/^[[:space:]]*SHA256: //p'
```

A restore is only accepted when the fingerprint matches. If it does not, stop and recover from the second copy before signing anything.

## Sign the first official APK

Prerequisites: the commit is accepted, its Android CI run is successful, `version.properties` contains a versionCode higher than every published release, release notes exist at `docs/releases/v<versionName>.md`, and the annotated tag `v<versionName>` is pushed to the same commit.

```bash
export LINKSONDA_KEYSTORE="$HOME/.secure/linksonda-release.p12"
export LINKSONDA_KEY_ALIAS=linksonda
export LINKSONDA_SIGNER_SHA256="<SHA-256 from keytool>"
export ANDROID_HOME="$HOME/Android/Sdk"

./scripts/prepare-release.sh RUN_ID COMMIT_SHA OUTPUT_DIRECTORY
```

The script downloads the unsigned release APK from the successful CI run, verifies its provenance and checksum, signs it locally, checks the signer certificate, writes `release.json`, and builds a checksummed bundle. Enter the keystore password at the prompt. The password is read from stdin and is never passed in argv or written to disk.

Create the draft release with the command printed by the script, inspect every asset, then publish. The first release-signed APK cannot update a debug-signed preview in place: uninstall the old app or use a clean device. Uninstalling removes local settings.

## Recovery

- **Lost machine:** restore the keystore from an offline copy, verify the fingerprint and continue.
- **Lost key:** existing installations cannot be updated. Users must uninstall the app, losing local data, and install a build signed with the new key. Keep the backups current.
- **Suspected compromise:** stop publishing, rotate the key and publish a security notice. Treat the old key as unusable for updates.

Never paste the keystore password or its contents into a chat, issue, pull request, CI log or shell command.
