# Build Order

**Status:** agreed and in progress. **M0 through M4.5 are complete**; three defects found
during M4.5 are the agreed next work, then M5 (the rollover job).
**Intended repo path:** `docs/build-order.md`
**Companion documents:** `docs/product-spec.md` (authority on behaviour), `docs/architecture.md`
(how it is built), `docs/scoring-cases.md` (the `:domain` test spec).

**Why this document exists.** `CLAUDE.md` says there is deliberately no phased plan in the repo and
that one must be proposed and agreed before building. This is that proposal. It also references
`docs/build-order.md` in its "Do not" section — "build anything from a later milestone than the one
in progress" — so this file is the referenced milestone list. It has since been agreed, and
milestones record their outcomes in place as they are completed.

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
   them rested on **unverified** platform behaviour the architecture said to *verify first*
   (architecture §2, T1). **M0 has now run those spikes and both passed:** Health Connect counts
   steps on-device, and exact alarms fire in confirmed deep Doze with sub-second slip. T1 is closed.
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
| M3 | `:data` persistence layer + seed library | TDD (instrumented) | M1, M2 | — |
| M4 | Check-in loop (capture, backfill, pending) | TDD in `:domain`; UI manual | M2, M3 | — |
| M4.5 | Check-in UI — one question per screen, summary | Hand-verified; `:app` has no tests | M4 | — |
| M5 | Rollover job (day close, expected check-ins, freeze) | TDD the pure core; hand-verify the worker | M3, M4 | — |
| M6 | Notifications, alarms, boot reschedule | Pure scheduling logic TDD'd; delivery hand-verified | M5 | — |
| M7 | Health Connect steps | TDD the mapping; hand-verify the read | M3 (M0 cleared) | M4–M6 |
| M8 | Item detail views and charts, **plus item configuration (spec §5.7)** | ViewModel TDD; charts hand-checked | M3, M4 | M7 |
| M9 | Seed-data fixture (spec O7) | N/A — it *is* test scaffolding | M3 | M4–M8 |
| M10 | Dashboard | ViewModel TDD against seeded data | M9 | — |
| M11 | Export | TDD the serialiser; hand-verify the picker | M3 | M10 |

---

## M0 — Platform verification spikes — **RUN 2026-09-04**

**Decided-by-docs.** Architecture §2 says, in bold, *verify this first*. Two assumptions the whole
platform integration rests on were marked **unverified**, and both were checkable in an afternoon.
Doing them first meant a bad surprise would land before, not after, the integration phases.

**Device:** Pixel 9 Pro · Android 17 · build `CP2A.260805.005`.

1. **On-device Health Connect step counting — CONFIRMED.** `SDK_AVAILABLE`, framework-provided with
   no separate install. After walking, steps appeared under
   `com.android.healthconnect.phone.jf9fc…` — the device-specific synthetic package architecture §2
   predicted, not a source app. **M7 is straightforward and the raw-sensor escape hatch stays
   unbuilt.** Noted for §8: Samsung Health and Google Health are both installed but wrote nothing, so
   a second origin needs no new hardware — the day-one origin-grouping guard is well justified.
2. **Exact alarms — CONFIRMED, including under Doze.** `USE_EXACT_ALARM` was declared,
   `canScheduleExactAlarms()` returned true, and **no runtime prompt ever appeared**, so the degraded
   fallback path does not need building. A 2-minute alarm fired with 0 s slip. Because 2 minutes never
   enters Doze, it was re-run properly: a 15-minute alarm, app swiped away, device forced into deep
   idle, `deviceidle get deep` confirming **IDLE** — and it fired with a **0.6 s slip** with battery
   optimisation unexempted. Well inside the ~1-minute bar a 21:00 prompt needs. **The core mechanic
   holds.**

**Residual (not a blocker, now a normal M6 observation).** One forced 15-minute run cannot show
maintenance windows, thermal throttling or adaptive-battery learning. Watch real firings over the
first several days of M6. This lives in architecture §8 as a risk mitigation, no longer an open
question.

**Exit criteria — met.** Both questions answered yes against the real device. `architecture.md` §2
updated with findings, §8 risk rows revised (battery-optimisation risk downgraded High → Medium),
T1 closed. No production code produced.

**Note.** This milestone had no dependency on the domain work. It gated M6 and M7; **both are now
clear.**

---

## M1 — Project skeleton, module boundaries, the clock — **BUILT 2026-09-04**

**Proposed.** A thin but real foundation, established test-first so the discipline is in place from
the first commit rather than retrofitted.

**Toolchain, as built.** Generated by the Android Studio wizard and kept: AGP 9.4.0 · Kotlin 2.2.10 ·
Gradle 9.6.0 · Compose BOM 2026.02.01 · compileSdk/targetSdk 37 · minSdk 34 · Java 11. Package
`com.zdredge.consistency`. Two AGP 9 facts worth knowing before touching the build: it **compiles
Kotlin natively**, so there is no `org.jetbrains.kotlin.android` plugin, and Gradle's
**configuration cache is on by default**, so a custom task that resolves configurations at execution
time will break it.

**Delivered.**

- Three modules per architecture §5. **`:domain` is a plain Kotlin JVM module, and that *is* the
  boundary enforcement** — `android.jar` is not on its classpath, so an Android import cannot
  compile. Proven by deliberately adding `import android.content.Context` and watching it fail with
  `Unresolved reference 'android'`; `DomainHasNoAndroidTest` is the runtime backstop.
- `DayResolver` plus the injected **`java.time.Clock`**, in `:domain`: the 04:00 boundary, Monday
  weeks, and day bounds. **21 JVM tests, green, no emulator.**
- `AppContainer` — manual constructor injection, no DI framework — and a `MainActivity` that renders
  the day resolved by `:domain`, confirmed on the Pixel.

**Decisions taken during the build.**

- `:data` declares `api(project(":domain"))` rather than `implementation`, so `:app` sees domain
  types through it. This matches architecture §5, where `:app` depends on both.
- **Test naming: camelCase identifiers + `@DisplayName`.** Backticked Kotlin test names cannot
  contain `.` or `:`, so they silently mangled `04:00` into `04-00` and **could not carry
  scoring-case IDs at all**. Display names can. That matters for M2, where every test should name
  the `docs/scoring-cases.md` case it covers.
- The wizard's catalog pairs a 2026 Compose BOM with 2023-era template library versions
  (`coreKtx 1.10.1`, `activityCompose 1.8.0`, `lifecycle 2.6.1`). This was predicted to clash; **it
  did not** — debug and release both build — so they were left alone rather than churned against a
  theoretical risk.

**Exit criteria — met.** Modules compile with the dependency direction enforced; `DayResolver` and
`Clock` exist with a green suite; the test clock can be driven to any instant.

---

## M2 — The scoring engine (`:domain`) — **BUILT 2026-09-04**

**Decided-by-docs.** This is where the plan spent its TDD budget. `docs/scoring-cases.md` is
explicitly "the test suite specification for `:domain`", so the milestone was close to literal: turn
each documented case ID into a failing test, then make it pass.

**Delivered: 133 JVM tests, no emulator, and complete case coverage.** All **77** in-scope cases are
proven by a test whose display name leads with the case ID. The only absences are the six rendering
and first-run-suppression cases that belong to M10 (9.5, 9.6, 9.8, 11.5, 11.6, 11.8).

Built in seven reviewed phases: the domain model; target resolution and directions; binary scoring
with attainment; response rate, runs and capture; pending and no-opportunity; item lifecycle,
roll-ups and dual granularity; derived metrics; panel bands and the two rings.

**The design decisions that carry the rulebook.**

- **`GoalOutcome` is tri-state — `MET` / `MISSED` / `EXCLUDED`, never a Boolean.** Three flavours of
  "no positive answer" must stay apart: silence is excluded *and* costs the check-in (1.13), an
  unresolved deferral is **missed** (A2.1), and a no-opportunity answer is excluded and costs nothing
  (10.3). `ThreeWayDistinctionTest` asserts all three side by side, including that the two exclusions
  share an outcome but differ by reason.
- **`ExclusionReason` distinguishes *why*.** `EXCLUDED` alone could not satisfy constraint 17's
  requirement that no-opportunity usage stay visible, nor separate an inactive item from a skipped
  one, nor an open week from a gap.
- **Response rate is computed from check-ins alone, never from answers.** That is what makes a `LATE`
  answer unable to repair a missed check-in (A1.2) and a week filled in on Sunday still report 0%
  (A1.5). The round-1 decision is enforced by the data flow, not by a guard.
- **Goal outcomes are not an input to the global run at all**, which is why 4.4 passes structurally
  rather than by special handling.
- **Order is load-bearing in `GoalScorer`**: silence, then unresolved deferral, then no-opportunity,
  then direction. 10.7 is proven by choosing a target the answer would otherwise satisfy.

**Three product errors the build exposed**, each fixed in the spec rather than worked around:

- **`MUST_INCLUDE` semantics were undocumented and the case table incomplete** — only three of the
  four selection combinations were specified. Added as 1.10b/1.10c, and §3.4 now records that an
  acceptable-set intent is expressed as an absence rule instead.
- **Meals was "exactly 3"**, which would have scored a fourth meal as a miss. The direction, not the
  arithmetic, was wrong; it is now "at least 3".
- **Spec §3.4 gave sleep duration as "got up − bedtime"**, contradicting every worked number and
  double-counting the lingering minutes into sleep. Corrected to "woke − bedtime".

The wider lesson, worth carrying into M3: **a direction must describe what would genuinely count as
failing.** Two of the three errors were targets that were satisfiable but misdescribed failure, and
only building the engine made them concrete enough to notice.

**Exit criteria — met.** Every scoring-rule case is a green JVM test, including the ring aggregates;
the suite runs with no emulator; it is the project's regression backbone from here on. It has already
caught its own author once, when an over-broad assertion from an earlier phase was falsified by A2.1.

---

## M3 — Persistence (`:data`) — **BUILT 2026-09-05**

Now that `:domain` defines the shapes it needs, the store that feeds it. Schema was fixed by
architecture §5; this milestone realised it and proved the queries.

**Outcome: 61 instrumented tests on the Pixel 9 Pro and 2 JVM tests, all green.** Six phases, one
commit each.

**Delivered.**
- **Eleven tables**, the ten from architecture §5 plus `roll_up_specs`, with `user_id` on every one
  from the first schema (constraint 15). Room 2.8.4, KSP 2.3.9, AGP 9.4.0, configuration cache on.
- **Five DAOs**, written result-first, and a repository mapping rows ↔ `:domain` types with no
  derived value stored anywhere.
- **The spec §4 seed library**, sixteen items populated on first run — see the scope note below.
- **Migration scaffolding** and a pinned schema identity hash.

**Three decisions worth carrying forward.**
- **Instrumented, not Robolectric.** Migrations and non-trivial queries are exactly what a
  re-implemented SQLite would lie about. Robolectric's API 37 support is beta-only and its SDK
  sandbox wants JDK 21 against this project's Java 11. The one exception is the schema version pin,
  a text check needing no SQLite, which runs as a fast JVM test.
- **Enums persist by name, never ordinal; dates as ISO-8601 text.** Both in architecture §5.
- **`:data` builds its own database**, so `:app` never touches Room and cannot reach past the
  repository to a DAO.

**Two scope changes, both agreed before building.**
- **`roll_up_specs` added.** `:domain` carried a `RollUpSpec` type and spec §3.4 requires roll-ups,
  but architecture §5 listed no table for them. Outside the three guarded tables, so in scope; in v1
  rather than a later migration for a requirement already written down.
- **The seed library landed here, not in M4.** It was unassigned in this plan and M4 cannot render a
  check-in without it. Note this is the item *library* only — no answers, no check-ins, no
  manufactured past. Seeded **history** remains M9 and is a different job.

**What M3 caught, in the same family as M2's three.**
- **Coffee needed a daily cap as well as a weekly one.** The walkthrough moved worked out, stretched
  and coffee to weekly targets as one group, but the three are not the same shape. The first two are
  lower bounds, where weekly granularity correctly forgives clustering. Coffee is an upper bound,
  where the same forgiveness hides the day worth seeing: five in one day and one on each of the
  others sums to ten and passes a weekly cap of fourteen cleanly. **Moving a target to weekly
  forgives clustering, which is right for a lower bound and wrong for an upper bound** (spec §4).
  Found by the user while reviewing the roll-up modelling question, not by a test.
- **A stale-schema packaging bug.** The task copying exported schemas into the androidTest assets did
  not re-run on incremental builds, so `MigrationTestHelper` was reading a schema one revision behind
  the code — which would have made the first real migration test verify a migration from a schema
  that no longer existed. Forced in `data/build.gradle.kts`.
- **A test that could not fail.** The first version of the schema-drift test was wrong: the export
  regenerates every build, so code and file can never disagree. Verified by adding a column and
  watching all sixty-one tests pass. The real risk is a schema changed *without a version bump*, so
  the identity hash is now pinned in a JVM test reading the committed file — and that test was
  verified to fail before being trusted.

**On migration tests.** This plan asked for one test per migration. **At M3 there are none** — there
is only schema v1, so a test claiming to verify a migration would be theatre. What Phase 6 delivered
is the apparatus: the exported v1 JSON committed, a `MigrationTestHelper` test rebuilding the schema
from it, and the version pin. The first real migration test arrives with the first schema change.

**Ask-first checkpoint — honoured.** No change to `answers`, `checkins` or `targets`. `roll_up_specs`
and the coffee target were both raised and agreed before any code was written.

**Exit criteria — met.** The worked example persists and reloads intact, with four sleep answers
carrying `day_date` = the 25th while arriving via the 26th's morning check-in; DAO tests green; the
repository feeds `:domain` with nothing derived stored. The round-trip test was falsified before
being trusted — dating a sleep answer by its check-in's day killed exactly the three tests that
should die.

---

## M4 — The check-in loop — **BUILT 2026-09-06**

**The first thing the user can actually use.** Answer a check-in in under 60 seconds, defer with
"not yet", backfill a missed one from the banner.

**Outcome: 184 `:domain` tests, 3 JVM, 79 instrumented on the Pixel 9 Pro, all green.** Six phases.

**Delivered.**
- **`:domain/checkin/`** — `CaptureResolver`, `AnswerDay`, `CheckInPlanner`, `CheckInContent`. Every
  decision that could be *wrong* rather than merely ugly, testable with no device.
- **Repository support** — generating expected check-ins, offering the outstanding ones, recording
  an answer against the check-in it was given in.
- **Two screens** — a landing surface with the outstanding-check-in banner, and the check-in itself:
  tap-first, all six answer types, keyboard only for the optional note. *(The check-in screen
  described here was a scrolling list of cards. **M4.5 replaced it** — see that section for what the
  screen is now. The landing surface is unchanged.)*
- **Schema v2** — `items.ordinal`, and the first real migration.

**Decisions.**
- **The capture window is the check-in's own day** (spec §3.2). Three statements had to be
  reconciled; only this reading fits all three. An answer at 22:00, an hour after the prompt, still
  counts.
- **Two check-ins a day, always.** The weekly questions append to Sunday night's rather than forming
  a third, so `Slot.WEEKLY` is an item's slot and never a check-in's. A third row would inflate
  Sunday's denominator for no behavioural reason.
- **No navigation library** (T5 still open). A sealed screen state and a `when`; revisit at five
  screens with a real back stack.
- **No new dependencies.** `lifecycle-viewmodel` was already transitive, so ViewModels come free and
  drafts survive rotation.
- **`items.ordinal` as schema v2** rather than an order inferred from the item. Order is data, so a
  question created later gets a position without a code change.

**What M4 caught.**
- **The capture reference day.** Capture was first measured against the day an answer is *dated to*.
  A sleep item answered this morning is dated *yesterday* but is being answered in its own proper
  window, so that would have made **every ordinary morning check-in record as a backfill**, quietly
  collapsing the in-window-only figure. Now `CaptureResolver.forEntry`, in `:domain` with tests.
- **The install-day check-in, found by installing the app and looking at it.** The morning check-in
  covers yesterday, when no item existed, so it rendered with a correct header and *zero questions* —
  while still sitting in the response-rate denominator as an unanswerable guaranteed miss. A
  check-in that would ask nothing is now never expected. Recorded in spec §3.2.
- **A stale-schema packaging bug's sibling**: five instrumented tests failed when that fix landed,
  because they had never seeded a library. The fixtures were wrong, not the rule.

**On testing the `:app` layer.** Build-order proposed ViewModel TDD against a fake repository, which
would have meant extracting an interface from `ConsistencyRepository` purely to support the test.
The decisions went to `:domain` instead — faster, no fake, and the ViewModel left with nothing to
assert. Both mistakes above were caught by domain tests or by looking at the screen; neither could
have hidden in a ViewModel test. **`:app` has no tests, deliberately.**

**Exit criteria — met.** A check-in can be completed, deferred and backfilled on the device; every
capture-state transition is covered by a fast test; the previous-day labelling is present and stated
in the largest type on the screen.

**Known gap, carried out of M4 — closed by M4.5.** An *answered* multi-select with nothing selected
— "I did none of these before bed" — is a different thing from silence and scores differently: it
**meets** a must-not-include goal, where silence is excluded. Storage always distinguished the two
and a DAO test asserted it, but the screen offered no way to say it, so the answer was unreachable.
M4.5 Phase 4 added the "None of these" row, and Phase 6 verified it in the data: a stored `pre_sleep`
row with no selections, sitting beside days that have no row at all. It is now a requirement in spec
§5.6 rather than an implementation detail that could be dropped by a later redesign.

**What M5 inherits.** `CheckInPlanner` is already the rule for which check-ins should exist — M5
wraps that same function in the rollover worker rather than writing a second one, and adds what only
a scheduled job can do: marking check-ins missed once grace closes, converting unresolved deferrals
to missed goals (A2.1), and freezing provisional step values.

---

## M4.5 — Check-in UI — **BUILT 2026-09-08**

**Not in the original plan.** M4 delivered a working check-in and the user, using it, did not like
it. This milestone was inserted between M4 and M5 out of a design discussion rather than the build
order, which is why it carries a decimal. It is worth recording that the trigger was *using the
thing*, not reviewing the plan.

**Outcome: 184 `:domain` tests, 3 JVM, 81 instrumented — all unchanged**, which is the correct result
for a milestone that touched only `:app`. Six phases.

**Delivered.**
- **One question per screen**, replacing M4's scrolling list of cards, with a segmented progress bar
  that distinguishes answered from skipped.
- **A dark visual language** — one `darkColorScheme`, no dynamic colour, soft corners, no neon.
- **Every answer type through one component** (`AnswerOption`): full-width stacked rows, uniform
  across bool, scale, both selects and numbers.
- **The time dial inline**, replacing a button that opened a picker in a dialog.
- **The note in a dialog**, because a field in the card's footer is where the keyboard opens.
- **A closing summary** listing every question and what was recorded, each row correctable in place.

**Decisions.**
- **The summary has no spec basis.** §5.6 was three sentences about being tap-first and under sixty
  seconds; there was no review step anywhere in the docs. It was a product decision made here, and
  §5.6 has been rewritten to say so rather than to imply the spec asked for it.
- **Done marks the check-in answered; Confirm does not.** The summary is a review of a completed
  check-in, not a gate before one — so reaching the summary and closing still counts. Deliberate: it
  keeps the question set, rather than the ceremony after it, as the thing that completes.
- **The summary is a page inside the check-in, not a third screen.** Routing it through
  `MainActivity` would have put it behind the `exit` flag, which is defect 1 below.
- **Entry decides exit.** A question opened from the summary returns there, with a single button
  rather than Back and Next quietly meaning something new.
- **Halves removed from the number options.** Two layouts were compared on the device; as full-width
  rows both read badly. 1.5 is still recordable through the keypad, which matters because spec §1
  rests its argument for reporting attainment on *"1.5 of 2 bottles every single day"*.

**What M4.5 caught.** Every one of these rendered perfectly and was wrong underneath.
- **The number stepper showed `0` for an unanswered question**, indistinguishable from a real zero —
  which scores differently, since silence is excluded and a real 0 misses "at least 3". Options fix
  it by construction: nothing is selected until something is tapped.
- **The time dial recorded a stale value.** Reading the picker on pointer release, even on the Final
  pass, is one interaction behind: tapping 9 on a dial showing 7 recorded 7:00. Found by tapping a
  value that *changed*, after an earlier test tapping an *unchanged* value had passed and proved
  nothing. It now takes two mechanisms — an observer for changes, a release handler for the tap that
  changes nothing.
- **Close dropped the question on screen**, so an answer given and then closed was simply lost. Found
  by reading the database, not by looking at the screen.
- **Selected options were invisible** — `secondaryContainer` was mapped to the card colour. Caught by
  a screenshot, not by source review.

**The generalisation, now in `CLAUDE.md`:** the dangerous class in this layer is **a displayed value
that cannot be told apart from a given one**. It recurs on every new surface — the stepper, the dial,
and then the summary, which had to be built to avoid it a third time.

**Known gap closed.** The answered-but-empty multi-select carried out of M4 — "I did none of these
before bed", which *meets* a must-not-include goal where silence is excluded — is reachable as of
Phase 4 and verified in Phase 6: a stored `pre_sleep` row with no selections, distinct from no row at
all. Spec §5.6 now requires it.

**What M5 inherits.** Nothing structural — this milestone changed no schema, no domain rule and no
repository behaviour except that `recordAnswer` no longer marks a check-in answered. It does hand
over four defects.

### Defects found during M4.5, to fix after the UI work

All four are about **state after leaving a question**, all predate or were exposed by the M4.5 UI
work, and all are agreed to be fixed once the UI settles. Defects 2 and 4 share a root: the
`hasContent` gate in `commitCurrent`.

**1. Reopening a check-in immediately after closing one is swallowed.** Every first reopen after a
close lands straight back on the home screen; a second tap works. Traced on the device, 4 attempts
out of 4:

```
open  → check-in opens
close → home
open  → home          ← bounced
open  → check-in opens
```

`MainActivity` runs `LaunchedEffect(state.exit) { if (state.exit) screen = Home }` when the check-in
screen enters composition, while `exit` is still `true` from the previous `close()`. `load()` clears
it, but does so in a coroutine, so the navigation wins the race. The second attempt works because
`load()` has completed by then.

The fix is to stop navigation depending on a flag that outlives the screen — either clear `exit`
synchronously at the start of `load()`, or make the exit a one-shot event rather than a state field.
The second is the better shape and is worth doing while M5 is still ahead rather than behind.

**2. Clearing an answer that was already stored does not delete it.** `CheckInViewModel.commitCurrent`
returns early when the draft has no content:

```kotlin
if (question.readOnly || !question.dirty || !question.draft.hasContent) return
```

`recordAnswer` is the only write path and there is no delete, so the stored row survives. The screen
reports the answer cleared and reopening shows the old value again, because `existingDraft` reads the
row that was never removed.

This applies to every answer type — un-selecting a bool or a chip, clearing a number, and now
**Reset** on a time dial, which is a prominent control that explicitly promises to clear. Read from
source and reasoned through rather than demonstrated: the only stored answers available to reproduce
against were real user data, and the experiment destroys one if the diagnosis is wrong.

Fixing it means deciding what "no answer" means at the storage boundary — delete the row, or keep it
and record the retraction. That is a product question (an edit history is spec territory), not just a
missing `else` branch, which is why it is not a one-line fix.

**3. `edited_at` is never set, and a correction overwrites how the answer was first given.** The
field exists on the schema, on the domain `Answer`, in the mapper and in a domain test
(scoring-cases 3.4) — but nothing in the app ever writes it. `CheckInViewModel.toAnswer` builds an
`Answer` without it, so every write stores `edited_at = null`.

Worse than the missing flag: `recordAnswer` replaces the row wholesale, so a correction also stamps a
fresh `submitted_at` and a freshly resolved `capture`. Spec §3.2 requires the opposite — *"History is
editable; edits set the edited flag. Never a silent overwrite"* — and constraint 4 exists precisely so
a backfilled-then-edited answer stays describable.

The rule to land, in `:domain` with tests rather than in the ViewModel:

- **first write** — `submitted_at` now, `capture` resolved, `edited_at` null
- **later change** — `submitted_at` and `capture` preserved, `edited_at` now
- **except resolving a deferral** — a `PENDING` capture must still move when the answer arrives, or
  A2.1 and A2.2 break. This is the exception that stops "preserve capture" from being a one-liner.

Not reachable through the UI today, since a completed check-in cannot be reopened. It is recorded now
because the first path that does reach it will otherwise get it wrong silently.

**4. A note with no answer is silently discarded.** Same `hasContent` gate as defect 2:

```kotlin
val hasContent: Boolean get() = isAnswered || deferred
```

A note is neither, so a question answered *only* with a note never reaches storage. Demonstrated on
the device in Phase 6: a note was typed on a time question, the summary displayed it under
"Recorded.", the check-in was confirmed, and no row for that item exists.

Two things make it worse than it sounds. The screen states the note was recorded, so the loss is
invisible. And spec §3.3 makes the note the place prose belongs — *"real answers consistently carry
more detail than one field can hold"* — so "no value, but here is what happened" is a natural thing
to want to write, and it is exactly what is thrown away.

The fix is bound up with defect 2, because both turn on what a draft with no primary value means at
the storage boundary. Decide them together.

### Deferred, not a defect

**Time questions all open at 07:00.** Right for "woke up" and "got out of bed", wrong for "went to
bed", which needs an AM/PM tap plus a drag. A per-item default is the fix and now has somewhere to
live: item configuration is spec §5.7, **assigned to M8** alongside the item detail view. The cheap
alternative is defaulting to the previous answer for that item, which improves with use and costs one
query per time question.

**The summary loses its scroll position after an edit.** Correcting a row near the bottom returns to
the top of the list. Tolerable at nine questions and annoying at twenty; recorded rather than fixed so
the behaviour can be seen before a fix is chosen. Hoisting the scroll state above the page switch is
the obvious approach.

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
- **Item configuration (spec §5.7)**, assigned here as of M4.5. It had no milestone at all and was
  flagged four times across M3, M4 and M4.5 without landing anywhere. It belongs beside the detail
  view because that is the surface already devoted to a single item, and editing an item's setup is
  what a user reaching that screen would next want. It also **unblocks per-item defaults** — the time
  questions currently all open at 07:00, which is wrong for bedtime, and there is nowhere to record a
  better starting value until items are configurable. Note this makes M8 the milestone that first
  touches item versioning from the UI: items are versioned and retired, never deleted.

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
| ~~T1 / M0~~ — platform verification | ~~M6, M7~~ — **cleared** | **M7:** on-device Health Connect step counting confirmed on the Pixel 9 Pro (Android 17); one origin, synthetic on-device package; the raw-sensor escape hatch stays unbuilt. **M6:** `USE_EXACT_ALARM` install-granted with no prompt, and 0.6 s slip in confirmed deep Doze — no fallback path needed. Multi-day observation of real firings continues in M6 as an architecture §8 mitigation. |
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
  a daily + weekly split — so the rings are no longer gated. M0 has cleared the platform gate for both M6 and M7.
  **No gate now blocks starting the build.**

---

## Suggested first three sessions

1. **M0 spikes in parallel with M1.** Answer the two platform questions on the device while standing
   up the modules and the `DayResolver` test-first. Neither blocks the other.
2. **M2, section by section.** Turn `docs/scoring-cases.md` into green tests, starting with target
   directions and 1.13, then attainment. This is the heart of the app and the heart of the TDD work.
3. **M3 against the worked example.** Persist the Tuesday-25-August fixture and prove it round-trips,
   then wire the repository to feed the now-tested `:domain`.
