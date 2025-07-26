# Parts Project Guide

## Development Environment

The project uses Nix for reproducible development environments. To get started:

1. Install Nix: https://nixos.org/download
2. Install direnv: https://direnv.net/docs/installation.html
3. Run `direnv allow` in the project root
4. The environment will activate automatically

Alternative: Use `nix develop` to enter the environment manually.

## Autonomous Development Workflow

- Always clarify the developer's intentions before writing code
- Do not attempt to read or edit files outside the project folder
- Add failing tests first, then fix them
- Work autonomously in small, testable increments
- Run targeted tests, and lint continuously during development
- Prioritise understanding existing patterns before implementing
- Don't commit changes, leave it for the user to review and make commits

## Build & Test Commands
- Start dev REPL: `make repl`
- Run all tests: `make test`
- Run tests in watch mode: `make test-watch`
- Run single test: `clojure -X:test/env:test/run :focus my.namespace/test-name`
- Check formatting: `make format-check`
- Fix formatting issues: `make format-fix`
- Build CSS: `make build-css`
- Watch CSS changes: `make css-watch`
- Start frontend development: `bunx shadow-cljs watch frontend`
- Build project: `make dist`
- Clean project: `make clean`
- Update dependencies: `clojure -M:antq --upgrade`

## Code Style Guidelines
- **Namespaces**: Use kebab-case (e.g., `parts.entity.user`)
- **Functions**: Use kebab-case with clear descriptive names
- **Docstrings**: Add docstrings to public functions explaining purpose and args
- **Error handling**: Use `ex-info` with `:type` key for classification
- **Testing**: Use fixtures when appropriate, name tests with `test-` prefix
- **Formatting**: Follow cljfmt rules, 2-space indentation
- **Frontend**: Use UIx components with `defui` and `$` for React components
- **Imports**: Group by type (backend/frontend), alphabetize within groups
- **Privacy**: Use `defn-` for private functions to limit namespace exposure
- **Spec**: Use `clojure.spec` for data validation and model constraints
