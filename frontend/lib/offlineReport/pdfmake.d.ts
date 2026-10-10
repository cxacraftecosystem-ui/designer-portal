// pdfmake 0.3 ships no type declarations. `lib/offlineReport/pdf.ts` describes the part it uses as
// `PdfMakeLike`; these let the two builds be imported at all.
declare module "pdfmake/build/pdfmake";
declare module "pdfmake";
