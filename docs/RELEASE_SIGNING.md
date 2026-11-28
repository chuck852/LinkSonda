# Release signing key

Official LinkSonda APKs are signed on the owner's Fedora machine. The signing key is never stored in Git, CI, a GitHub secret or an artifact. Android CI only produces an unsigned release APK.

This is the owner's step-by-step runbook. Do not create the production key until the owner has approved the storage layout, password manager entries and the clean-terminal procedure below. A temporary test key may be used to rehearse the process, but it must never sign a published release.

## 0. Fedora prerequisites and isolation

Use a trusted Fedora account and a checkout you control. The examples use JDK 25 and Android SDK Build-Tools 36.0.0.

```bash
java -version
keytool -help >/dev/null
export ANDROID_HOME="$HOME/Android/Sdk"
export APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
test -x "$APKSIGNER"
command -v gpg
command -v findmnt
command -v lsblk
```

Never run the signing script with `bash -x` or with `set -x`: expanded arguments can contain a password. The ceremony script disables xtrace at startup as a second line of defence, but do not rely on that alone.

Run the ceremony from a clean interactive terminal, not through OpenCode, an IDE agent, a chat tool, a CI job or a remote shell. The script refuses when common agent-session markers are present and requires a controlling `/dev/tty`. It also refuses to run as root.

A process running as the same Linux UID can still read the owner's files. The PKCS12 keystore is password-encrypted and the directory is mode `700`, but that does not provide hard isolation from a compromised same-UID process. For stronger separation, run signing from a dedicated OS account that does not run agents; otherwise do not run agent sessions as the signing user while the key is unlocked.

## 1. Storage layout

The owner approved two storage locations because no third offline medium is available:

| Item | Location | Purpose |
| --- | --- | --- |
| Production keystore | `$HOME/.secure/linksonda-release.p12` | Fedora system SSD |
| Backup 1, encrypted | `$HOME/.secure/backups/linksonda-release-<id>.p12.gpg` | Same Fedora SSD |
| Backup 2, encrypted | `/run/media/chuck/Other/linksonda-release-<id>.p12.gpg` | External HDD |

The first backup protects against file corruption and accidental deletion, not against loss of the SSD or the whole machine. The external HDD copy is the off-machine backup. The owner accepted the residual risk of keeping the first copy on the system SSD.

The two locations must be on different physical disks. Verify this before the ceremony:

```bash
findmnt -T "$HOME" -o SOURCE,FSTYPE
findmnt -T /run/media/chuck/Other -o SOURCE,FSTYPE
lsblk -o NAME,ROTA,MODEL,SERIAL,SIZE,MOUNTPOINTS
lsblk -s -no NAME -r /dev/sdc3
lsblk -s -no NAME -r /dev/sdg1
```

On the current machine the Fedora SSD is `sdc` (`SanDisk SSD PLUS 500GB`, `ROTA 0`) and the external HDD is `sdg` (`TOSHIBA MQ04UBF100`, `ROTA 1`). The last device name from each `lsblk -s` chain is the physical disk. The ceremony resolves those names with `findmnt` and `lsblk`, compares them, and fails closed if they are equal. It does not rely on mount paths or filesystem IDs alone.

The external HDD uses a filesystem that may not honour Unix modes; a copied encrypted backup can appear as mode `777` at the mount root. The backup is AES-256 encrypted, and the HDD is mounted `nosuid,nodev`. Do not put the production keystore on that filesystem.

Keep the key and backups out of every checkout. Do not sync `$HOME/.secure` or the HDD copy to cloud storage. Keep the passwords in the owner's password manager, separate from the media. Detach the external HDD after the ceremony.

## 2. Run the ceremony

The owner-run script is stored outside the repositories:

```text
/home/chuck/Projects/LinkSonda/.local/linksonda-signing/ceremony.sh
```

The audited SHA-256 for this revision is `3922b12b50a5d3866462a7a23a77d236e8fad5c4a73d238722fac25acfa9bead`. Check it before use:

```bash
sha256sum /home/chuck/Projects/LinkSonda/.local/linksonda-signing/ceremony.sh
```

Build an unsigned release APK first, then run from the owner's clean terminal:

```bash
bash /home/chuck/Projects/LinkSonda/.local/linksonda-signing/ceremony.sh \
  /home/chuck/Projects/LinkSonda/LinkSonda-Platform/app/build/outputs/apk/release/app-release-unsigned.apk
```

The script performs the whole ceremony:

1. Creates a PKCS12 keystore with alias `linksonda`, RSA 4096, validity 10000 days, at `$HOME/.secure/linksonda-release.p12`, mode `600`.
2. Requires a keystore password and a backup passphrase of at least 16 characters. Both are read silently from `/dev/tty`; neither is accepted in argv or the environment.
3. Creates the encrypted backup in `$HOME/.secure/backups/` with `gpg --no-symkey-cache`, AES-256 and the passphrase on stdin.
4. Asks only for the external HDD mount path, checks that it is writable, under `/run/media/$USER`, `/media/$USER` or `/mnt`, and on a different physical disk, then copies the encrypted backup there and verifies its SHA-256.
5. Restores both backups into `/dev/shm` (tmpfs), compares each certificate fingerprint with the production fingerprint, signs a disposable copy of the unsigned APK with each restored key, and verifies that both signer digests match.
6. Removes the temporary restore directories and writes `$HOME/.secure/linksonda-signing-report.txt`, mode `600`, with the public fingerprint, backup hashes, physical disk models/serials and restore confirmation.

The script makes no network requests. Secrets are passed only to local `keytool`, `gpg` and `apksigner` through stdin; `keytool` runs under `setsid` so it cannot consume the interactive terminal. The script does not print, log or place secrets in shell history. The report contains no passwords.

After the run, read the report, confirm that both backups say `restore fingerprint and test signature verified`, and detach the HDD. Do not delete either backup. If the script stops, fix the reported condition and rerun with `--resume` only when the keystore already exists.

A disposable-key rehearsal can use `LINKSONDA_SECURE_DIR` and a temporary directory on the HDD, but it must never use the production passwords or leave artefacts behind. The production ceremony is run only by the owner.

## 3. Manual restore verification

Use this if the ceremony script is unavailable or a backup must be verified during recovery. Restore into tmpfs, not into the home directory:

```bash
restore_dir=$(mktemp -d /dev/shm/linksonda-restore.XXXXXXXX)
chmod 700 "$restore_dir"

gpg --batch --no-symkey-cache \
  --output "$restore_dir/restored.p12" -d BACKUP.p12.gpg
chmod 600 "$restore_dir/restored.p12"
```

`gpg` prompts for the backup passphrase. Compare the restored fingerprint:

```bash
keytool -list -v \
  -keystore "$restore_dir/restored.p12" \
  -alias linksonda |
  sed -n 's/^[[:space:]]*SHA256: //p'
```

The output must equal the production fingerprint. A matching certificate alone does not prove that the private key can sign, so sign a disposable APK:

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

The signer digest must match. Repeat for both backups, then remove the temporary directory:

```bash
rm -rf "$restore_dir"
```

If either fingerprint or signature differs, stop and do not publish.

## 4. Sign the first official APK

Prerequisites: the commit is accepted, its Android CI run is successful, `version.properties` contains a versionCode higher than every published release, release notes exist at `docs/releases/v<versionName>.md`, and the annotated tag `v<versionName>` is pushed to the same commit.

The ceremony leaves the production keystore at `$HOME/.secure/linksonda-release.p12`.

```bash
export LINKSONDA_KEYSTORE="$HOME/.secure/linksonda-release.p12"
export LINKSONDA_KEY_ALIAS=linksonda
export LINKSONDA_SIGNER_SHA256="<SHA-256 from the report>"
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

## 6. Recovery and residual risk

- **Lost SSD or machine:** restore the keystore from the external HDD copy, verify the fingerprint and test signature as in section 3, then continue. The SSD copy is lost with the machine.
- **Lost external HDD:** the SSD copy remains on the machine; replace the external backup and test the restore again. This is not off-machine protection.
- **Lost both copies:** the key is lost. Existing installations cannot be updated. Users must uninstall the app, losing local data, and install a build signed with the new key.
- **Suspected compromise:** stop publishing, rotate the key and publish a security notice. Treat the old key as unusable for updates.
- **Same-UID agent risk:** a same-UID process can read the encrypted keystore and backups. Use a dedicated signing account for hard isolation and never run an agent with access to the key while signing. Do not treat mode `600` as isolation from the same user.

Never paste the keystore password, backup passphrase or key contents into a chat, issue, pull request, CI log or shell command.
