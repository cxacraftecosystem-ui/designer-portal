from typing import Any

from fastapi import HTTPException, status

FEEDBACK_KINDS: dict[str, str] = {
    "SUGGESTION": "Suggestion",
    "RECOMMENDATION": "Recommendation",
    "GRIEVANCE": "Grievance",
    "BUG": "Bug or problem",
    "OTHER": "Something else",
}

FEEDBACK_SEVERITIES: dict[str, str] = {
    "LOW": "Minor — worth mentioning",
    "MEDIUM": "Moderate — it slows me down",
    "HIGH": "Serious — I cannot do part of my work",
    "CRITICAL": "Blocking — I cannot work at all",
}

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

FEEDBACK_STATUSES: dict[str, str] = {
    "SUBMITTED": "Submitted",
    "ACKNOWLEDGED": "Acknowledged",
    "RESOLVED": "Resolved",
}

FEEDBACK_CLIENTS: dict[str, str] = {
    "WEB": "Web",
    "ANDROID": "Android",
}

FEEDBACK_VOCABULARIES: dict[str, dict[str, str]] = {
    "kind": FEEDBACK_KINDS,
    "severity": FEEDBACK_SEVERITIES,
    "area": FEEDBACK_AREAS,
    "status": FEEDBACK_STATUSES,
    "client": FEEDBACK_CLIENTS,
}


def vocabulary_payload() -> dict[str, Any]:
    return {
        name: [{"value": value, "label": label} for value, label in members.items()]
        for name, members in FEEDBACK_VOCABULARIES.items()
    }


def validate_choice(name: str, value: str | None, *, required: bool = False) -> str | None:
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
    if value is None:
        return ""
    key = str(value)
    return FEEDBACK_VOCABULARIES.get(name, {}).get(key, key)
