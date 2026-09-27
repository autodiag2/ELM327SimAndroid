#!/bin/bash

ORIGIN="./media"
CHANGELOG_DIR="fastlane/metadata/android/en-US/changelogs"

log() {
    echo
    echo "==> $1"
}

fail() {
    echo "ERROR: $1" >&2
    exit 1
}

# ----------------------------------------------------------------------
# 1. Check that we are at the repository root
# ----------------------------------------------------------------------

log "Checking repository root"

[[ -d "$ORIGIN" ]] || fail "media/ directory not found. Run this script from the repository root."
[[ -f "./gradlew" ]] || fail "gradlew not found. Run this script from the repository root."
[[ -f "./release_notes.txt" ]] || fail "release_notes.txt not found in repository root."
[[ -f "$ORIGIN/update_fastlane.sh" ]] || fail "media/update_fastlane.sh not found."

echo "Repository root: $(pwd)"
echo "media/: OK"
echo "gradlew: OK"
echo "release_notes.txt: OK"
echo "media/update_fastlane.sh: OK"

# ----------------------------------------------------------------------
# 2. Take release screenshots
# ----------------------------------------------------------------------

screenshots=(
    "main.png"
    "log.png"
    "settings.png"
    "side.png"
    "godot.png"
)

for screenshot in "${screenshots[@]}"; do
    read -r -p "navigate to ${screenshot%.png} [Y/i/n]: " answer
    answer="${answer:-Y}"

    case "$answer" in
        Y|y)
            log "Taking screenshot: $screenshot"

            adb exec-out screencap -p > "$ORIGIN/$screenshot"

            [[ -s "$ORIGIN/$screenshot" ]] ||
                fail "Screenshot failed: $ORIGIN/$screenshot"

            echo "Saved: $ORIGIN/$screenshot"
            ;;

        i|I)
            echo "Skipping $screenshot"
            ;;

        n|N|"")
            fail "Release cancelled."
            ;;

        *)
            fail "Invalid answer: $answer"
            ;;
    esac
done

# ----------------------------------------------------------------------
# 3. Copy release notes to the next changelog number
# ----------------------------------------------------------------------

log "Preparing release notes"

[[ -d "$CHANGELOG_DIR" ]] ||
    fail "Changelog directory not found: $CHANGELOG_DIR"

last_changelog=0

for file in "$CHANGELOG_DIR"/*.txt; do
    [[ -e "$file" ]] || continue

    name="$(basename "$file" .txt)"

    if [[ "$name" =~ ^[0-9]+$ ]] && (( name > last_changelog )); then
        last_changelog=$name
    fi
done

next_changelog=$((last_changelog + 1))
changelog_file="$CHANGELOG_DIR/${next_changelog}.txt"

log "Copying release notes"

echo "Last changelog: ${last_changelog}.txt"
echo "Next changelog: ${next_changelog}.txt"

cp "./release_notes.txt" "$changelog_file"

echo "Created: $changelog_file"

# ----------------------------------------------------------------------
# 4. Update Fastlane metadata/media
# ----------------------------------------------------------------------

log "Running media/update_fastlane.sh"

./media/update_fastlane.sh

# 4.1. Saving changes
git add .
git commit -m "update release metadatas"

# ----------------------------------------------------------------------
# 5. Bump version
# ----------------------------------------------------------------------

log "Running Gradle version bump"

./gradlew bumpVersion

# ----------------------------------------------------------------------
# Done
# ----------------------------------------------------------------------

log "Release preparation complete"

echo "Screenshots:"
for screenshot in "${screenshots[@]}"; do
    if [[ -f "$ORIGIN/$screenshot" ]]; then
        echo "  $ORIGIN/$screenshot"
    fi
done

echo "Changelog:"
echo "  $changelog_file"
