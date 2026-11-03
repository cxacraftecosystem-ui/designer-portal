SECRET_USER_FIELDS: tuple[str, ...] = (
    "passwordHash",
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

HAS_PASSWORD_KEY = "hasPassword"
