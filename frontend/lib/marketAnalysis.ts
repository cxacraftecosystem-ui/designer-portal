import { byCount } from "@/lib/format";

export const MIN_SAMPLE_FOR_QUANTILES = 5;

export const MIN_SAMPLE_FOR_VERDICT = 8;

export const NARROW_COVERAGE = 0.55;

const STOPWORDS = new Set([
  "and", "are", "but", "for", "from", "has", "have", "its", "not", "our", "that", "the",
  "their", "there", "they", "this", "was", "were", "will", "with", "you", "more", "most",
  "some", "such", "than", "then", "these", "those", "can", "could", "would", "should",
  "may", "might", "also", "very", "much", "many"
]);

const MIN_TOKEN = 3;

const SUPPORT_OVERLAP = 2;

const WORD_RUN = /[\p{L}\p{M}]+/gu;

const PY_FLOAT =
  /^[+-]?(?:\p{Nd}+(?:_\p{Nd}+)*(?:\.(?:\p{Nd}+(?:_\p{Nd}+)*)?)?|\.\p{Nd}+(?:_\p{Nd}+)*)(?:[eE][+-]?\p{Nd}+(?:_\p{Nd}+)*)?$/u;

export type MarketRow = Record<string, unknown>;

const PY_SPACE = /\p{White_Space}/u;

function countOf(count: number, noun: string): string {
  return count === 1 ? `${count} ${noun}` : `${count} ${noun}s`;
}

function isPySpace(character: string): boolean {
  const code = character.charCodeAt(0);
  if (code >= 28 && code <= 31) return true;
  return PY_SPACE.test(character);
}

export function pyStrip(text: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && isPySpace(text[start])) start += 1;
  while (end > start && isPySpace(text[end - 1])) end -= 1;
  return start === 0 && end === text.length ? text : text.slice(start, end);
}

function pythonFalsy(value: unknown): boolean {
  if (value === null || value === undefined || value === false || value === 0 || value === "") return true;
  if (Array.isArray(value)) return value.length === 0;
  if (typeof value === "object") return Object.keys(value as object).length === 0;
  return false;
}

function pyStr(value: unknown): string {
  if (typeof value === "string") return value;
  if (value === null || value === undefined) return "None";
  if (value === true) return "True";
  if (value === false) return "False";
  if (typeof value === "number") return String(value);
  try {
    return JSON.stringify(value) ?? "";
  } catch {
    return "";
  }
}

function pyText(value: unknown): string {
  return pythonFalsy(value) ? "" : pyStr(value);
}

function comparePyStrings(a: string, b: string): number {
  const left = [...a];
  const right = [...b];
  const shared = Math.min(left.length, right.length);
  for (let i = 0; i < shared; i += 1) {
    const x = left[i].codePointAt(0) ?? 0;
    const y = right[i].codePointAt(0) ?? 0;
    if (x !== y) return x < y ? -1 : 1;
  }
  return left.length === right.length ? 0 : left.length < right.length ? -1 : 1;
}

function addOne(digits: string): string {
  const out = digits.split("");
  let index = out.length - 1;
  while (index >= 0) {
    if (out[index] === "9") {
      out[index] = "0";
      index -= 1;
      continue;
    }
    out[index] = String(Number(out[index]) + 1);
    return out.join("");
  }
  return `1${out.join("")}`;
}

export function pySum(values: Iterable<number>): number {
  let total = 0;
  let compensation = 0;
  for (const value of values) {
    const stepped = total + value;
    compensation +=
      Math.abs(total) >= Math.abs(value) ? total - stepped + value : value - stepped + total;
    total = stepped;
  }
  return Number.isFinite(total) ? total + compensation : total;
}

export function pyRound(value: number, digits: number): number {
  if (!Number.isFinite(value)) return value;
  if (Math.abs(value) >= Number.MAX_SAFE_INTEGER) return value;
  const negative = value < 0 || Object.is(value, -0);
  const text = Math.abs(value).toFixed(Math.min(100, digits + 25));
  const dot = text.indexOf(".");
  const whole = dot < 0 ? text : text.slice(0, dot);
  const fraction = dot < 0 ? "" : text.slice(dot + 1);
  const kept = fraction.slice(0, digits).padEnd(digits, "0");
  const rest = fraction.slice(digits);

  let carry = false;
  const lead = rest ? rest.charCodeAt(0) - 48 : 0;
  if (lead > 5) {
    carry = true;
  } else if (lead === 5) {
    if (/[1-9]/.test(rest.slice(1))) carry = true;
    else {
      const combined = whole + kept;
      carry = (combined.charCodeAt(combined.length - 1) - 48) % 2 === 1;
    }
  }

  let all = whole + kept;
  if (carry) all = addOne(all);
  const cut = all.length - digits;
  const magnitude = digits > 0 ? `${all.slice(0, cut) || "0"}.${all.slice(cut)}` : all;
  const result = Number(magnitude);
  return negative ? -result : result;
}

function fixed0(value: number): string {
  const negative = value < 0 || Object.is(value, -0);
  return `${negative ? "-" : ""}${pyRound(Math.abs(value), 0).toFixed(0)}`;
}

function money0(value: number): string {
  const negative = value < 0 || Object.is(value, -0);
  const body = pyRound(Math.abs(value), 0).toFixed(0);
  return `${negative ? "-" : ""}${body.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}`;
}

export function formatRupeeAmount(value: number): string {
  return money0(value);
}

function tokens(text: string): Set<string> {
  const out = new Set<string>();
  for (const match of (text || "").matchAll(WORD_RUN)) {
    const word = match[0].toLowerCase();
    if ([...word].length >= MIN_TOKEN && !STOPWORDS.has(word)) out.add(word);
  }
  return out;
}

const NON_ASCII = /[^ -~]/;

const ONE_DIGIT = /\p{Nd}/u;

function asciiDigits(text: string): string {
  if (!NON_ASCII.test(text)) return text;
  let out = "";
  for (const character of text.normalize("NFKC")) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 128 || !ONE_DIGIT.test(character)) {
      out += character;
      continue;
    }
    let digit = 0;
    while (digit < 9 && ONE_DIGIT.test(String.fromCodePoint(code - digit - 1))) digit += 1;
    out += String.fromCharCode(48 + digit);
  }
  return out;
}

export function asNumber(value: unknown): number | null {
  if (value === null || value === undefined || typeof value === "boolean") return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value !== "string") return null;
  const text = pyStrip(value).replace(/,/g, "");
  if (!text) return null;
  if (!PY_FLOAT.test(text)) return null;
  const number = Number(asciiDigits(text.replace(/_/g, "")));
  return Number.isFinite(number) ? number : null;
}

export function quantile(sortedValues: number[], q: number): number {
  if (!sortedValues.length) throw new Error("quantile of an empty sample");
  if (sortedValues.length === 1) return sortedValues[0];
  const position = q * (sortedValues.length - 1);
  const lower = Math.floor(position);
  const upper = Math.ceil(position);
  if (lower === upper) return sortedValues[lower];
  return sortedValues[lower] + (sortedValues[upper] - sortedValues[lower]) * (position - lower);
}

export type PriceObservation = {
  amount: number;
  category: string;
  source: "RESPONDENT" | "COMPETITOR";
  group: string;
  label: string;
};

export type Distribution = {
  count: number;
  minimum: number;
  maximum: number;
  mean: number;
  p25: number;
  median: number;
  p75: number;
  quantilesReported: boolean;
};

export function describe(values: number[]): Distribution | null {
  if (!values.length) return null;
  const ordered = [...values].sort((a, b) => a - b);
  const count = ordered.length;
  const median = quantile(ordered, 0.5);
  const enough = count >= MIN_SAMPLE_FOR_QUANTILES;
  const total = pySum(ordered);
  return {
    count,
    minimum: ordered[0],
    maximum: ordered[count - 1],
    mean: total / count,
    p25: enough ? quantile(ordered, 0.25) : median,
    median,
    p75: enough ? quantile(ordered, 0.75) : median,
    quantilesReported: enough
  };
}

export type BandVerdictKind = "SOUND" | "NARROW" | "LOW" | "HIGH" | "UNVERIFIABLE" | "NO_EVIDENCE";

export type BandVerdict = {
  category: string;
  declaredLow: number | null;
  declaredHigh: number | null;
  evidence: Distribution | null;
  inside: number;
  below: number;
  above: number;
  coverage: number;
  verdict: BandVerdictKind;
  message: string;
};

function bandMessage(
  verdict: BandVerdictKind,
  category: string,
  low: number,
  high: number,
  dist: Distribution,
  inside: number,
  below: number,
  above: number
): string {
  const label = category || "this category";
  const range = `₹${money0(low)}–${money0(high)}`;
  const median = `₹${money0(dist.median)}`;
  if (verdict === "SOUND") {
    return `The band ${range} covers ${inside} of ${dist.count} price observations for ${label}; ` +
      `the median observed is ${median}.`;
  }
  if (verdict === "LOW") {
    return `The band ${range} sits below the evidence for ${label}: ${above} of ${dist.count} ` +
      `observations are above it, and the median observed is ${median}. The prototypes may be ` +
      `priced under what buyers said.`;
  }
  if (verdict === "HIGH") {
    return `The band ${range} sits above the evidence for ${label}: ${below} of ${dist.count} ` +
      `observations are below it, and the median observed is ${median}.`;
  }
  return `The band ${range} covers only ${inside} of ${dist.count} observations for ${label} ` +
    `(${below} below, ${above} above; median ${median}). Widening it, or splitting the category, ` +
    `would match what was recorded.`;
}

export function judgeBand(
  category: string,
  low: unknown,
  high: unknown,
  observations: PriceObservation[]
): BandVerdict {
  let declaredLow = asNumber(low);
  let declaredHigh = asNumber(high);
  if (declaredLow !== null && declaredHigh !== null && declaredLow > declaredHigh) {
    const swap = declaredLow;
    declaredLow = declaredHigh;
    declaredHigh = swap;
  }

  const values = observations.map((observation) => observation.amount);
  const dist = describe(values);
  const named = category || "this category";

  if (dist === null) {
    return {
      category, declaredLow, declaredHigh, evidence: null,
      inside: 0, below: 0, above: 0, coverage: 0, verdict: "NO_EVIDENCE",
      message: `No price was recorded in stage 8 for ${named}, so this band cannot be checked ` +
        `against the survey.`
    };
  }

  if (declaredLow === null || declaredHigh === null) {
    return {
      category, declaredLow, declaredHigh, evidence: dist,
      inside: 0, below: 0, above: 0, coverage: 0, verdict: "NO_EVIDENCE",
      message: `${dist.count} price ${byCount(dist.count, "observation exists", "observations exist")} for ${named}, but the band itself is incomplete.`
    };
  }

  let inside = 0;
  let below = 0;
  let above = 0;
  for (const value of values) {
    if (value >= declaredLow && value <= declaredHigh) inside += 1;
    if (value < declaredLow) below += 1;
    if (value > declaredHigh) above += 1;
  }
  const coverage = inside / dist.count;

  if (dist.count < MIN_SAMPLE_FOR_VERDICT) {
    return {
      category, declaredLow, declaredHigh, evidence: dist, inside, below, above, coverage,
      verdict: "UNVERIFIABLE",
      message: `Only ${dist.count} price ${byCount(dist.count, "observation was", "observations were")} recorded for ${named} — too few to ` +
        `judge the band against. ${inside} of them fall inside it.`
    };
  }

  let verdict: BandVerdictKind;
  if (coverage >= NARROW_COVERAGE) verdict = "SOUND";
  else if (above > below * 2) verdict = "LOW";
  else if (below > above * 2) verdict = "HIGH";
  else verdict = "NARROW";

  return {
    category, declaredLow, declaredHigh, evidence: dist, inside, below, above, coverage, verdict,
    message: bandMessage(verdict, category, declaredLow, declaredHigh, dist, inside, below, above)
  };
}

export type CompetitorPosition = {
  name: string;
  seller: string;
  category: string;
  price: number;
  percentile: number | null;
  versusMedian: number | null;
  note: string;
};

export function positionCompetitors(
  competitors: MarketRow[],
  observations: PriceObservation[]
): CompetitorPosition[] {
  const byCategory = new Map<string, number[]>();
  const pooled: number[] = [];
  for (const observation of observations) {
    if (observation.source !== "RESPONDENT") continue;
    pooled.push(observation.amount);
    if (observation.category) {
      const bucket = byCategory.get(observation.category);
      if (bucket) bucket.push(observation.amount);
      else byCategory.set(observation.category, [observation.amount]);
    }
  }
  for (const values of byCategory.values()) values.sort((a, b) => a - b);
  pooled.sort((a, b) => a - b);

  const out: CompetitorPosition[] = [];
  for (const row of competitors) {
    const price = asNumber(row.price);
    if (price === null) continue;
    const category = pyText(row.category);
    const name = pyStrip(pyText(row.name)) || "Unnamed product";
    const seller = pyStrip(pyText(row.seller));

    let sample = byCategory.get(category) ?? [];
    const matched = sample.length >= MIN_SAMPLE_FOR_QUANTILES;
    if (!matched) sample = pooled;
    if (sample.length < MIN_SAMPLE_FOR_QUANTILES) {
      out.push({
        name, seller, category, price, percentile: null, versusMedian: null,
        note: `₹${money0(price)}. Too few buyer price expectations were recorded in the survey to ` +
          `place this against them.`
      });
      continue;
    }

    let atOrBelow = 0;
    for (const value of sample) if (value <= price) atOrBelow += 1;
    const percentile = atOrBelow / sample.length;
    const median = quantile(sample, 0.5);
    const whose = matched
      ? `the ${sample.length} buyers asked about ${category}`
      : `all ${sample.length} buyers asked`;
    const comparison = percentile >= 0.5 ? "above" : "below";
    const share = percentile >= 0.5 ? percentile : 1 - percentile;
    out.push({
      name, seller, category, price, percentile, versusMedian: price - median,
      note: `₹${money0(price)} is ${comparison} what ${fixed0(share * 100)}% of ${whose} said they ` +
        `would pay (their median: ₹${money0(median)}).`
    });
  }
  return out;
}

export type SwotSupport = {
  kind: string;
  point: string;
  supportedBy: string[];
  overlap: number;
  hasOwnEvidence: boolean;
  supported: boolean;
};

export function linkSwotEvidence(swot: MarketRow[], responses: MarketRow[]): SwotSupport[] {
  const prepared: { name: string; tokens: Set<string> }[] = [];
  for (const row of responses) {
    let text = ["response", "productsDiscussed", "place"].map((key) => pyText(row[key])).join(" ");
    if (Array.isArray(row.productsDiscussed)) {
      text += ` ${row.productsDiscussed.map((tag) => pyStr(tag)).join(" ")}`;
    }
    prepared.push({ name: pyStrip(pyText(row.respondentName)) || "A respondent", tokens: tokens(text) });
  }

  const out: SwotSupport[] = [];
  for (const row of swot) {
    const point = pyStrip(pyText(row.point));
    if (!point) continue;
    const kind = pyText(row.kind);
    const own = pyStrip(pyText(row.evidence)).length > 0;
    const wanted = tokens(point);
    const matches: { shared: number; name: string }[] = [];
    for (const candidate of prepared) {
      let shared = 0;
      for (const word of wanted) if (candidate.tokens.has(word)) shared += 1;
      if (shared >= SUPPORT_OVERLAP) matches.push({ shared, name: candidate.name });
    }
    matches.sort((a, b) => (b.shared - a.shared) || comparePyStrings(a.name, b.name));
    const supportedBy = matches.slice(0, 5).map((match) => match.name);
    out.push({
      kind,
      point,
      supportedBy,
      overlap: matches.length ? matches[0].shared : 0,
      hasOwnEvidence: own,
      supported: own || supportedBy.length > 0
    });
  }
  return out;
}

export type TrendCluster = {
  members: string[];
  mentions: number;
  coOccurrences: number;
};

export function clusterProducts(
  responses: MarketRow[],
  { minimumMentions = 2, limit = 8 }: { minimumMentions?: number; limit?: number } = {}
): TrendCluster[] {
  const tagLists: string[][] = [];
  for (const row of responses) {
    const raw = row.productsDiscussed;
    const tags: unknown[] = Array.isArray(raw) ? raw : pyText(raw).split(",");
    const unique = new Set<string>();
    for (const tag of tags) {
      const text = pyStrip(pyStr(tag));
      if (text) unique.add(text.toLowerCase());
    }
    const cleaned = [...unique].sort(comparePyStrings);
    if (cleaned.length) tagLists.push(cleaned);
  }

  const mentions = new Map<string, number>();
  const pairs = new Map<string, { a: string; b: string; seen: number }>();
  for (const tags of tagLists) {
    for (const tag of tags) mentions.set(tag, (mentions.get(tag) ?? 0) + 1);
    for (let i = 0; i < tags.length; i += 1) {
      for (let j = i + 1; j < tags.length; j += 1) {
        const key = `${tags[i]}\u0000${tags[j]}`;
        const existing = pairs.get(key);
        if (existing) existing.seen += 1;
        else pairs.set(key, { a: tags[i], b: tags[j], seen: 1 });
      }
    }
  }

  const parent = new Map<string, string>();
  for (const tag of mentions.keys()) parent.set(tag, tag);
  const find = (start: string): string => {
    let node = start;
    while (parent.get(node) !== node) {
      const grandparent = parent.get(parent.get(node) as string) as string;
      parent.set(node, grandparent);
      node = grandparent;
    }
    return node;
  };

  const joined = new Map<string, number>();
  for (const pair of pairs.values()) {
    if (pair.seen < minimumMentions) continue;
    const rootA = find(pair.a);
    const rootB = find(pair.b);
    if (rootA !== rootB) parent.set(rootB, rootA);
    const root = find(pair.a);
    joined.set(root, (joined.get(root) ?? 0) + pair.seen);
  }

  const groups = new Map<string, string[]>();
  for (const tag of mentions.keys()) {
    const root = find(tag);
    const bucket = groups.get(root);
    if (bucket) bucket.push(tag);
    else groups.set(root, [tag]);
  }

  const clusters: TrendCluster[] = [];
  for (const [root, members] of groups) {
    if (!(members.length > 1 || (mentions.get(members[0]) ?? 0) >= minimumMentions)) continue;
    let total = 0;
    for (const member of members) total += mentions.get(member) ?? 0;
    clusters.push({
      members: [...members].sort(comparePyStrings),
      mentions: total,
      coOccurrences: joined.get(root) ?? 0
    });
  }
  clusters.sort((a, b) =>
    (b.mentions - a.mentions) || (b.members.length - a.members.length) || compareMembers(a.members, b.members)
  );
  return clusters.slice(0, limit);
}

function compareMembers(a: string[], b: string[]): number {
  const shared = Math.min(a.length, b.length);
  for (let i = 0; i < shared; i += 1) {
    const order = comparePyStrings(a[i], b[i]);
    if (order !== 0) return order;
  }
  return a.length - b.length;
}

export type MarketFindings = {
  observations: number;
  respondentPrices: Distribution | null;
  competitorPrices: Distribution | null;
  byCategory: Map<string, Distribution>;
  bands: BandVerdict[];
  competitors: CompetitorPosition[];
  swot: SwotSupport[];
  clusters: TrendCluster[];
  groupCounts: Map<string, number>;
  cautions: string[];
};

export function collectObservations(
  responses: MarketRow[],
  competitors: MarketRow[],
  defaultCategory = ""
): PriceObservation[] {
  const out: PriceObservation[] = [];
  for (const row of responses) {
    const amount = asNumber(row.priceExpectation);
    if (amount === null) continue;
    out.push({
      amount,
      category: defaultCategory,
      source: "RESPONDENT",
      group: pyText(row.respondentGroup),
      label: pyStrip(pyText(row.respondentName)) || "A respondent"
    });
  }
  for (const row of competitors) {
    const amount = asNumber(row.price);
    if (amount === null) continue;
    out.push({
      amount,
      category: pyText(row.category),
      source: "COMPETITOR",
      group: "",
      label: pyStrip(pyText(row.name)) || "A competitor product"
    });
  }
  return out;
}

export function analyse({
  responses,
  competitors,
  bands,
  swot
}: {
  responses: MarketRow[];
  competitors: MarketRow[];
  bands: MarketRow[];
  swot: MarketRow[];
}): MarketFindings {
  const observations = collectObservations(responses, competitors);
  const respondentValues: number[] = [];
  const competitorValues: number[] = [];
  for (const observation of observations) {
    if (observation.source === "RESPONDENT") respondentValues.push(observation.amount);
    else competitorValues.push(observation.amount);
  }

  const grouped = new Map<string, number[]>();
  for (const observation of observations) {
    const bucket = grouped.get(observation.category);
    if (bucket) bucket.push(observation.amount);
    else grouped.set(observation.category, [observation.amount]);
  }
  const byCategory = new Map<string, Distribution>();
  for (const [category, values] of grouped) {
    const described = describe(values);
    if (described) byCategory.set(category, described);
  }

  const bandVerdicts = bands.map((row) => {
    const category = pyText(row.category);
    return judgeBand(
      category,
      row.lowPrice,
      row.highPrice,
      observations.filter((observation) => observation.category === category || !observation.category)
    );
  });

  const groupCounts = new Map<string, number>();
  for (const observation of observations) {
    if (observation.source !== "RESPONDENT" || !observation.group) continue;
    groupCounts.set(observation.group, (groupCounts.get(observation.group) ?? 0) + 1);
  }

  const cautions: string[] = [];
  if (respondentValues.length && respondentValues.length < MIN_SAMPLE_FOR_VERDICT) {
    cautions.push(
      `Only ${respondentValues.length} respondent price ` +
        `${byCount(respondentValues.length, "expectation was", "expectations were")} recorded. The figures ` +
        `below describe what was collected; they are not a market estimate.`
    );
  }
  if (groupCounts.size) {
    let dominant = "";
    let dominantCount = -1;
    let total = 0;
    for (const [group, count] of groupCounts) {
      total += count;
      if (count > dominantCount) {
        dominant = group;
        dominantCount = count;
      }
    }
    if (total >= MIN_SAMPLE_FOR_QUANTILES && dominantCount / total > 0.8) {
      cautions.push(
        `${dominantCount} of ${total} priced responses come from one respondent group (${dominant}). ` +
          `The price figures describe that group rather than the market.`
      );
    }
  }
  if (competitorValues.length && !respondentValues.length) {
    cautions.push(
      "Competitor prices were recorded but no buyer price expectations were. Shelf prices say what " +
        "is charged, not what the buyers surveyed said they would pay."
    );
  }

  return {
    observations: observations.length,
    respondentPrices: describe(respondentValues),
    competitorPrices: describe(competitorValues),
    byCategory,
    bands: bandVerdicts,
    competitors: positionCompetitors(competitors, observations),
    swot: linkSwotEvidence(swot, responses),
    clusters: clusterProducts(responses),
    groupCounts,
    cautions
  };
}

export type DistributionPayload = {
  count: number;
  minimum: number;
  maximum: number;
  mean: number;
  p25: number;
  median: number;
  p75: number;
  quantilesReported: boolean;
};

export type BandPayload = {
  category: string;
  declaredLow: number | null;
  declaredHigh: number | null;
  evidence: DistributionPayload | null;
  inside: number;
  below: number;
  above: number;
  coverage: number;
  verdict: string;
  message: string;
};

export type CompetitorPayload = {
  name: string;
  seller: string;
  category: string;
  price: number;
  percentile: number | null;
  versusMedian: number | null;
  note: string;
};

export type SwotPayload = {
  kind: string;
  point: string;
  supported: boolean;
  hasOwnEvidence: boolean;
  supportedBy: string[];
  overlap: number;
};

export type ClusterPayload = { members: string[]; mentions: number; coOccurrences: number };

export type MarketFindingsPayload = {
  observations: number;
  cautions: string[];
  unsupportedSwot: { kind: string; point: string }[];
  respondentPrices: DistributionPayload | null;
  competitorPrices: DistributionPayload | null;
  byCategory: Record<string, DistributionPayload | null>;
  groupCounts: Record<string, number>;
  bands: BandPayload[];
  competitors: CompetitorPayload[];
  swot: SwotPayload[];
  clusters: ClusterPayload[];
};

function distributionPayload(dist: Distribution | null): DistributionPayload | null {
  if (!dist) return null;
  return {
    count: dist.count,
    minimum: pyRound(dist.minimum, 2),
    maximum: pyRound(dist.maximum, 2),
    mean: pyRound(dist.mean, 2),
    p25: pyRound(dist.p25, 2),
    median: pyRound(dist.median, 2),
    p75: pyRound(dist.p75, 2),
    quantilesReported: dist.quantilesReported
  };
}

export function marketFindingsPayload(findings: MarketFindings): MarketFindingsPayload {
  const byCategory: Record<string, DistributionPayload | null> = {};
  for (const category of [...findings.byCategory.keys()].sort(comparePyStrings)) {
    byCategory[category] = distributionPayload(findings.byCategory.get(category) ?? null);
  }
  const groupCounts: Record<string, number> = {};
  for (const group of [...findings.groupCounts.keys()].sort(comparePyStrings)) {
    groupCounts[group] = findings.groupCounts.get(group) as number;
  }
  return {
    observations: findings.observations,
    cautions: [...findings.cautions],
    unsupportedSwot: findings.swot
      .filter((point) => !point.supported)
      .map((point) => ({ kind: point.kind, point: point.point })),
    respondentPrices: distributionPayload(findings.respondentPrices),
    competitorPrices: distributionPayload(findings.competitorPrices),
    byCategory,
    groupCounts,
    bands: findings.bands.map((band) => ({
      category: band.category,
      declaredLow: band.declaredLow,
      declaredHigh: band.declaredHigh,
      evidence: distributionPayload(band.evidence),
      inside: band.inside,
      below: band.below,
      above: band.above,
      coverage: pyRound(band.coverage, 4),
      verdict: band.verdict,
      message: band.message
    })),
    competitors: findings.competitors.map((competitor) => ({
      name: competitor.name,
      seller: competitor.seller,
      category: competitor.category,
      price: competitor.price,
      percentile: competitor.percentile === null ? null : pyRound(competitor.percentile, 4),
      versusMedian: competitor.versusMedian === null ? null : pyRound(competitor.versusMedian, 2),
      note: competitor.note
    })),
    swot: findings.swot.map((point) => ({
      kind: point.kind,
      point: point.point,
      supported: point.supported,
      hasOwnEvidence: point.hasOwnEvidence,
      supportedBy: [...point.supportedBy],
      overlap: point.overlap
    })),
    clusters: findings.clusters.map((cluster) => ({
      members: [...cluster.members],
      mentions: cluster.mentions,
      coOccurrences: cluster.coOccurrences
    }))
  };
}

export function analysePayload(input: {
  responses: MarketRow[];
  competitors: MarketRow[];
  bands: MarketRow[];
  swot: MarketRow[];
}): MarketFindingsPayload {
  return marketFindingsPayload(analyse(input));
}
