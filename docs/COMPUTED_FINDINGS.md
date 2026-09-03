# Computed findings — arithmetic held up beside what the designer typed

Two stages of the workshop ask a designer to write down a conclusion that the rows in another stage
either support or contradict. Stage 9 asks for price bands and a SWOT, over a market survey captured
in stage 8. Stage 17 asks for a material cost, a labour cost and a margin, over the line items
recorded underneath them on the same page. Until recently nothing in this system compared the two,
and the report printed the assertion while the evidence sat one table away.

`backend/app/services/market_analysis.py` and `backend/app/services/cost_integrity.py` do the
comparison. Neither of them changes anything.

**Why this is its own document rather than a section of
[DESIGN_WORKSHOP.md](DESIGN_WORKSHOP.md).** That document describes how a field is declared, stored
and printed — one declaration, several surfaces, one authority. This one describes the opposite
arrangement: the *same algorithm written three times*, in Python, TypeScript and Kotlin, where the
whole value rests on the three agreeing to the rupee and nothing at build time proves they do. The
discipline that keeps them equal is the subject, and it is a different subject from the registry.

Sister documents: [DESIGN_WORKSHOP.md](DESIGN_WORKSHOP.md) for the stages and the report pipeline
these findings sit beside, [PERMISSIONS.md](PERMISSIONS.md) for who may read a workshop at all.

---

## 1. The three rules both modules obey

These are not defensive clutter to be simplified away later. Each of them was written because the
obvious alternative produces a document a ministry reads.

**They are pure.** No database, no network, no model. Rows in, findings out. That is what lets the
same code run inside a report render on the server, in the browser, and on a handset in a courtyard
with the radio off — which is the only arrangement where the designer who most needs the finding
gets it, because they are standing in the village where the survey was taken and can still act on
it.

**They refuse to conclude from too little.** Every figure carries the sample it came from; below a
floor, the figure is withheld rather than softened. `market_analysis` withholds quantiles below
`MIN_SAMPLE_FOR_QUANTILES` and withholds a *verdict* below the higher `MIN_SAMPLE_FOR_VERDICT`;
`cost_integrity` reports a sheet with no line items as `NOT_ITEMISED` and a sheet whose lines cannot
all be read as `INCOMPLETE`, rather than totalling the readable half. A tool that produces confident
numbers from three data points is worse than no tool, because the confidence is what gets carried
into the report.

**They never overwrite the designer.** Nothing here writes to stage 9 or stage 17. The declared band
stays exactly as typed and the typed subtotal stays exactly as typed; what is produced is a FINDING
beside it. A subtotal may legitimately differ from its lines — a rounding, a cost carried but not
itemised, a rate renegotiated after the lines were entered — and the designer was in the room when
it was decided. What they did not have was the arithmetic in front of them.

---

## 2. Market analysis — stage 9's Advanced tier

`backend/app/services/market_analysis.py` · `frontend/lib/marketAnalysis.ts` ·
`android/app/src/main/java/com/designprototype/workshop/data/DwMarketAnalysis.kt`

### 2.1 What it computes

From the stage-8 survey rows (`surveyResponse`, `competitorProduct`) and the stage-9 claims
(`priceBand`, `swotPoint`):

| Finding | Function |
|---|---|
| The price distribution, respondents and competitors **kept apart** | `describe`, via `collect_observations` |
| A verdict on each declared price band against that distribution | `judge_band` |
| Where each competitor product sits among the prices buyers named | `position_competitors` |
| Which SWOT points share vocabulary with a response, and which share none | `link_swot_evidence` |
| Which products respondents discussed together | `cluster_products` |
| The cautions that qualify all of the above | `analyse` |

Respondent and competitor prices are kept apart because they answer different questions: what a
buyer says they will pay, and what the shelf charges. Pooling them would produce one distribution
that describes neither.

### 2.2 The three floors, and why they differ

| Constant | Value | What it withholds |
|---|---|---|
| `MIN_SAMPLE_FOR_QUANTILES` | `5` | Quartiles. Below it, `p25`/`p75` are filled with the median and `quantilesReported` is **false**, so a caller that ignores the flag shows something defensible rather than a quartile computed from two numbers. |
| `MIN_SAMPLE_FOR_VERDICT` | `8` | Any verdict on a declared band. Below it the band is `UNVERIFIABLE`. |
| `NARROW_COVERAGE` | `0.55` | Nothing — it is the line between `SOUND` and the three actionable verdicts. |

The verdict floor is deliberately **higher** than the quantile floor, and the asymmetry is the
point: showing a spread computed from six numbers is honest, and telling a designer their considered
band is *wrong* on the strength of six numbers is not.

`NARROW_COVERAGE` is not a statistical threshold and is not presented as one. It is the coverage at
which "your band misses most of the people you asked" becomes worth interrupting a designer to say.
None of these three numbers is MEASURED; they are declared judgements, argued in the constants'
own docstrings, and the code says so.

### 2.3 The verdict vocabulary, and why `UNVERIFIABLE` is not a criticism

| Verdict | Means |
|---|---|
| `SOUND` | The band covers `NARROW_COVERAGE` or more of the observed market |
| `LOW` | The evidence clusters above the band (`above > below * 2`) |
| `HIGH` | The evidence clusters below it (`below > above * 2`) |
| `NARROW` | It excludes most of the market without being wholly on one side |
| `UNVERIFIABLE` | Fewer than `MIN_SAMPLE_FOR_VERDICT` observations. **A statement about the sample, not about the band** |
| `NO_EVIDENCE` | No observations in this category at all, or the band itself is incomplete |

`UNVERIFIABLE` exists because the alternative destroys the feature. Seven price expectations cannot
tell a designer that a band they set after two weeks in the cluster is wrong, and a designer told so
by a machine counting seven numbers will — correctly — stop believing the tool, taking the true
findings with them. The counts are still reported so they can look and decide for themselves.

`frontend/components/designworkshop/MarketFindingsPanel.tsx` carries the same rule into the pixels:
`UNVERIFIABLE` and `NO_EVIDENCE` are drawn NEUTRAL, and only `LOW`, `HIGH` and `NARROW` are allowed
to look like something to act on. Every chip carries a **word** as well as a tint, so the reading
survives greyscale, colour-blindness and the glare of a courtyard at noon.

A reversed band (low above high) is read in the order the designer meant rather than refused. The
two boxes are adjacent on a phone; transposing them is a slip, not a claim, and answering a slip
with "no verdict" would hide the real finding underneath it.

### 2.4 Why the cautions print before the figures

`cautions` and `unsupportedSwot` are named at the **top** of the wire payload
(`market_findings_payload`), not nested inside the sections they qualify, and the panel renders them
in that order — above even the sentence saying where the analysis came from, because that sentence
carries the observation count and no figure may precede a caution.

The reason is blunt: a caution that has to be scrolled to is a caution that will not be read, and
the whole value of this analysis rests on a designer seeing *"38 of 41 priced responses come from
one respondent group"* **before** they see the median. Three cautions exist today — too few
respondent prices to be a market estimate, a sample dominated by one respondent group, and
competitor prices recorded with no buyer expectations to compare them against.

### 2.5 Why the SWOT link is a retrieval aid and never a judgement

`link_swot_evidence` matches a SWOT point to responses that share at least `_SUPPORT_OVERLAP` (two)
content words. Two rather than one because one shared word is a coincidence at this vocabulary size,
and rather than three because three misses a short point ("price too high") against a short
response.

Shared words mean a response is worth reading next to the point and nothing more. What the check
does *reliably* is the negative case: a SWOT point that shares no vocabulary with any response and
cites no evidence of its own is an assertion, and saying so is worth more than any positive match.
A point whose `evidence` field is filled is supported by definition — the designer named a source,
and that is not second-guessed.

### 2.6 Why the browser computes locally rather than calling the endpoint

`GET /design-workshops/{id}/market-analysis` exists, and it is **the fallback, not the source**.

The panel reads stage 8 out of this device's IndexedDB draft and runs `analysePayload` over it. That
is the entire reason the TypeScript port exists: the designer who most needs to know their band is
₹300 under what buyers said is the one standing in the village where they asked, on no signal, and a
panel that fetched its findings would be blank exactly there. The endpoint is reached only when
stage 8 has never been downloaded to this device, and never for a workshop the server has no id for.

Two consequences the panel is careful about:

- **"Downloaded and empty" and "never downloaded" are different facts.** A workshop whose stage 8
  genuinely holds no rows must compute locally and say "no prices were recorded"; a device that has
  simply never read stage 8 must not, because it would report a forty-person survey as no evidence
  at all and mark every SWOT point unsupported. The panel distinguishes them on
  `serverLoadedAt !== null || stageHoldsSomething(stage)`.
- **The panel says which of the two it is showing.** The server's answer is computed from what has
  been SAVED; the local one from what is on screen. A designer reading a verdict on a band they
  typed two minutes ago has to know which.

The endpoint's own reason to exist is the report render — which must not depend on whichever device
happens to be looking — and a machine that has never synced the stage.

### 2.7 The port-parity rules

`market_analysis.py` is the authority. When a port disagrees with it, the port is broken. Six things
make disagreement easy in JavaScript, and each is written out by hand rather than delegated to a
library:

| Rule | The obvious code is wrong because |
|---|---|
| **Quantiles** — linear interpolation on `(n-1)` positions, numpy's default: `pos = q*(n-1)`, then `v[lo] + (v[hi]-v[lo])*(pos-lo)` | Every library has its own default. Three ports have to agree to the rupee, and none of them may take a numerics dependency. The caller sorts, because each caller computes several quantiles from one sample. |
| **Rounding** — `pyRound` implements Python's round-half-to-**even** | JS `toFixed` and `Intl.NumberFormat` round half *away from zero*. They differ on every exact half: a median of ₹624.50 prints ₹624 on the server and ₹625 in a naive port. Every figure in the payload goes through `round()` server-side, so every figure in the port goes through `pyRound`. |
| **Money formatting** — `money0` reproduces `format(x, ",.0f")` | `Intl.NumberFormat` is locale-sensitive where this must not be: an Indian locale groups ₹1,00,000 where the server prints ₹100,000. Two spellings of one figure in one panel read as two figures. |
| **The tokeniser** — `[\p{L}\p{M}]+`, matched in Python by a category scan (`ch.isalpha() or category startswith "M"`) | **`\p{M}` is load-bearing.** `\w` in both languages follows `isalnum()`, which is false for combining marks, so the virama in ରଙ୍ଗ and the matra in ଦାମ become word BOUNDARIES. Odia words shatter into fragments the length floor then discards, and "unsupported by the survey" becomes the automatic verdict for exactly the fieldwork this application exists to collect. Invisible in English testing. |
| **Number grammar** — `PY_FLOAT`, then `Number()` | `Number()` reads `"0x1A"` as 26 and `""` as 0; Python's `float()` refuses both. `String([5])` is `"5"` in JS and `"[5]"` in Python, so a one-element list would have become a price on one side and nothing on the other. Both sides now refuse non-scalars outright. |
| **Ordering and summation** — `comparePyStrings` (code points, not UTF-16 units), means summed in **sorted** order, `Counter.most_common` tie-break reproduced, co-occurrence pairs keyed on a NUL-separated string | Floating-point addition is not associative, so a mean summed over the original order can differ from one summed over the sorted array. A joined pair key on a space is ambiguous — `["a b","c"]` and `["a","b c"]` collide, and "floor mat" is a real tag in this survey. |

Two smaller rules in the same family: token length is counted in **code points**, not `.length`,
because an astral letter would otherwise read as two and slip past the floor; and `pythonFalsy` /
`pyStr` exist so that `str(value or "")` means the same thing on both sides, including the case
where `NaN` is truthy in Python and falsy in JavaScript.

> **Half of this parity is now proven by value, and half of it still is not — corrected 2026-08-19,
> because the paragraph that stood here said "not proven by any test in this repository" after the
> test landed, and a reader acting on that sentence rebuilds a harness that already exists.**
>
> **KOTLIN ↔ PYTHON: PINNED.**
> `android/app/src/test/java/com/designprototype/workshop/data/DwAnalysisParityTest.kt` is the
> harness this paragraph said did not exist. One case table
> (`android/app/src/test/resources/dw-analysis-cases.json`) is fed to
> `backend/app/services/market_analysis.py` and `cost_integrity.py` verbatim, the payload Python
> produced is frozen into `dw-analysis-expected.json`, and the test recomputes THE WHOLE CASE TABLE
> in Kotlin and diffs the two documents leaf by leaf — naming the case, the JSON path and both values
> when it fails. The cases were chosen at the seams listed in the table above rather than for
> coverage: half-to-even, `\p{M}`, code-point ordering, `PY_FLOAT`'s grammar, `DwPy.strip` against
> `trim()`. **The table is itself tested by mutation** — its header records how many leaves each
> guard moves when broken (HALF_EVEN→HALF_UP moves 5, dropping `\p{M}` moves 4, `toDoubleOrNull`
> moves 16, `trim()` moves 31) and records one case, `m28-python-whitespace-forms`, added because
> `trim()` originally moved NOTHING. A guard nothing can break is a guard nobody can trust, and that
> is what makes this a proof rather than a green tick. **No case count is stated here on purpose.**
> This document carried "59" in three places while the table held 62 (29 market + 5 competitor
> positions + 28 cost), copied out of a KDoc that has since stopped stating one for the same reason:
> a number in prose beside a table that grows is a claim that rots on the next commit, and
> `DwAnalysisParityTest`'s first assertion — that the case table and the golden describe the same
> cases — is the only thing a reader wanted the number for. Two of the cases are not synthetic at all:
> `m27-live-workshop` and `k26-live-workshop` are a real 270-row workshop's stage-8/9/17 rows beside
> what the live endpoints answered for them. The golden is a CACHE of the Python, not a second
> opinion: if the two disagree the Python is right.
>
> **TYPESCRIPT ↔ PYTHON: STILL UNVERIFIED, and this is now the whole of the gap.** Nothing diffs
> `frontend/lib/marketAnalysis.ts` against the Python by value. `backend/tests/test_market_analysis.py`
> tests the Python; `frontend/e2e/market-findings-panel.spec.ts` proves the panel renders a finding
> without touching the network; `frontend/e2e/market-analysis-sum-unit.spec.ts` pins exactly one
> seam — `pySum`, the compensated summation CPython 3.12 does and a `for` loop does not — against
> CPython's own output, using the same numbers the Kotlin side pins as `m29-mean-compensated-sum`.
> That seam was found by a differential fuzz across the Kotlin and the Python, and then found here
> by inspection, which is the shape of the risk: **the browser port is the one surface where a
> divergence has to be noticed by a human.** The cheapest fix is to run `dw-analysis-cases.json`
> through the TypeScript and diff it against `dw-analysis-expected.json`; the golden is already
> language-neutral JSON.
>
> **CLOSED 2026-09-03, by exactly that fix.** `frontend/e2e/market-analysis-port-unit.spec.ts` runs
> the shared case table through `frontend/lib/marketAnalysis.ts` and diffs the whole payload against
> the goldens, path by path so a failure names the key that moved. It found two real divergences on
> its first run — Unicode decimal digits, and U+0085 in `strip()` — and both were closed the same day.
> **The prediction in this paragraph was right about the shape and wrong about who would notice**: a
> human never did, in the months the gap was open; the diff did, in one run. See
> [OPEN_FINDINGS.md](OPEN_FINDINGS.md) for what each one cost.

---

## 3. Cost integrity — stage 17's sheets against their own lines

`backend/app/services/cost_integrity.py` ·
`android/app/src/main/java/com/designprototype/workshop/data/DwCostIntegrity.kt`

### 3.1 Why the registry cannot express this check

`stage_schema.derive_value` takes `(spec, row)` and reads only the keys named in `spec.derived_from`
— **the same row's other fields**. A roll-up across a sibling collection is out of its reach by
construction, which is why `stage_definitions` says so in a comment on `materialCost` instead of
declaring a derivation there. This is not a limitation being routed around; it is why the arithmetic
lives in its own pure module.

### 3.2 The tolerance, and why it is a rupee

`TOLERANCE_RUPEES = 1.00`.

There is no float drift to absorb. MONEY is stored as a fixed-2 string and every line `amount` is
already rounded to the paisa by `derive_value`, so a hundred lines accumulate at most half a rupee
of rounding between them. **The tolerance is not for the machine's arithmetic — it is for the
designer's.** Somebody who totals ₹1,649.50 of yarn and writes ₹1,650 has rounded to the rupee,
which is how a cost sheet is normally quoted, and that is not a sheet contradicting itself.

A rupee is also comfortably below every failure the check exists to catch. A transposed digit
(₹1,650 written as ₹1,560) is ₹90; a misplaced decimal is ten times the sheet; a line entered and
never added in is that line's whole amount. Nothing real lands between ₹1 and ₹10. Setting the
tolerance at a paisa would flag every rounded-to-the-rupee subtotal on every *correct* sheet in the
workshop — and a warning that fires on correct data is a warning designers learn to dismiss, which
costs the real findings underneath it.

`MARGIN_TOLERANCE_POINTS = 1.0` follows the same argument on `marginPercent`: a designer who
computes 25.4% and types 25 has rounded, while two points apart is a different claim about the same
product.

The comparison is `<=`, so a difference sitting **exactly** at the tolerance still agrees. The
boundary belongs to the designer, not to the warning.

### 3.3 The verdict vocabulary

Four checks run per sheet — `materialCost`, `labourCost`, `totalCost` and `marginPercent` — and each
returns one of:

| Verdict | Means |
|---|---|
| `AGREES` | Within tolerance of what the rows come to |
| `MISMATCH` | Outside it. **The only verdict that becomes a warning** |
| `NOT_ITEMISED` | There are no rows to roll up. **Not a criticism of the sheet** |
| `NOT_DECLARED` | Nothing was typed to compare against; the computed figure is reported anyway |
| `INCOMPLETE` | Rows exist but could not all be read, so no total was formed |
| `NOT_COMPUTABLE` | The computed side cannot be formed at all (a margin with no cost) |

The order of the refusals in `check_subtotal` is the order of the questions. Are there rows at all?
Could they all be read? Was anything typed to compare them with? Only then is a mismatch a mismatch.

`NOT_ITEMISED` is the case the whole module has to get right. Plenty of sheets are entered as totals
— a subcontracted product, a cost carried over from a previous workshop — and reporting every one of
them as a contradiction would bury the sheets that really are one. `INCOMPLETE` protects the same
property from the other side: a total formed from the readable half of the lines, compared against a
correct header, manufactures a mismatch that is not there.

The margin carries its own small vocabulary — `COMPUTED`, `AT_COST`, `BELOW_COST`, `NOT_COMPUTABLE`
— and only `BELOW_COST` is a finding. `Margin.percent` is margin **on cost**, `(price − cost)/cost`,
and saying which convention is meant is not pedantry: the same sheet is 25% on cost and 20% on
price, and the registry's own `marginPercent` allows up to 500, which only a markup on cost reaches.

Two details worth keeping when this is edited:

- **`totalCost` is a derived field, so its mismatch means something narrower**: the stored value is
  STALE. It was computed correctly from what the sheet held at the time and a head has moved since.
  The fix is mechanical — reopen the sheet and save it — and the message says so, which is not true
  of a subtotal mismatch where only the designer can know the answer.
- **The margin is computed from the cost heads, not from stored `totalCost`**, falling back to the
  stored value only when no head is filled. If the stored total is stale, a margin taken from it
  would be a second wrong number derived from the first, and the two findings would contradict each
  other on the same sheet.

The heads `totalCost` sums over are `materialCost`, `labourCost`, `packagingCost`, `finishingCost`,
`transportCost`, `overheadCost` — restated in `COST_HEADS` rather than read out of the registry, so
the module stays free of it and the ports have something to mirror.
`test_the_six_cost_heads_match_the_registry_declaration` pins the two together, so a head added to
the registry fails a test rather than being silently left out of the roll-up.

### 3.4 Nothing is ever auto-corrected

There is no path in this module that writes. Every function is read-only over dicts, the endpoint is
a `GET`, and there is deliberately no "apply the computed figure" affordance anywhere.

Replacing the header with the computed total would be a worse bug than the one it fixes. A subtotal
can legitimately differ from its lines for reasons the arithmetic cannot see, and the moment a
finding can rewrite a field, a designer reading a page of them is one mis-tap from replacing their
own judgement with a machine's. If such a button is ever added it must be a button somebody presses,
never a side effect of rendering. The same rule governs the market panel, for the same reason.

### 3.5 A line that names no sheet is printed, never dropped

Lines are matched to sheets on `costSheetRef`, which holds the parent's `_entryId`. Whatever is left
over is an ORPHAN and is reported as one, with its amount, because it is fieldwork somebody did and
money somebody spent — and because its absence from every subtotal is a candidate explanation for
any mismatch above it. `report_builder._parent_groups` takes the identical position for the printed
report (§5 below); the two must agree, or a total the integrity check calls unattributed appears in
the report as some product's material cost.

**`_entryId` has to be injected by the caller**, from the row's database id, and it is not part of
the stored `data`. A caller that forgets it gets sheets with no lines — `NOT_ITEMISED` everywhere —
rather than a wrong answer. The endpoint injects it explicitly and says why.

### 3.6 What is built and what is surfaced — read this before promising it to anyone

*Re-checked 2026-08-20. Every row is one grep; re-run them rather than trusting the table.*

*The 2026-08-08 version of this table said the Kotlin port was called by nothing and that "no
designer sees a cost-integrity finding on any surface". Android has surfaced both ports since, and
the sentence stood for eleven days — which is why the row below carries the caller's name and not
just a yes.*

| Piece | State |
|---|---|
| `backend/app/services/cost_integrity.py` | Complete, covered by `backend/tests/test_cost_integrity.py` |
| `GET /design-workshops/{id}/cost-integrity` | Live, read-only, gated by `load_workshop_or_404`. **Consumed by the web panel as a FALLBACK only** — `CostFindingsPanel` calls `dwCostIntegrity(workshopId)` when this browser has never downloaded the stage collections; when it has, it computes locally, as Android does |
| A TypeScript port | **BUILT — 2026-08-20.** `frontend/lib/costIntegrity.ts`, held equal to `backend/app/services/cost_integrity.py` case for case by `frontend/e2e/cost-integrity-port-unit.spec.ts` |
| A web UI | **BUILT — 2026-08-20.** `frontend/components/designworkshop/CostFindingsPanel.tsx`, mounted from `frontend/app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx` at `COSTING_STAGE`. Its header opens by stating the gap it closed, in the past tense ("the browser **had** NEITHER a port nor a panel … returned zero") — read that as the argument for the file, not as a live claim about the tree |
| `DwCostIntegrity.kt` | **Surfaced.** `DwFindingsPanel.CostFindingsCard` calls `DwCostIntegrity.analyse`, and `DwFindingsPanel.DwStageFindings` is mounted by `StageScreen` on every stage form |
| `DwMarketAnalysis.kt` | **Surfaced.** `DwFindingsPanel.MarketFindingsCard` calls `DwMarketAnalysis.analyse`, through the same `DwStageFindings` mount |

~~So today a designer sees a cost-integrity finding **on Android … and nowhere in a browser.** The web
is the half that is still missing, and it is missing entirely: no port, no panel, no call to the
endpoint.~~ **Closed 2026-08-20, and struck rather than deleted because it was quoted onward — into
§4's re-run greps below and into the `workshop_cost_integrity` docstring (that docstring was
corrected on 2026-08-22 and now states the two surfaces with the greps that check them — see §7).**
A designer now sees the
cost findings beside the costing stage in the browser as well, from `CostFindingsPanel` over the
TypeScript port, with the endpoint as the fallback for a browser that holds no collections. **What
the paragraph said about the ENDPOINT is still the interesting part and is unchanged**: neither
surface asks the server for the answer when it can compute it, so the endpoint remains a fallback
rather than the source of truth — which is why the port has to be held equal to the Python by a test
rather than by trust.

`DwStageFindings` dispatches on the stage key, so the two cards reach the designer standing in the
courtyard with the radio off, which is the arrangement §1 says the whole exercise is for. Nothing it
renders is written back — `DwFindingsPanel`'s own header states why, and `DwFindingsSurfaceTest`
pins every registry key it reads against the bundled registry, so a renamed field key fails a test
rather than silently emptying a card.

Market analysis is no longer the contrasting case it was: it is surfaced on the web
(`MarketFindingsPanel`, mounted on stage 9 in
`frontend/app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx`) **and** on Android.

~~The asymmetry that remains runs the other way — cost integrity is Android-only.~~ **That closing
sentence was false the day it was written and is struck rather than deleted, because a summary
sentence is where a reader who scrolled stops.** It sat fifteen lines below the rows recording
`frontend/lib/costIntegrity.ts` and `frontend/components/designworkshop/CostFindingsPanel.tsx` as
BUILT on 2026-08-20, and it withdrew, in the reader's last line, the exact repair the table above it
had just landed. **Both computations are surfaced on both surfaces: market analysis and cost
integrity each have a browser panel and an Android card.**

~~**The asymmetry that actually remains is not a SURFACE but a PROOF.**~~ **CLOSED 2026-09-03, and
the paragraph is corrected rather than deleted because what it found is the whole argument for the
spec that closed it.** It read: `frontend/lib/costIntegrity.ts` is held to the Python case for case
by `frontend/e2e/cost-integrity-port-unit.spec.ts`, while `frontend/lib/marketAnalysis.ts` is held to
it by nothing and only `pySum` is pinned. Both ports are now held to the Python by value:
`frontend/e2e/market-analysis-port-unit.spec.ts` walks the same twenty-nine-case table the handset
reads and compares the whole payload against goldens regenerated from
`backend/app/services/market_analysis.py`.

**Its first run found two live divergences, which is what an unproved port is for.** Unicode decimal
digits (`float("୧୨୩")` is 123.0 in Python and `NaN` in the browser) shifted a measured case's median
from ₹545 to ₹670 — the panel and the .docx disagreeing about one workshop — and U+0085, which
`str.strip()` removes and `String.prototype.trim()` does not, silently dropped a price, a competitor
and a whole category distribution. Both were closed the same day; the full write-up is in
[OPEN_FINDINGS.md](OPEN_FINDINGS.md), and the spec's `KNOWN_PORT_DIVERGENCES` set is kept, empty, for
the next one. §2.7's **TYPESCRIPT ↔ PYTHON** paragraph should be read with that date beside it.
Before quoting either paragraph onward, re-read both — this section has now stated the asymmetry
wrong in both directions and then had it closed underneath it.

---

## 4. Archive analytics — the one computation that is deliberately NOT on the edge

`backend/app/services/workshop_analytics.py` · `frontend/lib/workshopAnalytics.ts` ·
`frontend/app/(protected)/admin/analytics/page.tsx`

The rule everywhere else in this repository is that anything computable from data already on the
device runs on the device. A cross-workshop comparison is the case that rule does not reach: **the
input IS the other workshops, and a handset holds one.** There is deliberately no TypeScript or
Kotlin port of the arithmetic, and the absence is the design rather than an omission.

It folds stage 22 follow-ups, stage 17 cost sheets and stage 9 design opportunities across the whole
archive into adoption rates, interval cross-sections, cost-to-price distributions and recurring
themes — and it imports rather than re-derives the two things the modules above already own:
`cost_integrity.compute_margin`, so the archive and the per-workshop panel cannot disagree about one
sheet, and `market_analysis`'s `describe`, `as_number` and `_tokens`. The tokeniser is imported **by
its private name on purpose**: copying its fifteen lines would create a second place for the Indic
word-boundary bug (§2.7) to come back, in a module nobody would think to check.

Its floors are the same kind of object as §2.2's, and two of them are pinned to constants that
already exist so the two cannot drift:

| Constant | Value | Withholds |
|---|---|---|
| `MIN_WORKSHOPS_FOR_RATE` | `5` | Any rate for a group below this many **workshops** |
| `MIN_OBSERVATIONS_FOR_RATE` | `8` | Any rate below this many **decided observations** — checked *in addition*, because five workshops carrying one product each is still five products. Matches `MIN_SAMPLE_FOR_VERDICT` |
| `MIN_SHEETS_FOR_RATIO` | `= MIN_SAMPLE_FOR_QUANTILES` | A cluster's cost-to-price summary |
| `CONCENTRATION_LIMIT` | `0.6` | Nothing — above it the message **leads with the concentration**, because a reader who sees "78%" first has already formed the belief the caution was meant to prevent |
| `MIN_WORKSHOPS_FOR_THEME` | `2` | A theme that has appeared in only one workshop. One workshop naming a theme in four rows is one workshop with a theme |

Three counting rules are not the obvious ones and each is a decision:

- **A workshop is counted once, at its latest interval.** Stage 22 accumulates rows — the same four
  products at 3, 6 and 12 months — so pooling them would weight a well-followed-up workshop three
  times as heavily and drag its rate down with `TRIAL` statuses it has since grown out of. The rows
  set aside are counted and named (`superseded_rows`), never silently dropped.
- **`UNKNOWN` is not a negative.** It is the honest answer when nobody was home, and folding it into
  the denominator would report a *lower* adoption rate for exactly the clusters that were most
  careful about admitting what they did not see. Excluded from the rate, reported beside it.
- **`TRIAL` is not an adoption.** Denominator, never numerator. Calling it adopted today is the
  optimistic error, and this is a document a ministry reads.

**It refuses questions out loud.** `ArchiveFindings.not_computed` travels in the payload naming the
comparisons the registry cannot answer and why, so the page prints the refusal rather than leaving a
reader to assume the section is still loading. The largest is design *survival*: linking a row at 12
months to the row at 3 months needs `productRef`, and where it is blank the two cannot be matched —
so the module reports interval **cross-sections**, which are honest, instead of drawing a survival
curve through observations it cannot link.

`GET /api/analytics/design-workshops` is gated by `require_admin`, and the gate is about
**visibility, not mutation** — the endpoint issues SELECTs and writes nothing. The caller, not the
service, is responsible for excluding soft-deleted workshops: `delete_design_workshop` sets one
column on the `DesignWorkshop` row and does **not** touch its `DwStageEntry` rows, so a query that
filtered only the entries would quietly readmit a deleted workshop's follow-ups. The pure module
cannot see the difference.

---

## 5. Where a finding meets the report

Neither module is wired into `report_builder`. The report prints the designer's declared figures;
the findings are a screen-and-endpoint feature only. The one place the two touch is the grouping
rule in [DESIGN_WORKSHOP.md](DESIGN_WORKSHOP.md) §6, "A child collection prints under its parent" —
`_parent_groups` and `cost_integrity` must
bucket a child line by the same key, so that money the integrity check reports as belonging to no
sheet cannot appear in the report as some product's material cost.

---

## 6. The endpoints

The two per-workshop endpoints are `GET`, read-only, and gated by `load_workshop_or_404` — so a viewer grant
([PERMISSIONS.md](PERMISSIONS.md) §4.4) admits a co-designer to them exactly as it admits them to the
stages.

| Path | Reads | Serves |
|---|---|---|
| `/design-workshops/{id}/market-analysis` | `surveyResponse`, `competitorProduct`, `priceBand`, `swotPoint` | `market_findings_payload` |
| `/design-workshops/{id}/cost-integrity` | `costSheet`, `costMaterialLine`, `costLabourLine`, plus `finalProduct` for labels | `cost_findings_payload` |
| `/analytics/design-workshops` | `followUp`, `costSheet`, `designOpportunity` across every live workshop — **`require_admin`**, not a per-workshop check | `archive_findings_payload` |

Two differences between them are deliberate and easy to miss when copying one to write the other:

- The cost endpoint injects `_entryId` on every row (§3.5); the market endpoint does not, because
  nothing in that analysis joins a child to a parent.
- The cost endpoint resolves `productRef` to a product name in the route and passes the map in. The
  service is pure and cannot follow a reference, and a finding headed by a raw cuid is one a
  designer cannot trace back to a row.

---

## 7. Places the code and its own comments disagree

Found while writing this document, left **unchanged** because in each case the right fix is a
judgement about the code rather than about the prose. Each is a one-line edit for whoever owns the
module. *Re-checked 2026-08-19; the Status column is that recheck.*

| Where | Says | Actually | Status |
|---|---|---|---|
| ~~`cost_integrity.py` module docstring, and the comment in `analyse_cost_integrity`~~ | ~~`report_builder._child_groups`~~ | The method is `_parent_groups` | **CLOSED.** Both now cite `report_builder._parent_groups`; `grep -rn "_child_groups" backend/` is empty |
| `backend/tests/test_report_child_grouping.py`, the docstring on `test_a_sheet_that_has_not_synced_yet_claims_none_of_the_orphans` | "`cost_integrity.summarise_sheets` guards the identical join" | There is no `summarise_sheets`. The join is in `analyse_cost_integrity` | **OPEN**, and it MOVED: `report_builder._parent_groups` was fixed and the test that repeats the same citation was missed. This one line is why §7's closure grep can never come back empty |
| `frontend/lib/designWorkshopViewers.ts` header | "A design workshop is **currently** visible to exactly ONE account", and describes the viewer routes as "being built in parallel with this screen" | `load_workshop_or_404` has consulted `has_viewer_grant` since the grant landed, and all three routes exist | **OPEN.** The quoted `createdById != user.id and not admin` two-liner no longer exists in `design_workshops.py`; the live condition also consults `has_viewer_grant` |

The `_child_groups` / `summarise_sheets` pair — the first two rows — were the same mistake in both
directions, two modules citing each other by a symbol neither has, and they matter more than they
look, because both comments exist precisely to tell a future reader that the two joins must stay in
step. **Fixing one side and not the other, which is
what happened here, is the worst of the three outcomes:** a maintainer who greps for
`summarise_sheets` now finds only the test, concludes the sibling guard was deleted, and edits one
side of a join whose disagreement prints a fabricated material-cost breakdown under the wrong
product.

**The row this table no longer has is the pattern worth naming on its own, and it is named here
rather than left as a numbered pointer that the next deletion would orphan.** The
`workshop_cost_integrity` docstring in `backend/app/api/routes/design_workshops.py` said "no
designer sees a cost-integrity finding on any surface today" and ended "delete this paragraph when
the ports and a panel land, not before". A docstring that ends that way is an instruction the next
reader obeys — so when X lands and nobody deletes it, the sentence is not merely stale, it is armed:
the only reader who can act on it is one who already knows the answer, and the claim above it is
exactly what stops them looking. Both ports landed (§3.6) and the paragraph outlived them.
**Closed 2026-08-22**: the docstring now carries a dated statement and the two greps that re-check
it, which is the shape to prefer over an instruction — "true as of «date»; check `«grep»`".

---

## How this document is kept true

| Claim class | Kept true by |
|---|---|
| The floors, the tolerance and the verdict vocabularies | They are module-scope constants and docstrings: `MIN_SAMPLE_FOR_QUANTILES`, `MIN_SAMPLE_FOR_VERDICT`, `NARROW_COVERAGE` in `backend/app/services/market_analysis.py`; `TOLERANCE_RUPEES`, `MARGIN_TOLERANCE_POINTS`, `COST_HEADS` in `backend/app/services/cost_integrity.py`. Each table above should be diffable against the dataclass docstring that defines it in one read. |
| The port-parity rules (§2.7) | **Kotlin ↔ Python is mechanical now:** `android/app/src/test/java/com/designprototype/workshop/data/DwAnalysisParityTest.kt` diffs the whole of `dw-analysis-cases.json` against a golden frozen from the two backend modules (**no count restated — this row said 59 while the table held 62, and the KDoc now declines to state one at all**), and `DwOverflowParityTest.kt` covers the overflow edges. Regenerate the golden from the backend when either side changes; the Python is the authority. **TypeScript ↔ Python is not:** only `pySum` is pinned, by `frontend/e2e/market-analysis-sum-unit.spec.ts`. That is the honest remaining **UNVERIFIED**, and this row said "there is no cross-language test" for eleven days after one landed. |
| "Nothing is ever written back" | Structural, and therefore the easiest claim here to keep: both services are pure functions over dicts and both endpoints are `GET`. A `PUT`, a `db.` call or a returned form value in either module falsifies §1 and §3.4 at once. |
| Which surfaces actually render a finding (§3.6) | Four greps, and **the expected answer is different for each — none of the four is "empty" any more, and this row has now been wrong in both directions.** It first said "both should stay empty", which read a shipped Android panel as unwanted wiring; it was then corrected to say the frontend pair "is expected to stay EMPTY until somebody builds the web panel", and the web panel landed on 2026-08-20, so that half inverted too. Current expectations: `grep -rn "DwCostIntegrity\|DwMarketAnalysis" android/app/src/main` returns `ui/designworkshop/DwFindingsPanel.kt`; `grep -rn "cost-integrity\|costIntegrity" frontend/` returns `lib/costIntegrity.ts`, `components/designworkshop/CostFindingsPanel.tsx`, the `stages/[stageKey]/page.tsx` mount and `e2e/cost-integrity-port-unit.spec.ts`; `grep -rn "marketAnalysis" frontend/` returns `MarketFindingsPanel`. **For all four, DISAPPEARANCE is the alarm, not presence** — which is the shape this row should have had from the start, and is why it is written as "returns X" rather than as a yes/no. |
| §7's contradictions | They close when the cited symbols are corrected, and the Status column records which are still open. Re-run `grep -rn "_child_groups\|summarise_sheets" backend/`: `_child_groups` is now empty (row 1 closed), and `summarise_sheets` returns exactly one line, in `backend/tests/test_report_child_grouping.py`. When that line names `analyse_cost_integrity` the grep goes empty and rows 1 and 2 should both go. |

**Review triggers:** any change to `backend/app/services/market_analysis.py`,
`backend/app/services/cost_integrity.py`, `frontend/lib/marketAnalysis.ts`,
`frontend/components/designworkshop/MarketFindingsPanel.tsx`, the two Kotlin ports under
`android/app/src/main/java/com/designprototype/workshop/data/`, the Android panel
`android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwFindingsPanel.kt`, or the
stage-8/9/17 entity declarations in `backend/app/services/stage_definitions.py` — a renamed field key
silently empties an analysis rather than failing it.

**Known unverified:** the TypeScript half of the market-analysis parity (§2.7) — nothing diffs
`frontend/lib/marketAnalysis.ts` against the Python by value, and the browser is now the only
unpinned surface. **The old wording of this note — "whether `DwMarketAnalysis.kt` and
`DwCostIntegrity.kt` are faithful ports at all: they compile and they are unreferenced, so neither a
test nor a running screen has ever exercised them" — is withdrawn as of 2026-08-19, because both
halves of it became false.** `DwAnalysisParityTest` exercises them by value against a Python golden
and `DwFindingsPanel` renders them on the stage form. Nor does the old note's last clause survive on
the Kotlin side: the golden holds each finding's rendered `message` and `note` string, ₹-formatting
and all, so the leaf-by-leaf diff pins the SENTENCES as well as the numbers. That clause is still
true of the TypeScript, where nothing compares the wording to the Python's at all.
