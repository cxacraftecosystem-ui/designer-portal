import { AppShell } from "@/components/AppShell";
import { DesignerProfileOnboarding } from "@/components/designers/DesignerProfileOnboarding";
import { DesignWorkshopDraftBanner } from "@/components/designworkshop/DraftSyncBanner";
import { AppUpdateWatcher } from "@/components/dialogs/AppUpdateDialog";
import { ConfirmProvider } from "@/components/dialogs/ConfirmDialog";
import { OfflineWatcher } from "@/components/dialogs/OfflineDialog";
import { OutboxBanner } from "@/components/OutboxBanner";
import { UnsavedChangesProvider } from "@/components/UnsavedChangesGuard";

/**
 * Every protected page renders inside AppShell, which owns the three things that must apply to all
 * of them: the sign-in redirect, the route guards declared in lib/permissions.ts (ROUTE_GUARDS), and
 * the first-login password gate. Enforcing access here rather than page by page is why a direct URL
 * to /users, /review, /data or a create form cannot render content the API would refuse.
 *
 * THE THIRD ONE IS NEW AND IS NOT A ROUTE RULE, which is why it is worth naming here. `ROUTE_GUARDS`
 * answers "may this person open THIS page"; `mustChangePassword` answers "may this person use the
 * product at all" while their account still holds a password an administrator typed. AppShell
 * returns a full surface for it ABOVE the island and above both guards — read the comment beside the
 * branch before moving anything near it, and note that `/set-password`, the one route somebody who
 * does not know that password can use, is deliberately not in this tree.
 *
 * It is also where the app-wide dialogs are mounted, once each — same reasoning as the single
 * ToastProvider in the root layout:
 *
 * - `ConfirmProvider` gives every page below it `useConfirm()`, the themed replacement for
 *   `window.confirm()`. A second, nested provider would shadow this one and open its dialog behind
 *   whatever the outer one had already put on screen, so pages must never mount their own.
 * - `AppUpdateWatcher` raises the non-dismissable "Update required" prompt when this tab's build has
 *   gone stale across a deploy — the web's version of the Android required-update dialog.
 * - `OfflineWatcher` announces a lost connection the moment it drops, so a researcher does not fill
 *   in a long form before discovering it at the Save button.
 * - `OutboxBanner` is the other half of that: saves made offline go to the IndexedDB outbox
 *   (`lib/offline`), and this is where they are visible and where they drain. It renders inside the
 *   shell, above the page, because an outbox nobody can see is worse than no outbox — the researcher
 *   believes the record is filed when it is sitting in one laptop's browser storage.
 * - `DesignWorkshopDraftBanner` is the same guarantee for the local design-workshop store
 *   (`lib/designWorkshopStore`), which holds whole 22-stage documents rather than queued requests.
 *   It sits here rather than on the three design-workshop routes because a designer who captured a
 *   stage in a courtyard and then went to look at the artisan list must still be able to see that
 *   the workshop has not reached the repository — a warning that only exists on the page you are
 *   already worried about is a warning nobody reads in time.
 *
 * - `DesignerProfileOnboarding` takes a designer signing in for the first time to their profile
 *   page, from a landing route only — once per session where `sessionStorage` answers, and once
 *   per loaded page where the browser refuses it, which is the most that component can promise. It renders nothing and it is here for the
 *   same reason the four above are: it is a signed-in concern that has to be able to fire whichever
 *   protected page the session happens to open on. Read its header before touching it — a forced
 *   navigation is the most intrusive thing this client does to anybody, and the four rules that keep
 *   it from becoming a trap are all written down there.
 *
 * All six live here rather than in the root layout because they are for signed-in work: the login
 * and landing pages have nothing to confirm, nothing to save offline, and no lazy chunks to fail on.
 *
 * `UnsavedChangesProvider` wraps them because it has to sit above BOTH halves of a form page: the
 * `PageHeader` with its round back control, and the form itself, which are siblings. It is what lets
 * the one back control raise the form's "Unsaved changes" prompt, and so what lets the forms drop
 * the second, rounded "Back" pill they used to carry for that purpose alone.
 */
export default function ProtectedLayout({ children }: { children: React.ReactNode }) {
  return (
    <ConfirmProvider>
      <UnsavedChangesProvider>
        <AppShell>
          <OutboxBanner />
          <DesignWorkshopDraftBanner />
          {children}
        </AppShell>
      </UnsavedChangesProvider>
      <AppUpdateWatcher />
      <OfflineWatcher />
      <DesignerProfileOnboarding />
    </ConfirmProvider>
  );
}
