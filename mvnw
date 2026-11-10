#!/usr/bin/env bash
# Project-local Maven. Downloads Apache Maven once from Maven Central into ./.tools and verifies its SHA-512.
# Nothing is installed system-wide.
set -euo pipefail
V=3.9.16
DIR="$(cd "$(dirname "$0")" && pwd)/.tools"
MVN="$DIR/apache-maven-$V/bin/mvn"
if [ ! -x "$MVN" ]; then
  mkdir -p "$DIR"
  URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$V/apache-maven-$V-bin.zip"
  echo "mvnw: downloading Apache Maven $V (about 9 MB) from Maven Central" >&2
  curl -fsSL "$URL" -o "$DIR/maven-$V.zip"
  want=$(curl -fsSL "$URL.sha512" | tr -d ' \n' | cut -c1-128)
  # shasum exists on macOS, sha512sum on Linux and slim container images: use whichever is there
  if command -v sha512sum >/dev/null 2>&1; then got=$(sha512sum "$DIR/maven-$V.zip" | cut -d' ' -f1); else got=$(shasum -a 512 "$DIR/maven-$V.zip" | cut -d' ' -f1); fi
  if [ "$want" != "$got" ]; then echo "mvnw: CHECKSUM MISMATCH, refusing to use the download" >&2; exit 1; fi
  # the JDK images have no unzip: the JDK's own jar tool reads zip files too
  if command -v unzip >/dev/null 2>&1; then unzip -q -o "$DIR/maven-$V.zip" -d "$DIR"; else (cd "$DIR" && jar xf "maven-$V.zip" && chmod +x "apache-maven-$V/bin/"*); fi
  echo "mvnw: checksum verified, Maven ready in .tools/" >&2
fi
exec "$MVN" "$@"
