---
name: terse-ui-text
description: >-
  The house rule for USER-FACING TEXT in the T1DMDROID app: default to NOT adding text at all, and
  where a string is unavoidable, keep it EXTREMELY short. Read this BEFORE writing or editing any
  string the user will see — a Compose `Text("…")`, a string resource, a label, subtitle, section
  header, `SettingsNote`, dialog title/body/button, snackbar/toast, notification, alarm, predictive
  alert, or any §3.6 refusal/warning. Covers what counts as user-facing, the one-sentence ceiling,
  how warnings and alarms compress while keeping their WHY, when to delete a note outright, and what
  is OUT of scope (comments, KDoc, logs, docs, SPEC). Triggers: "add a label", "warning message",
  "settings note", "dialog copy", "error text", "empty-state text", "button text", "notification
  wording".
---

# Terse UI text

The app is for one competent user who knows their own diabetes. Every extra word is friction. The
default is **not to add text**; where a string is unavoidable, spend as few words as possible.

## First, ask whether the text should exist at all

Before adding a string, try to avoid it:

- **A label already says it** → no `SettingsNote`, no helper line. Delete rather than restate.
- **An icon carries it** → prefer the icon (with a `contentDescription`) over a caption.
- **The value is self-evident** → a stepper showing `15 min` needs no "15 minutes" beside it.
- **It's reassurance or hand-holding** ("Tap to feel it now", "You can change this later") → cut it.

Reach for prose only when it carries a fact the user cannot infer from the control and its value.

## When a string is unavoidable

1. **One extremely short sentence, max. Prefer a bare fragment.**
2. **Sentence case. No trailing period** on a fragment, label, chip, or button. A real sentence keeps
   its period.
3. **Never restate the obvious.** Assume expertise. No preamble, no "This setting…".
4. **Keep every number, unit, and interpolated value.** Shorten the words, never the facts.
5. **Match existing terminology** (BG, IOB, COB, bolus, basal, mg/dL) — don't coin synonyms.

## Warnings, refusals, and alarms — compress, but keep the WHY

These carry safety weight. Compress them like everything else, but a competent user must still learn
what happened and why. **Never** reduce a refusal to a bare status code with the reason gone.

| Verbose | Terse (keeps the WHY) |
|---|---|
| "The reading is 18 minutes old, older than the 15-minute freshness limit, so a dose can't be advised." | `Reading 18 min old (limit 15)` |
| "No forecast is available because the sensor is still warming up." | `Warming up` |
| "Forecasting is paused because the phone is too hot." | `Too hot — paused` |
| "Approaching hypoglycemia in about 20 minutes." | `Low in ~20 min` |
| "Signal lost 12 minutes ago." | `Signal lost 12 min` |

A string that keeps its meaning while shedding words is the goal. A string that loses a number, a
threshold, a duration, or the reason itself is a regression — that is the one line not to cross.

## SettingsNote specifically

- **Delete** the note when it merely restates its knob's label.
- **Keep** it only for a non-obvious fact — a unit, a dependency on another knob, a consequence — and
  then as a fragment: `Independent of the alarm thresholds`, `0 % = off`.

## Scope — user-facing strings ONLY

IN: `Text("…")` literals, string resources, notification / alarm / predictive-alert text, dialog and
snackbar text, labels, subtitles, section headers, `SettingsNote`.

OUT — leave these in the codebase's own dense, literate voice; do **not** terse-ify them:
comments, KDoc, `Timber`/log messages, identifiers, kv keys, test names, the design docs, `docs/`,
`README`. Their audience is a developer, not the app's user.

## Before you commit a string

- Could it be deleted, replaced by an icon, or folded into a label? If yes, do that.
- Is it one short sentence or a fragment, sentence case, no stray period?
- If it's a warning/refusal/alarm: does it still say what and why, tersely?
- Did you change any string that a test, a `when`-branch, an accessibility match, or the settings
  search index compares by value? Update that consumer in the same edit.
