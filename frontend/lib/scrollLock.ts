let holders = 0;
let restoreTo = 0;

export function lockPageScroll(): void {
  if (typeof document === "undefined") return;
  holders += 1;
  if (holders > 1) return;
  const root = document.documentElement;
  const gutter = window.innerWidth - root.clientWidth;
  restoreTo = window.scrollY;
  root.style.setProperty("--nav-scroll-gutter", `${gutter}px`);
  root.classList.add("nav-scroll-locked");
}

export function unlockPageScroll(): void {
  if (typeof document === "undefined") return;
  holders = Math.max(0, holders - 1);
  if (holders > 0) return;
  const root = document.documentElement;
  root.classList.remove("nav-scroll-locked");
  root.style.removeProperty("--nav-scroll-gutter");
  if (window.scrollY !== restoreTo) window.scrollTo(0, restoreTo);
}
