import { Children, Fragment, isValidElement, type ReactNode } from "react";

import { actionGroupClass, type ActionGroupAlign } from "@/components/ui/actionGroupLayout";

/**
 * The shared button row. Every group of buttons on a screen goes through this, so the rule in
 * `actionGroupLayout.ts` (one centred, two on one line, three or more stacked on a phone and pills
 * above it) is decided once.
 *
 * Mark a destructive child with `data-danger` and it is drawn last, set apart.
 */
export function ActionGroup({
  children,
  align = "center",
  fill = false,
  className,
  ref,
  ...rest
}: {
  children: ReactNode;
  /** Where a group of two or more sits from `sm` up. A single button is always centred. */
  align?: ActionGroupAlign;
  /** A lone primary action that should span the line on a phone. */
  fill?: boolean;
  className?: string;
  ref?: React.Ref<HTMLDivElement>;
} & Omit<React.HTMLAttributes<HTMLDivElement>, "children" | "className">) {
  const count = countActions(children);
  if (count === 0) return null;
  const layout = actionGroupClass(count, align, fill);
  return (
    <div ref={ref} className={className ? `${layout} ${className}` : layout} {...rest}>
      {children}
    </div>
  );
}

/** Counts rendered children, looking through fragments; null, false and "" are not actions. */
export function countActions(children: ReactNode): number {
  let count = 0;
  Children.forEach(children, (child) => {
    if (child === null || child === undefined || typeof child === "boolean" || child === "") return;
    if (isValidElement<{ children?: ReactNode }>(child) && child.type === Fragment) {
      count += countActions(child.props.children);
      return;
    }
    count += 1;
  });
  return count;
}
