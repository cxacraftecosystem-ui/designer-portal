"use client";

import { plainFromStoredRichText } from "@/components/richtext/storedRichText";
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
import { RequiredMark } from "@/components/ui/RequiredMark";

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
        {group.fields
          .filter((field) => field !== "experienceMonths")
          .map((field) => (
          <div key={field} className={wide(field) ? "min-w-0 md:col-span-2" : "min-w-0"}>
            <dt className="field-label">
              {DESIGNER_PROFILE_LABELS[field]}
              <RequiredMark when={isDesignerProfileFieldRequired(field)} />
            </dt>
            <dd className="mt-1 text-sm leading-6 text-ink-900">
              <FieldValue field={field} profile={profile} />
            </dd>
          </div>
        ))}
      </dl>
      {group.key === "address" ? <LocationRecord location={profile.location} /> : null}
    </section>
  );
}

function LocationRecord({ location }: { location: DesignerProfileLocation | null }) {
  return (
    <div className="mt-5 border-t border-line-200 pt-4">
      <h3 className="field-label">Location record</h3>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        District, village and map point.
      </p>
      <dl className="mt-3 grid gap-4 md:grid-cols-2">
        <Row label="State">{location?.state}</Row>
        <Row label="District">{location?.district}</Row>
        <Row label="Village">{location?.village}</Row>
        <Row label="Pincode">{location?.pincode}</Row>
        <Row label="Map point">{coordinateText(location?.subjectLatitude, location?.subjectLongitude)}</Row>
        <Row label="Device fix, and when it was taken">
          {coordinateText(location?.latitude, location?.longitude, location?.capturedAt)}
        </Row>
      </dl>
    </div>
  );
}

function Row({ label, children }: { label: string; children?: string | null }) {
  return (
    <div className="min-w-0">
      <dt className="field-label">{label}</dt>
      <dd className="mt-1 text-sm leading-6 text-ink-900">
        {children && children.trim() ? <span className="wrap-break-word">{children}</span> : <Blank />}
      </dd>
    </div>
  );
}

function coordinateText(
  latitude: number | null | undefined,
  longitude: number | null | undefined,
  capturedAt?: string | null
): string | null {
  if (typeof latitude !== "number" || typeof longitude !== "number") return null;
  const point = `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`;
  return capturedAt ? `${point} · ${formatDate(capturedAt)}` : point;
}

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
        className={field === "photoMediaId" ? "h-32 w-32" : "h-20 w-56"}
      />
    );
  }

  if (field === "cvMediaId") {
    return <DocumentPreview mediaId={typeof raw === "string" ? raw : null} noun="CV" className="h-104" />;
  }

  if (field === "experienceYears") return <ExperienceValue profile={profile} />;

  if (raw === null || raw === undefined || (typeof raw === "string" && !raw.trim())) return <Blank />;

  if (field === "empanelmentDate") return <span>{formatDate(String(raw))}</span>;

  if (field === "biography") {
    return <p className="whitespace-pre-line">{String(raw)}</p>;
  }

  if (field === "addressLine") {
    return <p className="whitespace-pre-line wrap-break-word">{plainFromStoredRichText(String(raw))}</p>;
  }

  if (field === "website") {
    const href = /^https?:\/\//i.test(String(raw)) ? String(raw) : `https://${String(raw)}`;
    return (
      <a
        href={href}
        target="_blank"
        rel="noreferrer"
        className="wrap-break-word text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline"
      >
        {String(raw)}
      </a>
    );
  }

  if (field === "email") {
    return (
      <a href={`mailto:${String(raw)}`} className="wrap-break-word text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
        {String(raw)}
      </a>
    );
  }

  return <span className="wrap-break-word">{String(raw)}</span>;
}

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
  return <span className="text-ink-500">{BLANK}</span>;
}
