import type { Metadata } from "next";

import { fetchCorpusCensus } from "@/components/hero/corpusCensus";
import HeroLanding from "@/components/hero/HeroLanding";

export const metadata: Metadata = {
  title: { absolute: "Design Prototype Workshop — The workshop ends. The report is already written." },
  description:
    "Capture every stage of a craft design & prototype workshop — photos, costings and interviews, even offline — and get the .docx or PDF report as you go."
};

export default async function Home() {
  const census = await fetchCorpusCensus(process.env.NEXT_PUBLIC_API_URL ?? "");
  return <HeroLanding census={census} />;
}
