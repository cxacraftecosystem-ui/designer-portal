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
export const metadata: Metadata = {
  title: { absolute: "Design Prototype Workshop — The workshop ends. The report is already written." },
  description:
    "A capture-to-report tool for craft and textile designers: every stage of a design & prototype development workshop recorded at Basic, Standard and Advanced tiers, with photographs, costings and structured interviews — then rendered as a .docx or PDF report. Captured offline, and generated on the phone itself."
};

export default async function Home() {
  // The public origin, not an internal one: this is the same base every client call uses, so there
  // is one answer to "where is the API" rather than a second that can rot unnoticed.
  const census = await fetchCorpusCensus(process.env.NEXT_PUBLIC_API_URL ?? "");
  return <HeroLanding census={census} />;
}
