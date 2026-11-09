#!/usr/bin/env bash
# Prépare le moteur Playwright embarqué, avant la compilation (Gradle le lance tout seul) :
#  - Node.js pour Android (nodejs-mobile), dans third_party/nodejs-mobile ;
#  - le projet Node (engine/) avec playwright-core, dans app/build/engine-assets, d'où il part dans l'APK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NODE_MOBILE_VERSION="24.20.0-0"
NODE_MOBILE_SHA256="f5ffbaf4f2679fa9180b0758c637c2f8fc8828300f95129badf213a028fb37bb"
NODE_MOBILE_URL="https://github.com/digidem/nodejs-mobile/releases/download/v$NODE_MOBILE_VERSION/nodejs-mobile-android-$NODE_MOBILE_VERSION.zip"

NM_DIR="$ROOT/third_party/nodejs-mobile"
DOWNLOADS="$ROOT/third_party/.download"
OUT="$ROOT/app/build/engine-assets/nodejs-project"

if [ "$(cat "$NM_DIR/version.txt" 2>/dev/null)" != "$NODE_MOBILE_VERSION" ]; then
  echo "nodejs-mobile $NODE_MOBILE_VERSION…"
  mkdir -p "$DOWNLOADS"
  zip="$DOWNLOADS/nodejs-mobile-android-$NODE_MOBILE_VERSION.zip"
  if [ ! -f "$zip" ]; then
    curl -fL --retry 3 -o "$zip.part" "$NODE_MOBILE_URL"
    mv "$zip.part" "$zip"
  fi
  echo "$NODE_MOBILE_SHA256  $zip" | sha256sum -c --quiet - || { rm -f "$zip"; echo "Somme de contrôle de nodejs-mobile fausse" >&2; exit 1; }
  rm -rf "$NM_DIR"
  mkdir -p "$NM_DIR"
  unzip -q "$zip" -d "$NM_DIR"
  echo "$NODE_MOBILE_VERSION" > "$NM_DIR/version.txt"
fi

# Le projet Node change quand engine/ change : l'app le recopie quand ce tampon n'est plus le même.
stamp="$(cat "$ROOT/engine/package.json" "$ROOT/engine/package-lock.json" "$ROOT/engine/main.js" | sha256sum | cut -c1-16)"
if [ "$(cat "$OUT/stamp.txt" 2>/dev/null)" != "$stamp" ]; then
  echo "Projet Node du moteur ($stamp)…"
  rm -rf "$OUT"
  mkdir -p "$OUT"
  cp "$ROOT/engine/package.json" "$ROOT/engine/package-lock.json" "$ROOT/engine/main.js" "$OUT/"
  (cd "$OUT" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error)
  # Ce qui ne sert qu'aux outils de développement de Playwright (trace viewer, rapports, types).
  pw="$OUT/node_modules/playwright-core"
  rm -rf "$pw/lib/vite" "$pw/types" "$pw/index.d.ts" "$OUT/node_modules/.bin"
  echo "$stamp" > "$OUT/stamp.txt"
fi
