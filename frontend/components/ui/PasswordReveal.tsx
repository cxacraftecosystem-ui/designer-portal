"use client";

import { Eye, EyeOff } from "lucide-react";

import { cn } from "@/lib/utils";

export function PasswordRevealButton({
  revealed,
  onToggle,
  className,
  size = 22,
  noun = "password"
}: {
  revealed: boolean;
  onToggle: () => void;
  className?: string;
  size?: number;
  noun?: string;
}) {
  return (
    <button
      type="button"
      aria-label={revealed ? `Hide ${noun}` : `Show ${noun}`}
      aria-pressed={revealed}
      onClick={onToggle}
      className={cn(
        "absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-ink-300 transition hover:text-ink-500",
        className
      )}
    >
      {revealed ? <EyeOff size={size} aria-hidden /> : <Eye size={size} aria-hidden />}
    </button>
  );
}
