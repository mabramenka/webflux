Repository search policy:
- Prefer fff MCP tools when available:
    - fffind for file, path, and class-name discovery
    - ffgrep for content search
    - fff-multi-grep for related symbols and multi-pattern searches
- These are MCP tools exposed by the fff-mcp server, not shell binaries on PATH.
- Do not treat missing fffind/ffgrep/fff-multi-grep shell commands as proof that fff is unavailable.
- If fff MCP tools are unavailable or insufficient, fall back to rg, git grep, find, and standard shell tools.

Commit message policy:
- Create commit subjects and PR titles with Conventional Commits: `<type>[optional scope]: <description>`.
- Use `feat:`, `fix:`, or `deps:` for changes that should make release-please open a release PR.
- Use `ci:`, `docs:`, `test:`, `refactor:`, `perf:`, `build:`, `style:`, or `chore:` for valid non-release changes.
- Use `deps:` for dependency updates; do not use `chore(deps):`, because release-please will parse it but skip the release.
- Do not create bare imperative subjects such as `Run dependency security check on schedule`.
- To force an explicit version, use a valid subject and a `Release-As: x.y.z` commit body footer.
