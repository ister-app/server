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

# inject_last_update <file-to-edit> <git-tracked-path>
# Bake Docusaurus `last_update` frontmatter from the source file's git history,
# so the documentation site (which has no history for synced files) can show
# "Last updated on ... by ...". Merges into an existing frontmatter block or
# creates one. Skips silently when the file has no history (untracked file).
inject_last_update() {
  local file="$1" src="$2" stamp date author
  stamp="$(git log -1 --format='%aI%x09%an' -- "$src")"
  [ -n "$stamp" ] || return 0
  date="${stamp%%$'\t'*}"
  author="${stamp#*$'\t'}"
  if [ "$(head -1 "$file")" = "---" ]; then
    sed -i "1a last_update:\n  date: $date\n  author: \"$author\"" "$file"
  else
    printf -- '---\nlast_update:\n  date: %s\n  author: "%s"\n---\n\n%s\n' \
      "$date" "$author" "$(cat "$file")" > "$file"
  fi
}

# The injection edits a copy, keeping the working tree clean.
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
cp -r doc "$build_dir/doc"

echo "=== injecting last_update frontmatter"
if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  # PR builds check out shallowly; per-file dates would all be the tip commit.
  # The zip a PR build produces is never published, so skip rather than lie.
  echo "shallow checkout — skipping last_update injection"
else
  while IFS= read -r f; do
    inject_last_update "$f" "${f#"$build_dir/"}"
  done < <(find "$build_dir/doc" -name '*.md')
fi

zip_name="server-docs-${version}.zip"
echo "=== packaging $zip_name"
rm -f "$zip_name"
(cd "$build_dir" && zip -qr "$OLDPWD/$zip_name" doc)
echo "built $zip_name ($(du -h "$zip_name" | cut -f1))"
