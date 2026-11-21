#!/usr/bin/env bash
# WAKE-PHONE: fetches the "Hey Prox" engine (sherpa-onnx 2.0.1's AAR) and its English keyword model from
# k2-fsa's own GitHub releases, checks them against the pins in app/wake-engine.sha256 and puts them where the
# build looks (app/libs/sherpa-onnx.aar, app/wake/assets/wake/). Neither is committed (.gitignore).
#
#   scripts/wake-engine.sh --print-hashes   download to a temp folder, print sha256 + size + licences, install nothing
#   scripts/wake-engine.sh --keywords       once: turn "HEY PROX" into the pinned model's tokens with sherpa-onnx's
#                                           own text2token (PyPI, in a throwaway uvx env, in a temp folder) and
#                                           write app/wake-keywords.txt; print its sha256 for the pins. You commit
#                                           both. The only step that runs PyPI code.
#   scripts/wake-engine.sh install          refuse unless every pin is filled in and matches, then install and
#                                           write app/wake-model.sha256 (the 5 model files' sha256; commit it).
#                                           No PyPI code.
#
# Run on the PC (it needs the network; --keywords also needs uv). The phone never downloads anything for "Hey Prox".
# WAKE_PINS / WAKE_KEYWORDS / WAKE_MODEL_SUMS override the three files' paths (for the unit test).
set -euo pipefail

cd "$(dirname "$0")/.."
PINS=${WAKE_PINS:-app/wake-engine.sha256}
KEYWORDS=${WAKE_KEYWORDS:-app/wake-keywords.txt}
MODEL_SUMS=${WAKE_MODEL_SUMS:-app/wake-model.sha256}
AAR_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
MODEL_NAME=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
MODEL_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$MODEL_NAME.tar.bz2
CHECKSUMS_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/checksum.txt
ENGINE_LICENCE_API=https://api.github.com/repos/k2-fsa/sherpa-onnx/license
ENGINE_LICENCE_PAGE=https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE
MODEL_PAGE=https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html
# "Hey Prox" before it's turned into the model's tokens. ":2.0" boost and "#0.20" threshold (tuned 11-18 from 1.5 and 0.35: it missed him, above all said slowly) (0..1; higher wakes
# less; the engine's default is 0.25), "@HEY_PROX" the name it reports. Tune the threshold in the live test, then
# run --keywords again and re-pin.
KEYWORD_RAW="HEY PROX :2.0 #0.20 @HEY_PROX"
# The engine (the AAR) is Apache-2.0 or MIT only. The keyword model may also be "unstated-own-phone" (Nathan,
# 11-18: "Add it to prox mobile as a feature"): its authors state no licence, so it ships only on his own phone.
ENGINE_LICENCES="Apache-2.0 MIT"
MODEL_LICENCES="Apache-2.0 MIT unstated-own-phone"
OWN_PHONE_LINE="This APK is for Nathan's own phone only and never goes to anyone else."
MODEL_FILES="encoder.onnx decoder.onnx joiner.onnx tokens.txt keywords.txt"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

pin() { sed -n "s/^$1=//p" "$PINS" | head -1 | sed 's/#.*//' | tr -d ' \t'; }
fetch() { curl -fsSL --proto '=https' --tlsv1.2 -o "$2" "$1"; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
size() { stat -c %s "$1"; }
refuse() { echo "Refused: $*" >&2; exit 1; }
licence_ok() { local v=$1; shift; for l in "$@"; do [ "$v" = "$l" ] && return 0; done; return 1; }
engine_licence() { curl -fsSL --proto '=https' "$ENGINE_LICENCE_API" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["license"]["spdx_id"])'; }
# One line: the model's tokens, a "#threshold" in 0.1..0.9 and "@HEY_PROX" (the app checks the same: KeywordLine).
keywords_shape_ok() {
    [ "$(grep -c . "$1")" = 1 ] || return 1
    grep -qE '(^| )@HEY_PROX( |$)' "$1" || return 1
    grep -qE '(^| )#0\.([1-8][0-9]*|90*)( |$)' "$1" || return 1
}

extract_model() {   # $1 = archive; leaves the model folder in $work/m/$MODEL_NAME
    mkdir -p "$work/m"
    tar -xjf "$1" -C "$work/m" --no-same-owner --no-same-permissions
    [ -f "$work/m/$MODEL_NAME/tokens.txt" ] || refuse "tokens.txt missing from the archive."
}

unknown_tokens() {  # $1 = tokens.txt, $2 = keywords file: prints tokens the model doesn't know
    awk 'NR==FNR { known[$1]=1; next } { for (i=1;i<=NF;i++) { t=$i; if (t ~ /^[:#@]/) continue; if (!(t in known)) print t } }' "$1" "$2"
}

print_hashes() {
    echo "Downloading the engine and the model (about 67 MB) to a temp folder…"
    fetch "$AAR_URL" "$work/engine.aar"
    fetch "$MODEL_URL" "$work/model.tar.bz2"
    echo
    echo "aar_sha256=$(sha "$work/engine.aar")    # $(size "$work/engine.aar") bytes, $AAR_URL"
    echo "model_sha256=$(sha "$work/model.tar.bz2")    # $(size "$work/model.tar.bz2") bytes, $MODEL_URL"
    echo
    echo "Compare the AAR's with the digest on https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1"
    echo "and the model's with its line in the kws-models checksum.txt:"
    fetch "$CHECKSUMS_URL" "$work/checksum.txt" && grep -F "$MODEL_NAME" "$work/checksum.txt" || echo "  (checksum.txt has no line for it)"
    echo
    echo "engine_licence: GitHub says $(engine_licence)  ($ENGINE_LICENCE_PAGE)"
    echo "model_licence:  not stated by GitHub; read $MODEL_PAGE and the model's own files (Apache-2.0, MIT or unstated-own-phone)"
    tar -tjf "$work/model.tar.bz2" | grep -iE 'licen|readme' | sed 's/^/  in the archive: /' || true
    echo
    echo "Nothing was installed. Fill aar_sha256 and model_sha256 into $PINS, then: scripts/wake-engine.sh --keywords"
}

keywords() {
    local model; model=$(pin model_sha256)
    [ -n "$model" ] || refuse "model_sha256 is empty in $PINS (run --print-hashes first)."
    command -v uvx >/dev/null || refuse "uvx (uv) isn't installed."
    fetch "$MODEL_URL" "$work/model.tar.bz2"
    [ "$(sha "$work/model.tar.bz2")" = "$model" ] || refuse "the model doesn't match model_sha256."
    extract_model "$work/model.tar.bz2"
    local dir="$work/m/$MODEL_NAME"
    [ -f "$dir/bpe.model" ] || refuse "bpe.model missing from the archive."
    echo "$KEYWORD_RAW" > "$work/keywords_raw.txt"
    # sherpa-onnx's own tool, in a throwaway environment, in the temp folder; never during install.
    (cd "$work" && uvx --python 3.12 --from "sherpa-onnx==2.0.1" --with "sentencepiece==0.2.0" --with "click==8.1.8" --with "pypinyin==0.55.0" \
        sherpa-onnx-cli text2token --tokens "$dir/tokens.txt" --tokens-type bpe --bpe-model "$dir/bpe.model" \
        "$work/keywords_raw.txt" "$work/keywords.txt")
    keywords_shape_ok "$work/keywords.txt" || refuse "text2token's line isn't one line with #threshold and @HEY_PROX: $(cat "$work/keywords.txt")"
    local missing; missing=$(unknown_tokens "$dir/tokens.txt" "$work/keywords.txt")
    [ -z "$missing" ] || refuse "tokens the model doesn't know: $missing"
    cp "$work/keywords.txt" "$KEYWORDS"
    echo "Wrote $KEYWORDS: $(cat "$KEYWORDS")"
    echo "keywords_sha256=$(sha "$KEYWORDS")"
    echo "Put that line in $PINS, commit both files, then: scripts/wake-engine.sh install"
}

install() {
    # Every check that needs no network first.
    local aar model el ml kw
    aar=$(pin aar_sha256); model=$(pin model_sha256); el=$(pin engine_licence); ml=$(pin model_licence)
    kw=$(pin keywords_sha256)
    if [ -z "$aar" ] || [ -z "$model" ] || [ -z "$el" ] || [ -z "$ml" ] || [ -z "$kw" ]; then
        refuse "$PINS still has an empty value (--print-hashes, then --keywords, first)."
    fi
    licence_ok "$el" $ENGINE_LICENCES || refuse "engine_licence is '$el' (only $ENGINE_LICENCES)."
    licence_ok "$ml" $MODEL_LICENCES || refuse "model_licence is '$ml' (only $MODEL_LICENCES)."
    [ -f "$KEYWORDS" ] || refuse "$KEYWORDS is missing (scripts/wake-engine.sh --keywords, then commit it)."
    [ "$(sha "$KEYWORDS")" = "$kw" ] || refuse "$KEYWORDS doesn't match keywords_sha256."
    keywords_shape_ok "$KEYWORDS" || refuse "$KEYWORDS isn't one line with #threshold and @HEY_PROX."

    local live; live=$(engine_licence)
    [ "$live" = "$el" ] || refuse "GitHub says sherpa-onnx is '$live', the pin says '$el'."
    echo "Downloading the engine and the model (about 67 MB) to a temp folder…"
    fetch "$AAR_URL" "$work/engine.aar"
    fetch "$MODEL_URL" "$work/model.tar.bz2"
    [ "$(sha "$work/engine.aar")" = "$aar" ] || refuse "the AAR doesn't match aar_sha256."
    [ "$(sha "$work/model.tar.bz2")" = "$model" ] || refuse "the model doesn't match model_sha256."
    extract_model "$work/model.tar.bz2"
    local dir="$work/m/$MODEL_NAME"
    # The int8 files where there are some (smaller, the ones sherpa-onnx's Android demo uses), else fp32.
    pick() {
        local f
        f=$(ls "$dir"/$1-*.int8.onnx 2>/dev/null | head -1)
        [ -n "$f" ] || f=$(ls "$dir"/$1-*.onnx 2>/dev/null | grep -v int8 | head -1)
        [ -n "$f" ] || refuse "no $1 model in the archive."
        echo "$f"
    }
    local enc dec joi
    enc=$(pick encoder); dec=$(pick decoder); joi=$(pick joiner)
    local missing; missing=$(unknown_tokens "$dir/tokens.txt" "$KEYWORDS")
    [ -z "$missing" ] || refuse "tokens the model doesn't know: $missing"

    mkdir -p app/libs app/wake/assets/wake
    cp "$work/engine.aar" app/libs/sherpa-onnx.aar
    cp "$enc" app/wake/assets/wake/encoder.onnx
    cp "$dec" app/wake/assets/wake/decoder.onnx
    cp "$joi" app/wake/assets/wake/joiner.onnx
    cp "$dir/tokens.txt" app/wake/assets/wake/tokens.txt
    cp "$KEYWORDS" app/wake/assets/wake/keywords.txt
    {
        echo "sherpa-onnx 2.0.1 ($el): $AAR_URL"
        echo "$MODEL_NAME ($ml): $MODEL_URL"
        echo "files: $(basename "$enc") $(basename "$dec") $(basename "$joi") tokens.txt"
        if [ "$ml" = "unstated-own-phone" ]; then
            echo "The keyword model's licence is unstated-own-phone: its authors state no licence."
            echo "$OWN_PHONE_LINE"
        fi
    } > app/wake/assets/wake/NOTICE.txt
    # The build checks each of these against the files it bundles.
    {
        echo "# WAKE-PHONE: sha256 of the 5 installed model files (scripts/wake-engine.sh install). The build checks them."
        for f in $MODEL_FILES; do echo "$(sha "app/wake/assets/wake/$f")  $f"; done
    } > "$MODEL_SUMS"
    echo "Installed. \"Hey Prox\" is: $(cat app/wake/assets/wake/keywords.txt)"
    echo "Commit $MODEL_SUMS, then build: env JAVA_HOME=\$HOME/Android/jdk17 ./gradlew assembleRelease"
}

case "${1:-}" in
    --print-hashes) print_hashes ;;
    --keywords) keywords ;;
    install) install ;;
    *) echo "usage: scripts/wake-engine.sh --print-hashes | --keywords | install" >&2; exit 2 ;;
esac
