#!/usr/bin/env bash
# Fails when the Testcontainers-backed integration tests did not actually run.
#
# Every *IntegrationTest is annotated @Testcontainers(disabledWithoutDocker = true), so a build
# on a runner without a reachable container runtime stays green while silently skipping the only
# tests that exercise Flyway, ddl-auto=validate and the PostgreSQL-specific native queries. This
# reads Gradle's JUnit XML and turns such a skip into a failure, so the build's green means what
# it says. Run after ./gradlew test (or build); exits non-zero when no report exists at all.
#
#   ci/check-integration-tests.sh
set -euo pipefail

cd "$(dirname "$0")/.."

failed=0
seen=0

echo "=== checking that every *IntegrationTest ran"
while IFS= read -r report; do
  seen=$((seen + 1))
  suite="$(grep -oP '<testsuite name="\K[^"]+' "$report" | head -1)"
  tests="$(grep -oP '<testsuite [^>]*\btests="\K[0-9]+' "$report" | head -1)"
  skipped="$(grep -oP '<testsuite [^>]*\bskipped="\K[0-9]+' "$report" | head -1)"
  if [ "${tests:-0}" -eq 0 ] || [ "${skipped:-0}" -ge "${tests:-0}" ]; then
    echo "::error::$suite: $tests test(s), $skipped skipped — the integration tests did not run (no container runtime?)"
    failed=1
  else
    echo "  $suite: $tests ran, $skipped skipped"
  fi
done < <(find . -path '*/build/test-results/test/TEST-*IntegrationTest.xml' 2>/dev/null | sort)

if [ "$seen" -eq 0 ]; then
  echo "::error::no *IntegrationTest reports found under */build/test-results/test; run ./gradlew test first"
  failed=1
fi

[ "$failed" -eq 0 ] || { echo "integration-test check failed" >&2; exit 1; }
echo "integration-test check passed ($seen suites)"
