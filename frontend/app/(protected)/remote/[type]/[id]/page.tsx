"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { ExternalLink, Search } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { RecordFieldList } from "@/components/records/RecordView";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { formatDateTime } from "@/lib/format";
import { fetchRemoteRecord, openInLabel, remoteRecordRows, remoteRecordError, safeOpenUrl, type RemoteRecord } from "@/lib/unifiedSearch";

/**
 * One record from the other portal, read-only (docs/SEARCH.md): what the other portal shares about
 * it, and a link to open it there, where that account's own permissions apply. Read-only: no form,
 * no file.
 */
export default function RemoteRecordPage() {
  const { type, id } = useParams<{ type: string; id: string }>();
  const [record, setRecord] = useState<RemoteRecord | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchRemoteRecord(type, id)
      .then((data) => {
        if (!cancelled) setRecord(data);
      })
      .catch((err) => {
        if (!cancelled) setError(remoteRecordError(err));
      });
    return () => {
      cancelled = true;
    };
  }, [type, id]);

  const openUrl = safeOpenUrl(record?.openUrl);

  return (
    <>
      <PageHeader
        title={record?.title || (error ? "Record" : "Loading…")}
        description={record?.subtitle ?? undefined}
        icon={<Search className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {record ? (
        <div className="grid gap-5">
          <RecordFieldList rows={remoteRecordRows(record, formatDateTime)} />
          {record.snippet ? (
            <section className="panel p-5">
              <p className="whitespace-pre-line text-sm leading-6 text-ink-700">{record.snippet}</p>
            </section>
          ) : null}
          {openUrl ? (
            <ActionGroup>
              <a className="field-button" href={openUrl} target="_blank" rel="noreferrer">
                <ExternalLink className="h-4 w-4" aria-hidden />
                {openInLabel(record.portal)}
              </a>
            </ActionGroup>
          ) : null}
        </div>
      ) : null}
    </>
  );
}
