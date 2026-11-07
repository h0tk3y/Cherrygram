#!/usr/bin/env bash
# Copies the CI build stubs for Cherrygram's closed-source (gitignored) sources into place.
#
#   ci/stubs/<path>         -> <path>   (Java/Kotlin sources)
#   ci/native-stubs/<path>  -> <path>   (TMessagesProj/jni/security, TMessagesProj/jni/colorado)
#
# Existing files are never overwritten, so a checkout that has the real closed-source files keeps
# them. In the Extra.kt stub copied by this run, @TG_APP_ID@ / @TG_APP_HASH@ are replaced with
# $TG_APP_ID / $TG_APP_HASH when those are set and non-empty (otherwise Extra falls back to the
# upstream Telegram app id/hash at runtime).
#
# Run from the repository root:  bash ci/apply-stubs.sh

set -euo pipefail

ROOT="$(pwd)"
if [[ ! -d "$ROOT/ci/stubs" || ! -d "$ROOT/TMessagesProj" ]]; then
    echo "apply-stubs: run this script from the repository root" >&2
    exit 1
fi

EXTRA_KT="TMessagesProj/src/main/java/uz/unnarsx/cherrygram/Extra.kt"
copied_extra=0
copied=0
kept=0

copy_tree() {
    local src_root="$1"
    [[ -d "$src_root" ]] || return 0
    local src rel dest
    while IFS= read -r -d '' src; do
        rel="${src#"$src_root"/}"
        dest="$ROOT/$rel"
        if [[ -e "$dest" ]]; then
            echo "apply-stubs: keeping existing $rel"
            kept=$((kept + 1))
            continue
        fi
        mkdir -p "$(dirname "$dest")"
        cp "$src" "$dest"
        echo "apply-stubs: stubbed $rel"
        copied=$((copied + 1))
        if [[ "$rel" == "$EXTRA_KT" ]]; then
            copied_extra=1
        fi
    done < <(find "$src_root" -type f -print0 | sort -z)
}

copy_tree "$ROOT/ci/stubs"
copy_tree "$ROOT/ci/native-stubs"

# Escapes a value for use as the replacement part of a sed s||| command.
sed_escape() {
    printf '%s' "$1" | sed -e 's/[\\|&]/\\&/g'
}

# Replaces placeholder $1 with value $2 in the copied Extra.kt (portable, no sed -i).
substitute() {
    local file="$ROOT/$EXTRA_KT"
    sed -e "s|$1|$(sed_escape "$2")|g" "$file" > "$file.tmp"
    mv "$file.tmp" "$file"
}

if [[ "$copied_extra" == 1 ]]; then
    if [[ -n "${TG_APP_ID:-}" ]]; then
        if [[ ! "$TG_APP_ID" =~ ^[0-9]+$ ]]; then
            echo "apply-stubs: TG_APP_ID must be a number" >&2
            exit 1
        fi
        substitute "@TG_APP_ID@" "$TG_APP_ID"
        echo "apply-stubs: set app id in $EXTRA_KT"
    else
        echo "apply-stubs: TG_APP_ID not set, Extra.kt falls back to the upstream app id"
    fi
    if [[ -n "${TG_APP_HASH:-}" ]]; then
        if [[ ! "$TG_APP_HASH" =~ ^[0-9a-fA-F]{32}$ ]]; then
            echo "apply-stubs: TG_APP_HASH must be 32 hex characters" >&2
            exit 1
        fi
        substitute "@TG_APP_HASH@" "$TG_APP_HASH"
        echo "apply-stubs: set app hash in $EXTRA_KT"
    else
        echo "apply-stubs: TG_APP_HASH not set, Extra.kt falls back to the upstream app hash"
    fi
fi

echo "apply-stubs: $copied file(s) stubbed, $kept existing file(s) kept"
