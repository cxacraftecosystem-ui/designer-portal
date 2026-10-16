"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { HTMLAttributes, MouseEvent, ReactNode } from "react";

import { ROW_OWN_CLICK_SELECTOR, rowClickIntent } from "@/lib/recordView";

/**
 * The click half shared by the row and the card: decide (`rowClickIntent`), then follow. `external`
 * is for a destination outside the app (a stored file's own URL), which always opens in a new tab.
 * `onOpen` is for a record whose read-only view is a dialog on the same page rather than a route
 * (an account on /users): a press anywhere opens it, and there is no tab to open it in.
 */
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
      // A control counts only when it sits INSIDE this surface; `closest` can walk past it to a link
      // the whole list is wrapped in, which is not this row's control.
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
    // Middle click never fires `click`; `auxclick` is where it arrives.
    onAuxClick: (event: MouseEvent<HTMLElement>) => {
      if (event.button === 1) follow(event);
    }
  };
}

/**
 * A list row whose whole surface opens the record it shows — the owner's ruling of 2026-10-16 that
 * a record opens READ-ONLY from anywhere on its card, and editing is the separate "Update" button.
 *
 * WHY A ROW CLICK AND A LINK, AND NOT ONE STRETCHED LINK. On a card the usual move is one link whose
 * `::after` covers the card. On a `<tr>` that does not work: a table row is not a containing block
 * in every engine, so the overlay escapes to the nearest positioned ancestor and swallows clicks
 * across the whole table. Giving the `<tr>` itself `role="link"` is worse — it destroys the row role
 * and orphans every cell for a screen reader. So the row carries a pointer handler, and the record's
 * NAME in the first cell is a real `<Link>` (`OpenableRowLink`) — the thing Tab lands on, Enter
 * follows, a screen reader announces, and a middle click or Ctrl-click opens in a new tab. The row
 * handler does what that link does for a press anywhere else on the row.
 *
 * THE ROW'S OWN CONTROLS KEEP THEIR CLICKS without each one remembering `stopPropagation`: the
 * decision in `rowClickIntent` looks for `ROW_OWN_CLICK_SELECTOR` between the target and the row, so
 * Update, Discuss, Delete, a preview tile or a status menu added next year all work as themselves.
 */
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

/**
 * The same thing for a record drawn as a CARD (a `<div>` or `<li>`): the whole card opens the record,
 * a real link inside it is the keyboard route, and the card's own buttons keep their clicks. A card
 * with no destination (`href` null) is drawn plainly — never a pointer over something that does
 * nothing.
 */
export function OpenableCard({
  href,
  external = false,
  as: Tag = "div",
  className = "",
  children
}: {
  href: string | null | undefined;
  external?: boolean;
  as?: "div" | "li" | "article";
  className?: string;
  children: ReactNode;
}) {
  const handlers = useOpenOnClick(href, external);
  if (!href) return <Tag className={className}>{children}</Tag>;
  return (
    <Tag data-openable-row="" {...handlers} className={`cursor-pointer transition-colors hover:bg-field-100 ${className}`.trim()}>
      {children}
    </Tag>
  );
}

/**
 * The real link in an openable row's first cell — the record's name. It is the row's keyboard and
 * screen-reader route, so it carries the visible focus ring the global `:focus-visible` rule draws.
 */
export function OpenableRowLink({ href, children, className = "" }: { href: string; children: ReactNode; className?: string }) {
  return (
    <Link href={href} className={`rounded-sm font-medium text-ink-900 underline-offset-2 hover:underline ${className}`.trim()}>
      {children}
    </Link>
  );
}
