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
