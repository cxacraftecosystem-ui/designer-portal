# The stale API image, twice in one day

Both times the symptom looked like a code defect and both times the code was correct. Writing it
down because the third occurrence will look different again and cost the same hour.

## What happened

**1. `merge: Extra inputs are not permitted`.** A stage save was refused with a schema error naming
a field that had just been added. The client sent `merge`; the server did not know it; `APIModel` is
`extra="forbid"`. The fix had been committed and the source was correct — the running container was
built before it.

**2. Questionnaire answers absent from the report.** A workshop with an attached questionnaire and
four recorded answers produced a ten-paragraph document with no annexure at all. The source was
complete: `report_questionnaires.py`, `SpecialSection.ANNEXURE_QUESTIONNAIRES`, the section carried
by six templates, `attach_report_questionnaires` called from the report path, and a branch in
`ReportBuilder.build`. The running container answered:

    AttributeError: type object 'SpecialSection' has no attribute 'ANNEXURE_QUESTIONNAIRES'

`draws_questionnaires` was therefore false, the five questionnaire queries never ran, and the
annexure was never rendered.

## Why the image goes stale so easily here

`docker-compose.yml` builds `design-workshop-backend:local` from `backend/Dockerfile` and mounts NO
source volume — deliberately, so the container matches a deployable image rather than a developer's
working tree. `docker compose up -d` will happily reuse an image built hours ago; only
`build` + `--force-recreate` replaces it. Every backend change therefore needs:

    docker compose --profile api build api
    docker compose --profile api up -d --force-recreate api

The web client, by contrast, hot-reloads. So a change touching both surfaces appears to work on the
web and to fail on the API, which reads exactly like a backend bug.

## How to tell in ten seconds, before debugging anything

Ask the CONTAINER what it knows, not the source:

    docker exec design-workshop-api python -c "
    from app.services.report_templates import SpecialSection
    print(hasattr(SpecialSection, 'ANNEXURE_QUESTIONNAIRES'))"

    docker exec design-workshop-api python -c "
    from app.schemas.design_workshops import StageEntryIn
    print('merge' in StageEntryIn.model_fields)"

An `AttributeError` or a `False` for something the source plainly has is the whole diagnosis.

## The generalisation worth keeping

**A defect reproduced against a running service is evidence about THAT BUILD, not about the code.**
Before tracing a failure through source that looks correct, confirm the process under test contains
the source you are reading. It is one command, and it has now saved that hour twice.

The same rule caught a third case in a different form: an Android build straddling a merge was
discarded rather than trusted, because its inputs belonged to neither tree.

---

## How this document is kept true

**Most of this file cannot rot, and that is deliberate.** Two of its three parts are history — an
account of two hours lost on 2026-08-13 — and history is kept true by not editing it. The
generalisation at the foot (*a defect reproduced against a running service is evidence about THAT
BUILD, not about the code*) is a habit, not a fact about this repository, and nothing here can
invalidate it.

The part that **can** go wrong is the middle: the diagnosis procedure and the reason the image goes
stale. Those describe live configuration, and a procedure that has quietly stopped working is worse
than none, because it returns a confident all-clear.

| Claim class | Kept true by |
|---|---|
| "`docker-compose.yml` builds `design-workshop-backend:local` from `backend/Dockerfile` and mounts NO source volume" | Reading `docker-compose.yml`. This is the premise of the whole document. **If a source volume is ever added, this file is not merely stale — it is actively misleading**, because the trap it describes stops existing and the ten-second check stops being necessary. Say so here rather than deleting the file; the incidents still happened. |
| "Only `build` + `--force-recreate` replaces the image" | The same file, plus the compose version in use. |
| The ten-second `docker exec … model_fields` check | **Run it.** It is one command and its own proof: if it errors for a reason other than the one being diagnosed, the procedure has drifted. It is written against `app.schemas.design_workshops.StageEntryIn`, so it also depends on that module path — a rename there breaks the check silently into a `ModuleNotFoundError` that reads like an unrelated problem. |
| The two incidents, and the `AttributeError` transcript | **Frozen.** They are evidence that this happened twice in one day, which is the argument for the habit. Do not update them to match current code; if it happens a third time, add a third numbered case — the header already predicts that the third occurrence *"will look different again and cost the same hour"*. |
| The Android postscript (a build straddling a merge, discarded rather than trusted) | The same rule in a second form, and the reason it is here rather than in an Android document. |

**Review triggers:** any change to `docker-compose.yml` or `backend/Dockerfile`, particularly the
addition of a bind mount; a rename of `app/schemas/design_workshops.py` or `StageEntryIn`.

**What would make this document obsolete, and it is worth wanting:** a compose setup that cannot
serve stale source — a mount in development, or a startup log line naming the build's git SHA so the
question is answered before it is asked. Until then the habit is the mechanism, and the habit is only
as good as the last person who read this page.
