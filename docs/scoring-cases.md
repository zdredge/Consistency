# Scoring Cases

**Intended repo path:** `docs/scoring-cases.md`

The spec's §3.4 rulebook expressed as concrete expected values. This is the test suite specification
for `:domain`. It exists because scoring failures are silent: a wrong direction comparison or a
target resolved from the wrong period does not crash, it produces a plausible number that is wrong.

Every case below should be a JVM unit test in `:domain` with no Android dependency.

**Three ambiguities in the spec were exposed while writing this, and have since been resolved with the
user** — see *Resolved: former ambiguities* at the bottom; the rules now live in the spec. Each
changed real numbers, which is why they were resolved rather than guessed. A later addition, §10,
covers the no-opportunity answer introduced at the walkthrough review.

---

## 1. Target directions

| # | Given | Then |
|---|---|---|
| 1.1 | water, AT_LEAST 2, answer 2 | met |
| 1.2 | water, AT_LEAST 2, answer 1 | missed; ratio 50% displayed but contributes 0 |
| 1.3 | coffee, AT_MOST 2, answer 2 | met |
| 1.4 | coffee, AT_MOST 2, answer 3 | missed |
| 1.5 | meals, EXACTLY 3, answer 3 | met |
| 1.6 | meals, EXACTLY 3, answer 4 | missed |
| 1.7 | vitamins, IS_TRUE, answer true | met |
| 1.8 | vitamins, IS_TRUE, answer false | missed |
| 1.9 | lingered, IS_FALSE, answer false | met |
| 1.10 | pre-sleep, MUST_INCLUDE `read_a_book`, selections {read_a_book, watched_youtube} | met |
| 1.10b | pre-sleep, MUST_INCLUDE `read_a_book`, selections {watched_youtube} | **missed.** The direction names *one* option (spec 3.4, "must include *option*"); other selections present are irrelevant to it. |
| 1.10c | pre-sleep, MUST_INCLUDE `read_a_book`, selections {} — answered, nothing chosen | **missed.** An answered "none of these" is a real answer, so it is a miss. Only a wholly absent answer is excluded (1.13). |
| 1.11 | pre-sleep, MUST_NOT_INCLUDE `scrolled_on_phone`, selections {read_a_book, watched_youtube} | met |
| 1.12 | pre-sleep, MUST_NOT_INCLUDE `scrolled_on_phone`, selections {scrolled_on_phone} | missed |
| **1.13** | **pre-sleep, MUST_NOT_INCLUDE `scrolled_on_phone`, no answer exists for the day** | **not scored — excluded from both numerator and denominator. Not met. Not missed.** |

**1.10b and 1.10c were added after M2 Phase 1**, which exposed that this section originally
specified only three of the four selection combinations — `MUST_INCLUDE` with the option *absent*
was never stated. It is a miss.

**1.13 is the highest-priority test in this document.** Spec constraint 11: silence is not success. A
naive implementation of "the forbidden option is not present" returns true for a missing answer and
silently inflates goal completion on exactly the days the user skipped.

## 2. Numeric goals: binary scoring, separate attainment

Scoring is binary. **Attainment is reported separately and must not be lost** — these are two
different numbers answering two different questions, and the tests should assert both.

| # | Given | Then |
|---|---|---|
| 2.1 | water AT_LEAST 2 bottles, answer 1.5 | goal **missed**; contributes 0 to goal completion, not 0.75 |
| 2.2 | same day | attainment for that day is **75%**, reported independently of the score |
| 2.3 | water AT_LEAST 2, answer 1.5 on all 14 days of the window | **hit rate 0%, average attainment 75%.** Both must be produced. Item lands in the going-badly panel by hit rate, with attainment shown as the secondary line. |
| 2.4 | water AT_LEAST 2, answer 3 | met; attainment caps at 100% rather than reporting 150% |
| 2.5 | coffee AT_MOST 2, answer 3 | missed. Attainment is meaningless for an at-most direction and must not be reported as 150% or 67%. Assert it is absent, not zero. |

## 3. Missed, backfilled, pending, edited

| # | Given | Then |
|---|---|---|
| 3.1 | night check-in never answered, day closed | response rate: denominator +1, numerator +0. Goal completion: that day contributes nothing to either side. |
| 3.2 | night check-in answered next morning, within grace | `capture = BACKFILLED`. Counts as answered for response rate. Goals scored normally. |
| 3.3 | night goal answered "not yet", resolved in next morning's check-in | `capture = IN_WINDOW`, per spec §3.2 |
| 3.4 | answer edited three days later | `capture` unchanged; `edited_at` set. The in-window-only figure derives from `capture` alone, so it is unaffected by the edit. |
| 3.5 | in-window-only response rate over a week containing two backfilled check-ins | strictly lower than the headline response rate. Both must be computable from the same rows. |

## 4. Runs

Spec §3.5: a run counts consecutive days on which every scheduled check-in was answered.

| # | Given | Then |
|---|---|---|
| 4.1 | days 1–5, all check-ins answered | current run 5 |
| 4.2 | day 6 night missed, never backfilled | current run resets to 0; longest run stays 5 permanently and never decreases |
| 4.3 | day 6 night backfilled on day 7 within grace | run is preserved and continues. Backfill within grace does not break a run. |
| 4.4 | day 7: every check-in answered, every goal failed | **run continues.** This is the entire point of the definition — it measures showing up, not performing. |
| 4.5 | per-item run for vitamins: met on 12 consecutive days, missed on day 13 | item longest run 12; current 0. Independent of the global run. |

## 5. Targets and container sizes over time

| # | Given | Then |
|---|---|---|
| 5.1 | water AT_LEAST 2 effective 2026-08-01; raised to 3 effective 2026-09-01. Answer of 2 on 2026-08-15 | met, scored against 2 |
| 5.2 | same, answer of 2 on 2026-09-15 | missed, scored against 3 |
| 5.3 | recomputing August's figures after the September raise | August is unchanged. Spec constraint 2. |
| 5.4 | container size 40 oz effective 2026-08-01, changed to 32 oz effective 2026-10-01. August answer "2" | resolves to 80 oz, not 64 |

## 6. Item lifecycle

| # | Given | Then |
|---|---|---|
| 6.1 | item created 2026-09-10; scoring the week of 2026-08-31 | item excluded entirely. Not counted as missed. Spec §3.4. |
| 6.2 | item retired 2026-10-05; scoring September | included. Scoring November: excluded. |
| 6.3 | item wording edited, creating version 2; scoring a period containing answers from both versions | both answers score; each stays bound to the version it was given under |
| 6.4 | a new option added to a multi-select's option list | no new item version; prior answers unaffected. Options hang off the item, not the version. |

## 7. Dual-granularity targets

Spec §3.4: a daily and a weekly target on one item are scored independently and never collapsed.

| # | Given | Then |
|---|---|---|
| 7.1 | steps, 10,000 daily and 70,000 weekly. Seven days of 8,000 (total 56,000) | daily missed ×7; weekly missed |
| 7.2 | same targets. Three days of 25,000 and four of 0 (total 75,000) | daily met ×3, missed ×4; **weekly met.** Both reported separately. |
| 7.3 | coffee recorded daily, **no daily target**, weekly AT_MOST 14 (sum). A week summing to 13 | weekly **met**; there are no daily misses because there is no daily target. |
| 7.4 | same, a week summing to 16 | weekly **missed**. One 5-coffee day inside an otherwise low week does not by itself fail anything; the week's sum does. This is the "soft via granularity, not partial credit" behaviour for worked out / stretched / coffee. |

7.2 is the case that justifies the rule. A single blended figure would hide which kind of week it was.
7.3–7.4 record that moving worked out / stretched / coffee to weekly-only targets is still binary
scoring — the week is a clean hit or miss — not a softened daily target.

## 8. Derived metrics

Computed from the three sleep/wake **time** items only. Never persisted.

| # | Given | Then |
|---|---|---|
| 8.1 | bedtime 23:30, woke 08:30, got up 08:52 | sleep duration 9h 00m; lingering 22m |
| 8.2 | bedtime 01:30 (belongs to the previous day under the 04:00 rule), woke 09:00 | sleep duration 7h 30m |
| 8.3 | bedtime 23:30, woke 08:30 | the naive subtraction is negative; the rule is that when waking is earlier on the clock than bedtime, add 24 hours |
| 8.4 | `got_up_at` missing | lingering is **unavailable**, not zero. Sleep duration also unavailable if either endpoint is missing. |
| 8.5 | bedtime 01:30 on 2026-08-26 entered via the morning check-in of 2026-08-26 | `day_date` is 2026-08-25. The 04:00 day rule and the sleep-day convention agree here; a test should assert that they do. |

## 9. Panels, window, partial periods

| # | Given | Then |
|---|---|---|
| 9.1 | hit rate 80% | going well |
| 9.2 | hit rate 79% | middling |
| 9.3 | hit rate 60% | middling |
| 9.4 | hit rate 59% | going badly |
| 9.5 | 13 days of history | all figures, panels and comparisons suppressed |
| 9.6 | 14 days of history | figures appear |
| 9.7 | Wednesday of a Monday-start week; weekly steps target 70,000; 30,000 over three elapsed days | shown as progress against elapsed days. **Not** scored as missed. The week is only marked met or missed once Sunday closes. |
| 9.8 | all dashboard figures on one screen | every one uses the same 14-day window |

---

## Resolved: former ambiguities

All three were gaps in the spec exposed by writing this document. Resolved by the user; the rules now
live in `docs/product-spec.md` §3.2 and §3.4. Cases below.

### A1 — Backfill after the grace window: capture `LATE`

A never-answered item can be filled in after grace closes. It records as `LATE`, and **a late answer
does not repair the check-in or the run.**

| # | Given | Then |
|---|---|---|
| A1.1 | night check-in of day 1 never answered; the item is filled in on day 5 | answer stored with `capture = LATE` |
| A1.2 | same | day 1's check-in **stays MISSED**. Response rate is unchanged by the late answer. |
| A1.3 | same | the run is **not** restored. Day 1 remains a break. |
| A1.4 | same | goal completion for day 1 now scores the late answer. The data counts even though the metric does not. |
| A1.5 | an entire week filled in on the following Sunday | response rate for that week is 0%. This is the round-1 decision that unlimited backfill must not produce a flattering number. |

### A2 — Unresolved pending: goal missed, check-in stays answered

| # | Given | Then |
|---|---|---|
| A2.1 | night item answered "not yet"; next morning's check-in missed | at rollover the pending answer converts to a **missed goal** |
| A2.2 | same | the **night check-in stays ANSWERED**. The user did complete it. |
| A2.3 | same | the morning check-in is MISSED, which breaks the run on its own. A2.2 does not rescue it. |
| A2.4 | night item answered "not yet"; morning check-in answered but the carried item left blank | same as A2.1: goal missed. Both check-ins stay answered and the run survives. |

**A2.1 is the only case in the entire rulebook where an absent value scores as missed rather than
being excluded.** A test should assert this against case 1.13, which is the opposite treatment, so
that the asymmetry is deliberate rather than an inconsistency someone later "fixes".

### A3 — Roll-ups over incomplete weeks: count observed, label incomplete

| # | Given | Then |
|---|---|---|
| A3.1 | workout yes on 4 days, no on 1, unanswered on 2 | roll-up reports **4**, not 4-of-5 |
| A3.2 | same, with a weekly target of AT_LEAST 4 | **met**, assessed against the observed 4 |
| A3.3 | same | the week is flagged **incomplete** wherever the figure is shown |
| A3.4 | workout yes on 3 days, unanswered on 4, weekly target AT_LEAST 4 | missed, and flagged incomplete. The app does not guess that the four blanks were workouts, nor that they weren't. |

---

## 10. No-opportunity (neutral) answers

New with the "took time for yourself" question and the weekly social goals. A goal may designate one
select option as *no opportunity*; choosing it excludes the period from that goal's completion
without counting as a miss, while the check-in still counts as answered. Spec §3.4 and constraint 17.

| # | Given | Then |
|---|---|---|
| 10.1 | took-time, answer `yes` | met |
| 10.2 | took-time, answer `no` | missed; contributes 0 to goal completion |
| 10.3 | took-time, answer `no opportunity` | **excluded** — numerator and denominator both unchanged. Not met, not missed. |
| 10.4 | same day as 10.3 | the check-in is still **ANSWERED**; response rate is unaffected; the global run is unaffected |
| 10.5 | took-time answered `no opportunity` on 14 consecutive days | the item appears in no panel by hit rate (no hits and no misses to rank); its per-item run is neither extended nor broken; and **usage frequency of the no-opportunity option is available on the detail view** |
| 10.6 | weekly "invited someone", answer `no opportunity` for the week | that week is **excluded** from the goal's completion; not missed; the weekly check-in still counts as answered |
| 10.7 | no-opportunity option evaluated against a target | exclusion happens **before** direction — a no-opportunity answer is excluded whatever the direction says, never run through the comparator |

**10.3 must be asserted against 1.13 and A2.1 together.** Three different treatments of "no positive
answer", and the difference is the whole point:

- **1.13** — silence (no answer at all): excluded, and it costs the check-in / response rate.
- **A2.1** — unresolved pending: scored **missed**.
- **10.3** — no-opportunity: excluded, and it costs **nothing**, because the user actively answered.

A single test placing the three side by side keeps the distinctions deliberate, so none is later
"fixed" into another.

---

## 11. Goal-completion rings (O1)

Goal completion is two figures, one per granularity, never merged with each other or with response
rate. Each is instance-based **within** its granularity. Spec §3.4, §5.1, constraint 8.

| # | Given | Then |
|---|---|---|
| 11.1 | 4 daily goals over 14 answered days, 42 of 56 daily instances met | **daily goal completion = 75%**, instance-based over answered days |
| 11.2 | same window, one daily goal answered `no opportunity` on 3 of its days | those 3 instances are excluded from numerator and denominator (§10); the ring is computed over the remaining scored instances |
| 11.3 | 7 weekly goals, 2 closed weeks in the window, 10 of 14 weekly instances met | **weekly goal completion ≈ 71%**, instance-based over closed weeks only |
| 11.4 | mid-window, the current week has not closed | the current week's weekly instances are **excluded** from the weekly ring (progress, not scored — §5.3); only closed weeks count |
| 11.5 | a window in which no weekly goal has yet closed a week | the weekly ring is **pending/empty, not 0%** — nothing has been scored to show |
| 11.6 | both rings on one dashboard | computed and shown as two separate rings; assert they are never summed, averaged together, or merged with the response-rate ring |
| 11.7 | a goal with no scored instances in the window (all no-opportunity, or newly created) | excluded from its ring's denominator entirely — same as an empty panel |
| 11.8 | fewer than 14 days of history | both goal-completion rings are suppressed with the rest of the dashboard (§5.5) |

Each ring is the panel hit rates aggregated within one granularity, so a ring and its panels always
tell the same story. A test should assert 11.1 and 11.3 are **kept distinct** — never combined into a
single goal-completion number — which is the O1 resolution and constraint 8.
