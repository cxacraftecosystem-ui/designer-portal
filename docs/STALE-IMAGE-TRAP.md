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
