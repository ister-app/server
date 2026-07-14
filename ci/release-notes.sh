#!/usr/bin/env bash
# Build the release notes for a server release.
#
#   ci/release-notes.sh <version> [<previous-tag>]
#
# Writes RELEASE_NOTES.md (fed to `gh release create --notes-file`) and prepends the same
# block to CHANGELOG.md. Runs fine outside CI, so the formatting can be checked locally.
#
# `gh release create --generate-notes` is not enough here: it lists PR titles only, so
# commits pushed straight to main go missing.
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${1:?usage: release-notes.sh <version> [<previous-tag>]}"
VERSION="${VERSION#v}"

# The previous tag, or the root commit when this is the first release.
PREV="${2:-$(git describe --tags --abbrev=0 2>/dev/null || true)}"
RANGE="${PREV:+$PREV..}HEAD"

# owner/repo, from the CI env or the origin remote. The `.git` suffix is stripped first:
# folding it into the owner/repo match makes the pattern ambiguous, and the greedy branch
# wins — which silently produces .../server.git/commit/<sha> links that 404.
REPO="${GITHUB_REPOSITORY:-$(git remote get-url origin 2>/dev/null |
  sed -E -e 's#\.git$##' -e 's#^.*[:/]([^/]+/[^/]+)$#\1#' || echo ister-app/server)}"

{
  echo "## server v${VERSION}"
  echo
  echo "| Image | Tag |"
  echo "|---|---|"
  echo "| \`ghcr.io/ister-app/server\` | \`${VERSION}\` |"
  echo "| \`ghcr.io/ister-app/migrations\` | \`${VERSION}\` |"
  echo

  # Group the commits by conventional-commit type. The release commits themselves are
  # dropped: they are the bump this file documents, so listing them is circular.
  #
  # `%h %s` rather than a delimiter — a commit subject may legitimately contain any
  # separator character, but never a space before the first one. That leading `^\S+ ` is
  # also what anchors the patterns below to the start of the subject: without it, the
  # "no prefix" lookahead would happily match at some later space inside the subject.
  #
  # grep -P, not -E: the "not deps" scopes and the catch-all both need a lookahead.
  section() {
    local title="$1" pattern="$2" body
    body="$(git log --no-merges --pretty='%h %s' "$RANGE" |
      grep -vP '^\S+ chore\(release\)' |
      grep -P "^\S+ ${pattern}" |
      while read -r sha subject; do
        echo "- ${subject} ([\`${sha}\`](https://github.com/${REPO}/commit/${sha}))"
      done || true)"
    [ -n "$body" ] || return 0
    echo "### ${title}"
    echo
    echo "$body"
    echo
  }

  section "Breaking changes"   '[a-z]+(\(.+\))?!:'
  section "Features"           'feat(\(.+\))?:'
  section "Fixes"              'fix(\((?!deps\)).+\))?:'
  section "Dependency updates" '(fix|chore)\(deps\):'
  section "Other"              '(chore|docs|ci|build|refactor|test|perf)(\((?!deps\)).+\))?:'
  # Anything without a conventional-commit prefix. The history predates the convention, and
  # a commit that slips past the commit-lint job must still show up somewhere — a silently
  # dropped commit is worse than an ugly section.
  section "Other changes"      '(?![a-z]+(\(.+\))?!?:)'

  echo "### Run"
  echo
  echo '```sh'
  echo "docker pull ghcr.io/ister-app/server:${VERSION}"
  echo '```'
  echo
  if [ -n "$PREV" ]; then
    echo "**Full changelog**: https://github.com/${REPO}/compare/${PREV}...v${VERSION}"
  fi
} > RELEASE_NOTES.md

# Prepend to the changelog, keeping the file's heading on top.
{
  echo "# Changelog"
  echo
  cat RELEASE_NOTES.md
  if [ -f CHANGELOG.md ]; then
    echo
    tail -n +2 CHANGELOG.md | sed '/./,$!d'
  fi
} > CHANGELOG.md.new
mv CHANGELOG.md.new CHANGELOG.md

echo "wrote RELEASE_NOTES.md and CHANGELOG.md for v${VERSION} (range: ${RANGE})" >&2
