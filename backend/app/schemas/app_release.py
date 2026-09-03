from pydantic import Field

from app.schemas.common import APIModel


class AppReleasePublishRequest(APIModel):
    """Recorded after the master admin's app has uploaded its own APK to object storage."""

    versionCode: int = Field(ge=1)
    versionName: str = Field(min_length=1, max_length=64)
    objectKey: str = Field(min_length=1)
    url: str | None = None
    notes: str | None = Field(default=None, max_length=2000)
    sizeBytes: int | None = Field(default=None, ge=1)
    """How many bytes the uploaded APK is, so a phone can tell a finished download from a stopped one.

    **WHY THE API CARRIES IT AT ALL.** ``WorkshopRepository.downloadApk`` accepted any 2xx and handed
    the bytes straight to Android's package installer. A response is successful the moment its
    *headers* are; the body streams afterwards, so a link that dies half-way through 66 MB, a proxy
    that truncates, or a captive portal answering a short page all produce a file on disk and an
    install attempt on it. The OS refuses the truncated file — that signature and parse check is the
    real integrity boundary and stays it — but it refuses in a system dialog the app cannot read,
    stacked over a *forced* update prompt that has no "Later". The declared length is what lets the
    app say "that download did not finish, try again" instead.

    **OPTIONAL, AND THAT IS A WIRE REQUIREMENT RATHER THAN A CONVENIENCE.** Three publishers post to
    this route and they ship independently: the Android app's "Push update to all"
    (``WorkshopRepository.publishAppUpdate``), the browser panel
    (``frontend/components/settings/PublishAppUpdatePanel.tsx``), and
    ``.github/workflows/publish-android.yml``. ``APIModel`` is ``extra="forbid"``, so this class is
    the only thing that decides whether a body carrying the key is accepted — and making the key
    REQUIRED would answer 422 to every publisher that has not shipped yet, on the one route whose
    failure mode is "no new build reaches the fleet". A release published without it simply records
    no size claim, exactly as every row written before this column existed does.

    ``ge=1`` REFUSES A ZERO. Nothing legitimate declares a zero-byte APK, and a stored 0 would be
    indistinguishable from "no claim" to a reader that tested truthiness — the Android side
    additionally treats a non-positive value as unknown rather than as a claim, so the two ends agree
    without depending on each other's care. The workflow's own floor guard
    (``MIN_APK_BYTES``) refuses anything under 40 MiB long before this is reached.
    """
