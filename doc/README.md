# Ister server documentation

This directory holds the documentation for the Ister media server.

## Contents

- `admin/en/` — the administrator guide in English (8 chapters: installation, configuration,
  libraries, multi-node, search, maintenance, naming conventions)
- `admin/nl/` — de beheerdershandleiding in het Nederlands (same chapters, translated/adapted)
- `architecture/en/` and `architecture/nl/` — developer-facing architecture documentation
  (modules, event system, scanning, media types & metadata, transcoding, continue watching,
  search, API & auth, native image & testing)
- `architecture/diagrams/` — hand-authored mermaid diagrams (English only, shared by both
  locales, committed)

## Conventions

- Chapters are numbered (`00-…`, `01-…`) per track; `en/` and `nl/` always carry the same
  filenames. A chapter added or renamed in one locale must be added/renamed in the other —
  CI enforces this.
- Plain Markdown, no front-matter, no site generator. Links between files are relative, so
  everything renders on GitHub as-is.
- The NL chapters are translations/adaptations, not word-for-word machine output; keep both
  in sync when the content changes.

## Building the docs zip

```shell
ci/build-docs.sh            # validate + package server-docs-<version>.zip
ci/build-docs.sh --check    # validate only (what the PR build runs)
```

The script checks EN/NL parity, relative links and mermaid fences, then zips `doc/`. The
version defaults to the one in `build.gradle` (without `-SNAPSHOT`); the release workflow
passes the release version explicitly and attaches the zip to each GitHub release. There is
nothing to capture from a running application, so no further setup is needed.
