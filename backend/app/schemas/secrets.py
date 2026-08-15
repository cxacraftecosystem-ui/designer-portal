"""Wire shapes for the runtime-editable API keys (see app.services.managed_secrets).

Note what is NOT here: no field of :class:`ManagedSecretDto` can carry a plaintext key. The list/
update/test responses all use that one model precisely so a future field cannot accidentally leak a
value into the ordinary (non-reveal) responses — the only model that carries plaintext is
:class:`ManagedSecretRevealDto`, returned by the single master-admin reveal endpoint.
"""

from datetime import datetime

from pydantic import Field

from app.schemas.common import APIModel


class ManagedSecretDto(APIModel):
    key: str
    label: str
    description: str | None = None
    # True when the key resolves to *something* — a stored override or an environment value.
    configured: bool
    # WHERE THE VALUE IN FORCE COMES FROM — not what the table looks like. "database" (a stored
    # override that this process can decrypt), "environment" (the deployed env var is what will be
    # sent), or "unset" (nothing usable anywhere). A stored override that cannot be decrypted is
    # NOT "database": the environment value is what every provider call uses, so that is what this
    # names, and `overrideUnreadable` below carries the fact that a dead row is sitting behind it.
    source: str
    # True when a ManagedSecret row exists whose ciphertext cannot be opened with this deployment's
    # key — the JWT_SECRET-rotated-without-SECRETS_ENCRYPTION_KEY case the service module documents.
    # The override is unrecoverable and must be re-entered; until it is, `source`/`hint`/`value`
    # describe whatever is standing in for it. A client that ignores this field is not wrong about
    # any value, only unaware that a broken row needs attention.
    overrideUnreadable: bool = False
    # Last four characters of the effective value, so two keys can be told apart without revealing
    # either. "…" when the value is too short to partially show safely. It follows `source`: when an
    # override cannot be decrypted this is the ENVIRONMENT key's hint, because a fingerprint that
    # names the key the deployment is not using is worse than showing none.
    hint: str | None = None
    lastStatus: str = "UNKNOWN"
    lastCheckedAt: datetime | None = None
    lastError: str | None = None
    # Display name (or email) of whoever last saved the override; null for environment-sourced keys.
    updatedBy: str | None = None
    updatedAt: datetime | None = None


class ManagedSecretSetIn(APIModel):
    """Body of PUT /secrets/{key}. Bounded because an API key is short — an 8 KB body here would be
    a paste of the wrong thing (a whole .env, a JSON credential file)."""

    value: str = Field(min_length=1, max_length=8000)


class ManagedSecretRevealDto(APIModel):
    """The eye button. Master admin only, one key at a time, and the read is audit-logged."""

    key: str
    # The value actually in force: the decrypted override when there is a readable one, otherwise
    # the environment value (that is what the provider calls use, so it is what the admin needs to
    # see). Null only when nothing resolves at all.
    #
    # THIS COMMENT USED TO CLAIM the value was also null "when a stored value cannot be decrypted
    # any more". It never was — the resolver falls back to the environment — and the response
    # simultaneously called that environment plaintext ``source: "database"``, so a client written
    # against the DTO could detect the case from neither field. Both halves are fixed: `source`
    # names where this plaintext came from, and `overrideUnreadable` says whether a dead override is
    # sitting behind it. Do not "restore" a null here; hiding the value in force from the one screen
    # that exists to show it is not an improvement on labelling it wrongly.
    value: str | None = None
    source: str
    # See ManagedSecretDto.overrideUnreadable — same meaning, same rotation casualty.
    overrideUnreadable: bool = False
