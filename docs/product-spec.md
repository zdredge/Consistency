# Habit Accountability App — Product Spec

**Status:** approved and in build. Five interview rounds.
**Intended repo path:** `docs/product-spec.md`
**Sole known user:** Zach. **Platform:** Google Pixel (Android).

**For a later session picking this up:** read §1 and the appendix before anything else. §1 explains
why nearly every other choice was made, and the appendix lists decisions that look like
inefficiencies and are not. §6 lists what is genuinely still open. The stack is chosen and
recorded in `docs/architecture.md`; implementation is under way — see `docs/build-order.md` for
which milestone is current.

---

## 1. Purpose and usage model

A phone-first personal accountability tracker. It asks a user-defined set of check-in questions at
scheduled times, records the answers, and reports back plainly on what was and wasn't done.

**Accountability, not motivation.** The app does not encourage, coach, or adapt. Its value is that
the record becomes undeniable and is asked for whether or not the user wants to give it.

**The first habit is answering.** The nudge mechanic is not "go do the thing", it is "come and
answer". This was a mid-interview refinement and it reorganised the product: **check-in response
rate is the primary metric**, goal completion is secondary, and the streak counts days answered
rather than days performed. Every dashboard decision follows from this.

**Show the distance, never close it.** The user must always be able to see how far they are from a
target. The app must never tell them what to do about it. That line is what separates information
from coaching, and it is the reason this product can be blunt without becoming a coach.

The concrete expression of this is **attainment**: for any numeric goal, how close the user came,
reported alongside — never merged into — whether they met it. A target is binary because partial
credit lets someone sit comfortably at 75% forever without ever hitting it. But a binary number
alone is a bad description of reality: 1.5 of 2 bottles every single day for a fortnight is a hit
rate of zero, and reporting only that turns perfect consistency into apparent total failure.

Both numbers exist because they answer different questions. *Did I meet it* is the score. *How close
was I* is the information. The user is entitled to the second without being told what it means.
Interpreting the gap — suggesting a target is too high, proposing a smaller step, encouraging a push
— is adaptive coaching and is permanently out of scope. Showing the gap is not.

**Usage rhythm:**

- Two check-ins per day. Deliberately few; notification fatigue is the primary abandonment risk.
- **Morning** covers last night: bedtime, pre-sleep activity, waking, rising.
- **Night** covers the day just ending. Time is user-set, chosen so the day is effectively over but
  the user is still awake. Default 21:00.
- Both check-ins are retrospective. **No feature may assume the app can influence the outcome it is
  asking about.**
- One session is roughly 5–10 questions, target under 60 seconds, phone in hand, low attention.
- **Weekly** questions are asked Sunday night, appended to that night's check-in, closing the
  Monday–Sunday week the moment it ends.

---

## 2. Scope

### In scope for v1

- User-definable tracked items, chosen from a pre-defined library or written from scratch.
- **Asked items:** prompt, primary answer type, unit, classification, schedule slot, targets.
- **Measured items:** populated from Android health data, no schedule slot, never asked. Steps only.
- **One primary answer format per item plus an optional free-text note.** The governing content rule.
- Two daily notification slots plus a Sunday-night weekly section.
- Notification escalation: fire, repeat twice at ~20 min, then mark missed.
- Answer capture states (in-window / backfilled / late / pending) with a separate edit timestamp; plus
  provisional/frozen state on measured values.
- Grace-period backfill to end of next day; editable history.
- Per-period targets with effective-from dates and a **direction** (at least / at most / exactly /
  must be yes / must be no / must include / must not include).
- **No-opportunity answers:** a goal may designate a neutral select option for periods that
  genuinely preclude the behaviour; choosing it excludes the goal from scoring for that period
  without counting as a miss, and stays visible as usage. See §3.4.
- Weekly roll-ups, declared explicitly.
- Two hardcoded cross-item metrics: sleep duration and minutes lingering in bed.
- "Not yet" answers on night goal questions, resolved in the next morning's check-in.
- Configurable container sizes for count-based numeric questions.
- Dashboard as specified in §5.
- Local storage plus plain export to a user-chosen location via the system document picker
  (`ACTION_CREATE_DOCUMENT`), as a full-database JSON snapshot. Not the Google Drive API; the user
  picks Drive from the system sheet if they want it there (architecture §4, §7).
- `user_id` on every record from day one, unused in v1.

### Deferred (post-v1, not rejected)

- Health integration beyond steps: workouts, sleep, heart rate.
- A third or fourth daily checkpoint.
- Multi-user accounts and cloud sync.
- Typed activity logging beyond the note field.
- A chronological journal view of all notes across all items.
- Development seed-data fixture — dev-only tooling (never shipped), scheduled as build-order M9. See O7.
- Any adaptive or coaching behaviour.

### Permanently out of scope

Each of these was decided with a reason. Reopening one means reopening the reason.

- **Intra-day nudging and live pacing.** Ruled out by the two-checkpoint retrospective design.
  Reintroducing it means redesigning §1.
- **Motivational or encouraging copy.** Deliberate product decision.
- **Escalating notifications after repeated misses.** Confrontation happens on next app open, not
  through louder notifications, because muting is the failure mode that ends the product.
- **Generalising specific behaviours into abstract composites.** Rejected with the screen-time
  example: the user is comfortable watching YouTube in bed and not comfortable scrolling social
  media in bed, and any metric merging them destroys the distinction that makes the question worth
  asking. Named activities, not derived abstractions.
- **A weekly event log.** Proposed and rejected: it duplicates what the primary answer plus optional
  note already covers, and adds entry friction to the session most at risk of feeling tedious. The
  three social questions stay independent because their answers can be genuinely disjoint — running
  into a friend while out, or a coffee catch-up that isn't "fun".
- **Manual step entry.** The user could not answer his own step question for the past week ("a lot,
  over 10,000 most days"). Manual entry would produce guesses that pollute every figure they touch.
- **A single blended consistency score.** See §5.1 and constraint 7.
- **A configurable formula builder for derived metrics.** Two hardcoded metrics instead; see §3.4.
- **Per-item chart type as a user setting.** Chosen automatically from answer type; see §5.4.

---

## 3. Domain rules

### 3.1 Time

| Rule | Decision |
|---|---|
| Day boundary | A day runs 04:00–03:59. A 01:30 bedtime belongs to the previous day. |
| **Sleep items** | **All four sleep/wake items — bedtime, pre-sleep activities, woke at, got out of bed — attach to the day the user went to bed, not the day they woke up.** See below. |
| Week boundary | **Weeks start Monday.** Reversed from Sunday mid-interview; reason: alignment with the work week. Side effect: weekly review falls naturally on Sunday night. |
| Periods with targets | Day and week only. **Month is a viewing window, not a target granularity.** |
| Storage | Store real timestamps and dates only. Never store a week or period identifier; derive them. |

#### The sleep-day convention

Discovered while modelling a real day, and not implied by anything above, so it needs stating
plainly: bedtime at 23:30 falls on day N under the 04:00 rule, but waking at 08:30 falls on day
N+1. If those answers landed on the days they physically happened, sleep duration would become a
cross-day computation and day N's record would be permanently incomplete.

So all four sleep items attach to **the night's day, meaning the day the user went to bed.** This
matches the convention sleep trackers use and keeps sleep duration an intra-day calculation.

**Consequence, which must be built deliberately:** the morning check-in of day N writes answers
dated day N−1. The morning UI must therefore make the date it is writing to unmistakable, which is
the same requirement the "not yet" carry-over already imposes — one mechanism serves both, rather
than two.

### 3.2 Answers, windows and correction

- Every answer record carries: item ID, item version, the day it belongs to, submission timestamp,
  **a capture state and a separate edit timestamp**, and an optional note.
- **Capture state — how the answer was first recorded:** in-window · backfilled · **late** · pending.
  **Edited is not a capture state.** An answer can be backfilled *and* later edited, and collapsing
  the two into one field loses one of them. Whether an answer was edited is a separate nullable
  timestamp. Measured items carry their own provisional/frozen state, which is unrelated to either.
  Amended after modelling a real day; the original single-state list could not represent a
  backfilled answer that was subsequently corrected.
- Windows do not close permanently. Backfill is allowed **until the end of the next day** and is
  permanently marked as backfilled. **The in-window-only figure must always be recoverable.**
- **Pending ("not yet"):** night goal questions may be answered "not yet". The question reappears in
  the next morning's check-in for a final answer and **still counts as in-window if resolved there.**
  The morning check-in must make it unmistakably clear that the item belongs to the previous day.
  This exists because the user identified a specific failure: a check-in arriving while items are
  still actionable, followed by never returning to complete them.
- **Late capture.** After the grace window closes, a never-answered item may still be filled in. It
  is recorded with capture `LATE`. A late answer **does not repair the check-in**: the check-in stays
  missed for response-rate purposes, and a late answer does not preserve a run. Rationale: unlimited
  backfill that repairs the metric was rejected in round 1 as making the record fiction, and filling
  in a whole week on Sunday must not yield a 100% response rate. The data is worth having; the metric
  is not for sale. This resolves a gap found while writing `docs/scoring-cases.md`: previously a
  missing answer was permanently unrecoverable while a wrong answer stayed editable forever.
- **An unresolved pending answer converts to a missed goal at rollover, but the check-in it was given
  in stays answered.** The user did complete that check-in; they simply did not follow through on the
  deferral. **This is the only case where a goal is scored as missed rather than excluded on a day**,
  and the asymmetry is deliberate: elsewhere an absent answer is excluded from scoring, but a
  deferral is an active choice not to answer yet. Note that this rarely rescues a run in practice,
  because the morning check-in that would have resolved the deferral is usually the one that was
  missed. The same rule applies to a carried-over item left blank inside an otherwise answered
  morning check-in.
- History is editable; edits set the edited flag. Never a silent overwrite.

### 3.3 Items

- **One primary answer format per item, plus one optional note.** The primary answer is scored and
  charted; the note is never required and never scored. This rule exists because real answers
  consistently carry more detail than one field can hold, and forcing that detail into the primary
  format is what makes check-ins slow.
- **Answer types:** yes/no · number · time-of-day · 1–5 scale · single-select · multi-select.
  Short text as a *primary* type is discouraged; the note field is where prose belongs.
- Numeric items carry a **user-defined unit**, including **counted containers**. A container has a
  configurable size (one water bottle = 40 oz), so entering "2" records both the count and the
  absolute amount. Container size is stored per period, so changing bottles does not re-score
  history.
- **Multi-select option lists** are curated per item and user-extendable. This is the mechanism that
  keeps specific behaviours distinguishable.
- **Goal** items have targets and are scored. **Observation** items are recorded and charted, never
  scored. The user classifies each.
- Items are **versioned and retired, never deleted.** Editing wording or type creates a new version;
  existing answers stay bound to the version they were given under.
- A retired item remains visible **for the periods in which it was active** and is absent from
  periods in which it was not.
- **Measured items** (steps in v1) have no schedule slot and are never asked. Today's value appears
  **read-only** in the night check-in so the user sees it in the moment. They carry targets and are
  scored like any other goal.
- If health permissions are declined at setup, steps is **hidden**, not downgraded to manual entry.
  The setup flow therefore has a branch in which a library item disappears.

### 3.4 Targets, scoring and derivation

- **Targets are stored per period with an effective-from date.** Raising a target never re-scores
  closed periods.
- **Targets key on (item, period type)**, so one item may hold a daily and a weekly target at once
  (steps: 10,000 daily and 70,000 weekly). Both are scored **independently and reported
  separately**, because 70,000 across three big rides is a different week from 10,000 every day, and
  collapsing them hides which one happened.
- **Targets carry a direction**, not just a value: at least · at most · exactly · must be yes ·
  must be no · must include *option* · must not include *option*. Coffee wants an upper bound;
  "scrolled on phone" wants an absence.
- **"Must include" names exactly one option**, so an answer omitting it is a miss even when other
  options were selected — watching YouTube does not satisfy "must read a book". Where several
  activities are *all* acceptable, express that as an **absence rule on the unacceptable one**, which
  is exactly what the pre-sleep item does: the goal is "did not scroll", not "read specifically", so
  YouTube and TV both pass (§2 records why — YouTube in bed is fine, scrolling is not). A
  "must include any of *these*" direction was considered and **rejected**: absence covers the real
  intent, and the one case it does not cover — answering that nothing at all was done — is not
  something worth scoring as a failure here. Note also that targets key on (item, period), so one
  item cannot hold both an include and an exclude rule for the same period.
- **Absence-based goals score only on answered days.** An empty answer trivially satisfies "did not
  include scrolling", so silence must never read as success. This is the single most likely place
  for the scoring to be implemented wrong.
- **No-opportunity answers are a neutral exclusion.** A goal may designate one select option as *no
  opportunity*, for behaviours some days or weeks genuinely preclude (taking time for yourself;
  inviting someone to something). Choosing it is an active answer: the check-in counts as answered, so
  **response rate and the run are unaffected**, but the goal is **excluded** from completion for that
  period — neither met nor missed — and the item's own run is not broken. It is evaluated **before**
  direction: a no-opportunity answer is excluded whatever the target says. This is deliberately
  different from silence (previous bullet): silence is excluded because nothing was said and it costs a
  check-in; a no-opportunity answer is excluded because the user said the period did not allow it, and
  it costs nothing. Because it is self-reported and neutral, **how often it is used must be visible** on
  the item detail view and table, so a pattern of leaning on it is legible rather than hidden — the
  same principle as a `LATE` answer keeping the data without repairing the metric.
- **Numeric goals score binary** against the target; the ratio earns no partial credit, because
  proportional credit lets the user sit at a comfortable 75% forever without ever hitting a target.
- **Average attainment is reported alongside hit rate**, never merged into it. Hit rate answers "how
  often did I meet this target"; average attainment answers "how close was I on average". They can
  diverge sharply and the divergence is the useful signal: drinking 1.5 of 2 bottles every day for a
  fortnight is a hit rate of 0% and an average attainment of 75%, which is a target set slightly too
  high rather than a discipline problem. Displaying only hit rate makes perfect consistency look like
  total failure. Surfaces are specified in §5.
- The app does **not** tell the user their target may be wrong. Interpreting the divergence is
  adaptive coaching and permanently out of scope; showing both numbers is not.
- **Scoring covers only items active in the period being scored.**
- **Metrics, never blended:**
  - *Response rate* — proportion of check-ins answered. **Primary.**
  - *Goal completion, split by granularity* — two figures, never merged into one and never merged
    with response rate:
    - *Daily goal completion* — met daily-goal instances ÷ daily-goal instances active on answered
      days, over the 14-day window.
    - *Weekly goal completion* — met weekly-goal instances ÷ weekly-goal instances over the weeks
      that have **closed** within the window; the partial current week is progress, not scored (§5.3).
    Both are unweighted and instance-based within their own granularity. They are kept apart because
    pooling daily and weekly goals into one ratio underweights the ~2 weekly instances per window
    against the ~14 daily ones — a hidden weighting that also makes the score untraceable. Surfaced as
    the two inner donut rings (§5.1); reasoning trail in O1 (§6).
  - A missed check-in reduces response rate and contributes nothing to goal completion, so skipping
    bad days is visible in the metric that catches it rather than flattering the one it doesn't.
- **Roll-ups** are explicit, not inferred: an item may declare a weekly roll-up naming a source item
  and an aggregation (count-of-yes · sum · average · max). Derived weekly figures must be labelled
  as derived, with their source visible.
- **Roll-ups count observed values only, and a week containing unanswered days is labelled
  incomplete wherever the figure appears.** Four workouts across a week with two blank days is
  reported as four, not as four-of-five, and a weekly target of at least four is assessed against
  the observed four. The two blanks might each have been a workout; the app does not guess in either
  direction, it says the week is incomplete. Consistent with how missed check-ins are treated
  elsewhere.
- **Cross-item derivation is a fixed hardcoded pair**, not a general engine: **sleep duration**
  (woke − bedtime) and **minutes lingering in bed** (got up − woke). Both are wired to the three
  built-in sleep/wake time items and do not generalise to user-created questions. This special-casing
  is deliberate; see constraint 13.
  **The two are disjoint and together account for the whole time in bed.** This line previously read
  "(got up − bedtime)" for sleep duration, which was a typo: it would have folded the lingering
  minutes into sleep and double-counted them, inflating every sleep duration. Corrected during M2 to
  match the worked numbers, which were right all along — scoring-cases 8.1 and 8.2 and the
  architecture §5 worked example all give 9h 00m for a 23:30 / 08:30 / 08:52 night, and 8.2 supplies
  no got-up time at all, so sleep duration cannot depend on one.

### 3.5 Runs

- **Global run:** consecutive days on which every scheduled check-in was answered. It measures
  showing up, not performing.
- **A backfilled answer within the grace window preserves the run.** The record stays flagged as
  backfilled so the strict figure remains recoverable.
- **Per-item runs:** each item additionally tracks its own longest run, shown on its detail view. A
  no-opportunity answer (§3.4) is neutral here: it neither extends nor breaks the item's run.
- Rationale, worth preserving: an all-goals-met run would sit near zero permanently with ten-plus
  goals, and under that definition the user's incentive on a bad day is to not open the app. Under
  this definition the bad day and the honest answer are the same action, so the run rewards honesty
  instead of punishing it. **Label it accordingly** — it is a measure of reporting discipline, not
  of vitamins.

---

## 4. Seed library

Ships pre-defined and pre-classified, with type, unit, slot and a suggested target on each entry.
Roughly 25–30 items grouped into sleep, movement, food, mind and social. Everything editable,
removable, extendable; users may also write their own from scratch.

**Morning:** bedtime (time) · pre-sleep activities (multi-select: read a book, watched YouTube,
scrolled on phone, watched TV, …) · woke at (time) · got out of bed at (time)

**Night:** meals (number) · vitamins (yes/no) · water (number, unit = bottles, size configurable) ·
worked out (yes/no) · stretched (yes/no) · coffee (number) · took time for yourself (single-select:
yes · no · no opportunity) · mindset (1–5)

**Goal granularity in the night set:**

- meals, vitamins and water are **daily** goals. **Meals is "at least 3", not "exactly 3."** An extra
  meal is not a failure and must not score as one — the user has no history of overeating, so an
  upper bound would manufacture false misses and punish a non-problem. This may deserve revisiting if
  the app ever has users for whom overeating *is* the thing being tracked; for this user it is not.
  The wider rule it follows: **a direction must describe what would actually count as failing**, or
  the score stops meaning anything (see also the pre-sleep absence rule in §3.4).
- **worked out, stretched and coffee are recorded daily but their targets are weekly**, assessed
  against the weekly roll-up — count-of-yes for the two yes/no items, sum for coffee: e.g. worked
  out ≥ 3/week, stretched ≥ 4/week, coffee ≤ 14/week (all illustrative and editable). This is the
  "one off day should not fail me" case, handled by choosing the right *period* rather than by
  softening the target: each weekly target is still a clean binary hit or miss (§3.4,
  dual-granularity). A daily target may also be added if wanted; the two score independently.
- **mindset is an observation, not a goal** — recorded and charted, never scored. Scoring a mood
  reintroduces exactly the shame this product is designed against; the derived *average mindset* is
  the signal, watched rather than targeted.
- **took time for yourself is a daily goal** (must be yes) carrying the no-opportunity option
  (§3.4), for days too busy to allow it.

**Measured:** steps (daily and weekly targets, read-only in the night check-in)

**Weekly, Sunday night:** saw friends · did something fun · invited someone — each a **weekly goal**
(single-select: yes · no · no opportunity), scored must-be-yes, with the no-opportunity option for a
week that genuinely allowed none of it (§3.4). They stay three independent questions.

**Derived:** sleep duration · minutes lingering in bed · workouts this week (count-of-yes) ·
stretches this week (count-of-yes) · coffee this week (sum) · average mindset

### Change log against the user's original sixteen

- **Mindset** moved weekly → daily 1–5. Seven-day mood recall is dominated by the last day; the
  user's own answer ("some days, but not every day") confirmed it isn't weekly-answerable.
- **Workouts this week** removed as an asked question; derived from the daily item, to avoid two
  contradicting sources of truth.
- **"What did you do before bed"** kept as a named-activity multi-select. A proposed
  screen-minutes-in-bed numeric was rejected; see permanently out of scope.
- **"Did you lay in bed this morning"** (yes/no) replaced by a second morning time, "got out of bed
  at", making both sleep duration and lingering minutes derivable. Cost accepted: three time pickers
  in the morning.
- **Steps** promoted from deferred into v1 with health integration, at the user's insistence, after
  his own week's answer demonstrated the question is unanswerable by hand. Stated rationale: steps
  matter to him as a proxy for getting outside, not only for fitness.
- **Friends / fun / invited** kept as three independent questions.
- **Water** measured in configurable bottles rather than ounces, because the user thinks in bottles
  and knows his bottle's size.

### Change log from the walkthrough review

Added after modelling a full day's check-ins with the user.

- **"Took time for yourself"** added as a daily goal, single-select with a **no-opportunity** option
  for days too busy to allow it. Introduced the neutral-answer concept now in §3.4 and constraint 17.
- **Worked out, stretched and coffee** moved from daily targets to **weekly** targets on their
  roll-ups. Rationale: a daily "must be yes" on an inherently non-daily behaviour reads as a ~50% hit
  rate and lands in "going badly" for no real failure; assessing the week keeps each target binary
  while letting one off day pass. This is granularity, not partial credit — partial credit stays
  rejected (constraint 8).
- **The three weekly social questions** confirmed as **goals** (must be yes) rather than
  observations, each carrying the no-opportunity option, since some weeks genuinely allow none of them.
- **Mindset** confirmed an **observation with no target.** A mindset goal would score the user on how
  they felt and drag goal completion down on low days — the shame path §1 avoids. Average mindset is
  surfaced as a derived signal instead.
- **Meals set to "at least 3" rather than "exactly 3"**, decided during M2 when the scoring engine
  exposed the consequence: under an exact target a fourth meal scores as a miss, and the attainment
  figure beside it would cap at 100%, so the dashboard would read "missed, 100%". The direction was
  wrong, not the arithmetic. No seed goal now uses `exactly`; the direction remains available.

---

## 5. Surfaces

### 5.1 Dashboard

Deliberately simple. Top to bottom:

**Outstanding check-in banner.** Persistent, not modal. States plainly what is unanswered and allows
backfilling in place. It does not block access to the dashboard, because trapping the user on open
is what teaches them not to open it.

**The score element.** One large donut, **three concentric rings**: response rate outer, **daily goal
completion** middle, **weekly goal completion** inner. Never one blended number — see constraints 7
and 8. Response rate is the primary metric and stays the outer, most prominent ring; it is the same
thing the run counts, so the top of the screen still tells one coherent story.

Goal completion is **split into two rings, one per granularity,** rather than collapsed into one.
Pooling daily and weekly goals into a single ratio silently drowns the ~2 weekly instances per window
under the ~14 daily ones — a hidden weighting that also makes the number untraceable. So each ring is
instance-based **within its own granularity**: the daily ring is met daily-goal instances over
active-on-answered-days; the weekly ring is met weekly-goal instances over the weeks that have
**closed** in the window (the current partial week is progress, not scored — §5.3). Both are unweighted
and neither is mixed into the other. This resolves O1 (§6).

**Ring geometry is a visual choice, not a metric one — settle it in the dashboard build.** Full
concentric rings are the working default, but a **half-ring / arc gauge** (each figure a semicircular
sweep) may read more cleanly and should be prototyped alongside them at M10. Whichever is chosen, the
three figures stay separate and unblended; the geometry does not touch the numbers.

**Longest run**, beside the donut, as a static historical fact that only ever increases. Never a live
counter that can break. Definition in §3.5.

**Three rotating panels** in a horizontal carousel: going badly, going well, middling. Each holds up
to three items with a progress bar showing hit rate over the window, plus a secondary line showing
**average attainment** whenever it is materially higher than the hit rate. That secondary line is
what stops "1.5 of 2 bottles every single day" from reading as unqualified failure.

- The **badly** panel loads first and is the default position. This is an accountability product and
  the bad news must not be the thing the user waits for.
- The carousel **auto-advances and is swipe-driven.** Assumption unless corrected: auto-advance
  every ~8 seconds, rotation stops permanently once the user swipes, so it never moves under a
  finger.
- Panel membership is **automatic by hit rate.** Fewer than three items shown when fewer qualify;
  an empty panel is hidden rather than padded.

### 5.2 Thresholds and windows

| Thing | Value |
|---|---|
| Rolling window, all dashboard figures | 14 days, one window everywhere |
| Comparison baseline | Two-week average. No comparison shown until two weeks of history exist. |
| Going well | Hit rate ≥ 80% |
| Middling | 60% ≤ hit rate < 80% |
| Going badly | Hit rate < 60% |

### 5.3 Partial periods

Weekly figures mid-week are shown as **progress against elapsed days**, not scored against the full
week. A week is only marked met or missed once it closes. Scoring a Tuesday against a seven-day
target makes every week look like a failure until Sunday, which is a demoralising bug wearing the
costume of honesty.

### 5.4 Item detail view

Chart type is chosen automatically from answer type. Per-item chart configuration is deliberately
not a setting.

| Answer type | View | Reason |
|---|---|---|
| Yes/no, single-select, multi-select | Calendar heatmap | The question is the pattern of days, not the value. A line of 0s and 1s is unreadable. Single-select (took time, the social goals) colours each day by the chosen option, no-opportunity among them. |
| Number, 1–5 scale | Line chart | These have magnitude; the point is drift over weeks. |
| Time-of-day | Line chart | **The y-axis is a clock and must respect the 04:00 boundary**, or a 01:30 bedtime plots as the earliest night of the month instead of the latest. |
| All types | Plain table, always available | The only view exposing backfilled, edited, pending and provisional states, and notes in bulk. |

Exact assignment per library item is finalised alongside the library.

**Notes surface here**, attached to the individual data point: tapping a day reveals that day's note.

**Per-item longest run** is shown here.

**No-opportunity usage is shown here** for goals that carry the option (constraint 17): how often the
neutral answer was chosen over the window, so leaning on it stays legible rather than hidden.

**Hit rate and average attainment are both shown here, side by side and never merged.** For numeric
goals the pair is the point: hit rate is how often the target was met, average attainment is how
close the user came on average over the window.

### 5.5 First run

Every trend, comparison, percentage and panel is **suppressed until 14 days of history exist.**
Before then the dashboard shows today's status and a plain count of check-ins answered, with an
explicit line stating that trends appear after two weeks. A chart drawn through four points is worse
than no chart.

### 5.6 Check-in screen

Tap-first, keyboard only for the optional note, under 60 seconds. Pending items carried from the
previous night must be unmistakably labelled as belonging to the previous day.

### 5.7 Setup

Library-first: the user picks from pre-defined items and may add custom ones. Zach's set arrives
pre-selected, pre-classified and pre-slotted, all editable, skippable in one tap. Includes the health
permissions prompt and its declined branch (§3.3).

---

## 6. Open questions

| # | Question | Notes |
|---|---|---|
| O1 | Goal-completion formula for the inner ring. | **Resolved.** Split into a daily ring and a weekly ring, each instance-based within its granularity, never merged. See below and §5.1. |
| O2 | Multi-select target interaction design | Mechanism accepted; the UI for "must not include *option*" is unbuilt and the user expects it to be fiddly. |
| O3 | Health integration API surface | Health Connect is the current Android path; the older Fit APIs have been deprecating. **Verify at build time rather than trusting this document.** |
| O4 | Measured-day finality | **Claude's decision, not the user's:** provisional for 24h after the 04:00 read, then frozen. Chosen because platform step-tracking behaviour is unverified, and this is the option that tolerates late syncing without permanently mis-scoring a day. Reversible in an afternoon; revisit after a week of real data. |
| O5 | Carousel timing | Assumed 8s auto-advance, stops on first swipe. Never confirmed. |
| O6 | Chart assignment per library item | Finalise with the library. |
| O7 | Development seed-data fixture | **Now planned as build-order M9.** A debug-only fixture that populates Room directly (not a standalone script). Target volume **~6 months** of generated history including gaps, backfills, retired items and effective-from changes — enough for month-over-month trends and comparisons. This is a testing tool, not a precondition for usefulness: the app works from day one and the dashboard needs 14 days. |

### O1 in detail — resolved

Response rate has an obvious denominator. Goal completion does not, and the user flagged this himself.
The problems: daily and weekly goals live on different period granularities and cannot be pooled into
one ratio without silently underweighting the weekly goals (~2 instances per window against ~14
daily); and an unweighted mean treats "took vitamins" and "hit 10,000 steps" as equally demanding.

An earlier provisional answer — *daily goals only* — was overtaken by the walkthrough reclassification
that moved worked out, stretched, coffee and the three social questions to **weekly** targets. After
that, "daily only" would leave the ring blind to seven of the roughly twelve goals, including all of
fitness and social — exactly the flatter-the-easy-stuff failure §1 is built against.

**Resolution: split goal completion into two rings, one per granularity.** A **daily goal completion**
ring (met daily-goal instances ÷ active-on-answered-days) and a **weekly goal completion** ring (met
weekly-goal instances ÷ instances over the closed weeks in the window). Each is instance-based *within*
its own granularity, so nothing is pooled across granularities and nothing is hidden; both sit inside
the response-rate ring on the donut (§5.1). Every ring stays explainable and traceable — a score change
maps to a specific behaviour — while the weekly goals are back in the headline. The visual cost is a
third concentric ring, accepted as a known, glanceable pattern.

Explicitly *not* chosen: per-goal importance weighting (it makes a score change untraceable to a
behaviour), and pooling all goals into a single ratio (it drowns the weekly goals). An unweighted mean
of every goal's hit rate was also considered and set aside — the two-ring split was preferred because
it stays purely instance-based and needs no averaging of averages.

**Still open, but visual only:** whether the two completion figures render as full concentric rings or
as a **half-ring / arc gauge** is a presentation choice for the dashboard phase (build-order M10), not
a change to the metric. A half-ring may be easier to read; both are worth prototyping then.

### Recorded trade-offs

**Guilt as a design goal.** The user's stated intent is that the app make the user feel the weight of
a missed or negative day. Claude's objection, recorded rather than blocking: shame-based tracking
fails by driving users away during exactly the bad stretches the record matters most for.
**Resolved across three rounds** by (1) bluntness without escalation, with confrontation at app-open
rather than in notifications; (2) a banner rather than a blocking modal; (3) defining the run as
days answered rather than days performed, so honesty on a bad day protects the streak instead of
breaking it. The guilt remains intended. The delivery mechanisms were each chosen to avoid the
uninstall path.

**Steps in v1.** Claude recommended cutting steps until integration existed. The user overruled and
raised integration into v1 instead. Cost accepted: a platform dependency, a permissions flow, and a
second class of tracked item, all in the first release.

**Auto-rotating carousel.** Claude pushed for swipe-only, on the grounds that a rotating panel keeps
the bad news off screen most of the time and moves while being read. The user chose both rotation and
swipe, with the bad panel leading. Compromise recorded; the "stops on first swipe" assumption is the
mitigation.

**Export size.** Low risk. A year of this data is on the order of 10,000 short rows.

---

## Appendix: constraints future work must not silently undo

Each of these looks like something worth tidying up and is not.

1. **The 04:00 day boundary.** Changing it re-dates every historical record.
2. **Per-period targets and container sizes with effective-from dates**, keyed on (item, period
   type). Collapsing to a single current value destroys the truthfulness of historical scoring.
3. **Target direction stored alongside target value.** A bare number cannot express "at most two".
4. **Capture state and edit timestamp as two separate fields on every answer.** Capture state is
   how it was first recorded; the edit timestamp is whether it was later corrected. Merging them
   into one flag loses the ability to describe a backfilled answer that was later edited, and makes
   every consistency figure unverifiable.
5. **The sleep-day convention.** Sleep items belong to the day the user went to bed. Re-dating them
   to the day they physically occurred breaks sleep duration and leaves every day's record
   incomplete.
6. **Item versioning and retirement instead of deletion.** Deleting an item orphans or misrepresents
   its history.
7. **Weeks and periods derived from stored dates**, never stored as identifiers.
8. **Response rate, daily goal completion and weekly goal completion reported separately, never
   merged.** A blended number cannot distinguish "not answering" from "not doing", nor daily
   follow-through from weekly — different problems with different remedies. Goal completion is split
   by granularity because pooling daily and weekly goals into one ratio underweights the weekly ones.
   Decided early, nearly undone by the dashboard, re-confirmed; the daily/weekly split was added when
   O1 was resolved (§5.1, §6).
9. **One primary answer format per item plus an optional note.** Do not widen primary formats to
   absorb detail.
10. **Named behaviours over derived abstractions.** YouTube and social scrolling must remain
   distinguishable.
11. **Absence-based goals score only on answered days.** Silence is not success.
12. **The run counts days answered, not days performed.** Changing it to require goals met
    reintroduces the incentive to hide bad days.
13. **The two cross-item metrics are hardcoded to the three sleep/wake *time* items**
    (bedtime, woke at, got out of bed; not the pre-sleep multi-select). This is intentional
    special-casing, not technical debt. Generalising it requires a type system, validation, retired-
    source handling and a UI, for a feature with two known uses.
14. **Two retrospective checkpoints.** Any feature assuming the app can influence a same-day outcome
    contradicts the core design.
15. **`user_id` present on every record from the first migration.**
16. **Attainment reported alongside hit rate, never merged into it or dropped.** The parallel of
    constraint 8, and it will look like the same number twice to anyone who wasn't here. It isn't:
    hit rate is whether the target was met, attainment is how close. Dropping attainment makes
    consistent near-misses indistinguishable from never trying. Merging them reintroduces the
    partial credit that was deliberately rejected. See §1, "show the distance, never close it".
17. **No-opportunity answers are neutral, visible, and evaluated before direction.** A goal may
    designate one select option as *no opportunity*; choosing it excludes that period from the goal's
    completion (neither met nor missed) and does not break the item's run, while the check-in still
    counts as answered. It is distinct from silence (constraint 11, which is excluded *and* costs
    response rate) and from an unresolved pending answer (scored as missed). It is not a general skip
    button: because it is self-reported and neutral, its usage frequency must stay visible on the item
    detail view. Removing that visibility, or letting it read as a miss, each break it.
