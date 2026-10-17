/**
 * The one layout rule for a row of buttons, as a pure function so a spec can call it.
 *
 * - ONE button on its line is centred. A form's primary action pinned in a footer passes `fill`,
 *   which keeps it full width on a phone.
 * - TWO share one line: equal halves on a phone (labels wrap inside the button, never past it),
 *   content-fit from `sm` up.
 * - THREE OR MORE stack full width below `sm` and become one row of pills from `sm` up. A child
 *   marked `data-danger` goes last and is set apart.
 *
 * Every class string is written out in full: Tailwind reads source text, so an interpolated class
 * compiles to nothing.
 */

export type ActionGroupAlign = "start" | "center" | "end";

export type ActionGroupShape = "one" | "two" | "many";

export function actionGroupShape(count: number): ActionGroupShape {
  if (count <= 1) return "one";
  if (count === 2) return "two";
  return "many";
}

const ONE = "flex items-center justify-center";
const ONE_FILL = "flex items-center justify-center max-sm:[&>*]:w-full";

const TWO_BASE =
  "flex flex-nowrap items-center gap-2 max-sm:[&>*]:min-w-0 max-sm:[&>*]:flex-1 max-sm:[&>*]:whitespace-normal max-sm:[&>*]:text-center";

const MANY_BASE =
  "flex flex-col gap-2 max-sm:[&>*]:w-full sm:flex-row sm:flex-wrap sm:items-center sm:[&>*]:rounded-full! [&>[data-danger]]:order-last max-sm:[&>[data-danger]]:mt-2 sm:[&>[data-danger]]:ms-4";

const JUSTIFY_TWO: Record<ActionGroupAlign, string> = {
  start: "justify-start",
  center: "justify-center",
  end: "justify-end"
};

const JUSTIFY_MANY: Record<ActionGroupAlign, string> = {
  start: "sm:justify-start",
  center: "sm:justify-center",
  end: "sm:justify-end"
};

/** The class list for a group of `count` actions. */
export function actionGroupClass(count: number, align: ActionGroupAlign = "center", fill = false): string {
  const shape = actionGroupShape(count);
  if (shape === "one") return fill ? ONE_FILL : ONE;
  if (shape === "two") return `${TWO_BASE} ${JUSTIFY_TWO[align]}`;
  return `${MANY_BASE} ${JUSTIFY_MANY[align]}`;
}
