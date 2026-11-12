#!/usr/bin/env bash
# Embeds external .cue sheets into their FLAC images (as the CUESHEET tag), so Slurpdrome can
# show such albums as separate tracks. Only metadata is touched — audio is not re-encoded.
#
# Usage:   tools/embed-cue.sh /path/to/music            # do it
#          tools/embed-cue.sh /path/to/music --dry-run  # just show what would happen
# Needs:   metaflac (package "flac"), iconv, file
set -euo pipefail
ROOT="${1:?usage: $0 /path/to/music [--dry-run]}"
DRY="${2:-}"
done=0; skipped=0; missing=0

while IFS= read -r -d '' cue; do
  dir="$(dirname "$cue")"
  # The image is named in the cue (FILE "x.flac" WAVE); fall back to the cue's own base name.
  ref="$(grep -a -m1 -i '^[[:space:]]*FILE' "$cue" | sed -E 's/^[[:space:]]*FILE[[:space:]]+"?([^"]*)"?.*/\1/' | tr -d '\r')"
  flac=""
  for cand in "$dir/$ref" "$dir/${ref%.*}.flac" "${cue%.*}.flac"; do
    [[ -f "$cand" && "${cand,,}" == *.flac ]] && { flac="$cand"; break; }
  done
  if [[ -z "$flac" ]]; then echo "no flac for: $cue"; missing=$((missing+1)); continue; fi
  if metaflac --show-tag=CUESHEET "$flac" | grep -q .; then skipped=$((skipped+1)); continue; fi

  # Russian rips are often CP1251 — tags must be UTF-8.
  charset="$(file -bi "$cue" | sed -E 's/.*charset=//')"
  tmp="$(mktemp)"
  if [[ "$charset" == "utf-8" || "$charset" == "us-ascii" ]]; then cp "$cue" "$tmp"; else iconv -f CP1251 -t UTF-8 "$cue" > "$tmp"; fi
  sed -i '1s/^\xEF\xBB\xBF//' "$tmp"   # strip BOM

  echo "embed: $flac"
  if [[ "$DRY" != "--dry-run" ]]; then
    metaflac --set-tag-from-file="CUESHEET=$tmp" "$flac"
  fi
  rm -f "$tmp"; done=$((done+1))
done < <(find "$ROOT" -type f -iname '*.cue' -print0)

echo "embedded: $done, already had one: $skipped, cue without flac: $missing"
echo "Then rescan in Navidrome and press Sync in Slurpdrome."
