"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import type { LucideIcon } from "lucide-react";

import { GlassSurface, GLASS_TILE } from "@/components/ui/GlassSurface";

export function EntryPointCard({
  label,
  description,
  icon: Icon,
  href,
  actionLabel = "Open"
}: {
  label: string;
  description: string;
  icon: LucideIcon;
  href: string;
  actionLabel?: string;
}) {
  return (
    <GlassSurface
      options={GLASS_TILE}
      className="flex flex-col gap-2 rounded-lg border border-line-200 bg-card/70 p-3 shadow-sm"
    >
      <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
        <Icon className="h-5 w-5 text-white" aria-hidden />
      </div>
      <div className="font-display text-base font-bold leading-snug text-ink-900">{label}</div>
      <p className="text-xs leading-5 text-ink-500">{description}</p>
      <div className="mt-auto flex flex-col gap-1.5 pt-1">
        <Link className="field-button h-9 min-h-0 px-3 text-xs" href={href}>
          <ArrowRight className="h-3.5 w-3.5" aria-hidden />
          {actionLabel}
        </Link>
      </div>
    </GlassSurface>
  );
}
