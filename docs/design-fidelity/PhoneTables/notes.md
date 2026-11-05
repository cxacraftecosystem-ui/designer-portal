# PhoneTables

## Android

- Shots: impl-android, -dark, -font2 (Tables tab, search "indigo").
- Before: tables rendered inline under the folder cards; Material outlined search with a floating label, sheet picker title in the display face, pills 36dp, every row card collapsed with a TextButton.
- Fixed: on phones Tables is its own screen: "‹ Tables" with the folder name, sheet picker ("Sheet" / "Tools · 120 rows" ▾, 52dp), ink count pills (Tools 3 · Processes 1 · Materials 0 · Artisans 0), a 48dp search field with the #9148d2 focus line and "3 of 120" inside.
- Row cards: bold first column with marked words, "Row 14"; the first card opens to a two-column grid of the key columns with "All 11 columns", the rest show one summary line; tap a card to open it.
- "This table (.csv)" and "Full report (.xlsx)" sit fixed at the bottom; back returns to Files. On tablets the tables stay inline in the right pane.
- Search still asks the server for every row (debounced) and keeps the previous rows while refreshing.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
