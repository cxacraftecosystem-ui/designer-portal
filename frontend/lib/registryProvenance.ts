import type { RegistrySource } from "@/lib/designWorkshopStore";

export function registryProvenanceNotice(source: RegistrySource | null): string {
  if (source === "memory") {
    return "This form was loaded earlier in this tab and may be out of date. Reload the page to see any fields added since.";
  }
  if (source === "cache") {
    return (
      "Couldn't connect, so this is the copy of the form saved on this device the last time you were online. " +
      "Fields added since then will appear once you reconnect."
    );
  }
  return "";
}
