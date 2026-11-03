import asyncio
import os
from datetime import UTC, datetime

from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.core.deps import invalidate_cached_user
from app.core.security import hash_password
from app.services import access_roster


async def upsert_admin(email: str, name: str, password: str, role: str) -> None:
    is_master_admin = role == "MASTER_ADMIN"
    existing = await db.user.find_unique(where={"email": email})
    if existing and not is_master_admin:
        print(
            f"Left existing {role.lower()} user alone: {email}. This script resets only the master "
            "admin; reset anybody else from the users screen, with a password link, or with "
            "scripts/provision_account.py."
        )
        return
    password_hash = hash_password(password)
    now = datetime.now(UTC)
    if existing:
        await db.user.update(
            where={"email": email},
            data={
                "name": name,
                "passwordHash": password_hash,
                "passwordSetAt": now,
                "mustChangePassword": True,
                "sessionsValidFrom": now,
                "role": role,
                "canManageQuestionnaire": True,
            },
        )
        invalidate_cached_user(existing.id)
        print(f"Updated {role.lower()} user: {email}")
    else:
        created = await db.user.create(
            data={
                "email": email,
                "name": name,
                "passwordHash": password_hash,
                "passwordSetAt": now,
                "mustChangePassword": True,
                "role": role,
                "authProvider": "LOCAL",
                "canManageQuestionnaire": is_master_admin,
            }
        )
        invalidate_cached_user(created.id)
        print(f"Created {role.lower()} user: {email}")
    await access_roster.admit(
        email,
        admit_role=role,
        full_name=name,
        note="Seeded by scripts/seed_admin.py.",
        decided=False,
    )


async def main() -> None:
    settings = get_settings()
    master_admin_email = settings.master_admin_email.lower()
    master_admin_name = settings.master_admin_name
    await connect_db()
    try:
        email = os.getenv("ADMIN_EMAIL", "admin@example.com").lower()
        name = os.getenv("ADMIN_NAME", "Repository Admin")
        password = os.getenv("ADMIN_PASSWORD")
        if not password:
            raise RuntimeError("ADMIN_PASSWORD must be set in .env before seeding local admin accounts")
        await upsert_admin(master_admin_email, master_admin_name, password, "MASTER_ADMIN")
        if email != master_admin_email:
            await upsert_admin(email, name, password, "ADMIN")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
