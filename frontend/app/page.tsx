import type { Metadata } from "next";

import { fetchCorpusCensus } from "@/components/hero/corpusCensus";
import HeroLanding from "@/components/hero/HeroLanding";

/**
 * The public landing page. A server component whose only fetch is the corpus census, taken here
 * rather than in the client island below so the numbers are in the first paint: fetched in the
 * browser they would arrive after it, and the ledger would visibly swap its figures under a reader
 * who had already started reading them.
 *
 * The route stays statically prerendered. `fetchCorpusCensus` asks for `revalidate: 300`, matching
 * the backend's own five-minute cache, so the page is built once and refreshed in the background —
 * nobody waits on the API, and a cold or absent API cannot delay a paint. It never throws: every
 * failure path returns the dated snapshot, which is why this is not wrapped in a try.
 */
/**
 * ── THE DESCRIPTION IS PART OF THE PAGE, AND IT WENT STALE THE WAY METADATA ALWAYS DOES ────────
 *
 * This file had not changed since the initial commit. Two whole feature waves landed on both
 * clients in that time and every one of them was written into the bands below — while this string,
 * which is the ONLY thing a search result, a Slack unfurl or a shared link ever shows, still
 * described the product as capture plus a .docx. The bands are the ones an editor reads and
 * remembers to update; nobody scrolls past `export const metadata` looking for a claim to correct.
 * So when a capability is added to `components/hero/*`, ask whether it changes the one sentence
 * that reaches people who never open the page.
 *
 * FOUR THINGS WERE ADDED, AND EACH IS THE NARROWEST TRUE FORM OF ITSELF.
 *
 *   "a microphone on every narrative box" — the walkthrough's own sentence, reused verbatim rather
 *   than paraphrased. "A microphone on every box" is the paraphrase that suggests itself and it is
 *   FALSE on both clients, deliberately: a name, a code and a number box have none, because a
 *   recogniser returns the nearest dictionary word and a respondent's name is the string sittings
 *   are searched by. Seventeen numeric and code boxes on Android opt out by hand.
 *
 *   the six things "filed under the workshop they were captured at" — the "Design & prototype
 *   workshop" box, mounted at six places: `ArtisanForm`, `ProductForm`, `ProcessForm` and
 *   `ToolForm`, plus `app/(protected)/media/page.tsx` and `app/(protected)/questionnaire/page.tsx`
 *   (`grep -rn "<DesignWorkshopSelect" components app` is the count).
 *
 *   ⚠ THEY ARE NAMED ONE BY ONE BECAUSE THIS STRING SAID "EVERY RECORD" AND THAT IS FALSE. Craft
 *   and Workshop — two of the eight types the page's own heading counts — have no
 *   `designWorkshopId` COLUMN, so there is nothing to mount a box onto: in `schema.prisma` that
 *   column belongs to Artisan, ProductDocumentation, ToolDocumentation, MediaFile, Process,
 *   Questionnaire and QuestionnaireInterview, and its own header counts them — "it holds for every
 *   one of these six". A craft is a `name String @unique`, one shared tradition row many artisans
 *   point at; a workshop is itself a container. Six names are longer than "every record" and are
 *   the reason this line can be checked, which a description that outruns the schema cannot be.
 *
 *   Written as FILING and never as scoping: the picker's own module insists nothing there narrows
 *   what anyone may choose, and a description implying records are fenced to a workshop would
 *   promise an API behaviour that does not exist.
 *
 *   "every file it has generated kept beside it" — report history. Each export already carried its
 *   checksum, size, page count, template and registry version, including the ones a handset wrote
 *   with no signal; what was new is that they are on a screen. Note the verb is KEPT, not compared:
 *   the comparison between two files is real but is careful about which direction it can be certain
 *   in, and a metadata line has no room to be careful. See `ReportEngine.tsx`'s fifth fact.
 *
 *   "readable stage by stage by an appointed inspector" — the INSPECTOR tier. ⚠ Never "an inspector
 *   or anybody senior to one": `lib/permissions.ts` sets `INSPECTION_ROLES` to the one-member set
 *   `["INSPECTOR"]`, mirroring the server's frozenset, and the inspection surface refuses
 *   professors, admins and the master admin with a 403 on purpose. APPOINTED is the load-bearing
 *   word — an admin appoints an inspector to a workshop and does not thereby gain the read.
 *
 * The title is the page's headline and stays as it is; a headline that chased the feature list
 * would stop being a headline.
 */
export const metadata: Metadata = {
  title: { absolute: "Design Prototype Workshop — The workshop ends. The report is already written." },
  description:
    "A capture-to-report tool for craft and textile designers: every stage of a design & prototype development workshop recorded at Basic, Standard and Advanced tiers, with photographs, costings and structured interviews — a microphone on every narrative box, and artisans, products, processes, tools, media and interviews filed under the workshop they were captured at. Rendered as a .docx or PDF report, with every file it has generated kept beside it, and the workshop readable stage by stage by an appointed inspector. Captured offline, and generated on the phone itself."
};

export default async function Home() {
  // The public origin, not an internal one: this is the same base every client call uses, so there
  // is one answer to "where is the API" rather than a second that can rot unnoticed.
  const census = await fetchCorpusCensus(process.env.NEXT_PUBLIC_API_URL ?? "");
  return <HeroLanding census={census} />;
}
