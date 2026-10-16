"use client";

import { useParams } from "next/navigation";
import { Boxes } from "lucide-react";

import { ExistingMedia } from "@/components/media/ExistingMedia";
import { RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { productFields } from "@/components/records/recordFields";
import type { ProductDocumentation } from "@/lib/types";

/** One product record, read-only. Update (in the header) opens the form — see `RecordView.tsx`. */
export default function ProductViewPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <RecordViewPage<ProductDocumentation>
      kind="product"
      id={id}
      endpoint={`/products/${encodeURIComponent(id)}`}
      noun="product record"
      icon={<Boxes className="h-5 w-5" aria-hidden />}
      titleOf={(product) => product.productName || "Product"}
      collabType="product"
    >
      {(product) => (
        <>
          <RecordFieldList rows={productFields(product)} />
          <ExistingMedia linkedRecordType="product" linkedRecordId={product.id} recordHold={null} title="Media" readOnly />
        </>
      )}
    </RecordViewPage>
  );
}
