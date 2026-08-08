# Market research — finding what comparable products cost, and who sells them

**Status: dormant.** Off by default, no route imports it, nothing is installed for it, and a fresh
clone with no new environment variables behaves exactly as it did before this document existed.
This is the fourth capability of `backend/app/ai_features/`; the three image capabilities and the
rules the whole package obeys are in [AI_FEATURES.md](AI_FEATURES.md), which is also the single
index of every variable this package reads.

Code: `backend/app/ai_features/market_research.py` and three providers under
`backend/app/ai_features/providers/`. The analysis this feeds is
`backend/app/services/market_analysis.py`.

---

## 1. What it does, and the hole it fills

`market_analysis.py` already computes everything that can be computed from what a designer wrote
down: price distributions per category, a verdict on each declared price band, where each
competitor product sits, which SWOT points are backed by something a respondent actually said. It
is pure, it has no network, and it is ported to the browser and the handset so a designer with no
signal gets the same analysis as one at a desk. It has been proven equal to those ports over 76
cases.

What it cannot do is say what the **rest of the market** charges, because nobody in the room wrote
that down. Stage 8 captures the competitor products a designer personally stood in front of —
typically three or four, all local, all in one bazaar. A price band judged against those alone is
judged against whichever shops the team happened to walk into.

This capability closes that gap. Given a keyword or a product description plus whatever context
the workshop app already holds, it returns comparable products with:

| Field | Why it is there |
|---|---|
| `name` | What the thing is called on the listing |
| `seller` | Who is charging that. Half of "who sells them" |
| `price` | Parsed to a number, from whatever the listing wrote |
| `currency` | ISO 4217. Never converted — see §5 |
| `sourceUrl` | Where the number came from |
| `retrievedAt` | When it was true |

and merges them into the same `PriceObservation` shape `market_analysis` already consumes, so the
existing distribution and band machinery runs on retrieved data **unchanged** — with one difference
that is the entire point of the design, in §4.

### Why you would want this for craft documentation

**A price band is the single most consequential number in a workshop report.** It decides what the
prototypes are made of, which buyer the cluster is aimed at, and how a ministry reads the
intervention's viability. Right now it is checked against a survey of the people in one district
and three or four competitor products from one market. "Sambalpuri ikat stoles sell for ₹1,200 to
₹3,500 nationally, and one heirloom bridal weave lists at ₹1,20,000" is a fact that changes that
conversation, and no amount of arithmetic on the captured rows will produce it.

**The premium tail is the finding.** A cluster that believes its ceiling is ₹3,000 because that is
what the local bazaar charges is a cluster leaving the top of its own market to somebody else. The
outlier handling in §5 is written specifically so the expensive end survives — it is the row a
designer most needs to see, and it is the row a naive filter throws away first.

**It works offline.** The `local_catalogue` provider does the whole capability against a file on
the device (§3). A state handloom board's price list, shipped with the deployment, answers in
milliseconds in a village with no signal. That is not a fallback; it is the default `auto` picks.

---

## 2. The agentic / programmatic line

The request that produced this feature asked, specifically, *what part of it needs to be done in an
agentic manner, and what part can actually be done programmatically*. The proposal that came with
it put query formulation and field extraction on the agentic side. Verifying it moved both across.
The module docstring of `market_research.py` carries the full argument; this is the summary.

### Programmatic — built, no model involved, unit-tested

| Step | Function | Why no model |
|---|---|---|
| Issuing the query, pagination | each provider | An HTTP call with a page counter |
| **Deciding what to search for** | `plan_queries` | Moved. See below |
| Price parsing | `parse_price` | Separator rules, not judgement |
| Currency normalisation | `normalise_currency` | Symbol → ISO code, a lookup |
| Deduplication | `deduplicate` | URL equality after a documented normalisation |
| Outlier rejection | `reject_outliers` | A stated fence, applied in log space |
| Merge into `PriceObservation` | `to_price_observations` | A field mapping |
| Refusing to conclude | `consolidate` | A sample-size floor |

### Agentic — genuinely needs a model, deliberately NOT built

**Reading a rambling free-text brief.** *"something like the ikat stoles but for younger buyers in
Bhubaneswar, maybe around 800"* contains a product, a place and a price band that no deterministic
rule extracts reliably. This is the only item left on the list.

It is not built because the workshop app does not have to ask the question that way. Craft,
category, materials and district are already captured against controlled vocabularies, and stage 9
already holds the declared band; `ResearchBrief` takes them as fields. A model here would replace
data the app already has with an inference, and would make the same brief return different
comparables next month — which, for evidence that ends up in a ministry report, is a defect.

If someone later wants it anyway, the seam is `ResearchBrief`: an agentic planner is a function that
returns one, and everything downstream is unchanged and still deterministic. Add it as its own
capability with its own flag, so the retrieval half keeps working when the model is switched off.

### The two things that moved, and why

**1. Extracting structured fields from an unstructured page — eliminated, not automated.**

This is only necessary if you fetch arbitrary product pages. Every provider here is a documented
commerce or search API that returns `title` / `source` / `price` / `link` as fields, so there is no
page to read. The same decision removed the need for a model *and* the need to argue about
scraping: nothing crawls, so nothing has to reason about robots.txt (§6). That is not a coincidence
— it is the reason the provider choice was made this way round.

**2. Deciding what to search for — moved to `plan_queries`.**

A structured brief plus an ordered set of narrowing facets — craft and category, then materials,
then place — produces at most a handful of queries a designer can read, re-run and disagree with.
Reproducibility is the whole argument: *"we searched for X, Y and Z on 8 August 2026"* is a method
section; *"the model decided what to search for"* is not one, and cannot be re-derived when the
report is questioned a year later.

Two properties of that function are worth stating on their own:

- **A bare keyword plans exactly one query.** Facets that are empty produce a query identical to
  the broad one, which is deduplicated away. One query is one credit.
- **The designer's declared price band never reaches a query.** Searching *"handloom stole under
  ₹800"* and then testing a ₹600–800 band against what came back would be retrieving evidence
  selected to agree with the claim under test. There is a test named after this.

### A third thing that looks agentic and is neither

Judging whether a retrieved product is genuinely comparable. `score_relevance` scores vocabulary
overlap and nothing more; anything below the threshold is **returned with its score**, not deleted,
and the final call belongs to the designer who was in the room. That is exactly the standing
`market_analysis.link_swot_evidence` already has — a retrieval aid, not a judgement — and it is why
this ships without a model behind it.

It also refuses to filter at all when filtering would empty the result. An English marketplace
answering an Odia keyword scores zero on every row; returning nothing would report that as *"the
market has nothing"*, which is a different and false statement. In that case everything is kept and
a caution says so.

---

## 3. Providers, and what each one costs

Three providers, one local and two hosted. `auto` (the default) picks the first that is configured.

| Provider | Kind | Needs | Peak RAM | Latency | Money |
|---|---|---|---|---|---|
| `local_catalogue` | local | `AI_MARKET_RESEARCH_CATALOGUE_PATH` — **no packages at all** | **28 MB** *(MEASURED)* | 0.18 s / 5k rows, 1.7 s / 50k, 8 s / 120k *(MEASURED, upper bound)* | free |
| `serper_shopping` | hosted | `SERPER_API_KEY` | ~20 MB *(ESTIMATED)* | 1–3 s per query page *(ESTIMATED)* | 2,500 free queries *(VENDOR-STATED)*; ~$1.00 → ~$0.30 per 1,000 *(THIRD-PARTY REPORTED)* |
| `serpapi_shopping` | hosted | `SERPAPI_API_KEY` | ~20 MB *(ESTIMATED)* | 2–6 s per query page *(ESTIMATED)* | 250 searches/month free; $25/1,000 → $275/30,000 *(VENDOR-STATED)* |

Every number is labelled, and the labels mean what they mean everywhere else in this package.
**MEASURED** was run on a machine and the method is in §8. **ESTIMATED** means somebody reasoned
about it. **VENDOR-STATED** means the vendor's own page said so. **THIRD-PARTY REPORTED** is a
fourth label introduced here, and it exists because serper's pricing page could not be read
directly: those figures came from third-party summaries and are *not* labelled vendor-stated, since
they are not. The same strings are on `ResourceProfile` in `registry.py` and are printed by the
probe.

### `local_catalogue` — the edge device

The whole capability against a `.csv`, `.tsv` or `.jsonl` file on disk. No network, no key, no
vendor, no terms accepted, and nobody learns what was searched for. Where the consent position on
sending craft vocabulary to a US search vendor is unclear — and for fieldwork gathered under a
research agreement it often is — this is the provider with no such question to answer.

```csv
name,seller,price,currency,url,updated
Sambalpuri ikat silk stole,Boyanika,"₹2,450",INR,https://…,2026-07-01
Sambalpuri ikat cotton stole,Utkalika,"₹1,299",INR,,2026-07-01
```

Column names are matched case-insensitively and several aliases are accepted
(`title`/`product` for name, `shop`/`vendor`/`source` for seller, and so on). Two behaviours are
worth knowing:

- **A row's own `updated` column becomes its retrieval timestamp.** A catalogue price was true when
  the catalogue was compiled, not when this happened to read it; stamping it `now` would be a small
  lie that gets more wrong every month. With no such column, the file's mtime is used.
- **A row with no URL still gets a citable source**: `file:///…/prices.csv?row=42`. The row number
  is in the query string rather than the fragment because `canonical_url` drops fragments — with
  `#row=42` every row would compare equal and deduplication would keep exactly one of them.

Limits: files over **16 MB** are refused from their `stat()` before a byte is read, and the scan
stops at 200,000 rows. It is not a search engine and will not discover a product nobody wrote down.

### `serper_shopping` and `serpapi_shopping` — the hosted pair

Both call a documented Google Shopping API and both return structured fields. Two vendors rather
than one because a capability with a single hosted provider is a hardcoded vendor with extra steps.

**What leaves the building: the query, and nothing else.** The designer's keyword plus the craft,
category, materials and district on the brief. No respondent name, no photograph, no captured
survey row. That is a materially smaller disclosure than the hosted *image* providers make, and it
is worth stating plainly because "we sent it to a search API" is otherwise a sentence with no bound
on it.

**SerpApi puts the API key in the query string**, because that is the only place it accepts one. It
therefore appears in the URL of an outbound request, and any proxy that logs full URLs will log the
key. Worth knowing before pointing `SERPAPI_ENDPOINT` at one.

**Neither has ever been run against the real service.** Their request shapes follow each vendor's
published documentation as of August 2026 and are covered by stubbed tests. Vendor key names are
read through a small helper that accepts alternatives, so a field rename degrades to a blank field
rather than losing the batch — but the first person with an account should expect to spend ten
minutes confirming it, exactly as `AI_FEATURES.md` §8 says of remove.bg.

---

## 4. Provenance — the property this feature exists to protect

**The worst possible outcome of this feature is a scraped number reaching a ministry report as
though a respondent said it.** Everything below is arranged so that cannot happen quietly.

Every retrieved observation is stamped `source="RETRIEVED_LISTING"` — a value that is neither
`RESPONDENT` (a person who was asked) nor `COMPETITOR` (a shelf a designer stood in front of, in a
shop, on a date, and wrote down). Four consequences follow:

1. **Every existing filter in `market_analysis` excludes it, with no new code.**
   `position_competitors` skips anything that is not `RESPONDENT`; so do the respondent figures in
   `analyse`. There is a test asserting that sixty retrieved listings cannot move a competitor's
   percentile by a rupee.
2. **`assert_surveyed(observations, where=…)` raises rather than filters.** A caller who has merged
   retrieved prices into a respondent sample has a bug, and quietly repairing it would let that bug
   reach a report where the number is indistinguishable from fieldwork. `surveyed_only()` is there
   for the caller who wants the defensive filter deliberately.
3. **The label carries the audit trail**: `"<name> - <seller> (retrieved <timestamp> from <url>)"`,
   so a figure in a report traces back to the listing that produced it.
4. **The wire form says so twice** — `"provenance": "RETRIEVED_LISTING"` on the result and on every
   product — so a consumer that never reads this document still cannot merge the rows by accident.

And in words, first in every result's `cautions`, before any figure:

> These 12 price(s) were RETRIEVED from serper_shopping on 2026-08-08T09:00:00+00:00. They are shop
> listings — what a seller asks — not answers from anybody the survey spoke to, and they must not be
> reported as demand, willingness to pay, or a respondent's price expectation.

**Retrieved data gets no special credibility, either.** Five retrieved prices are judged by exactly
the floors `market_analysis` applies to five surveyed ones: a band tested against them comes back
`UNVERIFIABLE`, not `SOUND`.

---

## 5. What it refuses to do

**It never converts between currencies.** Listings in a currency other than the sample's are
excluded, counted, and reported — never converted. A conversion needs a rate; a live rate is a
second network dependency and a moving number, and a rate pasted into an environment variable is a
number that was true once and is applied silently to a report a year later. Excluding is honest and
reversible; converting is neither. The configured currency wins over a mere majority, so an Indian
craft brief that happens to retrieve six US listings and five Indian ones still reports the Indian
market.

**It withholds a distribution below five priced listings** — the same floor
`market_analysis.MIN_SAMPLE_FOR_QUANTILES` uses, asserted equal by a test. The listings are still
shown; only the median and spread are withheld.

**It rejects nothing as an outlier below eight priced listings**, which is deliberately higher, and
is the same relationship `market_analysis` already draws between describing (5) and judging (8).
*Throwing a data point away is a judgement.* This floor was found by a test, not reasoned out in
advance: five real craft prices — ₹1,299, ₹1,850, ₹2,450, ₹3,200 and a ₹1,20,000 bridal weave — put
the quartiles so close together that even a wide fence rejected the heirloom piece, which is the
single most interesting row in the sample.

**Above that floor, the fence is 3 × IQR applied to `log(price)`.** Both choices are deliberate.
Craft prices are ratio-scaled and legitimately span two orders of magnitude; a conventional
1.5-IQR fence on raw rupees puts the lower bound below zero, so it can *only* ever reject expensive
things — precisely the data a heritage craft cluster must see. What the fence is meant to catch is
the retrieval accident: a ₹5 sample swatch, a ₹3,00,000 wholesale lot of a hundred pieces.

**Nothing is ever silently dropped.** Every removed row appears in `rejected` with a reason, and
`len(products) + len(rejected) + duplicatesRemoved` always equals the number of rows the provider
returned. There is a test named after that identity.

**Finding nothing is reported as a failed search, not as a finding about the market.**

---

## 6. The legal and ethical boundary — what an operator is agreeing to

**Enabling a hosted provider means:** you have an account with that vendor, you accept their terms,
you pay per query, and the text of your searches — the designer's keyword plus the craft, category,
materials and district — is sent to a third-party company, most likely outside India, and is
subject to whatever they do with query logs. For fieldwork gathered under a research agreement,
whether that is allowed is a **consent question, not a technical one**, and it belongs to whoever
holds the agreement. If the answer is no, `local_catalogue` is the provider with no such question.

**There is no crawler here, and that is a design decision rather than an omission.** No code in
this package will fetch a URL a vendor did not offer through an API. Consequently:

- **robots.txt is not consulted, because nothing crawls.** The obligation was designed out rather
  than complied with. If anyone later adds a provider that fetches arbitrary pages, that provider
  must handle robots.txt, and this paragraph stops being true.
- **An identifying `User-Agent` is sent on every outbound request**
  (`AI_MARKET_RESEARCH_USER_AGENT`). The default identifies the software; override it to add a
  human to contact before somebody blocks the address.
- **Outbound calls are throttled**, process-wide, per provider
  (`AI_MARKET_RESEARCH_MIN_INTERVAL_SECONDS`, default 1 s). This is a *courtesy floor*, not
  rate-limit compliance — the vendor's published quota is the operator's responsibility. It exists
  because a queue worker running a batch of briefs otherwise looks like a burst from one address,
  and the address getting blocked is shared with everything else on the box.
- **Per-query cost is bounded by configuration**, not by hope: `AI_MARKET_RESEARCH_MAX_QUERIES`
  caps the queries per brief and `AI_MARKET_RESEARCH_MAX_RESULTS` is split *across* those queries
  rather than given to each. Three queries each fetching twenty results would be sixty credits for
  one button press.

**What is not built, deliberately:** a general-purpose scraper, a headless browser, a price-history
store, and anything that retrieves on a schedule. A retrieval is something a person asks for, once,
and can point at afterwards.

---

## 7. Turning it on, and calling it

### Offline, from a price list — no packages, no account

```bash
# backend/.env
AI_FEATURES_ENABLED=true
AI_MARKET_RESEARCH_ENABLED=true
AI_MARKET_RESEARCH_CATALOGUE_PATH=/var/lib/fieldrepo/prices/odisha-handloom.csv
```

No `pip install` of any kind: `csv` and `json` are stdlib.

### Hosted

```bash
AI_FEATURES_ENABLED=true
AI_MARKET_RESEARCH_ENABLED=true
SERPER_API_KEY=<key from serper.dev>
AI_MARKET_RESEARCH_MAX_QUERIES=1      # while you are finding out what it costs
AI_MARKET_RESEARCH_USER_AGENT="FieldRepo/0.1 (research; you@example.org)"
```

Nothing here needs `pip install` either — `requests` is already a core dependency. Settings are
cached, so restart the API and the queue worker after any change. Confirm with the probe:

```bash
cd backend
python -c "from app.ai_features import format_probe; print(format_probe())"
```

### Calling it

```python
from app.ai_features import (
    Capability, AiFeatureError, ResearchBrief, is_available,
    research_market, to_price_observations, surveyed_only,
)

if is_available(Capability.MARKET_RESEARCH):
    try:
        result = research_market(ResearchBrief(
            keyword="sambalpuri ikat stole",
            craft="handloom",
            category="stole",
            materials=("tussar silk",),
            place="Sambalpur",
        ))
    except AiFeatureError as exc:
        log.warning("no comparables this time: %s", exc.message)
    else:
        for caution in result.cautions:          # print these BEFORE any figure
            report.note(caution)
        retrieved = to_price_observations(result)   # stamped RETRIEVED_LISTING
```

A plain string is accepted where a `ResearchBrief` is (`research_market("ikat stole")`).

**This belongs on the background queue, not in a request.** It is several HTTP calls with a
courtesy delay between them — tens of seconds against CloudFront's thirty-second origin timeout,
which this backend has already been bitten by on media uploads.

**Failure is always an exception**, from the same family as the rest of the package
([AI_FEATURES.md §5](AI_FEATURES.md)), plus two of its own:

| Class | `code` | Means |
|---|---|---|
| `InvalidBrief` | `invalid_brief` | The keyword has no searchable word. Refused before a provider is loaded, so a blank query never spends a credit |
| `ProvenanceViolation` | `provenance_violation` | A retrieved price reached a computation that may only see surveyed ones. A programming error, deliberately loud |

Note what is *not* an exception: finding nothing, finding too few to describe, and finding listings
in three currencies are all **results with cautions**, because the listings themselves are still
worth showing.

---

## 8. How the measured numbers were measured

The local provider was actually run, on 2026-08-08, with the repository's own venv.

- **Machine:** Intel i5-class laptop, Windows 11, CPython 3.14.6.
- **Metric:** `PeakWorkingSetSize` from `GetProcessMemoryInfo` for the whole process, sampled after
  each stage — the same method as `AI_FEATURES.md` §7.
- **Input:** synthetic craft catalogues of 5,000 / 50,000 / 120,000 rows (0.5 / 5.3 / 12.7 MB CSV),
  with Indian-grouped prices, three runs each.

**Peak working set was flat at 27–28 MB for all three sizes** — 17 MB of that the interpreter,
~5 MB importing the package. It does not grow with the file because the scan keeps at most one
result budget of listings per overlap level rather than scoring the whole file and sorting it. That
matters, and it is not how it was written first: the obvious score-sort-truncate implementation was
measured before the bucket scan and took the same process to **57 MB and 15 s** on the 120,000-row
file, because a broad query matches most rows of a craft catalogue.

**Timings were 0.18 s / 1.7 s / 8 s** (best of nine). They were taken while the repository's own
test suite was running on the same machine and varied by up to 3× run to run, so **treat them as an
upper bound**; the memory figure did not vary at all and is the one that decides whether this fits
on the 1 GiB box. It does, comfortably — this is the cheapest provider in the package. Keep a
catalogue to the categories a workshop is actually about: 120,000 rows is seconds of CPU on two
burstable vCPU, and the queue is where that belongs.

**The 16 MB ceiling was confirmed** by pointing the provider at a 22 MB export, which was refused
from its `stat()` before a byte was read.

One optimisation came out of the same measurements and is worth recording because it is a
correctness-adjacent change: the Unicode-correct tokeniser (letters *and combining marks*, so Odia
does not shatter into fragments — see `market_analysis._is_word_character` for why that matters)
cost **97 µs per 40-character row**, about two seconds of pure tokenising for a 20,000-row
catalogue. It now takes an ASCII fast path, **25 µs**, which is *exactly* equivalent rather than an
approximation: Unicode's combining-mark categories begin at U+0300, so a wholly-ASCII string
contains no marks and a letter regex finds precisely what the character scan would. Non-ASCII text
still takes the scan and is unchanged at 85 µs. A test asserts both paths agree with
`market_analysis`, on Odia and on English.

The hosted providers were **not** measured: that needs an account and the calls cost money.

---

## 9. Tests

```bash
cd backend
./.venv/Scripts/python.exe -m pytest tests/test_market_research.py \
    tests/test_market_research_providers.py -q
```

134 tests, no network, run with none of the optional dependencies installed. Provider responses are
recorded literals in the test files; the local provider is driven against real files in `tmp_path`,
because that is what it reads in production too.

What they defend, beyond the obvious:

- **Dormancy.** Every flag off, the probe naming all three settings, a disabled call raising a
  typed error that names the variable, and — checked in a clean subprocess — `import
  app.ai_features` loading none of the three provider modules, not `requests`, and not
  `app.services.market_analysis`.
- **Provenance.** That retrieved prices are counted as neither respondent nor competitor, that
  sixty of them cannot move a competitor's percentile, that `assert_surveyed` raises on a mixed
  list, and that the wire form declares itself.
- **The tokeniser has not drifted** from `market_analysis`'s Unicode rule, on Odia and through the
  ASCII fast path.
- **The price parser**, on a table that includes `₹1,20,000.00`, `1,20,000`, `Rs 1,299`,
  `1.234,56 €`, `₹1,299 (20% off)` — which must not be ₹20 — and nine strings that must return
  nothing rather than a guess.
- **The accounting identity**: nothing is silently dropped.
- **The circularity guard**: the declared price band never reaches a query.

What they cannot cover: a hosted provider actually returning good comparables. That needs an
account. §3 says what to expect.

Two of these tests changed the implementation rather than confirming it. The outlier floor moved
from 5 to 8 because a five-price craft sample rejected its own heirloom weave (§5), and the local
provider's scan was rewritten from score-sort-truncate to bounded buckets because the measurement
showed it holding a listing object per matching row (§8).

---

## 10. What is deliberately not done

- **No route, no queue job, no UI.** Nothing calls this. Wiring it into `fieldrepo-queue` and
  deciding where retrieved comparables are stored and shown is a separate decision with its own
  cost and consent consequences.
- **No agentic query planner.** §2 says why, and where the seam is if somebody wants one.
- **No currency conversion.** §5.
- **No crawler, no headless browser, no scheduled retrieval, no price history.** §6.
- **No fields in `app/core/config.py`.** The package reads its own settings; the note in
  `AI_FEATURES.md` §9 about moving `ENV_VARS` into `Settings` covers these fifteen variables too.
- **No third hosted vendor.** Two is enough to prove the registry is not a hardcoded vendor. A
  third is a descriptor in `registry.py`, a `MarketResearchProvider` subclass that maps its key
  names onto `RawListing`, and nothing else — none of the parsing, deduplication, currency or
  provenance logic is per-vendor, by design.
