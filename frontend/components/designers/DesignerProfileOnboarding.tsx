"use client";

import { useEffect, useRef } from "react";
import { usePathname, useRouter } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { designerProfileIsEmpty, getMyDesignerProfile } from "@/lib/designers";

const PROFILE_PATH = "/designers/profile";

const LANDING_PATHS = new Set(["/dashboard"]);

function markKey(userId: string): string {
  return `field_repo_designer_onboarding:${userId}`;
}

const handled = new Set<string>();

function alreadyHandled(userId: string): boolean {
  if (handled.has(userId)) return true;
  try {
    return window.sessionStorage.getItem(markKey(userId)) === "1";
  } catch {
    return false;
  }
}

function markHandled(userId: string): void {
  handled.add(userId);
  try {
    window.sessionStorage.setItem(markKey(userId), "1");
  } catch {
  }
}

export function DesignerProfileOnboarding() {
  const { user, passwordChangeRequired } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const checking = useRef(false);

  useEffect(() => {
    if (!user) return;
    if (passwordChangeRequired) return;
    if (user.role !== "DESIGNER") return;
    if (!LANDING_PATHS.has(pathname) || pathname === PROFILE_PATH) return;
    if (alreadyHandled(user.id)) return;
    if (checking.current) return;

    checking.current = true;
    let cancelled = false;
    let settled = false;

    getMyDesignerProfile()
      .then((profile) => {
        if (cancelled) return;
        markHandled(user.id);
        if (designerProfileIsEmpty(profile)) {
          router.replace(`${PROFILE_PATH}?welcome=1`);
        }
      })
      .catch(() => {
      })
      .finally(() => {
        settled = true;
        checking.current = false;
      });

    return () => {
      cancelled = true;
      if (!settled) checking.current = false;
    };
  }, [user, passwordChangeRequired, pathname, router]);

  return null;
}
