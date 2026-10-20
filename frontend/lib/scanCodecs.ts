/**
 * The designer portal's decoders for the top-bar dispatcher (`lib/scanDispatch.ts`): the DPW2 join
 * card and the DPW1 record grammar, which exist only here. The Field Repository's copy of this file
 * exports `undefined`, and its dispatcher refuses every DPW code by prefix.
 */

import type { ScanCodecs } from "@/lib/scanDispatch";
import { decodeJoinCard } from "@/lib/joinCards";
import { decodeWorkshopCode } from "@/lib/workshopCodes";

export const SCAN_CODECS: ScanCodecs | undefined = {
  decodeJoinCard: (raw) => decodeJoinCard(raw),
  decodeRecordCode: (raw) => {
    const decoded = decodeWorkshopCode(raw);
    if (!decoded.ok) return { ok: false, message: decoded.message };
    return { ok: true, recordType: decoded.ref.recordType, id: decoded.ref.id, code: decoded.code };
  }
};
