"""One seeded world for the search tests (docs/SEARCH.md), shared by the database-backed modules.

Every title carries a per-run ``stamp`` so a query can be scoped to this run's rows on a database
nobody truncates, and every ranking query is narrowed further by this run's craft. Identity values
(Aadhaar, phone, Pehchan, e-mail, date of birth, address) are seeded on purpose, distinctive enough
that an assertion that none reaches the index cannot pass by accident.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from app.core.db import db
from app.core.security import hash_password
from prisma import Json

AADHAAR = "234123412346"
PHONE = "9876501234"
PEHCHAN = "PEHX77441"
EMAIL = "ramesh.private@example.net"
ADDRESS = "House 42 Gali Seven"
DOB = datetime(1971, 3, 9, tzinfo=UTC)

#: Strings that must never appear in a document's title, subtitle or body.
SECRETS = (AADHAAR, PHONE, PEHCHAN, EMAIL, ADDRESS, "1971-03-09")


def new_stamp() -> str:
    return "q" + uuid.uuid4().hex[:9]


async def _user(stamp: str, slug: str, role: str) -> Any:
    return await db.user.create(
        data={
            "email": f"search-{slug}-{stamp}@example.org",
            "name": f"Search {slug} {stamp}",
            "role": role,
            "passwordHash": hash_password("search-test-unused"),
        }
    )


async def seed(stamp: str) -> SimpleNamespace:
    """Every record type, two design workshops (one the designer's, one a professor's), linked and
    unlinked artisans, and records naming an artisan by id, by free text and not at all."""
    w = SimpleNamespace(stamp=stamp)
    w.professor = await _user(stamp, "professor", "PROFESSOR")
    w.researcher = await _user(stamp, "researcher", "RESEARCHER")
    w.designer = await _user(stamp, "designer", "DESIGNER")
    w.contributor = await _user(stamp, "contributor", "FIELD_CONTRIBUTOR")
    owner = w.professor.id

    w.code = await db.craftcode.create(data={"code": f"sc-{stamp}", "name": f"Indigo {stamp}"})
    w.craft = await db.craft.create(
        data={
            "name": f"Indigo dyeing {stamp}",
            "localName": "नील रंगाई",
            "description": "Resist dyeing in fermented vats.",
            "craftCode": w.code.code,
            "place": "Bagru",
        }
    )
    w.other_craft = await db.craft.create(
        data={"name": f"Madder printing {stamp}", "place": "Sanganer"}
    )
    w.workshop = await db.workshop.create(
        data={
            "title": f"Dyers meet {stamp}",
            "date": datetime(2026, 9, 2, tzinfo=UTC),
            "place": "Bagru",
            "description": "Field visit to the vats.",
            "createdById": owner,
        }
    )
    common = {"place": "Bagru", "createdById": owner}
    w.unlinked = await db.artisan.create(
        data={
            "name": f"Ramesh Kumar {stamp}",
            "localName": "रमेश कुमार",
            "craftId": w.craft.id,
            "workshopId": w.workshop.id,
            "aadhaarNumber": AADHAAR,
            "phone": PHONE,
            "email": EMAIL,
            "pehchanCardAvailable": True,
            "pehchanCardNumber": PEHCHAN,
            "address": ADDRESS,
            "dateOfBirth": DOB,
            **common,
        }
    )
    w.linked = await db.artisan.create(
        data={"name": f"Sita Devi {stamp}", "craftId": w.craft.id, **common}
    )
    w.link = await db.crossportalartisanlink.create(
        data={
            "artisanId": w.linked.id,
            "remoteArtisanId": f"fr-{stamp}",
            "matchedBy": "PEHCHAN",
            "consentRecordedById": owner,
        }
    )

    product = {"craftName": w.craft.name, "craftId": w.craft.id, **common}
    w.product_title = await db.productdocumentation.create(
        data={
            "productName": f"Indigo stole {stamp}",
            "artisanName": w.linked.name,
            "artisanId": w.linked.id,
            "remarks": "Hand block printed.",
            **product,
        }
    )
    w.product_body = await db.productdocumentation.create(
        data={
            "productName": f"Cotton dupatta {stamp}",
            "artisanName": w.unlinked.name,
            "artisanId": w.unlinked.id,
            "remarks": f"An indigo stole variant. Call {PHONE} or mail {EMAIL}.",
            **product,
        }
    )
    w.product_free = await db.productdocumentation.create(
        data={
            "productName": f"Table runner {stamp}",
            "artisanName": "A weaver from the next village",
            "remarks": "Dyes from the indigo vat.",
            **product,
        }
    )
    w.tool = await db.tooldocumentation.create(
        data={
            "toolkitName": f"Wooden block {stamp}",
            "englishName": "Printing block",
            "artisanName": w.linked.name,
            "artisanId": w.linked.id,
            **product,
        }
    )
    w.process = await db.process.create(
        data={"name": f"Vat preparation {stamp}", "productId": w.product_title.id, "createdById": owner}
    )
    await db.processstep.create(
        data={"processId": w.process.id, "name": "Ferment", "notes": "Seven days.", "sortOrder": 1}
    )

    w.dw_designer = await db.designworkshop.create(
        data={
            "title": f"Block print camp {stamp}",
            "clusterName": f"Bagru {stamp} cluster",
            "craftName": w.craft.name,
            "state": "Rajasthan",
            "district": "Jaipur",
            "createdById": w.designer.id,
        }
    )
    w.dw_professor = await db.designworkshop.create(
        data={
            "title": f"Weaving camp {stamp}",
            "clusterName": f"Kota {stamp} cluster",
            "createdById": owner,
        }
    )
    w.stage = await db.dwstageentry.create(
        data={
            "designWorkshopId": w.dw_designer.id,
            "stageKey": "CLUSTER_CRAFT_BACKGROUND",
            "entityKey": "background",
            "data": Json({}),
            "searchText": f"Glossolalia {stamp} dyers of the river bank",
        }
    )
    w.report = await db.dwreportexport.create(
        data={
            "designWorkshopId": w.dw_designer.id,
            "format": "pdf",
            "templateId": "default",
            "fileName": f"camp-report-{stamp}.pdf",
        }
    )
    w.plan = await db.annualplanentry.create(
        data={
            "planYear": 2026,
            "workshopNo": f"W-{stamp}",
            "workshopNoKey": f"w-{stamp}",
            "clusterName": f"Bagru  {stamp}  Cluster",
            "craftName": w.craft.name,
            "state": "Rajasthan",
            "district": "Jaipur",
        }
    )

    media = {
        "mediaType": "AUDIO",
        "mimeType": "audio/mpeg",
        "sizeBytes": 1024,
        "bucket": "test-bucket",
        "uploadedById": owner,
    }
    w.transcript_dw = await db.mediafile.create(
        data={
            "originalFilename": f"camp-interview-{stamp}.mp3",
            "objectKey": f"search/{stamp}/dw.mp3",
            "designWorkshopId": w.dw_designer.id,
            "transcriptText": f"We soak the cloth in indigo {stamp} three times.",
            **media,
        }
    )
    w.transcript_open = await db.mediafile.create(
        data={
            "originalFilename": f"vat-talk-{stamp}.mp3",
            "objectKey": f"search/{stamp}/open.mp3",
            "craftId": w.craft.id,
            "transcriptText": f"The vat smells sweet when it is ready. Phone {PHONE}.",
            "transcriptSummary": "How a vat is judged ready.",
            **media,
        }
    )

    w.question_id = await db.questionnairequestion.create(
        data={
            "sectionCode": f"S-{stamp}",
            "sectionTitle": "Identity",
            "prompt": "Aadhaar number",
            "sortOrder": 1,
        }
    )
    w.question_dyes = await db.questionnairequestion.create(
        data={
            "sectionCode": f"S-{stamp}",
            "sectionTitle": "Practice",
            "prompt": "Which dyes do you use?",
            "sortOrder": 2,
        }
    )
    w.interview = await db.questionnaireinterview.create(
        data={"title": f"Practice interview {stamp}", "language": "Hindi", "createdById": owner}
    )
    await db.questionnaireinterviewartisan.create(
        data={"interviewId": w.interview.id, "artisanId": w.linked.id}
    )
    await db.questionnaireresponse.create(
        data={
            "interviewId": w.interview.id,
            "questionId": w.question_id.id,
            "answeredById": owner,
            "answerText": AADHAAR,
        }
    )
    await db.questionnaireresponse.create(
        data={
            "interviewId": w.interview.id,
            "questionId": w.question_dyes.id,
            "answeredById": owner,
            "answerText": "Indigo and madder.",
        }
    )
    w.cluster_key = f"bagru {stamp} cluster"
    return w


def every_record(w: SimpleNamespace) -> list[tuple[str, str]]:
    return [
        ("artisans", w.unlinked.id),
        ("artisans", w.linked.id),
        ("crafts", w.craft.id),
        ("crafts", w.other_craft.id),
        ("clusters", w.cluster_key),
        ("clusters", f"kota {w.stamp} cluster"),
        ("workshops", w.workshop.id),
        ("designWorkshops", w.dw_designer.id),
        ("designWorkshops", w.dw_professor.id),
        ("interviews", w.interview.id),
        ("transcripts", w.transcript_dw.id),
        ("transcripts", w.transcript_open.id),
        ("reports", w.report.id),
        ("products", w.product_title.id),
        ("products", w.product_body.id),
        ("products", w.product_free.id),
        ("tools", w.tool.id),
        ("processes", w.process.id),
        ("media", w.transcript_dw.id),
        ("media", w.transcript_open.id),
    ]


async def index_world(w: SimpleNamespace) -> None:
    from app.services import search_indexer

    for entity_type, entity_id in every_record(w):
        await search_indexer.index_one(entity_type, entity_id)


async def document(entity_type: str, entity_id: str) -> dict[str, Any] | None:
    rows = await db.query_raw(
        'SELECT * FROM "SearchDocument" WHERE "entityType" = $1 AND "entityId" = $2',
        entity_type,
        entity_id,
    )
    return rows[0] if rows else None


def user_ns(user: Any) -> SimpleNamespace:
    return SimpleNamespace(id=user.id, email=user.email, role=user.role, name=user.name)
