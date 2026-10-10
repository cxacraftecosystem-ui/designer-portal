"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { forgetOfflinePages } from "@/components/OfflineAppShell";
import { clearReportCache } from "@/lib/offlineReport/reportCache";

import {
  ApiError,
  apiFetch,
  getToken,
  onPasswordChangeRequired,
  sessionReplacedSince,
  setSessionOwesPasswordChange,
  setToken
} from "@/lib/api";
import { mustChangePassword } from "@/lib/signIn";
import type { User } from "@/lib/types";

type AuthContextValue = {
  user: User | null;
  loading: boolean;
  /**
   * BOTH SIGN-IN CALLS RETURN THE ACCOUNT, and that return value is not a convenience.
   *
   * `serialize_user` carries `usageConsentGate` on every sign-in answer, and /login must branch on
   * it — record the tick, or hold a standing refusal on screen — BEFORE it navigates. Reading it out
   * of `user` instead would mean waiting a render for context state to settle, during which /login's
   * own "already signed in" effect has already fired and replaced the route. The row is in hand the
   * moment the request resolves; handing it back is what lets the caller decide in the same tick.
   */
  login: (email: string, password: string) => Promise<User>;
  loginWithGoogle: (googleIdToken: string) => Promise<User>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
  /**
   * The signed-in account owes a new password, so the server refuses everything but the gate's own
   * routes. `mustChangePassword(user)`, read once here for the components that sit OUTSIDE `AppShell`
   * (`DesignerProfileOnboarding`, `OfflineWatcher`): a request they make while this is true is a
   * request the server will refuse, and a notice they raise is drawn over the gate.
   */
  passwordChangeRequired: boolean;
  /**
   * THE ONE LATCH, shared by both hosts of the gate — `/login` and `AppShell`.
   *
   * Called the moment `POST /auth/change-password` has succeeded. It clears the flag on the account
   * this provider holds, so whichever screen is next reads an account that no longer owes anything —
   * there is no second, page-local copy for a navigation to leave behind — and then re-reads `/me`.
   * See the implementation for why a re-read already in flight cannot put the flag back, while one
   * that starts afterwards can (an administrator may raise it again, and must be obeyed).
   */
  markPasswordChanged: () => void;
  /**
   * Forget this tab's session without asking the server anything: the token and the account, gone.
   *
   * For the moment the server has ALREADY ended the account's sessions — `/set-password` after a
   * redemption, which stamps `sessionsValidFrom` — so the token held here is dead and the account
   * held here is stale. `logout()` would spend a request on `POST /auth/logout`, which keeps no
   * server state, to be refused for a token that no longer counts; this is the local half alone.
   */
  clearSession: () => void;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  /** Every `/me` this provider has sent, counted. See `markPasswordChanged`. */
  const probes = useRef(0);
  /** `probes` at the moment this tab last replaced its password; -1 before it ever has. */
  const changedAtProbe = useRef(-1);
  /** The `/me` a gated refusal started, while it is in flight — so twenty refusals cost one re-read. */
  const gateProbe = useRef<Promise<void> | null>(null);

  /**
   * One door for the account this provider holds, so the module-level flag the background drains
   * read (`sessionOwesPasswordChange` in `lib/api.ts`) can never disagree with `user`.
   */
  const adopt = useCallback((next: User | null) => {
    setSessionOwesPasswordChange(mustChangePassword(next));
    setUser(next);
  }, []);

  const refreshMe = useCallback(async () => {
    const probe = ++probes.current;
    /** The token this re-read goes out with — see the catch. */
    const sentWith = getToken();
    try {
      /*
        `redirectOn401: false` is load-bearing, not tidiness.

        This provider is mounted in the ROOT layout, above the public landing page and /login as well
        as the protected tree, and the effect below runs this probe on every single page load. Left
        on the default, a visitor holding an EXPIRED token in localStorage — the returning designer
        who last signed in weeks ago — sends it, gets a 401, and `apiFetch` hard-navigates her off
        the public home page to a sign-in form she never asked for, mid-read. Audit 2026-08-15
        (MINOR, frontend) filed exactly that.

        Nothing is lost by opting out, because this function already handles the 401 completely: it
        clears the dead token in the catch below and sets `user` to null, and `AppShell` turns that
        into a soft `router.replace("/login")` FOR PROTECTED ROUTES ONLY. That is the routing
        decision the app already makes correctly; `apiFetch`'s blanket one only ever duplicated it
        on protected pages and got it wrong on public ones.

        If you delete this argument, the landing page starts bouncing returning visitors again.
      */
      const me = await apiFetch<User>("/me", {}, { redirectOn401: false });
      /*
        A RE-READ SENT BEFORE THIS TAB REPLACED ITS PASSWORD MAY NOT PUT THE FLAG BACK.

        It can have been answered before the change landed — the re-read a gated refusal starts is
        typically still in flight while the person is typing the new password — and adopting its
        `mustChangePassword: true` would re-lock somebody the second after they complied and then
        tell them the current password they had just replaced was wrong. Everything else on it is
        as true as it ever was, so only that one field is overruled. A re-read sent AFTER the change
        is the server's word, flag and all: an administrator who raises it again is obeyed at this
        tab's next `/me`, which is the whole point of the gate reading the live account.
      */
      adopt(probe <= changedAtProbe.current ? { ...me, mustChangePassword: false } : me);
    } catch (err) {
      // Only discard the stored token when the server explicitly rejected it. On network
      // failures / 5xx the token may still be valid, so keep it for a later retry.
      //
      // AND ONLY THE TOKEN IT REJECTED. A re-read sent before this tab changed its password goes out
      // with the token the change retired; answered after the fresh one was adopted, its 401 is about a
      // session already let go of, and ending the account here would sign the person out the moment
      // they complied (`sessionReplacedSince`, which `apiFetch` asks too).
      if (err instanceof ApiError && (err.status === 401 || err.status === 403) && !sessionReplacedSince(sentWith)) {
        setToken(null);
        adopt(null);
      }
      // AND KEEP THE ACCOUNT ON THOSE TOO. This used to null `user` while keeping the token, and a
      // dropped connection during a re-read is an ordinary event on the connections this product is
      // used over: a person holding a valid token was sent to the sign-in form. On the first probe
      // there is no account yet, so nothing changes there.
    } finally {
      setLoading(false);
    }
  }, [adopt]);

  useEffect(() => {
    refreshMe();
  }, [refreshMe]);

  /*
    A REQUEST REFUSED BY THE PASSWORD GATE MEANS THE ACCOUNT THIS TAB HOLDS IS OUT OF DATE: an
    administrator raised the flag on a session that was already open, or a page load raced the
    sign-in that set it. `/me` is on the server's allow-list, so re-reading it brings the flag home
    and `AppShell` draws the form in place of the page — no navigation, no second sign-in.

    One re-read at a time: a page that fires six requests gets six refusals and one `/me`. That is
    also what stops a loop, if `/me` itself were ever refused this way: the refusal arrives while its
    own re-read is still the one in flight.
  */
  useEffect(
    () =>
      onPasswordChangeRequired(() => {
        if (gateProbe.current) return;
        gateProbe.current = refreshMe().finally(() => {
          gateProbe.current = null;
        });
      }),
    [refreshMe]
  );

  const login = useCallback(
    async (email: string, password: string) => {
      const result = await apiFetch<{ accessToken: string; user: User }>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password })
      });
      setToken(result.accessToken);
      adopt(result.user);
      return result.user;
    },
    [adopt]
  );

  const loginWithGoogle = useCallback(
    async (googleIdToken: string) => {
      const result = await apiFetch<{ accessToken: string; user: User }>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ googleIdToken })
      });
      setToken(result.accessToken);
      adopt(result.user);
      return result.user;
    },
    [adopt]
  );

  const logout = useCallback(async () => {
    try {
      await apiFetch("/auth/logout", { method: "POST" });
    } finally {
      setToken(null);
      adopt(null);
      // The copies kept for a report built with no connection are this account's; a shared laptop
      // must not hand them to the next person who signs in. Drafts are NOT touched — see
      // `setDraftSessionUser`.
      void clearReportCache();
      forgetOfflinePages();
    }
  }, [adopt]);

  const markPasswordChanged = useCallback(() => {
    // Every `/me` sent up to now was sent before the change, and may say so — see `refreshMe`.
    changedAtProbe.current = probes.current;
    setSessionOwesPasswordChange(false);
    setUser((current) => (current ? { ...current, mustChangePassword: false } : current));
    // Best-effort: the account is already right where it matters, and this only refreshes the rest
    // of it (`passwordSetAt` among others). `refreshMe` settles every failure itself. It is sent with
    // the token the change answered with: the gate adopts that before calling here, because the
    // change retired the one it was sent with (`changeOwnPassword`).
    void refreshMe();
  }, [refreshMe]);

  const clearSession = useCallback(() => {
    setToken(null);
    adopt(null);
  }, [adopt]);

  const passwordChangeRequired = mustChangePassword(user);

  const value = useMemo(
    () => ({
      user,
      loading,
      login,
      loginWithGoogle,
      logout,
      refreshMe,
      passwordChangeRequired,
      markPasswordChanged,
      clearSession
    }),
    [user, loading, login, loginWithGoogle, logout, refreshMe, passwordChangeRequired, markPasswordChanged, clearSession]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}
