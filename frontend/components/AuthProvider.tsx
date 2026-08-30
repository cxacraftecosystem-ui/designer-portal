"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { ApiError, apiFetch, setToken } from "@/lib/api";
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
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  const refreshMe = useCallback(async () => {
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
      setUser(me);
    } catch (err) {
      // Only discard the stored token when the server explicitly rejected it. On network
      // failures / 5xx the token may still be valid, so keep it for a later retry.
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        setToken(null);
      }
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refreshMe();
  }, [refreshMe]);

  const login = useCallback(async (email: string, password: string) => {
    const result = await apiFetch<{ accessToken: string; user: User }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password })
    });
    setToken(result.accessToken);
    setUser(result.user);
    return result.user;
  }, []);

  const loginWithGoogle = useCallback(async (googleIdToken: string) => {
    const result = await apiFetch<{ accessToken: string; user: User }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ googleIdToken })
    });
    setToken(result.accessToken);
    setUser(result.user);
    return result.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await apiFetch("/auth/logout", { method: "POST" });
    } finally {
      setToken(null);
      setUser(null);
    }
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, loginWithGoogle, logout, refreshMe }),
    [user, loading, login, loginWithGoogle, logout, refreshMe]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}
