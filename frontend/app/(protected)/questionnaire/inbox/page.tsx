"use client";

import { Inbox } from "lucide-react";

import { InboxList } from "@/components/questionnaire/InboxList";
import { PageHeader } from "@/components/PageHeader";
import { V2Gate } from "@/components/questionnaire/V2Gate";
import { qs } from "@/lib/questionnaire/qs";

export default function QuestionnaireInboxPage() {
  return (
    <>
      <PageHeader title={qs("inboxTitle")} icon={<Inbox className="h-5 w-5" aria-hidden />} />
      <V2Gate>
        <InboxList />
      </V2Gate>
    </>
  );
}
