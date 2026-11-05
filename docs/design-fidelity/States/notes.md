# States

## Android

- Android shots: impl-android-loading, -empty, -noresults, -offline, -error, each with a -dark variant (noresults is light only) at phone size, composed in the real ExplorerScreen (HANG/FAIL fakes, an empty artisan, offline with a 14-minute-old cache).
- Before: state cards with 24dp corners, 16sp titles, 14sp body, pill buttons, empty icon on white, and the offline banner shown twice (card and bottom strip).
- Fixed: 16dp cards, 56dp icon tile (lavender for empty, white on amber/red), 18sp-class bold title, 13-14sp body, 44dp buttons with 12dp corners.
- Offline shows the cached data's age ("Showing what was loaded 14 min ago") and "Saved files still open"; the bottom strip only appears when there is no cached copy.
- Couldn't load counts down ("Trying again in 8 s"), offers Try now, and keeps selection and filters.
- Loading skeleton follows the page: record card bars and three file rows with tiles.
- No results is the unified search's state (fix-0014); there is no result count after "Did you mean" because the API returns none for the suggestion.
- Type sizes and line heights snap to the app type roles (TypeScaleGuardTest forbids literal sp): board 13px reads 14sp, 15px reads 16sp, 20px reads 18sp, 22/26px read 24sp.
