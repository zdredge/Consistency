# CLAUDE.md

Personal habit accountability app for Android. Single user, single device (Google Pixel), no
backend, no accounts, no cloud services. Native Kotlin and Jetpack Compose.

## Read before writing any code

1. `docs/product-spec.md` — **the authority on behaviour.** Read §1 and the appendix in full before
   your first change. §1 explains why most other decisions were made; the appendix lists decisions
   that look like inefficiencies and are not.
2. `docs/architecture.md` — how it is built. §5 has the data model with a worked example.
3. `docs/scoring-cases.md` — the scoring rulebook as concrete expected values. Treat as the test
   suite specification for `:domain`.
4. `docs/build-order.md` — the agreed phased build plan. Which milestone is in progress governs
   what you may build.

**M0 through M4.5 are complete.** The agreed next work is the **four defects M4.5 found**, listed at
the end of its build-order section — all four concern state after leaving a check-in question, and
two of them share a root. **M5 (the rollover job) follows.** Do not begin a later milestone than the
one in progress. Two ordering facts behind it: `:domain` can be proven correct
without a device, and the dashboard cannot be evaluated without substantial seeded history, so scoring
belongs early and the dashboard late. The dashboard is also gated on the seed-data fixture (spec O7;
designed as build-order M9) and the goal-completion formula (spec O1).

**Precedence:** where the spec and the architecture document disagree, the spec wins and the
architecture document is wrong. Say so rather than picking one silently.

## Export — decided

Export is a plain **full-database JSON snapshot** written to a user-chosen location via Android's
system document picker (`ACTION_CREATE_DOCUMENT`). **Do not build a Google Drive API integration** —
there is no cloud project, no OAuth, and no budget for one; the user picks Drive from the system sheet
if they want it there. This was previously a spec/architecture conflict (spec §2 once said "optionally
to Drive"); it is now resolved and applied in the spec (spec §2, architecture §4 and §7).

## Hard rules

The full list is the appendix of the spec, each with the reason it exists. These are the ones most
likely to be broken by well-intentioned code:

- **Never merge response rate, daily goal completion and weekly goal completion into one number.**
  Three separate donut rings answering three different questions (not answering vs not doing daily vs
  not doing weekly). Goal completion is split by granularity so weekly goals aren't drowned by the
  more frequent daily ones. Spec constraint 8, §5.1, §3.4.
- **Never persist a derived value.** Sleep duration, lingering minutes, weekly roll-ups and hit rates
  are computed on read. See architecture T4.
- **All date arithmetic goes through the injected `Clock` and `DayResolver`.** Nothing else decides
  which day a timestamp belongs to. The day boundary is 04:00, not midnight.
- **Sleep items belong to the day the user went to bed**, so the morning check-in writes answers
  dated *yesterday*. `answers.day_date` is deliberately not derivable from the check-in's date. Do
  not "tidy up" this apparent denormalisation.
- **`capture` and `edited_at` are two separate fields**, not one state enum. An answer can be
  backfilled and later edited.
- **Absence-based goals score only on answered days.** An empty answer must never satisfy a
  must-not-include target. This is the single most likely scoring bug. The one exception is an
  unresolved pending answer, which scores as missed — see scoring-cases A2.
- **A no-opportunity answer is a neutral exclusion, not a miss.** A goal may flag one select option
  as "no opportunity"; when chosen it excludes that period from goal completion, does not break the
  item's run, and leaves response rate untouched (the check-in is answered). Evaluate it *before*
  direction. Keep its usage visible on the detail view — do not let it silently inflate completion,
  and do not score it as a miss. Distinct from silence (scoring-cases 1.13) and pending (A2). Spec
  §3.4, constraint 17, scoring-cases §10.
- **Hit rate and average attainment are separate numbers and both must be produced.** Binary scoring
  is deliberate, but reporting only hit rate makes "1.5 of 2 bottles every day" read as total
  failure. Never merge them, never substitute one for the other.
- **A `LATE` answer records data without repairing the metric.** It does not un-miss a check-in and
  does not restore a run.
- **The hardcoded sleep-metric logic is intentional special-casing, not technical debt.** Do not
  generalise it into a formula engine.
- **Items are versioned and retired, never deleted.**
- **Enums persist by name, never by ordinal, and dates as ISO-8601 text.** An ordinal means
  reordering `Direction` silently re-labels every historical row. ISO-8601 makes a `day_date` range
  query sort chronologically as text, with no conversion in the WHERE clause. Architecture §5.
- **A check-in's window is its own day**, ending at 04:00. `IN_WINDOW` until then, `BACKFILLED`
  through the end of the next day, `LATE` after. Spec §2's "repeat twice, then mark missed" is the
  notification sequence stopping, not the window shutting. Capture is measured against the **check-in
  being answered**, never the day the answer is dated to — a sleep item answered this morning is
  dated yesterday but is in its own proper window, and confusing the two makes every morning check-in
  read as a backfill. Spec §3.2, `CaptureResolver`.
- **A check-in that would ask nothing is never expected.** No row, so it cannot be missed. Covers
  install day (the morning check-in covers yesterday, when no item existed) and a fully retired
  library. Found in M4 by installing the app and looking at it.
- **Moving a target to weekly forgives clustering, which is right for a lower bound and wrong for an
  upper bound.** Worked out ≥ 3/week is correct; coffee ≤ 14/week alone is not, because five in one
  day passes it. An upper bound needs the daily target too. Found in M3; spec §4.

## Module boundaries

| Module | Contains | May depend on |
|---|---|---|
| `:domain` | Scoring, runs, roll-ups, derived metrics, target resolution | Nothing Android |
| `:data` | Room entities, DAOs, migrations, repository, Health Connect | `:domain` |
| `:app` | Compose UI, ViewModels, receivers, workers, scheduler, notifications | `:data`, `:domain` |

**`:domain` must have zero Android imports.** If it appears to need one, the logic is in the wrong
module. This is what makes the whole rulebook testable without an emulator, which is the point.

## Conventions

- Kotlin, coroutines and Flow. Suspend functions for anything touching storage or Health Connect.
- Package root `com.zdredge.consistency`. Toolchain and versions live in `gradle/libs.versions.toml`;
  the current set is recorded in architecture §4. **AGP 9 compiles Kotlin natively** — do not add
  `org.jetbrains.kotlin.android` — and Gradle's configuration cache is on, so a custom task that
  resolves configurations at execution time will break the build.
- **`:domain` is a plain Kotlin JVM module, not an Android library.** That is what makes the
  Android-free rule real rather than aspirational. Do not convert it. `:data` uses
  `api(project(":domain"))` so `:app` sees domain types through it.
- Source dirs by module type: `src/main/kotlin` in `:domain`, `src/main/java` in the Android modules.
- All date arithmetic goes through `DayResolver` (in `:domain`) and the injected `java.time.Clock`.
- The scoring rulebook lives in `:domain`'s `scoring` package and is **pure**: it receives plain data
  and returns numbers, never touching storage or the real clock. `GoalOutcome` is **tri-state**
  (`MET`/`MISSED`/`EXCLUDED`) and `GoalResult` carries an `ExclusionReason`. Do not reduce either to
  a Boolean: three different flavours of "no positive answer" have to stay apart, and the reasons are
  not interchangeable (silence costs a check-in, no-opportunity costs nothing).
- Manual constructor injection via `AppContainer` in `:app`. No DI framework unless the wiring
  becomes genuinely painful, and then ask first.
- One answer row per item per day, keyed `(item_id, day_date)`.
- Enums for `answer_type`, `direction`, `capture`, `slot`, `state`. No magic strings.
- All Health Connect calls behind a single interface in `:data`, with **one** implementation. The
  interface exists for version pinning, not provider abstraction.
- Charts: Compose Canvas for the calendar heatmap, Vico for line charts. Time-of-day line charts must
  respect the 04:00 boundary or a 01:30 bedtime plots as the earliest night of the month.

## UI

Rules earned in M4.5, which rebuilt the check-in screen. The full account is in that milestone's
build-order section.

- **A displayed value must never look like a given one.** This is the dangerous class in this layer
  and it recurs on every new surface. The number stepper rendered `valueNumber ?: 0.0`, so an
  unanswered question showed `0` — identical to a real zero, which scores differently. The
  always-visible time dial had the same trap, and the summary would have been the third. It is the UI
  face of constraint 11: silence and an answer are different states, so the screen must never make
  them look alike. Prefer designs where the distinction is structural — nothing is selected until
  something is tapped — over designs that rely on a placeholder.
- **Every answer type renders through `AnswerOption`** (`app/.../ui/checkin/AnswerOption.kt`). A new
  answer type does not get its own control. Four slightly different `FilterChip` treatments drifted
  into existence before this, which is how "all answers look the same" stops being true.
- **Value formatting is shared** in `app/.../ui/checkin/AnswerFormat.kt`, so an input and the summary
  cannot disagree about the same stored number — no `19:30` under a dial reading 7:30 PM.
- **Skips are shown, never scolded.** No red, no warning, no gate on finishing. Silence is a real
  answer; a screen that pressures the user out of it corrupts what is being measured.
- **What the user sees must match what was stored.** Three M4.5 defects rendered perfectly and were
  wrong underneath, and each was found by reading the database or by tapping a value that did not
  change — not by looking. When verifying a UI change that writes, check the row.

## Testing

- `:domain` gets fast JVM unit tests covering every case in `docs/scoring-cases.md`. This is not
  optional; scoring failures are silent and produce plausible wrong numbers rather than crashes.
  **As of M2 this is complete**: all 77 in-scope cases are proven, and every test display name leads
  with its case ID so coverage can be checked mechanically against the document. The six absences are
  the rendering and first-run-suppression cases belonging to M10.
  **JUnit 5 (Jupiter)**; `@ParameterizedTest` maps onto the scoring-cases tables, one test per table
  rather than one per row.
- **Test naming: conventional camelCase identifiers plus `@DisplayName`.** Put the doc-faithful text
  in the display name and **lead it with the scoring-case ID** it covers, e.g.
  `@DisplayName("8.5 - a 01:30 bedtime on the 26th is dated the 25th")`. Backticked Kotlin test names
  are not used: a JVM method name cannot contain `.` or `:`, so they cannot write `04:00` or carry a
  case ID at all. Naming this way makes coverage of `scoring-cases.md` checkable from the test report.
- Run them with `.\gradlew.bat :domain:test` (add `--rerun-tasks` to defeat Gradle's up-to-date
  check); the readable report is at `domain/build/reports/tests/test/index.html`.
- `:data` gets **instrumented** tests on the device, on **JUnit 4** as Android requires; JUnit 4 and
  5 coexist in the project. Robolectric was considered in M3 and rejected: migrations and non-trivial
  queries are exactly what a re-implemented SQLite would lie about. Run them with
  `.\gradlew.bat :data:connectedDebugAndroidTest` — **the Pixel must be connected** (`adb devices`).
  The exception is the schema version pin, which needs no SQLite and so runs as a JVM test under
  `.\gradlew.bat :data:testDebugUnitTest`.
- **`:app` has no tests, deliberately.** Every decision that could be *wrong* rather than merely
  ugly lives in `:domain`; what is left in a ViewModel is assembly and what is left in a composable
  is layout. If you find yourself wanting to test a ViewModel, that is the signal a rule has leaked
  upward — move it down rather than extracting a repository interface to fake.
- **Before trusting a test that guards something important, make it fail.** M3 produced two tests
  that could not: one asserted a drift the build makes impossible, and one read a stale packaged
  asset. Both looked green and guarded nothing. Mutating the code under a new guard costs one run.
- Alarm scheduling and the boot receiver are currently expected to be verified by hand on the device.
  If you see a better approach, propose it — this is open question T3 and the user is a QA analyst
  who is not satisfied with the current answer.

## Do not

- Add features not in the spec. If something seems missing, say so; do not implement it.
- Build anything from a later milestone in `docs/build-order.md` than the one in progress.
- Add motivational or encouraging copy. The product is deliberately blunt. See spec §2,
  permanently out of scope.
- Add escalating notifications for repeated misses. Chosen against deliberately.
- Introduce any paid, cloud, or account-requiring dependency. Firebase and FCM are not needed;
  every notification is a local alarm.
- Generalise a specific named behaviour into an abstract composite. "Watched YouTube" and "scrolled
  on phone" must stay distinguishable; there is no "screen time" metric.

## Ask first

- Anything that changes the schema of `answers`, `checkins` or `targets`.
- Anything that touches how a day, week or run is defined.
- Adding a dependency.
- Any change that would contradict the spec appendix, even if the spec looks wrong.

## Glossary

- **Item** — a thing tracked. *Asked* items appear in check-ins; *measured* items come from Health
  Connect and are never asked.
- **Goal / Observation** — a goal has targets and is scored; an observation is recorded and charted
  but never scored. User-classified. Mindset is an observation; the weekly social items are goals.
- **No opportunity** — a neutral select option on certain goals ("took time for yourself", the three
  weekly social goals) for a period that genuinely precluded the behaviour. Excluded from goal
  completion, does not break the run, still counts as an answered check-in. Not a miss, not silence.
- **Slot** — when an asked item appears: morning, night, or weekly.
- **Check-in** — one scheduled session. A row exists for every check-in that was *expected*, which
  is the denominator for response rate.
- **Capture** — how an answer was first recorded: in-window, backfilled, late, or pending.
- **Pending / "not yet"** — a night goal deferred to the next morning's check-in; still counts as
  in-window if resolved there.
- **Direction** — the comparator on a target: at least, at most, exactly, is true, is false, must
  include, must not include.
- **Response rate** — proportion of expected check-ins answered. The primary metric.
- **Goal completion** — proportion of targets met; secondary to response rate. Split by granularity
  into **daily goal completion** (met daily-goal instances over answered days) and **weekly goal
  completion** (met weekly-goal instances over closed weeks), shown as two donut rings and never
  merged with each other or with response rate. Spec §5.1, O1.
- **Run** — consecutive days on which every scheduled check-in was answered. Measures showing up,
  not performing. Backfill within grace preserves it.
- **Hit rate** — how often a single item met its target over the 14-day window. Drives the
  going-well / middling / going-badly panels at 80% and 60%.
- **Average attainment** — for numeric goals, how close the user came to the target on average over
  the window. Reported beside hit rate, never merged into it. Not meaningful for at-most directions.
- **Roll-up** — a weekly figure derived from one daily item plus an aggregation.
- **Derived metric** — sleep duration and lingering minutes, computed from the three sleep/wake time
  items. Hardcoded, never persisted.
