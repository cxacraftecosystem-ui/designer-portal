"use client";

import { CloudOff } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { useToast } from "@/components/ui/Toast";

export function OfflineDialog({ open, onDismiss }: { open: boolean; onDismiss: () => void }) {
  return (
    <FieldDialog
      open={open}
      onClose={onDismiss}
      role="alertdialog"
      tone="warning"
      icon={<CloudOff className="h-4 w-4" aria-hidden />}
      dismissOnBackdrop
      zIndex={106}
      title="You are offline"
      description={
        <>
          You are offline. Anything you have already typed stays on screen, and saving still works &mdash;
          your entry will upload when you are back online.
        </>
      }
      footer={
        <button type="button" className="field-button" onClick={onDismiss}>
          Continue offline
        </button>
      }
    >
      <p className="mt-2 text-sm leading-6 text-ink-500">
        Entries waiting to upload are listed at the top of the page. They are kept in this browser, so do not
        clear its data or switch devices until they have uploaded. Searching and opening records you have not
        already loaded need a connection.
      </p>
    </FieldDialog>
  );
}

export function OfflineWatcher() {
  const [open, setOpen] = useState(false);
  const { toast } = useToast();
  const { passwordChangeRequired } = useAuth();
  const wasOffline = useRef(false);
  const gated = useRef(passwordChangeRequired);
  useEffect(() => {
    gated.current = passwordChangeRequired;
  }, [passwordChangeRequired]);

  const goOffline = useCallback(() => {
    wasOffline.current = true;
    setOpen(true);
  }, []);

  const goOnline = useCallback(() => {
    setOpen(false);
    if (!wasOffline.current) return;
    wasOffline.current = false;
    if (gated.current) return;
    toast({ id: "connection", title: "Back online", description: "Saving and uploading work again.", tone: "success" });
  }, [toast]);

  useEffect(() => {
    if (typeof navigator !== "undefined" && navigator.onLine === false) goOffline();
    window.addEventListener("offline", goOffline);
    window.addEventListener("online", goOnline);
    return () => {
      window.removeEventListener("offline", goOffline);
      window.removeEventListener("online", goOnline);
    };
  }, [goOffline, goOnline]);

  return <OfflineDialog open={open && !passwordChangeRequired} onDismiss={() => setOpen(false)} />;
}
