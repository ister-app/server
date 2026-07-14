# Contributing

## Commit messages

This repository uses [Conventional Commits](https://www.conventionalcommits.org/). This is not
cosmetic: the nightly release workflow derives the version bump and the release notes from the
commit subjects, so an unlabelled commit ends up in a catch-all section and never bumps anything
above a patch. A `commit-lint` job on every pull request enforces the format.

```
<type>[(optional scope)][!]: <description>
```

| Type | Use for | Release |
|---|---|---|
| `feat` | new behaviour a user can see | **minor** (1.2.0 → 1.3.0) |
| `fix` | a bug fix | patch (1.2.0 → 1.2.1) |
| `perf` | a change that only makes things faster | patch |
| `refactor` | a change with no behaviour change | patch |
| `test` | tests only | patch |
| `docs` | documentation only | patch |
| `build` | build files, Gradle, Dockerfiles | patch |
| `ci` | workflows | patch |
| `chore` | anything else (incl. dependency bumps) | patch |

A `!` before the colon, or a `BREAKING CHANGE:` line in the body, makes the next release a
**major**:

```
feat(api)!: drop the deprecated /movies/all endpoint
```

Scopes are optional and free-form; the module name is the obvious choice (`api`, `disk`, `worker`,
`transcoder`, `search`, `database`).

Examples from a good day:

```
feat(podcast): download the newest episodes on subscribe
fix(transcoder): stop A/V drift on a forward seek
chore(deps): bump spring-boot to 4.1.1
```

## Versions and releases

Do not edit `version` in `build.gradle` by hand. `main` always carries a `-SNAPSHOT` version; the
release workflow strips it on the tagged commit and immediately puts the next snapshot back. See
`.github/workflows/release.yml`.

Releases run nightly, on their own, when `main` has new commits *and* the last commit built green.
To cut one early, or to force a specific bump, run the **Release** workflow by hand
(`workflow_dispatch`).
