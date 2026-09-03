# Build Order

**Status:** proposal for approval. Nothing here is agreed yet and no code has been written.
**Intended repo path:** `docs/build-order.md`
**Companion documents:** `docs/product-spec.md` (authority on behaviour), `docs/architecture.md`
(how it is built), `docs/scoring-cases.md` (the `:domain` test spec).

**Why this document exists.** `CLAUDE.md` says there is deliberately no phased plan in the repo and
that one must be proposed and agreed before building. This is that proposal. It also references
`docs/build-order.md` in its "Do not" section — "build anything from a later milestone than the one
in progress" — so this file is the referenced milestone list. Until it is approved, treat it as a
draft: the milestone boundaries and the two decision gates below are the parts most worth arguing
with before a line of Kotlin is written.

**How to read this.** Same tagging convention as the architecture document:

- **Decided-by-docs** — the ordering follows directly from a stated constraint; changing it means
  reopening that constraint.
- **Proposed** — my sequencing choice, defensible but negotiable. Push on these.
- **Gate** — work that cannot proceed until a named decision or verification is resolved.

---

## 0. Guiding principles for the sequence

Four facts fix most of the order; everything else is arrangement around them.

1. **`:domain` can be proven correct with no device** (`CLAUDE.md`, architecture §4). Scoring is the
   part that fails *silently* — a wrong number, not a crash — and it is the part with a ready-made
   test spec in `docs/scoring-cases.md`. So scoring is built **first and test-first**, before any
   Android surface exists to distract from it.
2. **The dashboard cannot be evaluated without months of history** and was blocked on two items —
   the goal-completion formula (spec **O1**, now resolved as a daily + weekly ring split) and a
   seed-data fixture (spec **O7**, now build-order M9). So the dashboard is built **last**.
3. **The four hard requirements are platform integrations, not logic** (architecture §1):
   exact-time notifications, step reads, the ~04:00 rollover, and reschedule-after-reboot. Two of
   them rest on **unverified** platform behaviour the architecture says to *verify first*
   (architecture §2, open question T1). Those verifications are afternoon spikes and gate only the
   phases that depend on them, so they run up front and in parallel with domain work.
4. **The app is barely running most of the time** (architecture §1.1). Anything that must survive
   the process dying lives in the database, not in memory. That shapes *what* each phase persists,
   not the order, but it is the reason the check-in loop and the rollover job are separate phases.

### TDD posture — where test-first genuinely applies, and where it does not

The request is TDD "when possible", and the honest answer is that its applicability varies sharply
by module. The plan is arranged to **maximise the surface that can be driven by fast tests** by
pushing logic out of the untestable Android edges.

| Layer | Test-first? | How |
|---|---|---|
| `:domain` scoring, runs, derivation | **Yes, strictly.** | `docs/scoring-cases.md` is already the failing-test list. Red → green → refactor, one case ID per test. Pure JVM, no emulator. |
| Pure logic extracted from workers/schedulers | **Yes.** | "Which check-ins should exist for day N", "which alarms to set given settings" are pure functions returning plain data. Test them; the Android wrapper just executes the result. This is the main lever for TDD-ing the parts architecture §4 calls hard to test (T3). |
| `:data` Room migrations, DAO queries, mapping | **Yes, with a caveat.** | Instrumented/Robolectric tests. Slower feedback than JVM but still test-first: write the query's expected result, then the DAO. Migrations get a test each. |
| ViewModels | **Yes.** | Inject a fake repository and the test `Clock`; assert emitted state. No Compose needed. |
| Compose UI | **No — manual.** | Front-end tests are tedious for the value returned here. The UI is verified by hand on the device. This is why the plan pushes every decision worth testing *out* of the Compose layer and into ViewModels and pure functions, so what is left in the UI is presentation with nothing to assert. |
| Alarms, boot receiver, notification delivery | **No — hand-verified.** | Architecture T3 is unresolved and flagged by the user (a QA analyst) as unsatisfying. The mitigation is to shrink this untestable surface to the thinnest possible wrapper (see M6) so almost nothing lives here untested. |

**The rule of thumb for the whole build:** if a behaviour can produce a wrong *number* or a wrong
*decision*, it belongs in a pure function with a test. If it can only produce a wrong *side effect*
on the device, isolate it so there is nothing left in it to get wrong.

---

## Milestone overview

| # | Milestone | Test approach | Gated by | May run parallel with |
|---|---|---|---|---|
| M0 | Platform verification spikes | Throwaway; findings written up | — | M1, M2 |
| M1 | Project skeleton, modules, `Clock`/`DayResolver` | TDD (JVM) | — | M0 |
| M2 | `:domain` scoring engine | **TDD (JVM), the core TDD phase** | M1 | M0 |
| M3 | `:data` persistence layer | TDD (instrumented + Robolectric) | M1, M2 | — |
| M4 | Check-in loop (capture, backfill, pending) | TDD ViewModels; UI manual | M2, M3 | — |
| M5 | Rollover job (day close, expected check-ins, freeze) | TDD the pure core; hand-verify the worker | M3, M4 | — |
| M6 | Notifications, alarms, boot reschedule | Pure scheduling logic TDD'd; delivery hand-verified | M5 | — |
| M7 | Health Connect steps | TDD the mapping; hand-verify the read | M0, M3 | M4–M6 |
| M8 | Item detail views and charts | ViewModel TDD; charts hand-checked | M3, M4 | M7 |
| M9 | Seed-data fixture (spec O7) | N/A — it *is* test scaffolding | M3 | M4–M8 |
| M10 | Dashboard | ViewModel TDD against seeded data | M9 | — |
| M11 | Export | TDD the serialiser; hand-verify the picker | M3 | M10 |

---

## M0 — Platform verification spikes

**Decided-by-docs.** Architecture §2 says, in bold, *verify this first*. Two assumptions the whole
platform integration rests on are marked **unverified**, and both are checkable in an afternoon.
Doing them now means a bad surprise lands before, not after, the integration phases are built on top.

**Deliverables** — throwaway spike code, deleted after; findings recorded in `architecture.md` §2 by
replacing the **unverified** tags with what was observed.

1. **On-device Health Connect step counting** (architecture §2, the single most decision-relevant
   unknown). Grant `READ_STEPS`, walk, confirm records appear attributed to the *device* and not to
   a source app. If it works, M7 is straightforward and the raw-sensor escape hatch stays unbuilt.
   If it does not, that changes M7's scope before any of it is written.
2. **Exact alarms.** Confirm `USE_EXACT_ALARM` can be declared and that
   `setExactAndAllowWhileIdle` fires on time on the actual Pixel with battery optimisation in its
   real state. This is the mechanic the product is built around; if it is unreliable, that is a
   product-level conversation, not a coding one.

**Exit criteria.** Both questions answered yes/no against the real device, and architecture §2
updated. No production code produced or expected.

**Note.** This milestone has no dependency on the domain work, so M1/M2 begin immediately alongside
it. It gates M6 and M7 only.

---

## M1 — Project skeleton, module boundaries, the clock

**Proposed.** A thin but real foundation, established test-first so the discipline is in place from
the first commit rather than retrofitted.

**Deliverables.**
- Three Gradle modules per architecture §5: `:domain` (no Android imports), `:data`, `:app`, with
  the dependency direction enforced. **`:domain`'s Android-free-ness is worth a check** — even a
  simple test asserting it has no Android on its classpath, because architecture §5 calls it "the
  rule that matters".
- The single injected `Clock` and `DayResolver` (architecture §5, spec appendix constraint 1). This
  is built here, first, because *nothing else in the codebase is allowed to decide which day a
  timestamp belongs to*, and M2 needs it on day one.
- Manual constructor injection wiring (architecture §4; `CLAUDE.md` conventions). No DI framework.

**TDD.** `DayResolver` is pure and is the first red-green-refactor in the repo. Cases come straight
from the spec's 04:00 rule and the sleep-day convention:
- 03:59 belongs to the previous day; 04:00 to the current day.
- A 01:30 timestamp resolves to the previous day (scoring-cases 8.2, 8.5).
- Week-of resolution starts Monday (spec §3.1), derived from the date, never stored.
Write those assertions first; implement `DayResolver` to pass them.

**Exit criteria.** Modules compile with the boundary enforced; `DayResolver` and `Clock` exist with
a green test suite; the test `Clock` can be driven to any instant.

---

## M2 — The scoring engine (`:domain`) — the core TDD phase

**Decided-by-docs.** This is where the plan spends its TDD budget. `docs/scoring-cases.md` is
explicitly "the test suite specification for `:domain`", every case "should be a JVM unit test with
no Android dependency". So the milestone is, almost literally: **turn each case ID into a failing
test, then make it pass.**

**Deliverables — worked in this sub-order, because later cases depend on earlier machinery:**

1. **Target resolution and directions** — scoring-cases §1 (1.1–1.13) and §5 (effective-from,
   container sizes). Build target lookup by `(item, period, date)` first; several later cases can't
   be expressed without it.
   - **1.13 is the highest-priority test in the whole document** (spec constraint 11: silence is
     not success). Write it early and keep it green: a missing answer to a `MUST_NOT_INCLUDE` goal
     is **excluded**, never satisfied. A naive "forbidden option absent → true" implementation
     passes everything else and fails only this, silently inflating goal completion.
2. **Binary scoring with separate attainment** — scoring-cases §2. Assert *both* numbers on every
   numeric case: hit/miss binary, and attainment as an independent value that caps at 100% (2.4) and
   is *absent — not zero* for at-most directions (2.5). Constraint 16: never merge, never drop.
3. **Capture states in scoring** — scoring-cases §3. In-window vs backfilled vs late vs pending, and
   the separation of `capture` from `edited_at` (3.4). The in-window-only figure must be derivable
   from `capture` alone.
4. **Runs** — scoring-cases §4. The run counts days *answered*, not performed (4.4 is the whole
   point). Longest run only ever increases (4.2). Per-item runs independent (4.5).
5. **Item lifecycle** — scoring-cases §6. Active-in-period only; versioned answers each score under
   their own version; options hang off the item, not the version.
6. **Dual-granularity targets** — scoring-cases §7. Daily and weekly scored independently, never
   collapsed (7.2 is the justifying case).
7. **Derived metrics** — scoring-cases §8. Sleep duration and lingering, the hardcoded pair only
   (spec constraint 13). The wrap-past-midnight rule (8.3) and unavailable-not-zero (8.4).
8. **The three resolved rulebook additions** — scoring-cases A1 (`LATE` records data without
   repairing the metric), A2 (unresolved pending → missed goal, check-in stays answered), A3
   (roll-ups count observed, label incomplete).
   - **A2.1 vs 1.13 must be asserted together in one test** as scoring-cases instructs: A2.1 is the
     *only* case where an absent value scores as missed, and 1.13 is the opposite. Testing them side
     by side records the asymmetry as deliberate so nobody later "fixes" it into consistency.
9. **No-opportunity exclusion** — scoring-cases §10. A designated select option excludes the period
   as neutral, leaving response rate and run intact, and is evaluated **before** direction. **Assert
   10.3 against 1.13 and A2.1 in one test** — silence excluded-and-costly, pending missed,
   no-opportunity excluded-and-free — so the three-way distinction is locked. Also 7.3–7.4: worked
   out / stretched / coffee are weekly-only targets, still binary per week, not softened daily ones.

**TDD mechanics.** Every case above already has Given/Then columns — those are assertions waiting to
be typed. Work one section at a time: paste the section's cases as `@Test` stubs that fail, then
implement the smallest `:domain` change that greens them, then refactor. Domain types are plain
Kotlin data classes fed by the caller; the engine never reaches into a database or asks the real
time (architecture §6, "the scoring path is one-directional").

**Goal completion in M2.** With O1 resolved (spec §5.1: a **daily** ring and a **weekly** ring, each
instance-based within its granularity), both goal-completion aggregates are pure `:domain`
computations and are built and tested here — scoring-cases §11 — alongside response rate, hit rate,
per-item completion and attainment. Only the *rendering* of the rings waits for M10.

**Exit criteria.** Every scoring-rule case in `docs/scoring-cases.md` is a green JVM test — including
the goal-completion ring *aggregates* in §11 (the daily and weekly ratios, kept distinct); only their
rendering and first-run suppression (11.5, 11.6, 11.8) belong to M10. The suite runs with no emulator.
This suite is the project's regression backbone from here on.

---

## M3 — Persistence (`:data`)

**Proposed.** Now that `:domain` defines the shapes it needs, build the store that feeds it. Schema
is fixed by architecture §5; this milestone realises it and proves the queries.

**Deliverables.**
- Room entities, DAOs, migrations for the tables in architecture §5: `items`, `item_versions`,
  `select_options`, `targets`, `container_sizes`, `checkins`, `answers`, `answer_selections`,
  `measured_values`, `measured_origins`. `user_id` on every table from the first migration
  (constraint 15), unused.
- The repository that maps Room entities ↔ `:domain` types. **Derived values are never persisted**
  (`CLAUDE.md`; architecture T4) — the repo hands raw rows to `:domain` and stores nothing computed.
- **Typed nullable value columns**, not a JSON blob (architecture T2, marked Assumed — flag if the
  mapping turns ugly).

**TDD.**
- Reproduce the architecture §5 **worked example (Tuesday 25 Aug 2026)** as a fixture and assert it
  round-trips: four sleep answers carrying `day_date` = the 25th while arriving via the 26th's
  morning check-in. This is the concrete proof that `answers.day_date` is not redundant.
- One test per migration.
- DAO query tests written result-first: state the rows expected back, then write the query.
- A test that a single day cannot hold two answer rows for one item — `(item_id, day_date)`
  uniqueness (`CLAUDE.md` conventions).

**Ask-first checkpoint.** `CLAUDE.md` requires asking before any schema change to `answers`,
`checkins` or `targets`. This milestone *implements* the documented schema; it does not change it. If
the worked example reveals a needed column, that is a stop-and-ask, not a quiet edit.

**Exit criteria.** Schema migrates cleanly; the worked example persists and reloads intact; DAO
tests green; the repo feeds `:domain` with no derived values stored.

---

## M4 — The check-in loop

**Proposed, and this is the first thing the user can actually use.** The core interaction: answer a
check-in in under 60 seconds, backfill a missed one in place, defer with "not yet".

**Deliverables.**
- Check-in screen (spec §5.6): tap-first, keyboard only for the optional note. One primary answer
  format per item plus the note (constraint 9).
- Answer capture writing the correct `capture` state and `day_date`, including the **sleep-day
  convention**: the morning check-in writes answers dated *yesterday*, and the UI must make that
  date unmistakable (spec §3.1, §5.6; `CLAUDE.md`).
- "Not yet" / pending, carried into the next morning and labelled as belonging to the previous day.
- In-place backfill from the outstanding-check-in banner (spec §5.1).
- The **no-opportunity** answer option on the goals that carry it ("took time for yourself" and the
  weekly social goals) — presented as an ordinary answer choice. Its neutral scoring lives entirely
  in `:domain`; the screen only needs to record the chosen option.

**TDD.** The capture-state and day-dating decisions are logic, not UI — extract them into a pure
function or a ViewModel tested with a fake repo and the test `Clock`, and drive those first:
- Morning answer for a sleep item lands on `day_date` = yesterday.
- Answer inside window → `IN_WINDOW`; after close but within grace → `BACKFILLED`; "not yet"
  resolved next morning → `IN_WINDOW` (scoring-cases 3.3).
- **The Compose screen itself is verified by hand** — no front-end tests. The extraction above is
  what makes that safe: the screen should be left with nothing to assert but layout.

**Exit criteria.** A check-in can be completed, deferred, and backfilled on a real device; every
capture-state transition is covered by a fast test; the previous-day labelling is present and tested.

---

## M5 — The rollover job

**Decided-by-docs.** Architecture §6 calls this "the only thing that writes without the user" and
warns that if it silently fails "the app looks fine and every number is subtly wrong". It is
separated from M4 because it is the process-not-running case in concentrated form.

**Deliverables.**
- A `WorkManager` job near 04:00 that: closes the previous day, generates the next day's **expected
  check-in rows** (the denominator for response rate — without them the primary metric is
  unmeasurable), converts unresolved pending answers to missed goals (scoring-cases A2), and freezes
  provisional step values (spec O4: provisional 24h, then frozen).
- Last-successful-rollover recorded and surfaced in-app, not only in logs (architecture §8 risk row).

**TDD.** The worker is split in two:
- **A pure "rollover plan" function** — given the current stored state and a date, return the list
  of rows to write and states to change. This is fully testable in JVM: assert the expected
  check-ins generated, the pending→missed conversions, the freeze transitions. This is where the
  behaviour lives and where the tests are.
- **A thin `WorkManager` wrapper** that runs the plan and persists it. Almost nothing in it to test;
  what remains is hand-verified.

**Exit criteria.** The rollover plan is green under JVM tests including the A2 pending conversion and
the O4 freeze timing; the worker runs on-device and records its last success; staleness is visible
in the app.

---

## M6 — Notifications, alarms, boot reschedule

**Gate: M0 exact-alarm spike must have passed.** Decided-by-docs that this is a hand-verified
milestone (architecture T3) — but the plan deliberately shrinks what "hand-verified" covers.

**Deliverables.**
- `AlarmManager` exact alarms for the two daily slots plus the escalation repeats — fire, repeat
  twice at ~20 min, then mark missed (spec §2). No escalation *beyond* that; louder-after-misses is
  permanently out of scope (spec §2; `CLAUDE.md`).
- High-importance notification channel (architecture §4). The channel can be silenced by the user
  and that cannot be engineered around — which is *why* confrontation lives at app-open, not in
  louder notifications. Nothing in this milestone tries to defeat muting.
- `BOOT_COMPLETED` receiver that reschedules every alarm (architecture §4, §6): a reboot otherwise
  silently ends all notifications.
- The `Scheduler`, with its **two callers** noted in architecture §6: the boot receiver, and the
  settings screen when the night check-in time changes (which must cancel and re-set real alarms,
  not merely store a preference).

**TDD.** Same split as M5. "Given these settings and this now, which alarms should exist?" is a pure
function returning a list of alarm specs — tested. The `Scheduler` then sets and cancels exactly
that list. What is left to hand-verify on the Pixel: that a set alarm actually fires on time, and
that alarms actually return after a reboot. Those two facts, and only those, are the untestable
residue T3 is about.

**Exit criteria.** The alarm-spec function is green under tests; on the device, both daily check-ins
fire on time, escalation repeats behave, a missed check-in is marked missed, and alarms survive a
reboot. Settings-time-change re-sets alarms (verified).

---

## M7 — Health Connect steps

**Gate: M0 on-device-counting spike.** If the spike passed, this is the documented single-source
path; if it did not, the raw-sensor escape hatch (architecture §4) comes into scope and M7 grows.

**Deliverables.**
- All Health Connect calls behind the **single interface in `:data`, with exactly one
  implementation** (architecture §5; `CLAUDE.md`). The interface is for version pinning against the
  alpha API churn, not provider abstraction — do not add a second implementation "for symmetry".
- Steps as a measured item: no schedule slot, never asked, read-only in the night check-in (spec
  §3.3), scored like any other goal with daily and weekly targets.
- The **origin-grouping guard, day one** (architecture §5, §8 risk): group step records by origin;
  if more than one origin appears for a day, do not silently sum — log and surface. This is the
  dormant Galaxy-Watch double-count risk. A few lines, not a reconciliation system.
- Declined-permission branch: if health permission is refused at setup, steps is **hidden**, not
  downgraded to manual entry (spec §3.3). Manual step entry is permanently out of scope (spec §2).

**TDD.** The mapping from Health Connect records → `measured_values` + `measured_origins`, and the
provisional/frozen state machine, are pure and tested with fake record inputs — including the
two-origins-for-one-day case asserting it is flagged, not summed. The live read itself is
hand-verified (it already was, in M0).

**Exit criteria.** Steps appear read-only in the night check-in and score against their targets; the
multi-origin case is flagged in a test and surfaced in the app; the declined branch hides steps.

---

## M8 — Item detail views and charts

**Proposed.** With real answers accumulating, the per-item view becomes meaningful. Dashboard still
waits (it needs history and O1); the detail view does not.

**Deliverables** (spec §5.4).
- Automatic chart-type selection from answer type — *not* a user setting (spec §2, §5.4): calendar
  heatmap for yes/no, single-select and multi-select; line chart for number and 1–5 scale; line chart
  for time-of-day **with the y-axis respecting the 04:00 boundary** or a 01:30 bedtime plots as the
  earliest night of the month (spec §5.4; `CLAUDE.md`; architecture §4).
- The always-available plain table — the only surface exposing backfilled/edited/pending/provisional
  states and notes in bulk (spec §5.4).
- Per-item longest run, and hit rate **and** average attainment side by side, never merged (spec
  §5.4; constraint 16).
- **No-opportunity usage frequency** surfaced here (spec constraint 17): how often the neutral option
  was chosen must be legible, so leaning on it is visible rather than hidden.
- Notes surfaced per data point (spec §5.4).
- Charts: Compose Canvas for the heatmap, Vico for line charts (architecture §4; `CLAUDE.md`).

**TDD.** The chart-type selection is a pure map from answer type — tested exhaustively. The
clock-axis transform for time-of-day (the 04:00 wrap) is pure and is the one with a real bug waiting
in it — test it directly. The rendered charts themselves are hand-checked.

**Exit criteria.** Each answer type renders its correct view; the time-of-day axis respects 04:00
under test; hit rate and attainment both display; the table exposes every state.

---

## M9 — Seed-data fixture (spec O7)

**Purpose: this is a testing tool, not a precondition for the app being useful.** Worth stating
plainly, because spec O7 is easy to misread as "the app needs months of data to help anyone". It does
not. The app is useful from day one — it asks, records, and reports, and under spec §5.5 the dashboard
deliberately shows only today's status and a plain answered-count until 14 days exist. Real usefulness
needs *14 days*; the fixture's ~6 months is for exercising month-over-month trends and comparisons,
not a precondition, and real history accrues naturally.

What the fixture is *for* is **verifying the app's own functionality** — specifically the surfaces
that are impractical to exercise by living through them. You cannot hand-test first-run suppression
flipping at day 14, a retired item vanishing from the periods it wasn't active in, effective-from
target changes not re-scoring old periods, or a multi-origin step day being flagged, by waiting
months for each to occur naturally. The fixture manufactures those states on demand so they can be
checked in an afternoon.

**Decided-by-docs.** On Android this is **not a standalone script** but a **debug-build-only fixture
that populates Room directly**, behind a hidden developer screen or an adb broadcast (architecture
§7, amendment 2). Knowing that distinction before starting is the point of flagging it here.

**Deliverables.**
- A debug-only fixture that generates history **on demand, in scenarios**, each exercising a
  specific behaviour worth verifying: a dataset that crosses the 14-day suppression boundary; one
  with in-window, backfilled, late and pending captures; one with edited answers; one with retired
  and re-versioned items; one with a multi-origin step day; one with weeks containing gaps; one with
  effective-from target and container-size changes; and one with a no-opportunity streak on a goal
  that carries the option. **Default volume ~6 months** (spec O7), so month-over-month trends and
  comparisons are exercisable; a scenario may use less where it only needs a boundary condition.
- Never present in release builds.

**TDD.** The generator's *output* is asserted — that each scenario actually produces the state it
claims to — but the fixture is itself test scaffolding, so it is lightly tested rather than driven.

**Exit criteria.** A debug build can load any named verification scenario in one action; release
builds cannot; each scenario provably contains the state it is meant to exercise.

**Note.** M9 depends only on M3 and can be built any time after it, in parallel with M4–M8. It is
placed before M10 because the dashboard is its first and biggest consumer.

---

## M10 — The dashboard

**Depends on M9 for test data. O1 is resolved (no longer a gate).**

**O1 — resolved.** Goal completion is split into two rings, one per granularity: a **daily** ring
(met daily-goal instances ÷ active-on-answered-days) and a **weekly** ring (met weekly-goal instances
÷ instances over closed weeks), each instance-based within its granularity and never merged (spec
§5.1, §3.4, scoring-cases §11). Both aggregates are computed in `:domain` at M2; this milestone only
renders them.

**Deliverables** (spec §5.1, §5.2, §5.3, §5.5).
- The outstanding-check-in banner: persistent, not modal, backfill in place (it must not trap the
  user on open — that is what teaches them not to open it).
- The score element: three separate figures — response rate, daily goal completion, weekly goal
  completion — **never one blended number** (constraint 8). The weekly figure shows *pending* before
  any week has closed in the window, not 0%. Response rate is the same thing the run counts.
- **Visual — explore here:** the ring geometry is open. Full concentric rings are the working default;
  prototype a **half-ring / arc gauge** too, which may read more cleanly (spec §5.1). Presentation
  only — the three figures stay separate and unblended whichever geometry wins.
- Longest run beside the donut, as a static fact that only increases (spec §3.5, §5.1).
- Three rotating panels — going badly (loads first and is default), going well, middling — by hit
  rate at the 80% / 60% thresholds, each showing hit rate with average attainment as a secondary
  line when materially higher (spec §5.1, §5.2).
- Partial-period handling: weekly figures mid-week shown as progress against elapsed days, not
  scored against the full week (spec §5.3, scoring-cases 9.7).
- First-run suppression: every trend, percentage and panel hidden until 14 days of history exist
  (spec §5.5, scoring-cases 9.5–9.6).

**TDD.** With M9's seeded history, the dashboard ViewModel is testable against fixed data: the two
goal-completion rings kept distinct and each instance-based within its granularity (scoring-cases
§11), panel membership at the 79/80 and 59/60 boundaries (9.1–9.4), the 14-day suppression boundary
(9.5–9.6), one 14-day window used everywhere (9.8), mid-week weekly shown as progress not missed
(9.7). The carousel timing (spec O5: 8s auto-advance, stops on first swipe) is an assumption to
confirm and is hand-checked.

**Exit criteria.** All three rings render separately and none is blended (constraint 8); the daily and
weekly goal-completion figures are traceable to specific behaviours; panels populate correctly at the
threshold boundaries under test; first-run suppression works; mid-week weeks show as progress.

---

## M11 — Export

**Proposed, last.** Low-risk and self-contained (spec §2; architecture §4, §7.1).

**Deliverables.**
- Plain export via the **Storage Access Framework document picker** (`ACTION_CREATE_DOCUMENT`), the
  user pointing it at Drive, local storage or anywhere. The payload is a **full-database JSON
  snapshot** — lossless (versions, capture states, edit timestamps, step origins, notes), one file,
  re-importable if an import feature is ever added. **Do not build a Google Drive API integration** —
  no cloud project, no OAuth; this is now decided, not an open conflict (spec §2, architecture §4 and
  §7, `CLAUDE.md`).
- A year of data is ~10,000 short rows (spec §6), so size is a non-issue.

**TDD.** The serialiser — rows in, export bytes out — is pure and fully tested. The picker itself is
hand-verified.

**Exit criteria.** Export produces a correct file under test; the picker writes it to a
user-chosen location on the device; no cloud dependency exists anywhere in the build.

---

## Decision gates, collected

These are the points where the build stops and asks, per `CLAUDE.md`'s "Ask first" list.

| Gate | Blocks | What must happen |
|---|---|---|
| ~~O1~~ — goal-completion ring formula | — | **Resolved:** two rings, daily + weekly, each instance-based within its granularity (spec §5.1, §3.4). No longer a gate. |
| **T1 / M0** — platform verification | M6, M7 | Verify exact alarms and on-device step counting on the real Pixel *before* building on them. |
| **Schema changes** | M3 and anything later | Any change to `answers`, `checkins` or `targets` is a stop-and-ask, never a quiet edit. |
| **New dependency** | any milestone | Adding one is ask-first. The stack in architecture §4 is the agreed set. |
| **O4 / O5** — measured-day finality, carousel timing | M5, M10 | Lower-stakes assumptions (provisional-24h, 8s auto-advance); confirm when reached, both reversible. |

## What this plan deliberately does not do

- **No feature not in the spec.** If a milestone seems to need something absent, that is a
  stop-and-say, not an addition (`CLAUDE.md`).
- **No motivational copy, no escalating notifications, no screen-time composite, no blended score,
  no persisted derived value.** These are the permanently-out-of-scope items and the constraints
  most likely to be broken by well-meaning code (spec §2 appendix; `CLAUDE.md`).
- **No work pulled forward across a gate.** O1 (the goal-completion ring formula) is now resolved —
  a daily + weekly split — so the rings are no longer gated; the remaining gate is M0's platform
  verification, which still blocks M6 and M7.

---

## Suggested first three sessions

1. **M0 spikes in parallel with M1.** Answer the two platform questions on the device while standing
   up the modules and the `DayResolver` test-first. Neither blocks the other.
2. **M2, section by section.** Turn `docs/scoring-cases.md` into green tests, starting with target
   directions and 1.13, then attainment. This is the heart of the app and the heart of the TDD work.
3. **M3 against the worked example.** Persist the Tuesday-25-August fixture and prove it round-trips,
   then wire the repository to feed the now-tested `:domain`.
