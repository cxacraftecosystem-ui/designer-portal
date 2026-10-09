from pydantic import EmailStr, Field

from app.core.security import MAX_PASSWORD_LENGTH
from app.schemas.common import APIModel


class UserCreate(APIModel):
    """``POST /api/users``. See ``services/account_provisioning.create_account`` for who may send it.

    ``password`` is stored as sent — never trimmed — and bounded at the one ceiling every password
    field shares (``security.MAX_PASSWORD_LENGTH``), so a temporary password can always be typed back
    as ``currentPassword`` when the person replaces it.

    ``mustChangePassword`` defaults to True: a password one person typed for another is a shared
    secret, and the account is sent to the change-password screen at its first sign-in unless the
    provisioner says otherwise. The six capability flags are for admins only; a ministry admin who
    sets one is refused rather than silently ignored.
    """

    email: EmailStr
    name: str = Field(min_length=1, max_length=160)
    password: str = Field(min_length=8, max_length=MAX_PASSWORD_LENGTH)
    role: str = "RESEARCHER"
    mustChangePassword: bool = True
    canManageQuestionnaire: bool = False
    canManageCrafts: bool = False
    canManageWorkshops: bool = False
    canReview: bool = False
    canViewProvenance: bool = False
    canDownloadDataset: bool = False


class UserUpdate(APIModel):
    """``PATCH /api/users/{id}``. Every field optional; what each caller may send is per field —
    see ``routes/users.update_user``.

    ``mustChangePassword`` alone asks an existing account to choose a new password at its next
    sign-in without touching the one it has. Sent beside ``password`` it decides whether the new
    password is temporary; left out there, it is (True), because a password set for somebody else is
    one they did not choose.

    ``mustChangePassword: false`` sent ON ITS OWN WITHDRAWS a required change (2026-10-09): a
    provisioner on an account it manages, saying the password the account holds is final. It ends no
    session — one opened with that password is opened with the account's final one.

    ``role`` RAISED IS A PROMOTION, and a promotion does not carry a lower provisioner's credential
    upward (rule 5 of ``services/account_provisioning.py``): the request withdraws every outstanding
    password link of the account, and is refused (409) while the account still holds a temporary
    password, unless it sets ``password`` too.

    ``email`` CHANGED is a move between addresses, and a bar stays with the account: a provisioner
    who is not an admin gets a 409 moving an account OFF an address an administrator rejected or
    suspended, or a DESIGNER off one whose empanelment was ended — as onto one — or ANY account
    whose address carries an ended empanelment onto an address with an active one, while an admin's
    move carries the bar to the new address and leaves the old one barred. Any spelling of another
    account's Gmail mailbox is a 409 "Email already exists" for everybody, and any spelling of the
    master admin's mailbox is 403 to everybody but a master admin.
    """

    email: EmailStr | None = None
    name: str | None = Field(default=None, min_length=1, max_length=160)
    password: str | None = Field(default=None, min_length=8, max_length=MAX_PASSWORD_LENGTH)
    role: str | None = None
    mustChangePassword: bool | None = None
    canManageQuestionnaire: bool | None = None
    canManageCrafts: bool | None = None
    canManageWorkshops: bool | None = None
    canReview: bool | None = None
    canViewProvenance: bool | None = None
    canDownloadDataset: bool | None = None
