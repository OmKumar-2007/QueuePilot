# ADR-007: A small field-list schema instead of full JSON Schema

Status: Accepted (2026-11-03)

## Options
- Full JSON Schema + a library (e.g. networknt) — powerful, but a large spec to learn and an extra dependency.
- **A field list with types and constraints**, validated by ~150 lines of our own code.

## Decision
Field-list schema (types: STRING, INTEGER, NUMBER, BOOLEAN, ENUM, *_LIST, FILE; constraints: required, min, max, maxLength, maxItems, values). Unknown fields are rejected. Total input size is capped at 64 KB (NFR1); larger data goes through artifacts.

## Consequences
Easy to explain and test; enough for every planned task. Can be replaced by JSON Schema later behind `InputValidator`.
