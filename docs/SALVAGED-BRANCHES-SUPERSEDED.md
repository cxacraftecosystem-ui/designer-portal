# The salvaged WIP branches are superseded — do not merge them

Four `worktree-*` branches carry a single "WIP salvage" commit each, together about 6,400 lines of
real feature work with tests. They look like unmerged value. **They are not. Merging any of them
regresses `main`.** Checked 2026-08-09, file by file, before nearly merging them.

| branch | carried | why it must not be merged |
|---|---|---|
| `worktree-wf_639858b8-add-1` | workshop search | `DwWorkshopSearch.kt` is **byte-identical** to main's, and main's `DwWorkshopSearchTest.kt` is 653 lines to the branch's 508 with **zero** lines unique to the branch. Main strictly contains it. |
| `worktree-wf_639858b8-add-2` | photo intake, QR on the codes screen | Main extracted `DwQrSymbolImage` into the shared `ui/RecordCodeCard.kt` and generalised artisan-only lookup into `lookUpRecordCode` for every record type. The branch still has the local, artisan-only copy. |
| `worktree-wf_639858b8-add-3` | inline images in the rich-text editor | The branch draws `InlinePhotographPlate`, a placeholder reading "the picture is not stored on this device". Main **resolves and renders the actual photograph** (`media?.resolve(block.media)`, RichTextEditor.kt:629). A placeholder explaining an absence would replace the thing itself. |
| `worktree-wf_b97444b2-9ed-2` | viewers admin | `WorkshopViewersScreen.kt` (655 lines) and `DesignWorkshopViewersTest.kt` (321 lines) both show **zero** lines unique to the branch. |

## The measurement that settles it, and the one that misleads

`git diff --stat main...<branch>` reports thousands of added lines for every one of these, because the
three-dot form measures from the **merge base** — it answers "what did this branch add", not "what
does main lack". Both sides added the same files independently, so the branch's whole contribution
shows up as new.

The question worth asking is the other one:

```sh
# lines present in the branch and absent from main, per file
diff <(git show HEAD:"$f") <(git show "$branch":"$f") | grep -c '^>'
```

Zero, or a handful of stale comment lines, on every feature file above.

## The specific trap

Both `add-2` and `b97444b2-9ed-2` show ~175 "branch-only" lines in `MainActivity.kt`. That is large
enough to look like a missing feature, and two of those lines are:

```kotlin
data object Settings : Screen
```

Main **deliberately deleted** that, and says so where it used to be (`MainActivity.kt:452`): nothing
ever assigned it, `navigate` sends SETTINGS to `Appearance` and SETTINGS_HUB to `AdminHub`, so it was
"a route with no door … a third answer to 'where do settings live' that only a person can delete".

Merging these branches resurrects it, along with a copy of the Verhoeff table that main moved out of
`MainActivity.kt` into `data/ArtisanIdentity.kt` and `ui/designworkshop/DwIdentityOcr.kt`.

**A branch being unmerged is not evidence that it is unincorporated.** These were the source later
lanes built on; main carried their work forward and then improved it. Check what main lacks, not what
the branch added.
