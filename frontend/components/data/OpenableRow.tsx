"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { HTMLAttributes, MouseEvent, ReactNode } from "react";

import { ROW_OWN_CLICK_SELECTOR, rowClickIntent } from "@/lib/recordView";

export function useOpenOnClick(href: string | null | undefined, external = false, onOpen?: () => void) {
  const router = useRouter();

  function decide(event: MouseEvent<HTMLElement>) {
    const surface = event.currentTarget;
    const target = event.target instanceof Element ? event.target : null;
    const own = target?.closest(ROW_OWN_CLICK_SELECTOR);
    let selected: string | null = null;
    try {
      selected = window.getSelection()?.toString() ?? null;
    } catch {
      selected = null;
    }
    return rowClickIntent({
      button: event.button,
      ctrlKey: event.ctrlKey,
      metaKey: event.metaKey,
      shiftKey: event.shiftKey,
      insideRow: !!target && surface.contains(target),
      onOwnControl: !!own && surface.contains(own),
      selectedText: selected
    });
  }

  function follow(event: MouseEvent<HTMLElement>) {
    if (!href && !onOpen) return;
    const intent = decide(event);
    if (intent === "ignore") return;
    if (!href) {
      if (event.button === 0) onOpen?.();
      return;
    }
    if (intent === "open-new-tab" || external) window.open(href, "_blank", "noopener");
    else router.push(href);
  }

  return {
    onClick: follow,
    onAuxClick: (event: MouseEvent<HTMLElement>) => {
      if (event.button === 1) follow(event);
    }
  };
}

export function OpenableRow({
  href,
  onOpen,
  className = "",
  children,
  ...rest
}: { href?: string; onOpen?: () => void; className?: string; children: ReactNode } & Omit<
  HTMLAttributes<HTMLTableRowElement>,
  "onClick"
>) {
  const handlers = useOpenOnClick(href, false, onOpen);
  return (
    <tr
      {...rest}
      data-openable-row=""
      {...handlers}
      className={`cursor-pointer transition-colors hover:bg-field-100 ${className}`.trim()}
    >
      {children}
    </tr>
  );
}

export function OpenableCard({
  href,
  onOpen,
  external = false,
  as: Tag = "div",
  className = "",
  children
}: {
  href: string | null | undefined;
  onOpen?: () => void;
  external?: boolean;
  as?: "div" | "li" | "article";
  className?: string;
  children: ReactNode;
}) {
  const handlers = useOpenOnClick(href, external, onOpen);
  if (!href && !onOpen) return <Tag className={className}>{children}</Tag>;
  return (
    <Tag data-openable-row="" {...handlers} className={`cursor-pointer transition-colors hover:bg-field-100 ${className}`.trim()}>
      {children}
    </Tag>
  );
}

export function OpenableRowLink({ href, children, className = "" }: { href: string; children: ReactNode; className?: string }) {
  return (
    <Link href={href} className={`rounded-sm font-medium text-ink-900 underline-offset-2 hover:underline ${className}`.trim()}>
      {children}
    </Link>
  );
}
