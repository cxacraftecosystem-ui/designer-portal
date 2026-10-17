"""The User columns that never leave the API, in ANY payload — the one list every encoder imports.

``routes/auth.py`` and ``routes/users.py`` strip them from the user they serialise, and
``services/records.public_encode`` strips them from every User embedded in a record (a media file's
``uploadedBy``, a workshop's ``createdBy``, a review's reviewer, the data browser, the dataset export).
One tuple, so a column added to the two-step machinery cannot be hidden in one place and leaked in
another. ``tests/test_two_factor.py`` walks a fully populated User through every one of them.

Shared with the documentation portal, which keeps the same file under the same name.
"""

SECRET_USER_FIELDS: tuple[str, ...] = (
    "passwordHash",
    # Two-step verification (app/services/two_factor.py). The secrets are ciphertext and the codes are
    # hashes, and none of them is anybody's business but the server's; the counters and the locks say
    # how close somebody is to guessing their way in. `twoFactorEnabled` and `twoFactorEnabledAt` are
    # deliberately NOT here: the users screen shows the first and the account screen the second.
    "twoFactorSecret",
    "twoFactorPendingSecret",
    "twoFactorPendingSince",
    "twoFactorRecoveryCodes",
    "twoFactorLastStep",
    "twoFactorFailedAttempts",
    "twoFactorFailureWindowStartedAt",
    "twoFactorLockedUntil",
    "twoFactorLockouts",
    "twoFactorRecoveryFailedAttempts",
    "twoFactorRecoveryWindowStartedAt",
    "twoFactorRecoveryLockedUntil",
    "twoFactorRecoveryLockouts",
)

#: The one fact about ``passwordHash`` a client may know: whether there is one. Added beside the
#: stripped fields by both ``serialize_user`` functions (``routes/auth``, ``routes/users``), so the
#: account screen can offer "Set a password" and the users screen a link or a temporary password.
HAS_PASSWORD_KEY = "hasPassword"
