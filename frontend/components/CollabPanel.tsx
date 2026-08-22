"use client";

import { useCallback, useEffect, useState } from "react";
import { Trash2 } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { isAdmin } from "@/lib/permissions";
import type { EntryComment, RecordRevision } from "@/lib/types";

/**
 * Comments + edit history for a single record, powered by the data-access API.
 * - Anyone who can see the record can read comments; posting requires COMMENT-tier access (or owner/admin).
 * - Edit history is visible to the record's owner and admins (the API returns 403 otherwise, which we hide).
 */
export function CollabPanel({ recordType, recordId }: { recordType: string; recordId: string }) {
  const [comments, setComments] = useState<EntryComment[]>([]);
  const [revisions, setRevisions] = useState<RecordRevision[] | null>(null);
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** Which comment is being withdrawn, so only its own control goes quiet. */
  const [removingId, setRemovingId] = useState<string | null>(null);
  const { user } = useAuth();
  const confirm = useConfirm();

  const load = useCallback(async () => {
    try {
      const c = await apiFetch<EntryComment[]>(`/data-access/comments?recordType=${recordType}&recordId=${recordId}`);
      setComments(c);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load comments");
    }
    // Edit history is owner/admin-only; silently skip if not permitted.
    try {
      const r = await apiFetch<RecordRevision[]>(`/data-access/revisions?recordType=${recordType}&recordId=${recordId}`);
      setRevisions(r);
    } catch {
      setRevisions(null);
    }
  }, [recordType, recordId]);

  useEffect(() => {
    load();
  }, [load]);

  async function postComment() {
    if (!body.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await apiFetch("/data-access/comments", { method: "POST", body: JSON.stringify({ recordType, recordId, body: body.trim() }) });
      setBody("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to post comment");
    } finally {
      setBusy(false);
    }
  }

  /**
   * WITHDRAW A COMMENT — the third of the three comment routes, which had no surface on either client.
   *
   * `DELETE /data-access/comments/{id}` has existed alongside the GET and the POST all along and
   * nothing anywhere called it. So a reviewer who typed a note onto the wrong artisan, or wrote
   * something that should not stand on a record other researchers read, had no way to remove it on
   * the web or on the handset: the comment was permanent.
   *
   * WHO IS OFFERED IT MIRRORS THE HANDLER, read rather than assumed: it 403s unless the caller is
   * the comment's AUTHOR or an admin. Offering the control more widely would put a button in front
   * of people whose only possible outcome is a refusal.
   *
   * Confirmed first, and in the danger tone, because there is no undo — the row is deleted outright
   * rather than tombstoned. A double press is harmless either way: the handler returns 204 in
   * silence for a comment that is already gone.
   */
  async function removeComment(comment: EntryComment) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this comment?",
        comment.body,
        "It is removed for everyone who can see this record. This cannot be undone."
      )
    );
    if (!ok) return;
    setRemovingId(comment.id);
    setError(null);
    try {
      await apiFetch(`/data-access/comments/${comment.id}`, { method: "DELETE" });
      // Refetched rather than spliced out of local state, so what is on screen is what the server
      // holds — including a comment somebody else withdrew while this panel was open.
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete comment");
    } finally {
      setRemovingId(null);
    }
  }

  return (
    <div className="grid gap-4">
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div>
        <h3 className="font-display font-bold text-base text-ink">Comments</h3>
        <ul className="mt-2 grid gap-2">
          {comments.length === 0 ? <li className="text-sm text-ink-muted">No comments yet.</li> : null}
          {comments.map((c) => (
            <li key={c.id} className="rounded-md border border-line-200 bg-field-50 p-2 text-sm">
              <div className="text-ink">{c.body}</div>
              <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-ink-muted">
                <span>
                  {c.author?.name ?? "Someone"} · {formatDateTime(c.createdAt)}
                </span>
                {/* Only where the server would allow it — see `removeComment`. */}
                {user && (c.authorId === user.id || isAdmin(user)) ? (
                  <button
                    type="button"
                    className="inline-flex items-center gap-1 text-error-600 underline underline-offset-2 disabled:no-underline disabled:opacity-60"
                    disabled={removingId === c.id}
                    onClick={() => removeComment(c)}
                  >
                    <Trash2 className="h-3 w-3" aria-hidden />
                    {removingId === c.id ? "Deleting…" : "Delete"}
                  </button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
        <div className="mt-2 flex gap-2">
          <input
            className="field-input flex-1"
            placeholder="Add a comment (needs comment access)"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !busy) postComment();
            }}
          />
          <button className="field-button" disabled={busy} onClick={postComment}>
            Post
          </button>
        </div>
      </div>

      {revisions ? (
        <div>
          <h3 className="font-display font-bold text-base text-ink">Edit history</h3>
          {/* The caption has to match what the API actually returns. Identity and contact columns
              (Aadhaar, Pehchan card, phone, email, address) are logged by the server WITHOUT their
              value — see access.REVISION_REDACTED_FIELDS — so they render as "(value recorded) →
              (cleared)". Saying "original values are the first before" without that exception told
              an admin the left-hand column was the old number when it never is for those fields. */}
          <p className="text-xs text-ink-muted">
            Original values are the first &quot;before&quot; of each field — except identity and contact fields
            (Aadhaar, Pehchan card, phone, email, address), where only the fact that they changed is recorded, never
            the value. Visible to the owner and admins.
          </p>
          <ul className="mt-2 grid gap-2">
            {revisions.length === 0 ? <li className="text-sm text-ink-muted">No edits recorded.</li> : null}
            {revisions.map((r) => (
              <li key={r.id} className="rounded-md border border-line-200 bg-field-50 p-2 text-sm">
                <div className="text-xs text-ink-muted">
                  {r.editedBy?.name ?? "Unknown"} · {formatDateTime(r.createdAt)}
                </div>
                <ul className="mt-1 grid gap-0.5">
                  {Object.entries(r.changes).map(([field, change]) => (
                    <li key={field} className="text-xs">
                      <span className="font-semibold text-ink">{field}</span>:{" "}
                      <span className="text-red-700 line-through">{String(change.old ?? "—")}</span>{" → "}
                      <span className="text-emerald-700">{String(change.new ?? "—")}</span>
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
