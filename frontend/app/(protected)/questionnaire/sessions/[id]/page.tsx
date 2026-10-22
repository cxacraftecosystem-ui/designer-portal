"use client";

import { useParams } from "next/navigation";

import { SessionWorkspace } from "@/components/questionnaire/SessionWorkspace";
import { V2Gate } from "@/components/questionnaire/V2Gate";

/** The capture workspace. The id is the server's, or the clientKey of a session still on this device. */
export default function SessionWorkspacePage() {
  const params = useParams<{ id: string }>();
  return (
    <V2Gate>
      <SessionWorkspace id={decodeURIComponent(params.id)} />
    </V2Gate>
  );
}
