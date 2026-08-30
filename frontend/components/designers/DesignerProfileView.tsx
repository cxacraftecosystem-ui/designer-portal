"use client";

/**
 * A designer's profile, read only — the same twenty-two columns, the same eight groups and the same
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

import { plainFromStoredAddress } from "@/components/designers/storedAddress";
import { StoredMediaImage } from "@/components/designers/StoredMediaImage";
import { DocumentPreview } from "@/components/media/DocumentPreview";
import {
  DESIGNER_PROFILE_GROUPS,
  DESIGNER_PROFILE_LABELS,
  isDesignerProfileFieldRequired,
  type DesignerProfileGroup
} from "@/components/designers/profileCopy";
import { formatDate } from "@/lib/format";
import type { DesignerProfile, DesignerProfileField, DesignerProfileLocation } from "@/lib/designers";

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
        {/*
          THE MONTHS HALF OF THE EXPERIENCE PAIR IS DRAWN BY ITS YEARS ROW, NOT BY ONE OF ITS OWN.

          `experienceMonths` is listed in this group’s fields because it is a real writable column and
          every one of them belongs to exactly one group — that is what lets a reader check this
          screen against the editor. It is filtered out HERE, and only here, because the two columns
          are one answer: the editor draws them as two dropdowns under a single heading, and this
          screen prints them as “12 years 6 months” on the row above. A second row reading “Months: 6”
          under it would be the same fact stated twice, in two places that could then disagree.

          It is a FILTER and not a `return null` inside `FieldValue`, because the `<dt>` is drawn by
          this map: returning nothing from the value would leave a labelled row with no value in it.
        */}
        {group.fields
          .filter((field) => field !== "experienceMonths")
          .map((field) => (
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
      {/*
        THE SECOND ADDRESS, UNDER THE FIRST — and both are drawn because either alone is wrong.

        The four rows above are `DesignerProfile`’s own flat columns, which is where every live
        designer’s address actually is. The block below is the related `Location` row, which is the
        only place a DISTRICT, a village or a map point has ever been able to live (requirement 29).
        Nothing was backfilled from one into the other and nothing could be — `Location.latitude`
        and `longitude` are NOT NULL, so a row cannot be made for a profile that has an address and
        no coordinate without inventing the coordinate — so until the retiring migration happens a
        profile may carry its address in either place. A page that drew only one of the two would
        show some designers a blank where their address is.
      */}
      {group.key === "address" ? <LocationRecord location={profile.location} /> : null}
    </section>
  );
}

/**
 * The related `Location` row, read only — the stated address first, then where the device was.
 *
 * THE TWO GROUPS ARE KEPT APART ON SCREEN BECAUSE THEY ANSWER DIFFERENT QUESTIONS, and merging them
 * is the failure the model exists to prevent: fifteen live artisan records carry Kharagpur
 * coordinates for artisans in Bagru, Kutch and Rudraprayag, because a fix of the desk the record was
 * typed at was read as the subject’s address. So the stated answers say who said them, and the
 * device fix is labelled as a device fix and shown with the moment it was taken.
 *
 * EVERY ROW IS DRAWN EVEN WHEN THERE IS NO `Location` ROW AT ALL, which is this screen’s whole
 * contract: a blank reads as “nobody has answered this”, and a skipped row reads as nothing.
 */
function LocationRecord({ location }: { location: DesignerProfileLocation | null }) {
  return (
    <div className="mt-5 border-t border-line-200 pt-4">
      <h3 className="field-label">Location record</h3>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        The district, the village and the map point are held on the same location record every other record page uses.
        The four boxes above are this profile’s own address columns, and an address may still be in either place — so
        both are shown rather than one of them being chosen for you.
      </p>
      <dl className="mt-3 grid gap-4 md:grid-cols-2">
        <Row label="State">{location?.state}</Row>
        <Row label="District">{location?.district}</Row>
        <Row label="Village">{location?.village}</Row>
        <Row label="Pincode">{location?.pincode}</Row>
        {/* The researcher’s pin on the SUBJECT’S place — an answer a person gave. */}
        <Row label="Map point">{coordinateText(location?.subjectLatitude, location?.subjectLongitude)}</Row>
        {/*
          PROVENANCE, NAMED AS PROVENANCE. “Where the device was” is not “where the designer is”, and
          the timestamp is what makes it judgeable at all — a coordinate with no moment attached
          cannot be weighed against anything.
        */}
        <Row label="Device fix, and when it was taken">
          {coordinateText(location?.latitude, location?.longitude, location?.capturedAt)}
        </Row>
      </dl>
    </div>
  );
}

/** One `<dt>`/`<dd>` pair with this screen’s single wording for an unanswered box. */
function Row({ label, children }: { label: string; children?: string | null }) {
  return (
    <div className="min-w-0">
      <dt className="field-label">{label}</dt>
      <dd className="mt-1 text-sm leading-6 text-ink-900">
        {children && children.trim() ? <span className="break-words">{children}</span> : <Blank />}
      </dd>
    </div>
  );
}

/**
 * A coordinate pair as text, or null when it is not a pair.
 *
 * BOTH OR NEITHER, deliberately: half a coordinate is not a place, and printing one number would
 * invite a reader to believe the record holds a location it does not. Five decimals is about a
 * metre, which is finer than any of these fixes and coarse enough to read.
 */
function coordinateText(
  latitude: number | null | undefined,
  longitude: number | null | undefined,
  capturedAt?: string | null
): string | null {
  if (typeof latitude !== "number" || typeof longitude !== "number") return null;
  const point = `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`;
  return capturedAt ? `${point} · ${formatDate(capturedAt)}` : point;
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

  /*
    THE EXPERIENCE PAIR IS TESTED BEFORE THE BLANK TEST BELOW, AND THAT ORDER IS THE BUG IT AVOIDS.

    This branch used to sit under `if (raw === null …) return <Blank />`, which was correct while
    there was one column. With two, a designer who recorded 6 months and no whole years has a null
    `experienceYears` — so the blank test would fire on the years, print “Not filled in”, and the
    months they did answer would appear nowhere on this page at all.
  */
  if (field === "experienceYears") return <ExperienceValue profile={profile} />;

  if (raw === null || raw === undefined || (typeof raw === "string" && !raw.trim())) return <Blank />;

  if (field === "empanelmentDate") return <span>{formatDate(String(raw))}</span>;

  if (field === "biography") {
    // `whitespace-pre-line` rather than a Markdown renderer: this column is plain text on every
    // client and in the .docx, so rendering it as anything richer here would show the web reader a
    // document the ministry's copy will not contain.
    return <p className="whitespace-pre-line">{String(raw)}</p>;
  }

  /*
    ── THE ADDRESS IS THE ONE COLUMN ON THIS SCREEN THAT MAY NOT BE A STRING ────────────────────

    `addressLine` took rich text on 2026-08-30 without changing shape: it holds the designer's prose
    whenever nothing is formatted, and `{"blocks":[{"kind":"PARAGRAPH","spans":[…]}]}` the moment a
    word is bolded. Every other branch on this screen ends in `String(raw)`, and `String(raw)` on a
    stored document prints the braces — verbatim, silently, on the page an admin opens to check
    whether a colleague's report cover will have a blank line on it. It would not read as a bug in
    this component; it would read as a designer who had pasted something strange into their address.

    FLATTENED RATHER THAN RE-RENDERED WITH ITS MARKS, and that is the deliberate half. This screen's
    job is to show what the report will carry, and what the report carries is the flattened text:
    `prefill_from_profile` copies this column into `designerAddress`, a registry TEXT field, through
    `rich_text.plain_from_stored`. Drawing the bold here that the .docx will not print would be this
    screen telling its reader something about a document it cannot see — §17's "claims about the
    report" trap, on the one page whose whole purpose is answering a question about the report.

    `whitespace-pre-line` because the flattened form of a two-paragraph address has a newline in it,
    and an address whose lines run together is harder to check than one that does not.
    `plainFromStoredAddress` is identity on prose, so an address written before this change renders
    exactly as it did yesterday — which is the case that matters, since it is every live row.
  */
  if (field === "addressLine") {
    return <p className="whitespace-pre-line break-words">{plainFromStoredAddress(String(raw))}</p>;
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

/**
 * The experience pair as one phrase — “12 years 6 months”, “6 months”, “0 years”, or a blank.
 *
 * ── NULL AND 0 ARE DIFFERENT ANSWERS HERE TOO, WHICH IS WHY THE TEST IS `=== null` ────────────
 *
 * A falsy test would print nothing for a stored 0 and this screen would report “Not filled in” over
 * an answer somebody deliberately gave — an artisan or designer in their first year has 0 years of
 * experience, and that is a fact about them, not an empty box. The editor keeps the two apart with a
 * blank-first option and the encoder keeps them apart with `wholeNumberOrNull`; this is the third
 * place the same rule has to hold, because it is the one a reader actually looks at.
 *
 * The words are said rather than the numbers left bare for the reason the registry gives for its own
 * `unit="years"`: a bare number in a list of names reads as an identifier.
 */
function ExperienceValue({ profile }: { profile: DesignerProfile }) {
  const parts: string[] = [];
  if (profile.experienceYears !== null && profile.experienceYears !== undefined) {
    parts.push(`${profile.experienceYears} year${profile.experienceYears === 1 ? "" : "s"}`);
  }
  if (profile.experienceMonths !== null && profile.experienceMonths !== undefined) {
    parts.push(`${profile.experienceMonths} month${profile.experienceMonths === 1 ? "" : "s"}`);
  }
  if (!parts.length) return <Blank />;
  return <span>{parts.join(" ")}</span>;
}

function Blank() {
  return <span className="text-ink-300">{BLANK}</span>;
}
