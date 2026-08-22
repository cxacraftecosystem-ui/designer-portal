from fastapi import APIRouter

from app.api.routes import (
    access,
    ai_keys,
    analytics,
    app_release,
    artisans,
    asr_models,
    auth,
    crafts,
    dashboard,
    data_access,
    data_browser,
    datasets,
    design_workshop_viewers,
    design_workshops,
    designers,
    export,
    feedback,
    map_points,
    media,
    preferences,
    processes,
    products,
    public,
    questionnaire,
    questionnaire_forms,
    reference,
    review,
    search,
    secrets,
    settings,
    tasks,
    tools,
    users,
    workshops,
)

api_router = APIRouter(prefix="/api")

api_router.include_router(auth.router)
api_router.add_api_route("/me", auth.me, methods=["GET"], tags=["auth"])
api_router.include_router(users.router)
# The platform allow-list — who may sign in AT ALL — and the queue of people asking to. Mounted next
# to users because it is the other half of "who has an account here", and BEFORE designers because
# the designer roster is now the narrower of the two lists: it says who is empanelled, this one says
# who may reach the product. See app/services/access_roster.py for why they are two tables.
api_router.include_router(access.router)
api_router.include_router(artisans.router)
api_router.include_router(crafts.router)
api_router.include_router(workshops.router)
api_router.include_router(products.router)
api_router.include_router(processes.router)
api_router.include_router(tools.router)
api_router.include_router(media.router)
api_router.include_router(questionnaire.router)
# Custom questionnaires — /api/questionnaires (PLURAL), a form a designer authored themselves, as
# distinct from the one global artisan questionnaire on the singular prefix above. Mounted after it
# so the two prefixes are read together by anyone scanning this file.
api_router.include_router(questionnaire_forms.router)
# BEFORE design_workshops, and the order is load-bearing rather than stylistic. Both routers carry
# the /design-workshops prefix, and design_workshops declares GET /{workshop_id} — which matches
# /design-workshops/eligible-viewers perfectly well and answers 404 "Record not found". Registered
# the other way round, the admin's designer picker is empty on a server where the route exists.
# FastAPI matches in registration order, so the literal path has to be mounted first.
api_router.include_router(design_workshop_viewers.router)
api_router.include_router(design_workshops.router)
# The empanelment roster that gates a designer's sign-in, and the profile their reports are
# prefilled from. Next to design_workshops because it is the same product surface, and separate
# from users because the two facts it keeps are deliberately not the role column — see
# app/services/designers.py.
api_router.include_router(designers.router)
api_router.include_router(dashboard.router)
# The cross-workshop comparison, /api/analytics/design-workshops. On its own prefix rather than
# under /design-workshops, which is already shared by two routers and carries a GET /{workshop_id}
# that would swallow any literal path mounted after it (see the note above design_workshop_viewers).
# Admin-only inside the module; nothing here is scoped to a caller's own workshops.
api_router.include_router(analytics.router)
api_router.include_router(search.router)
api_router.include_router(map_points.router)
api_router.include_router(review.router)
api_router.include_router(export.router)
api_router.include_router(data_browser.router)
# The bulk, admin-authenticated dataset API. Mounted like any other router; what makes it different
# is the credential it accepts (see app/api/routes/datasets.py and deps.require_dataset_admin).
api_router.include_router(datasets.router)
api_router.include_router(app_release.router)
# Next to app_release because it is the same kind of thing: this deployment handing its own client a
# large immutable binary it cannot get anywhere else. The difference is that the APK is redirected to
# object storage while these bytes are served from the API itself, which
# app/services/asr_artifacts.py argues at length.
api_router.include_router(asr_models.router)
api_router.include_router(feedback.router)
api_router.include_router(preferences.router)
api_router.include_router(settings.router)
api_router.include_router(ai_keys.router)
api_router.include_router(secrets.router)
api_router.include_router(data_access.router)
api_router.include_router(tasks.router)
api_router.include_router(reference.router)
# Last, and on its own line with this note, because it is the only unauthenticated router here:
# everything above is scoped to the caller, this one is scoped to nobody. See app/api/routes/public.py.
api_router.include_router(public.router)
