#!/usr/bin/env bash
set -euo pipefail

subject="${1:-}"
pattern='^(feat|fix|deps|ci|docs|test|refactor|perf|build|style|chore|revert)(\([^)]+\))?(!)?: .+$'

if [[ -z "$subject" ]]; then
  echo "::error::Commit subject is empty."
  exit 1
fi

if [[ "$subject" =~ ^chore\(deps\)(!)?: ]]; then
  echo "::error::Use 'deps: ...' for dependency updates so release-please can create a release."
  exit 1
fi

if [[ ! "$subject" =~ $pattern ]]; then
  echo "::error::Commit subject must use Conventional Commits: <type>[optional scope]: <description>"
  echo "::error::Release-triggering types in this repository are feat, fix, and deps."
  echo "::error::Received: $subject"
  exit 1
fi
