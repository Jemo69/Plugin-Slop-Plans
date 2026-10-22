#!/usr/bin/env bash
#
# Rename the template into your plugin.
#
#   ./rename.sh <applicationId> "<Display Name>" [short-slug] [author]
#
# Examples:
#   ./rename.sh de.jemo.kvaesitso.bahn "Bahn Timetable" bahn "Jemo"
#   ./rename.sh com.acme.tides "Tide Times"
#
# Everything the launcher keys off - applicationId, package, provider authority - is
# rewritten in place. Run this before your first commit: the authority must never change
# once a plugin has been installed, or every existing install is orphaned.

set -euo pipefail

OLD_PACKAGE="de.jemo.kvaesitso.slopplans"
OLD_SLUG="slopplans"
OLD_NAME="Slop Plans"
OLD_AUTHOR="Slop"

NEW_PACKAGE="${1:-}"
NEW_NAME="${2:-}"
NEW_SLUG="${3:-}"
NEW_AUTHOR="${4:-}"

if [[ -z "$NEW_PACKAGE" || -z "$NEW_NAME" ]]; then
    echo "usage: $0 <applicationId> \"<Display Name>\" [short-slug] [author]" >&2
    echo "   e.g. $0 de.jemo.kvaesitso.bahn \"Bahn Timetable\" bahn \"Jemo\"" >&2
    exit 1
fi

if [[ -z "$NEW_SLUG" ]]; then
    NEW_SLUG="$(basename "$NEW_PACKAGE")"
fi

cd "$(dirname "$0")"

if [[ ! "$NEW_PACKAGE" =~ ^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$ ]]; then
    echo "'$NEW_PACKAGE' is not a valid applicationId (lowercase, at least two segments)." >&2
    exit 1
fi

echo "Renaming $OLD_PACKAGE -> $NEW_PACKAGE"
echo "           '$OLD_NAME' -> '$NEW_NAME'"
echo

# 1. Move the source directory to match the new package.
OLD_DIR="app/src/main/java/$(echo "$OLD_PACKAGE" | tr '.' '/')"
NEW_DIR="app/src/main/java/$(echo "$NEW_PACKAGE" | tr '.' '/')"
OLD_TEST_DIR="app/src/test/java/$(echo "$OLD_PACKAGE" | tr '.' '/')"
NEW_TEST_DIR="app/src/test/java/$(echo "$NEW_PACKAGE" | tr '.' '/')"

if [[ -d "$OLD_DIR" ]]; then
    mkdir -p "$(dirname "$NEW_DIR")"
    mv "$OLD_DIR" "$NEW_DIR"
    echo "moved $OLD_DIR -> $NEW_DIR"
fi
if [[ -d "$OLD_TEST_DIR" ]]; then
    mkdir -p "$(dirname "$NEW_TEST_DIR")"
    mv "$OLD_TEST_DIR" "$NEW_TEST_DIR"
    echo "moved $OLD_TEST_DIR -> $NEW_TEST_DIR"
fi

# 2. Rewrite references in every text file that mentions the template.
FILES=$(grep -rl \
    -e "$OLD_PACKAGE" \
    -e "$OLD_SLUG" \
    . \
    --exclude-dir=.git \
    --exclude-dir=build \
    --exclude-dir=.gradle \
    --exclude-dir=.opencode \
    --exclude=rename.sh \
    2>/dev/null || true)

for file in $FILES; do
    # The provider authority is "<package>.<type>" - handle it before the bare package.
    sed -i.bak \
        -e "s/${OLD_PACKAGE}\./${NEW_PACKAGE}./g" \
        -e "s/${OLD_PACKAGE}/${NEW_PACKAGE}/g" \
        -e "s/\bpackage ${OLD_SLUG}\b/package ${NEW_SLUG}/g" \
        -e "s/\b$OLD_NAME\b/$NEW_NAME/g" \
        -e "s/\b$OLD_AUTHOR\b/${NEW_AUTHOR}/g" \
        "$file"
    rm -f "$file.bak"
done

# 3. The project name in settings.gradle.kts is free text, not the package.
sed -i.bak -e "s/rootProject.name = \".*\"/rootProject.name = \"$NEW_NAME\"/" settings.gradle.kts
rm -f settings.gradle.kts.bak

# 4. The template ships with sample data on and no API key.
cat <<EOF

Done. Next:

  1. Set applicationId/namespace to   $NEW_PACKAGE   (already done)
  2. Point $NEW_SLUG's provider authority at a globally unique name if the auto-generated one clashes
  3. Open app/src/main/res/values/strings.xml and replace the placeholder strings
  4. Build:  ./gradlew :app:assembleDebug
  5. The plugin ships with sample data enabled. Turn it off in the settings activity
     once your own provider works.
EOF