# States · loading, empty, no results, offline, couldn't load (web)

Was off: grey skeleton bars, a round icon empty state with the generic body, a red bar for load errors with the server message, a plain amber offline line.
Fixed: skeleton shaped like the page (70%/50% bars, two 40px blocks, three 44px rows) in the board's soft gradient; empty folder with a 56px purple tile, 18px "Nothing recorded here yet", "Files appear as soon as someone records for <folder>." and Record now; couldn't-load card fully red with "!" tile, "This folder didn’t load", "Trying again in 8 s. Your selection and filters are kept." and filled Try now; offline banner amber (#fef3c7 / #fcd34d) "You're offline. Showing what was loaded … Downloads pause and pick up again by themselves when you're back online."
Still different, and why:
- Each state is captured inside the page (impl-loading/empty/noresults/offline/error.png) rather than as five cards side by side.
- Offline on the web is a banner over kept data; the board's card is labelled Android.
- No results is DataSearchPanel (restyle on fix-0014; "38 results." after the suggestion is not shown because the API sends no count).
- The board's caption in the loading card is annotation, not product copy.
