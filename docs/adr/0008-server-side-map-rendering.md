# ADR 0008: Server-side SVG rendering for Map exports and thumbnails

## Status

Accepted — 2026-05-24.

## Context

Two product features require generating images from a Map:

- **PDF export** — a printable Map for a client to take home as homework support
  (CONTEXT.md, "Features planned for post-launch"). The
  `GET /api/maps/:id/pdf` route is already wired but returns 501.
- **Thumbnail previews** on the Maps list at `/app/maps`.

The Map renders today in the browser via `@xyflow/react`, with per-type SVG
shapes (`resources/public/images/nodes/*.svg`), edge geometry that clips lines
to the visible outline (`aps.parts.frontend.geometry`), and `getBezierPath` for
edge curves. That rendering is interactive and lives entirely client-side; it
cannot service either of the new use cases on its own.

The two use cases have opposite profiles: PDF is high-fidelity,
low-frequency, latency-tolerant; thumbnails are low-fidelity, high-frequency,
latency-critical. One technology has to serve both.

## Decision

**Render Maps server-side as SVG in Clojure**, in two namespaces serving two
different jobs:

- `aps.parts.render.preview` — a glanceable, monochrome, iconic SVG (every
  Part is a brand-teal circle, every Relationship is a straight line).
  Optimised for **recognition at thumbnail scale** on the Maps list at
  `GET /api/maps/:id/preview.svg`.
- `aps.parts.render.document` — the high-fidelity styled SVG (inlined
  per-type shape SVGs, FontMetrics-wrapped labels, per-side bezier edges
  with per-type colours and an arrowhead marker). Transcoded to PDF via
  Apache Batik for the **printable client hand-out** at
  `GET /api/maps/:id/render.pdf`. Document chrome (title, date, "Made with
  Parts") is part of this renderer.

**Separated by use case, not by output format.** An initial framing of "one
renderer, two outputs (SVG and PDF)" was tried and rejected during build:
the two artifacts differ as *designs*, not as bytes. A 14px wrapped label
and a pixel-detailed yellow hexagon are invisible noise at 200px-thumbnail
scale, and a one-colour stick-figure topology is illegible as a printed
hand-out for a client. The right axis is *job*, not *format*.

Supporting decisions:

- **Promote `aps.parts.frontend.geometry` → `aps.parts.common.geometry`
  (`.cljc`).** The shape math is already pure and is now consumed both
  client-side (edge intersection in the canvas) and server-side (by the
  document renderer; the preview renderer skips it — straight lines need
  no intersection math).
- **Inline the existing `resources/public/images/nodes/*.svg`** (document
  only) as `<symbol>` definitions in `<defs>` at server boot, with one
  `<use width=100 height=100>` per Part. Preserves visual fidelity and
  keeps the SVG files as the single source of design truth. SVG's viewBox
  auto-stretch reproduces the canvas's CSS squish for the manager
  (100×108) and firefighter (120×120) shapes for free.
- **Replicate the canvas's edge style** (document only). Per-side cubic
  bezier (a server-side port of `getBezierPath`'s math) for singular
  edges; the existing quadratic-bow math (`edges.cljs/quadratic-path`,
  50px offset) for bidirectional pairs.
- **Render structure only by default** — Part shapes, labels, Relationship
  lines. Clinical fields (`notes`, `body_location`) are excluded because
  the PDF is a client-facing hand-out and CONTEXT.md's stance on clinical
  data is conservative. An `:include-notes?` opt-in flag is the extension
  point if a therapist-facing variant is needed later. The preview
  renderer ignores all of label, notes, and `body_location` by design.
- **Wrap label text server-side** with `java.awt.FontMetrics`, emitting one
  `<tspan>` per line (cap 3, ellipsis on overflow). Avoids `<foreignObject>`,
  whose Batik support is incomplete. The SVG declares
  `"Liberation Sans", Arial, sans-serif`; production runs on Linux with
  Liberation Sans available.
- **No server-side cache.** Emit an HTTP `ETag` computed from
  `MAX(lower(sys_period))` over the Map's parts, relationships, and
  metadata rows (`bt/latest-change-at` × 3, max in Clojure); browsers
  handle caching via `If-None-Match` → 304. Bitemporal already encodes
  the perfect invalidation signal — there is nothing the server needs
  to remember. Both renderers share the same ETag value (it indexes the
  Map's data, not the render variant — `preview.svg` and `render.pdf`
  are separate URLs in the browser cache anyway).

## Alternatives considered

- **Headless browser (Chromium + Playwright).** Renders the live canvas via
  the existing React Flow code; zero visual divergence. Rejected: ~300MB
  Chromium binary on the server, 1–5 second per-render cost (untenable for
  the thumbnail use case without aggressive caching), a moving security
  target, and the opposite of CONTEXT.md's "boring/reliable/proven" bias.
  Adds a JS runtime to a JVM service for a feature that does not need one.
- **Client-side capture, server stores.** Browser screenshots the canvas via
  `html-to-image` and POSTs to the server. Rejected: thumbnails only update
  when someone is viewing the Map, so a Map nobody has opened recently has a
  stale thumbnail. Forecloses any future background/server-side use (e.g.
  emailed handouts).
- **Hybrid — SVG for thumbnails, headless browser for PDF.** Each tool best
  at its job. Rejected: two systems, two dependency surfaces, two failure
  modes, for a small saving in renderer code. (Note: we *did* split into
  two renderers — but both stayed in-JVM, sharing tech and differing only
  in design. The "two systems" rejection was specifically about adding
  Chromium as a second runtime.)
- **One renderer, two output formats (SVG and PDF) via a single function.**
  Initially decided this way; rejected during build. Conflated *output
  format* (a tech axis) with *render design* (a product axis). A renderer
  parametrised to serve both ended up generating thumbnails with invisible
  14px labels and pixel-detailed hexagons — visual noise at thumbnail
  scale. The right axis is *use case*: preview-for-recognition vs
  document-for-print, sharing tech but separated as designs.
- **In-memory or persistent server-side cache.** Either a JVM memoize, or a
  rendered-SVG column on `map_metadata` populated on change-batch commit.
  Rejected in favour of HTTP ETag: bitemporal already gives an exact
  invalidation signal and the browser already has a cache; either
  alternative duplicates infrastructure the platform supplies for free.
- **SVG snapshot test for visual parity.** Render a small fixture Map,
  diff against a checked-in reference SVG. Considered and rejected at launch
  in favour of manual review at PR time — the test fixture maintenance cost
  is real, and the renderer is small enough that drift is visible in the
  PR diff itself. Easy to add later if drift becomes a real problem.

## Consequences

- **Three renderers to keep aligned, in two parity pairs.** The canvas
  (`@xyflow/react` + CSS) and the document renderer must produce visually
  compatible output for the print artifact — drift is bounded (both
  consume the same shape SVGs, the same geometry polygons, the same edge
  math) but real. The preview renderer has no canvas counterpart — it's
  a purposeful simplification, so canvas/preview drift is not a meaningful
  concept. **Defended by manual review at PR time only.** That is a
  deliberate trade: no snapshot test, no CI safety net. Designers tweaking
  a node SVG, or a CSS color change to the canvas, will not break a test —
  the first signal will be an export that looks noticeably off. The bet
  is that visible drift gets caught quickly because the export is
  something the operator and the cohort look at frequently during the
  launch phase. If that bet fails, the answer is an SVG snapshot test,
  not a heavier visual-diff system.
- **Apache Batik becomes a runtime dependency** — `batik-transcoder` plus a
  handful of transitive Batik / Xerces jars (~10MB total). Mature and stable;
  not in active feature development upstream but well-maintained for security.
  No other code path uses it, so it stays cleanly excisable.
- **A server-side font dependency.** Both `java.awt.FontMetrics` and Batik
  resolve fonts from the JVM. The SVG declares
  `"Liberation Sans", Arial, sans-serif`; Liberation Sans is present on every
  standard Linux distribution including the production runtime. We do not
  ship a font.
- **`MAX(lower(sys_period))` becomes part of the public ETag contract.**
  Any future change to how bitemporal rows are written — e.g. a soft
  re-write that closes and reopens `sys_period` without a real data
  change — would spuriously invalidate the ETag and force re-renders.
  Worth a note on the change-event module if that pattern ever appears.
- **The Scrubber gets rendering at any `valid_at` for free.** The renderer
  takes already-hydrated Map data; passing a time-sliced view (via
  `bt/as-of-valid`) renders any historical state without renderer changes.
  When the Scrubber ships it can produce a printable "Map as of last
  Tuesday" with no new render code.
- **`notes` and `body_location` can be added to the PDF as an opt-in later.**
  The `:include-notes?` flag is the obvious extension point; until then the
  renderer is client-facing-safe by construction. The structural choice
  (exclude by default, opt-in to include) means accidental disclosure
  requires writing code, not removing a flag.
