"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { forgetOfflinePages } from "@/components/OfflineAppShell";
import { startNewReadGeneration } from "@/lib/inFlight";
import { forgetMyWorkshopPosts } from "@/lib/myWorkshopPosts";
import { clearReportCache } from "@/lib/offlineReport/reportCache";
import { clearReviewTrays } from "@/lib/reviewAids";

import {
  ApiError,
  apiFetch,
  endSession,
  getSessionMarker,
  onPasswordChangeRequired,
  sessionReplacedSince,
  setSessionOwesPasswordChange
} from "@/lib/api";
import { migrateStoredToken } from "@/lib/session";
import type { loginBody } from "@/lib/oidcSignIn";
import { forgetCachedMe, meFailure, meRetryDelay, readCachedMe, withinTimeout, writeCachedMe, type MeReach } from "@/lib/meProbe";
import { mustChangePassword } from "@/lib/signIn";
import { isTwoFactorChallenge, type SignInResult, type TwoFactorChallenge } from "@/lib/twoFactor";
import type { User } from "@/lib/types";

type OidcLoginBody = ReturnType<typeof loginBody>;

type AuthContextValue = {
  user: User | null;
  loading: boolean;
  reach: MeReach;
  retryMe: () => void;
  login: (email: string, password: string) => Promise<User | TwoFactorChallenge>;
  loginWithGoogle: (googleIdToken: string) => Promise<User | TwoFactorChallenge>;
  loginWithOidc: (body: OidcLoginBody) => Promise<User | TwoFactorChallenge>;
  verifyTwoFactor: (challengeToken: string, code: string) => Promise<User>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
  passwordChangeRequired: boolean;
  markPasswordChanged: () => void;
  clearSession: () => void;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [reach, setReach] = useState<MeReach>("ok");
  const attempts = useRef(0);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const probeRef = useRef<() => Promise<void>>(async () => undefined);
  const probes = useRef(0);
  const changedAtProbe = useRef(-1);
  const gateProbe = useRef<Promise<void> | null>(null);

  const adopt = useCallback((next: User | null) => {
    setSessionOwesPasswordChange(mustChangePassword(next));
    writeCachedMe(getSessionMarker(), next);
    setUser(next);
  }, []);

  const refreshMe = useCallback(async () => {
    const probe = ++probes.current;
    const sentWith = getSessionMarker();
    try {
      const me = await withinTimeout((signal) => apiFetch<User>("/me", { signal }, { redirectOn401: false }));
      if (retryTimer.current) clearTimeout(retryTimer.current);
      retryTimer.current = null;
      attempts.current = 0;
      setReach("ok");
      adopt(probe <= changedAtProbe.current ? { ...me, mustChangePassword: false } : me);
    } catch (err) {
      setReach("ok");
      if (err instanceof ApiError && (err.status === 401 || err.status === 403) && !sessionReplacedSince(sentWith)) {
        endSession();
        adopt(null);
      } else if (meFailure(err, false) === "unreachable" && getSessionMarker()) {
        setReach("retrying");
        if (retryTimer.current) clearTimeout(retryTimer.current);
        const delay = meRetryDelay(attempts.current);
        attempts.current += 1;
        retryTimer.current = setTimeout(() => {
          retryTimer.current = null;
          void probeRef.current();
        }, delay);
      }
    } finally {
      setLoading(false);
    }
  }, [adopt]);

  useEffect(() => {
    probeRef.current = refreshMe;
  }, [refreshMe]);

  const retryMe = useCallback(() => {
    if (retryTimer.current) clearTimeout(retryTimer.current);
    retryTimer.current = null;
    attempts.current = 0;
    void refreshMe();
  }, [refreshMe]);

  useEffect(() => {
    void migrateStoredToken().finally(() => {
      const cached = readCachedMe(getSessionMarker());
      if (cached) {
        setSessionOwesPasswordChange(mustChangePassword(cached));
        setUser(cached);
        setLoading(false);
      }
      void refreshMe();
    });
    return () => {
      if (retryTimer.current) clearTimeout(retryTimer.current);
    };
  }, [refreshMe]);

  useEffect(() => {
    if (reach !== "retrying") return;
    const again = () => {
      if (document.visibilityState === "visible") retryMe();
    };
    window.addEventListener("online", again);
    document.addEventListener("visibilitychange", again);
    return () => {
      window.removeEventListener("online", again);
      document.removeEventListener("visibilitychange", again);
    };
  }, [reach, retryMe]);

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

  const open = useCallback(
    (result: SignInResult): User | TwoFactorChallenge => {
      if (isTwoFactorChallenge(result)) return result;
      adopt(result.user);
      return result.user;
    },
    [adopt]
  );

  const verifyTwoFactor = useCallback(
    async (challengeToken: string, code: string) => {
      const result = await apiFetch<SignInResult>(
        "/auth/two-factor/verify",
        { method: "POST", body: JSON.stringify({ challenge_token: challengeToken, code }) },
        { redirectOn401: false }
      );
      const account = open(result);
      if (isTwoFactorChallenge(account)) throw new Error("Sign in again.");
      return account;
    },
    [open]
  );

  const login = useCallback(
    async (email: string, password: string) => {
      return open(
        await apiFetch<SignInResult>("/auth/login", {
          method: "POST",
          body: JSON.stringify({ email, password })
        })
      );
    },
    [open]
  );

  const loginWithGoogle = useCallback(
    async (googleIdToken: string) => {
      return open(
        await apiFetch<SignInResult>("/auth/login", {
          method: "POST",
          body: JSON.stringify({ googleIdToken })
        })
      );
    },
    [open]
  );

  const loginWithOidc = useCallback(
    async (body: OidcLoginBody) => {
      return open(
        await apiFetch<SignInResult>("/auth/login", {
          method: "POST",
          body: JSON.stringify(body)
        })
      );
    },
    [open]
  );

  const logout = useCallback(async () => {
    try {
      await apiFetch("/auth/logout", { method: "POST" });
    } finally {
      endSession();
      adopt(null);
      void clearReportCache();
      forgetOfflinePages();
      clearReviewTrays();
      startNewReadGeneration();
      forgetMyWorkshopPosts();
    }
  }, [adopt]);

  const markPasswordChanged = useCallback(() => {
    changedAtProbe.current = probes.current;
    setSessionOwesPasswordChange(false);
    setUser((current) => (current ? { ...current, mustChangePassword: false } : current));
    void refreshMe();
  }, [refreshMe]);

  const clearSession = useCallback(() => {
    endSession();
    forgetCachedMe();
    adopt(null);
  }, [adopt]);

  const passwordChangeRequired = mustChangePassword(user);

  const value = useMemo(
    () => ({
      user,
      loading,
      reach,
      retryMe,
      login,
      loginWithGoogle,
      loginWithOidc,
      verifyTwoFactor,
      logout,
      refreshMe,
      passwordChangeRequired,
      markPasswordChanged,
      clearSession
    }),
    [user, loading, reach, retryMe, login, loginWithGoogle, loginWithOidc, verifyTwoFactor, logout, refreshMe, passwordChangeRequired, markPasswordChanged, clearSession]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}
