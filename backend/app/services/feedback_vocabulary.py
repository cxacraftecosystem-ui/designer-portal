"""The one definition of every closed list a feedback report is filed against.

WHY THIS IS A MODULE AND NOT THREE TUPLES IN ``routes/feedback``. A feedback report is written on
three surfaces — the web form, the Android form and whatever posts next — and read on two more: the
administrator's inbox and the research export. SKILL.md's cross-client rule says the shared
vocabulary must come from the SERVER, because two clients that each hold their own copy of "the
kinds of feedback there are" will one day describe the same submission differently, and the research
the owner asked for is then counting two categories that are one. So the words live here,
``GET /feedback/vocabulary`` serves them, both clients render them, and ``validate_choice`` below is
the only thing that decides whether a posted value is a member.

THE ``workshopKind`` PRECEDENT, FOLLOWED DELIBERATELY. That vocabulary is a plain ``String?`` column
validated server-side against a dict in ``services/stage_schema``, NOT a Prisma enum. Three reasons
this one copies it rather than reaching for ``enum FeedbackKind``:

1. **A Prisma enum member is a migration.** Adding "TRANSLATION" to a live enum means an
   ``ALTER TYPE`` on a database this deployment reaches through a pooler, coordinated with a client
   that would otherwise post a value the column cannot hold. A row in a dict here is a deploy.
2. **A stored value that is no longer a member still reads.** If a category is ever retired, the
   reports filed under it keep their word and keep printing; a dropped enum member is a read error
   on historical rows, which is the worst possible outcome for a grievance register whose whole
   purpose is that old entries stay legible.
3. **The label travels with the value.** An enum gives clients ``SKILL_UPGRADATION`` and leaves each
   of them to invent "Skill Upgradation" separately. Here the label is served beside the value.

WHY ``severity`` AND ``area`` ARE NULLABLE AND ``kind`` IS NOT. Kind is the question the form is
built around — it decides which prompt the reader is answering and which queue an administrator
works — so a report without one is a report nobody can route. Severity and area are refinements: a
person filing a grievance at nine in the evening should not be made to rank their own distress on a
four-point scale before the app will accept it, and "which screen" is frequently "all of it".
Nothing is backfilled, because there is nothing to backfill — ``FeedbackReport`` is a new table.

WHY ``OTHER`` IS IN ``FEEDBACK_KINDS`` AND NOT IN ``FEEDBACK_STATUSES``. ``WORKSHOP_KIND``'s own
comment sets the test: OTHER belongs where the list samples a space that keeps growing, and does not
where the members ARE the categories. Five kinds is a sample — somebody will one day want to report
a translation error, or send praise — so OTHER is there and a report parked under it is still
findable. The three statuses are not a sample: they are the whole of what can have happened to a
report, and an OTHER there would mean "we have done something to this and will not say what".
"""

from typing import Any

from fastapi import HTTPException, status

#: What a person is telling us. The FIRST member is what the forms open on, and it is SUGGESTION
#: rather than BUG on purpose: a form that opens on "Bug" invites every remark to be filed as a
#: defect, and what the owner asked for is a grievance/suggestion/recommendation register, not a
#: second issue tracker.
#:
#: SUGGESTION AND RECOMMENDATION ARE BOTH HERE AND THEY ARE NOT THE SAME WORD TWICE. The owner named
#: all three separately, and on this product they do separate: a suggestion is about the software
#: ("let me filter the artisan list by craft"), a recommendation is about the work the software is
#: for ("this cluster should be documented before the monsoon"). Folding them would lose the second
#: kind entirely, because it would be filed under the first and read as a feature request.
FEEDBACK_KINDS: dict[str, str] = {
    "SUGGESTION": "Suggestion",
    "RECOMMENDATION": "Recommendation",
    "GRIEVANCE": "Grievance",
    "BUG": "Bug or problem",
    "OTHER": "Something else",
}

#: How pressing it is, in the reporter's own judgement.
#:
#: FOUR RUNGS, WORDED AS CONSEQUENCES rather than as severities. "High" invites everybody to pick it;
#: "I cannot do part of my work" is a claim a person makes only when it is true, and it is also the
#: sentence an administrator triaging the queue actually needs. The keys stay abstract so a label can
#: be reworded without touching stored rows.
FEEDBACK_SEVERITIES: dict[str, str] = {
    "LOW": "Minor — worth mentioning",
    "MEDIUM": "Moderate — it slows me down",
    "HIGH": "Serious — I cannot do part of my work",
    "CRITICAL": "Blocking — I cannot work at all",
}

#: Which part of the product it is about.
#:
#: EVERY MEMBER IS A SURFACE THIS REPOSITORY ACTUALLY HAS, not a taxonomy of software. They are the
#: nav's own groups plus the things people talk about that cut across all of them — signing in,
#: speed, media, working offline. A reporter who finds none of them picks OTHER and names the screen
#: in the details, which is why the free text is required and this is not.
FEEDBACK_AREAS: dict[str, str] = {
    "SIGN_IN": "Signing in and access",
    "DASHBOARD": "Dashboard and navigation",
    "RECORDS": "Artisan, craft, product, tool and process records",
    "QUESTIONNAIRE": "Questionnaires and interviews",
    "DESIGN_WORKSHOP": "Design & prototype workshops",
    "REVIEW": "Review and approvals",
    "DATA": "Browsing, search, maps and downloads",
    "MEDIA": "Photographs, recordings and uploads",
    "OFFLINE": "Working offline and syncing",
    "PERFORMANCE": "Speed and reliability",
    "ACCESSIBILITY": "Readability, contrast and motion",
    "ACCOUNT": "Account, settings and preferences",
    "OTHER": "Something else",
}

#: Where a report has got to. THE WHOLE OF THE REDRESSAL PROMISE IS THESE THREE WORDS, so each says
#: what the READER may conclude from it rather than what the administrator did:
#:
#: * SUBMITTED — it is recorded and nobody has looked yet. Honest, and where most reports sit.
#: * ACKNOWLEDGED — a named person has read it. This is the rung the brief calls for: *"a grievance
#:   mechanism that cannot show a person their grievance was seen is not a redressal mechanism."* It
#:   deliberately promises NOTHING about an outcome, because promising one here is how a queue full
#:   of acknowledged-and-forgotten reports comes to look like a queue of solved ones.
#: * RESOLVED — it is finished, and ``responseNote`` says how. See ``routes/feedback`` for why the
#:   note is required on this transition and optional on the other.
#:
#: THERE IS NO "REJECTED" AND ITS ABSENCE IS THE DESIGN. A grievance an administrator disagrees with
#: is still RESOLVED — with a note saying so, which the person who filed it can read. A status that
#: lets an institution close a complaint by declaring it invalid, with no words attached, is the
#: mechanism this one exists instead of.
FEEDBACK_STATUSES: dict[str, str] = {
    "SUBMITTED": "Submitted",
    "ACKNOWLEDGED": "Acknowledged",
    "RESOLVED": "Resolved",
}

#: Which app the report was written in. CAPTURED, NEVER ASKED — see ``schemas/feedback``.
FEEDBACK_CLIENTS: dict[str, str] = {
    "WEB": "Web",
    "ANDROID": "Android",
}

#: The one map the vocabulary route and the validator both read, so a list cannot be served under a
#: name the validator does not know.
FEEDBACK_VOCABULARIES: dict[str, dict[str, str]] = {
    "kind": FEEDBACK_KINDS,
    "severity": FEEDBACK_SEVERITIES,
    "area": FEEDBACK_AREAS,
    "status": FEEDBACK_STATUSES,
    "client": FEEDBACK_CLIENTS,
}


def vocabulary_payload() -> dict[str, Any]:
    """Every list, as ``[{value, label}]`` in declaration order.

    ORDERED LISTS AND NOT A JSON OBJECT, because a client renders these as a dropdown and the order
    is part of the definition — SUGGESTION is first because that is what the form opens on, and
    ``FEEDBACK_SEVERITIES`` runs low to high because a scale that arrives shuffled is a scale each
    client re-sorts by its own idea of the ranking. Python dicts have kept insertion order since
    3.7, so the declaration above IS the order; a JSON object would leave it to the client's parser.
    """
    return {
        name: [{"value": value, "label": label} for value, label in members.items()]
        for name, members in FEEDBACK_VOCABULARIES.items()
    }


def validate_choice(name: str, value: str | None, *, required: bool = False) -> str | None:
    """One posted value against one list. Returns the value, or raises 422 naming the members.

    ``None`` and ``""`` are both "not answered" and collapse to ``None``, so a client that sends an
    empty string for an untouched dropdown stores a NULL rather than a member that is not a member.
    A required list refuses both.

    THE 422 LISTS THE MEMBERS. A refusal that only says "invalid kind" leaves the author of a client
    guessing at spellings against a server they cannot read; naming them costs one join and is the
    difference between a five-minute fix and a session.
    """
    members = FEEDBACK_VOCABULARIES[name]
    cleaned = (value or "").strip()
    if not cleaned:
        if required:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"A feedback {name} is required. One of: {', '.join(members)}.",
            )
        return None
    if cleaned not in members:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unknown feedback {name} '{cleaned}'. One of: {', '.join(members)}.",
        )
    return cleaned


def label_for(name: str, value: Any) -> str:
    """The human label for a stored value, falling back to the value itself.

    THE FALLBACK IS THE POINT, and it is the second reason this is not a Prisma enum. A row stored
    under a category since retired still prints — as its raw key, which is ugly and truthful —
    rather than raising a KeyError in the middle of a CSV export of the grievance register. Every
    read surface (the export, the inbox) goes through here for that reason.
    """
    if value is None:
        return ""
    key = str(value)
    return FEEDBACK_VOCABULARIES.get(name, {}).get(key, key)
