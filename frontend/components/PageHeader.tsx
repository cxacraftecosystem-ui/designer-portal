import { BackButton } from "@/components/BackButton";

export function PageHeader({
  title,
  description,
  actions,
  icon,
  back = true
}: {
  title: string;
  description?: string;
  actions?: React.ReactNode;
  icon?: React.ReactNode;
  /** Round back control before the title; the dashboard opts out with back={false}. */
  back?: boolean;
}) {
  return (
    <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="flex items-start gap-3">
        {back ? (
          <div className="mt-1">
            <BackButton />
          </div>
        ) : null}
        {/*
          `field-header-chip` CARRIES NO STYLE OF ITS OWN — it is a stable HOOK, and the one thing on
          this component a surface is allowed to repaint.

          The chip is `surface-200` + `purple-700` on every screen in the product, and it stays that
          way here: this class name appears in no `@layer components` rule, so adding it changed
          nothing on any page. What it gives is a name for the end of app/globals.css to hang a
          scoped rule on, and today one surface takes it up — `[data-surface="ministry"]`, stamped on
          <main> by AppShell for the four ministry-only routes, repaints it into the ministry ramp.
          Every ministry screen passes an `icon`, so this is the mark they all share.

          A `tone` PROP WAS THE ALTERNATIVE AND IS THE WRONG SHAPE HERE. It would have to be threaded
          through by every ministry page, and then remembered by the next one — the argument
          `ui/RequiredMark.tsx` makes for itself: one owner, and the next screen gets it right
          without being told. A recipe class scoped by an ancestor attribute has exactly that
          property, which is why the ministry accent is spent on recipe classes and never on a bare
          utility (see the prohibition in that block's own header: a rule on `.text-purple-700` would
          repaint every StatusBadge on the page).
        */}
        {icon ? (
          <div className="field-header-chip mt-1 grid h-10 w-10 place-items-center rounded-xl bg-field-200 text-field-600">
            {icon}
          </div>
        ) : null}
        <div>
          <h1 className="display-title text-3xl md:text-4xl">{title}</h1>
          {description ? <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">{description}</p> : null}
        </div>
      </div>
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  );
}
