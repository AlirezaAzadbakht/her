#!/usr/bin/env bash
# Renders the store images from store.html with headless Chrome.
set -euo pipefail
cd "$(dirname "$0")"
chrome=${CHROME:-google-chrome}

shot() { # out width height query
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=1 --virtual-time-budget=3000 \
    --window-size="$2,$3" --screenshot="$PWD/$1" "file://$PWD/store.html?$4" 2>/dev/null
}

shot icon-512.png 512 512 "slide=icon"
for lang in fa en; do
  mkdir -p "$lang"
  shot "$lang/feature.png" 1024 500 "lang=$lang&slide=feature"
  for i in 1 2 3 4 5 6; do shot "$lang/0$i.png" 1080 1920 "lang=$lang&slide=$i"; done
done
