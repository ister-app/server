#!/usr/bin/env bash
# Validates and packages the documentation under doc/ as server-docs-<version>.zip.
#
# Unlike the player's docs build there are no screenshots to capture, so this is pure
# validation + zip: EN/NL chapter parity, relative links, and balanced mermaid fences.
# Full mermaid syntax validation (mmdc) is deliberately left out — it would pull a Node
# toolchain into a Java repo's CI for little gain.
#
#   ci/build-docs.sh [version]     validate and build the zip
#   ci/build-docs.sh --check      validate only (used by the PR build)
#
# The version defaults to the one in build.gradle, without the -SNAPSHOT suffix.
set -euo pipefail

cd "$(dirname "$0")/.."

check_only=0
version=""
if [ "${1:-}" = "--check" ]; then
  check_only=1
elif [ -n "${1:-}" ]; then
  version="$1"
fi
[ -n "$version" ] || version="$(grep -oP "^\s*version = '\K[0-9]+\.[0-9]+\.[0-9]+" build.gradle)"

failed=0

# Every chapter must exist in both locales, with the same filename — a chapter added in
# one language and forgotten in the other is exactly the drift this check exists for.
echo "=== validating en/nl parity"
for track in doc/admin doc/architecture; do
  if ! diff <(ls "$track/en") <(ls "$track/nl") >/dev/null; then
    echo "::error::$track: en/ and nl/ chapters differ:"
    diff <(ls "$track/en") <(ls "$track/nl") | sed 's/^/  /' || true
    failed=1
  fi
done

# Every relative link target must exist (anchors and external URLs are skipped).
echo "=== validating relative links"
while IFS= read -r ref; do
  md_file="${ref%%:*}"
  target="${ref#*:}"
  target="${target%%#*}"                      # drop an anchor suffix
  [ -n "$target" ] || continue                # pure in-page anchor
  path="$(realpath -m "$(dirname "$md_file")/$target")"
  if [ ! -e "$path" ]; then
    echo "::error::$md_file links to $target but $path does not exist"
    failed=1
  fi
done < <(grep -RoP '(?<!\!)\[[^]]*\]\(\K[^)]+(?=\))' doc --include='*.md' | grep -vE ':(https?|mailto):')

# A mermaid block without its closing fence swallows the rest of the page when rendered.
echo "=== validating mermaid fences"
while IFS= read -r md_file; do
  count=$(grep -c '^```' "$md_file" || true)
  if [ $((count % 2)) -ne 0 ]; then
    echo "::error::$md_file has an unbalanced number of code fences ($count)"
    failed=1
  fi
done < <(grep -Rl '```mermaid' doc --include='*.md')

[ "$failed" -eq 0 ] || { echo "docs validation failed" >&2; exit 1; }
echo "docs validation passed"

[ "$check_only" -eq 0 ] || exit 0

zip_name="server-docs-${version}.zip"
echo "=== packaging $zip_name"
rm -f "$zip_name"
zip -qr "$zip_name" doc
echo "built $zip_name ($(du -h "$zip_name" | cut -f1))"
