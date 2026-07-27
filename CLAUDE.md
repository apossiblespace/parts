# Parts Project Guide

## Development Environment

The project uses Nix + direnv for reproducible development environments.

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
See `make help` for the standard targets (repl, test, format, build).
- Run single test: `clojure -X:test/env:test/run :focus my.namespace/test-name`
- Start frontend development: `pnpm exec shadow-cljs watch frontend`
- Update dependencies: `clojure -M:antq --upgrade`

## Code Style Guidelines
- **Docstrings & comments**: Explanatory length is fine when the thing being explained is genuinely complex; what matters is plain, simple language. Avoid jargon where a plain word works; don't restate what the code already says
- **Error handling**: Use `ex-info` with `:type` key for classification
- **Testing**: Use fixtures when appropriate, name tests with `test-` prefix
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
