#!/usr/bin/env bash
# Mirrors the current signed release APK into the vikastomar.life portfolio site
# (a separate, public-facing Vercel deployment) so its Serein project page always
# links to a working download, without exposing this private repo's source.
#
# The portfolio's git repo can itself be private — Vercel serves the deployed
# static output publicly regardless of the source repo's GitHub visibility, so
# this file becomes reachable at https://vikastomar.life/downloads/serein/... once
# the portfolio repo is pushed and Vercel redeploys.
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
apk="$project_root/Serein-android-release.apk"
portfolio_root="${SEREIN_PORTFOLIO_REPO:-$project_root/../Vikas-Tomar-Portfolio}"
dest_dir="$portfolio_root/public/downloads/serein"

if [[ ! -f "$apk" ]]; then
  echo "No release APK found at $apk. Run tools/build_release.sh first." >&2
  exit 1
fi
if [[ ! -d "$portfolio_root/.git" ]]; then
  echo "Portfolio repo not found at $portfolio_root. Set SEREIN_PORTFOLIO_REPO to its path." >&2
  exit 1
fi

mkdir -p "$dest_dir"
cp "$apk" "$dest_dir/Serein-android-release.apk"

version_name="$(sed -n 's/.*versionName = "\(.*\)"/\1/p' "$project_root/app/build.gradle.kts" | head -1)"

cd "$portfolio_root"
if git diff --quiet -- public/downloads/serein/Serein-android-release.apk; then
  echo "Portfolio download is already up to date with this build."
  exit 0
fi

git add public/downloads/serein/Serein-android-release.apk
git commit -m "Update Serein download to v${version_name:-latest}

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
git push

echo "Pushed. Vercel will redeploy; the download will update at:"
echo "  https://vikastomar.life/downloads/serein/Serein-android-release.apk"
