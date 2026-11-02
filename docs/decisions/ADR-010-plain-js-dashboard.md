# ADR-010: Dashboard in plain HTML/CSS/JavaScript served by the coordinator

Status: Accepted (2026-11-03)

## Decision
No React/Angular build chain. A multi-view single page with hash routing, ES modules, `fetch` polling (1–2 s), and a small hand-written SVG layered layout for workflow DAGs. Any third-party script (if needed for charts) is vendored into `static/vendor/` so the demo works offline.

## Consequences
Every team member can read and change the UI without learning a framework. Polling meets NFR2 (≤ 2 s freshness); Server-Sent Events can be added later if needed.
