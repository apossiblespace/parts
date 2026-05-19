# Parts Project Guide

## Development Environment

The project uses Nix for reproducible development environments. To get started:

1. Install Nix: https://nixos.org/download
2. Install direnv: https://direnv.net/docs/installation.html
3. Run `direnv allow` in the project root
4. The environment will activate automatically

Alternative: Use `nix develop` to enter the environment manually.

### CI/CD
GitHub Actions workflows use Nix for consistency with local development:
- All tests, linting, and formatting checks run in the same Nix environment
- Caching is handled by DeterminateSystems/magic-nix-cache-action
- No separate tool setup required

## Autonomous Development Workflow

- Always clarify the developer's intentions before writing code
- Do not attempt to read or edit files outside the project folder
- Add failing tests first, then fix them
- Work autonomously in small, testable increments
- Run targeted tests, and lint continuously during development
- Prioritise understanding existing patterns before implementing
- Prioritise simplicity; avoid over-engineering. If you can deliver ~80% of the result with ~20% of the code or complexity, always surface that option before implementing the fuller version
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
- Start frontend development: `pnpm exec shadow-cljs watch frontend`
- Build project: `make dist`
- Clean project: `make clean`
- Update dependencies: `clojure -M:antq --upgrade`

## Code Style Guidelines
- **Namespaces**: Use kebab-case (e.g., `parts.entity.user`)
- **Functions**: Use kebab-case with clear descriptive names
- **Docstrings & comments**: Explanatory length is fine when the thing being explained is genuinely complex; what matters is plain, simple language. Avoid jargon where a plain word works; don't restate what the code already says
- **Error handling**: Use `ex-info` with `:type` key for classification
- **Testing**: Use fixtures when appropriate, name tests with `test-` prefix
- **Formatting**: Follow cljfmt rules, 2-space indentation
- **Frontend**: Use UIx components with `defui` and `$` for React components
- **Imports**: Group by type (backend/frontend), alphabetize within groups
- **Privacy**: Use `defn-` for private functions to limit namespace exposure
- **Spec**: Use `clojure.spec` for data validation and model constraints

<!-- BACKLOG.MD MCP GUIDELINES START -->

<CRITICAL_INSTRUCTION>

## BACKLOG WORKFLOW INSTRUCTIONS

This project uses Backlog.md MCP for all task and project management activities.

**CRITICAL GUIDANCE**

- If your client supports MCP resources, read `backlog://workflow/overview` to understand when and how to use Backlog for this project.
- If your client only supports tools or the above request fails, call `backlog.get_backlog_instructions()` to load the tool-oriented overview. Use the `instruction` selector when you need `task-creation`, `task-execution`, or `task-finalization`.

- **First time working here?** Read the overview resource IMMEDIATELY to learn the workflow
- **Already familiar?** You should have the overview cached ("## Backlog.md Overview (MCP)")
- **When to read it**: BEFORE creating tasks, or when you're unsure whether to track work

These guides cover:
- Decision framework for when to create tasks
- Search-first workflow to avoid duplicates
- Links to detailed guides for task creation, execution, and finalization
- MCP tools reference

You MUST read the overview resource to understand the complete workflow. The information is NOT summarized here.

</CRITICAL_INSTRUCTION>

<!-- BACKLOG.MD MCP GUIDELINES END -->
