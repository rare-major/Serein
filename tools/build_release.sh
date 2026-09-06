#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
keystore_path="${SEREIN_RELEASE_KEYSTORE:-$project_root/signing/serein-release.p12}"
key_alias="${SEREIN_RELEASE_KEY_ALIAS:-serein-release}"
keychain_service="Serein Android Release Signing"
keychain_account="rare-major"
expected_certificate_sha256="686980c0df4120cb3ff15c551c1b1c619cf9e48886ebe6030f10d91a9e088a14"

if [[ -z "${SEREIN_RELEASE_STORE_PASSWORD:-}" ]]; then
  SEREIN_RELEASE_STORE_PASSWORD="$(/usr/bin/security find-generic-password \
    -a "$keychain_account" -s "$keychain_service" -w)"
fi
if [[ -z "${SEREIN_RELEASE_KEY_PASSWORD:-}" ]]; then
  SEREIN_RELEASE_KEY_PASSWORD="$SEREIN_RELEASE_STORE_PASSWORD"
fi

export SEREIN_RELEASE_KEYSTORE="$keystore_path"
export SEREIN_RELEASE_KEY_ALIAS="$key_alias"
export SEREIN_RELEASE_STORE_PASSWORD
export SEREIN_RELEASE_KEY_PASSWORD

if [[ ! -f "$keystore_path" ]]; then
  echo "Release keystore not found: $keystore_path" >&2
  exit 1
fi

cd "$project_root"
./gradlew testDebugUnitTest testReleaseUnitTest lintRelease assembleRelease

sdk_dir="$(sed -n 's/^sdk.dir=//p' local.properties)"
build_tools="$(find "$sdk_dir/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
apk="$project_root/app/build/outputs/apk/release/app-release.apk"
deliverable="$project_root/Serein-android-release.apk"

signature_report="$("$build_tools/apksigner" verify --verbose --print-certs "$apk")"
printf '%s\n' "$signature_report"
grep -Fq "certificate SHA-256 digest: $expected_certificate_sha256" <<<"$signature_report" || {
  echo "Release is not signed by the expected Serein certificate." >&2
  exit 1
}
"$build_tools/aapt" dump permissions "$apk" | grep -q 'android.permission.INTERNET' && {
  echo "Release unexpectedly requests INTERNET." >&2
  exit 1
}
"$build_tools/aapt" dump xmltree "$apk" AndroidManifest.xml | grep -q 'android:debuggable.*0xffffffff' && {
  echo "Release is unexpectedly debuggable." >&2
  exit 1
}
"$build_tools/aapt" dump xmltree "$apk" AndroidManifest.xml | grep -Eq \
  'androidx\.compose\.ui\.tooling\.PreviewActivity|androidx\.activity\.ComponentActivity' && {
  echo "Release unexpectedly contains a debug tooling activity." >&2
  exit 1
}
test -s "$project_root/app/build/outputs/mapping/release/mapping.txt" || {
  echo "Release R8 mapping is missing or empty." >&2
  exit 1
}

cp "$apk" "$deliverable"
shasum -a 256 "$deliverable"
