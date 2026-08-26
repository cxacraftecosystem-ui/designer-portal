"use client";

/**
 * ANOTHER designer's profile: read-only for anyone who may see it, editable by the owner and by
 * admins, and by nobody else.
 *
 * THE EDITOR IS NOT RENDERED FOR SOMEBODY WHO MAY NOT WRITE, and that distinction is the whole
 * point of this file. Hiding a Save button is not a permission — the boxes underneath it would
 * still be typeable, the request is still one `fetch` away, and a reader who has typed a paragraph
 * into a form that cannot save it has been lied to twice. So the branch is between two different
 * components: `DesignerProfileForm`, which owns the only PUT in this feature, or
 * `DesignerProfileView`, which owns no request at all. Nothing on the read-only side can be coaxed
 * into writing, because there is nothing there to coax.
 *
 * The server draws the same line and draws it harder: `_assert_may_touch_profile` refuses a
 * non-owner non-admin on both the GET and the PUT. This page mirrors that rule; it does not replace
 * it. What is at stake is not tidiness — the text here is printed verbatim in a report submitted to
 * a ministry under the profile owner's name, so one designer editing another's copy is one designer
 * putting words in another's mouth on a signed document.
 *
 * **A 404 HERE MEANS TWO THINGS AND MUST KEEP MEANING BOTH.** The API answers 404 — never 403 —
 * with an identical detail string for "no such user" and "not a profile you may see", because a 403
 * would confirm that a cuid belongs to a real account and let somebody enumerate the staff by
 * watching which ids answer which. The copy below therefore says "no such designer, or not one this
 * account may see" rather than picking one; saying more would leak exactly what the status code is
 * there to hide.
 */

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { IdCard } from "lucide-react";

import { DesignerProfileForm } from "@/components/designers/DesignerProfileForm";
import { DesignerProfileView } from "@/components/designers/DesignerProfileView";
import { DESIGNER_PROFILE_COPY_NOTICE } from "@/components/designers/profileCopy";
import { PageHeader } from "@/components/PageHeader";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { useAuth } from "@/components/AuthProvider";
import { ApiError, apiFetch } from "@/lib/api";
import {
  designerProfileIsEmpty,
  getDesignerProfile,
  saveDesignerProfile,
  type DesignerProfile
} from "@/lib/designers";
import { isAdmin, roleLabel } from "@/lib/permissions";

/** `GET /users/directory` — id, name, email and role, readable by any authenticated account. */
type DirectoryUser = { id: string; name: string; email: string; role: string };

export default function DesignerProfilePage({ params }: { params: Promise<{ userId: string }> }) {
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
  const { userId } = use(params);
  const { user } = useAuth();

  const [profile, setProfile] = useState<DesignerProfile | null>(null);
  const [account, setAccount] = useState<DirectoryUser | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

  const isOwner = Boolean(user?.id) && user?.id === userId;
  /**
   * Mirrors `_assert_may_touch_profile` exactly: the owner, or an admin. Read off the
   * server-issued role on every render, so a stale localStorage value cannot widen it — and it
   * decides which COMPONENT renders, not which button is visible.
   */
  const mayEdit = isOwner || isAdmin(user);

  useEffect(() => {
    let cancelled = false;
    setNotFound(false);
    setError(null);
    // Cleared, not left standing, when the id changes. Next reuses this component across a param
    // change, so without this the previous designer's twenty-one values sit on screen under the new
    // one's heading for the length of the fetch — a profile attributed to the wrong person, which
    // on a screen whose whole subject is "who signs this report" is the worst possible stale read.
    setProfile(null);
    setAccount(null);
    getDesignerProfile(userId)
      .then((loaded) => {
        if (!cancelled) setProfile(loaded);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 404) {
          setNotFound(true);
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this designer profile");
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  /**
   * The account behind the id, so the page can say whose profile this is.
   *
   * A separate, best-effort request: the profile payload carries no name (it is twenty-one columns,
   * two identifiers and two timestamps), and a page headed "Designer profile" with no name on it leaves an admin
   * unable to tell whether they opened the right person. `/users/directory` is open to any signed-in
   * account and answers with nothing but id, name, email and role. A failure here is silent — the
   * profile is what the page is for, and losing the heading's subtitle must not lose the page.
   */
  useEffect(() => {
    let cancelled = false;
    apiFetch<DirectoryUser[]>("/users/directory")
      .then((rows) => {
        if (!cancelled) setAccount(rows.find((row) => row.id === userId) ?? null);
      })
      .catch(() => {
        /* the heading falls back to the profile's own display name */
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  const displayName = account?.name || profile?.displayName || "This designer";
  const subtitle = account
    ? `${account.email} · ${roleLabel(account.role)}`
    : profile?.institution ?? null;

  return (
    <>
      <PageHeader
        title={notFound ? "Designer profile" : `${displayName}’s designer profile`}
        description={
          notFound
            ? undefined
            : mayEdit
              ? "The details every report this designer generates is signed with. Editable by them and by admins — and by nobody else, because this text is printed under their name."
              : "The details every report this designer generates is signed with. Read only from this account."
        }
        icon={<IdCard className="h-5 w-5" aria-hidden />}
        actions={
          isOwner ? (
            <Link href="/designers/profile" className="field-button-secondary">
              My designer profile
            </Link>
          ) : null
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {notFound ? (
        <RestrictedPanel
          title="No profile to show"
          body={
            "There is no designer with that id, or it is not one this account may see — the API answers the same way to both, on purpose, " +
            "so that a list of ids cannot be used to work out who works here. If you were sent this link by a colleague, ask an admin to open it."
          }
        />
      ) : profile === null ? (
        !error ? <section className="panel p-4 text-sm text-ink-700">Loading this profile…</section> : null
      ) : mayEdit ? (
        <>
          {subtitle ? <p className="mb-4 text-sm text-ink-muted">{subtitle}</p> : null}
          <DesignerProfileForm
            // Keyed on the account, so moving from one designer to another REMOUNTS the editor. The
            // form is uncontrolled — its boxes are seeded from `defaultValue` once — so without this
            // a navigation between two profiles would leave the first designer's text in the second
            // designer's form, and saving it would write one person's biography onto another's
            // report. The fetch above already clears `profile`, and this is the second half of the
            // same guarantee.
            key={profile.userId}
            profile={profile}
            save={(body) => saveDesignerProfile(userId, body)}
            onSaved={setProfile}
            possessive={isOwner ? "my" : "this"}
          />
        </>
      ) : (
        <>
          {subtitle ? <p className="mb-4 text-sm text-ink-muted">{subtitle}</p> : null}
          <p className="mb-5 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-muted">
            {/* Say WHY it is read-only rather than leaving a page that merely has no controls: a
                screen with nothing to press reads as broken, and the reason here is a rule worth
                stating — the profile is the wording a report is signed with. */}
            Only {displayName} and an admin may change this profile: it is the wording their reports are signed with, so it is
            theirs to write. {DESIGNER_PROFILE_COPY_NOTICE}
          </p>
          {designerProfileIsEmpty(profile) ? (
            <section className="panel p-4 text-sm leading-6 text-ink-700">
              {/* An empty profile is a real, actionable state — the cover of this designer's next
                  report will carry a blank "Designer:" line — so it is named rather than drawn as a
                  wall of "Not filled in" rows that look like a rendering fault. NO COUNT: it is not
                  the profile's twenty-one, because `DesignerProfileView` draws `cvMediaId` through
                  `DocumentPreview` — which carries its own "No CV on file." — before the blank test
                  the other twenty fall through. This clause said "twenty-one" for a few minutes on
                  2026-08-26, renumbered by a sweep that was right about every other sentence. */}
              {displayName} has not filled in a designer profile yet. Until they do, a workshop they start is created with
              nothing but the name on their account.
            </section>
          ) : (
            <DesignerProfileView profile={profile} />
          )}
        </>
      )}
    </>
  );
}
