"use client";

/**
 * A designer's profile, read only — the same twenty-one columns, the same eight groups and the same
 * labels as the editor, because they are one record seen twice.
 *
 * EVERY FIELD IS DRAWN, INCLUDING THE EMPTY ONES, and that is the point of the screen rather than an
 * oversight. What this page is really for is answering "will the cover of this person's report have
 * a blank line on it" — a report that says "Designer:" and nothing after it is a document that
 * cannot be submitted — so a blank is shown as "Not filled in" instead of being quietly skipped. A
 * view that dropped its empties would look complete no matter how little had been typed, which is
 * precisely the silent-emptiness failure this repository keeps meeting: a list that stops without
 * saying so is indistinguishable from a place with no records.
 */

import { StoredMediaImage } from "@/components/designers/StoredMediaImage";
import { DocumentPreview } from "@/components/media/DocumentPreview";
import {
  DESIGNER_PROFILE_GROUPS,
  DESIGNER_PROFILE_LABELS,
  isDesignerProfileFieldRequired,
  type DesignerProfileGroup
} from "@/components/designers/profileCopy";
import { formatDate } from "@/lib/format";
import type { DesignerProfile, DesignerProfileField } from "@/lib/designers";

/** The one wording for "there is nothing in this box", so no group can invent a second one. */
const BLANK = "Not filled in";

export function DesignerProfileView({ profile }: { profile: DesignerProfile }) {
  return (
    <div className="grid gap-5">
      {DESIGNER_PROFILE_GROUPS.map((group) => (
        <GroupPanel key={group.title} group={group} profile={profile} />
      ))}
    </div>
  );
}

function GroupPanel({ group, profile }: { group: DesignerProfileGroup; profile: DesignerProfile }) {
  return (
    <section className="panel p-4">
      <h2 className="font-display text-lg font-bold text-ink-900">{group.title}</h2>
      {group.blurb ? <p className="mt-1 text-sm leading-6 text-ink-muted">{group.blurb}</p> : null}
      <dl className="mt-3 grid gap-4 md:grid-cols-2">
        {group.fields.map((field) => (
          <div key={field} className={wide(field) ? "min-w-0 md:col-span-2" : "min-w-0"}>
            {/*
              THE SAME ASTERISK THE EDITOR DRAWS, FROM THE SAME BOOLEAN. This screen exists to answer
              "will the cover of this person's report have a blank line on it", and four of these
              boxes are now the ones that stop a save outright — so an admin reading a colleague's
              profile can see WHICH of the blanks below is the one to chase. Marking them here and
              nowhere else would be two screens disagreeing about one record, which is exactly what
              `profileCopy` exists to prevent; `Blank`'s single wording is untouched, because "there
              is nothing in this box" is one fact however important the box is.
            */}
            <dt className="field-label">
              {DESIGNER_PROFILE_LABELS[field]}
              {isDesignerProfileFieldRequired(field) ? " *" : ""}
            </dt>
            <dd className="mt-1 text-sm leading-6 text-ink-900">
              <FieldValue field={field} profile={profile} />
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

/**
 * The paragraph, the two images and the CV need the whole row; a portrait squeezed into half of one
 * is a thumbnail, and half a row of a rendered PDF page is not readable at all.
 */
function wide(field: DesignerProfileField): boolean {
  return (
    field === "biography" ||
    field === "photoMediaId" ||
    field === "signatureMediaId" ||
    field === "cvMediaId"
  );
}

function FieldValue({ field, profile }: { field: DesignerProfileField; profile: DesignerProfile }) {
  const raw = profile[field];

  if (field === "photoMediaId" || field === "signatureMediaId") {
    if (typeof raw !== "string" || !raw) return <Blank />;
    return (
      <StoredMediaImage
        mediaId={raw}
        alt={field === "photoMediaId" ? "Photograph of the designer" : "The designer’s signature"}
        // A signature is a wide, short mark and a portrait is not; giving both the same square frame
        // letterboxes one of them into illegibility.
        className={field === "photoMediaId" ? "h-32 w-32" : "h-20 w-56"}
      />
    );
  }

  // THE CV IS DRAWN BY `DocumentPreview` AND NOT BY THE BLANK TEST BELOW, because that component
  // already draws its own empty state ("No CV on file.") — and it has to, since it is the same
  // component the editor mounts and the editor needs an empty slot to attach into. Routing an absent
  // id through `<Blank />` here would give this screen a third wording for one fact.
  //
  // A READER OF SOMEBODY ELSE'S PROFILE MAY NOT BE ENTITLED TO THE BYTES, which is the reason this
  // is worth a comment at all: `MediaFile.url` is gated server-side at the encoder, so an admin
  // reading a designer's page can legitimately get a row with a filename and no url.
  // `DocumentPreview` says so in those words rather than drawing an empty frame.
  if (field === "cvMediaId") {
    return <DocumentPreview mediaId={typeof raw === "string" ? raw : null} noun="CV" className="h-[26rem]" />;
  }

  if (raw === null || raw === undefined || (typeof raw === "string" && !raw.trim())) return <Blank />;

  if (field === "experienceYears") {
    const years = Number(raw);
    // `unit="years"` on the registry field it is copied into — said in words here for the same
    // reason the registry says it there: a bare number in a list of names reads as an id.
    return <span>{Number.isFinite(years) ? `${years} year${years === 1 ? "" : "s"}` : String(raw)}</span>;
  }

  if (field === "empanelmentDate") return <span>{formatDate(String(raw))}</span>;

  if (field === "biography") {
    // `whitespace-pre-line` rather than a Markdown renderer: this column is plain text on every
    // client and in the .docx, so rendering it as anything richer here would show the web reader a
    // document the ministry's copy will not contain.
    return <p className="whitespace-pre-line">{String(raw)}</p>;
  }

  if (field === "website") {
    const href = /^https?:\/\//i.test(String(raw)) ? String(raw) : `https://${String(raw)}`;
    return (
      <a
        href={href}
        target="_blank"
        rel="noreferrer"
        className="break-words text-purple-700 underline-offset-2 hover:underline"
      >
        {String(raw)}
      </a>
    );
  }

  if (field === "email") {
    return (
      <a href={`mailto:${String(raw)}`} className="break-words text-purple-700 underline-offset-2 hover:underline">
        {String(raw)}
      </a>
    );
  }

  return <span className="break-words">{String(raw)}</span>;
}

function Blank() {
  return <span className="text-ink-300">{BLANK}</span>;
}
