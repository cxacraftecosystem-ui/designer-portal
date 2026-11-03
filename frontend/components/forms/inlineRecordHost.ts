import type { ReactNode } from "react";

import type { ArtisanIdentityMatch } from "@/lib/types";

export type InlineHostSeed = {
  artisanId?: string;
  artisanName?: string;
  workshopId?: string;
};

export function seedHasArtisan(seed: InlineHostSeed | undefined): boolean {
  return Boolean(seed?.artisanId || seed?.artisanName);
}

export type InlineRecordHostProps<TRecord> = {
  footerFields?: ReactNode;
  onCreated?: (record: TRecord) => void;
  onCancel?: () => void;
  onDiscardAndLeave?: () => void;
  onQueued?: () => void;
};

export type InlineRecordSurfaceProps = Pick<InlineRecordHostProps<unknown>, "footerFields" | "onDiscardAndLeave">;

export type UseExistingArtisan = (artisan: ArtisanIdentityMatch) => void;
