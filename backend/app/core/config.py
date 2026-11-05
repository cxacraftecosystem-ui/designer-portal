from functools import lru_cache
from ipaddress import ip_address
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit, urlunsplit

from pydantic import AnyHttpUrl, Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_BACKEND_ROOT = Path(__file__).resolve().parents[2]

_ALLOWED_JWT_ALGORITHMS = frozenset({"HS256", "HS384", "HS512"})

_LOCAL_DB_HOSTNAMES = frozenset({"localhost", "host.docker.internal", "postgres", "db"})

_SSL_QUERY_KEYS = frozenset({"sslmode", "sslaccept", "sslcert", "sslidentity", "sslpassword"})


def _is_local_db_host(host: str) -> bool:
    host = host.strip().strip("[]").lower()
    if (
        not host
        or host in _LOCAL_DB_HOSTNAMES
        or host.endswith((".local", ".internal", ".localhost"))
    ):
        return True
    try:
        address = ip_address(host)
    except ValueError:
        return False
    return address.is_loopback or address.is_private or address.is_link_local


def _with_explicit_sslmode(url: str, require: bool | None) -> str:
    if require is False:
        return url
    try:
        parts = urlsplit(url)
    except ValueError:
        return url
    if not parts.scheme.startswith("postgres"):
        return url
    existing_keys = {key.lower() for key, _ in parse_qsl(parts.query, keep_blank_values=True)}
    if existing_keys & _SSL_QUERY_KEYS:
        return url
    if require is None and _is_local_db_host(parts.hostname or ""):
        return url
    query = f"{parts.query}&sslmode=require" if parts.query else "sslmode=require"
    return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))


class Settings(BaseSettings):
    database_url: str = Field(alias="DATABASE_URL")
    database_use_transaction_pooler: bool = Field(
        default=True, alias="DATABASE_USE_TRANSACTION_POOLER"
    )
    database_connection_limit: int = Field(default=10, alias="DATABASE_CONNECTION_LIMIT")
    database_pool_timeout: int | None = Field(default=None, alias="DATABASE_POOL_TIMEOUT")
    database_connect_timeout: int = Field(default=30, alias="DATABASE_CONNECT_TIMEOUT")
    database_require_ssl: bool | None = Field(default=None, alias="DATABASE_REQUIRE_SSL")
    jwt_secret: str = Field(alias="JWT_SECRET")
    jwt_algorithm: str = "HS256"
    jwt_expires_minutes: int = Field(default=60 * 24 * 7, alias="JWT_EXPIRES_MINUTES")
    dataset_token_expires_minutes: int = Field(
        default=60 * 24 * 30, alias="DATASET_TOKEN_EXPIRES_MINUTES"
    )
    allow_weak_jwt_secret: bool = Field(default=False, alias="ALLOW_WEAK_JWT_SECRET")

    auth_user_cache_enabled: bool = Field(default=True, alias="AUTH_USER_CACHE_ENABLED")
    auth_user_cache_ttl_seconds: float = Field(default=5.0, alias="AUTH_USER_CACHE_TTL_SECONDS")
    auth_user_cache_max_entries: int = Field(default=512, alias="AUTH_USER_CACHE_MAX_ENTRIES")
    secrets_encryption_key: str | None = Field(default=None, alias="SECRETS_ENCRYPTION_KEY")
    two_factor_encryption_key: str | None = Field(default=None, alias="TWO_FACTOR_ENCRYPTION_KEY")
    two_factor_previous_keys: str | None = Field(default=None, alias="TWO_FACTOR_PREVIOUS_KEYS")
    two_factor_key_required: bool = Field(default=False, alias="TWO_FACTOR_KEY_REQUIRED")

    security_hsts_enabled: bool = Field(default=True, alias="SECURITY_HSTS_ENABLED")
    security_hsts_max_age: int = Field(default=63_072_000, alias="SECURITY_HSTS_MAX_AGE")
    security_force_hsts: bool = Field(default=False, alias="SECURITY_FORCE_HSTS")
    backend_expose_docs: bool = Field(default=False, alias="BACKEND_EXPOSE_DOCS")

    aws_access_key_id: str = Field(alias="AWS_ACCESS_KEY_ID")
    aws_secret_access_key: str = Field(alias="AWS_SECRET_ACCESS_KEY")
    aws_region: str = Field(default="us-east-1", alias="AWS_REGION")
    aws_s3_bucket: str = Field(alias="AWS_S3_BUCKET")
    aws_s3_endpoint: str | None = Field(default=None, alias="AWS_S3_ENDPOINT")
    aws_s3_public_base_url: str | None = Field(default=None, alias="AWS_S3_PUBLIC_BASE_URL")
    aws_s3_sse_algorithm: str = Field(default="AES256", alias="AWS_S3_SSE_ALGORITHM")

    media_presigned_reads: bool = Field(default=False, alias="MEDIA_PRESIGNED_READS")
    media_presigned_read_ttl_seconds: int = Field(
        default=900, alias="MEDIA_PRESIGNED_READ_TTL_SECONDS"
    )

    asr_model_dir: str | None = Field(default=None, alias="ASR_MODEL_DIR")

    mail_from_address: str | None = Field(default=None, alias="MAIL_FROM_ADDRESS")
    mail_from_name: str = Field(default="Design Prototype Workshop", alias="MAIL_FROM_NAME")
    mail_reply_to: str | None = Field(default=None, alias="MAIL_REPLY_TO")
    mail_ses_region: str = Field(default="ap-south-1", alias="MAIL_SES_REGION")
    mail_ses_configuration_set: str | None = Field(
        default=None, alias="MAIL_SES_CONFIGURATION_SET"
    )
    mail_max_attempts: int = Field(default=5, alias="MAIL_MAX_ATTEMPTS")
    mail_batch_size: int = Field(default=10, alias="MAIL_BATCH_SIZE")
    weekly_digest_enabled: bool = Field(default=True, alias="WEEKLY_DIGEST_ENABLED")
    report_schedules_enabled: bool = Field(default=True, alias="REPORT_SCHEDULES_ENABLED")
    report_schedule_attach_max_bytes: int = Field(
        default=7 * 1024 * 1024, alias="REPORT_SCHEDULE_ATTACH_MAX_BYTES"
    )

    ministry_exports_enabled: bool = Field(default=False, alias="MINISTRY_EXPORTS_ENABLED")
    ministry_exports_run_hour_ist: int = Field(default=6, alias="MINISTRY_EXPORTS_RUN_HOUR_IST")
    ministry_exports_prefix: str = Field(default="exports/ministry/", alias="MINISTRY_EXPORTS_PREFIX")
    ministry_exports_link_seconds: int = Field(default=300, alias="MINISTRY_EXPORTS_LINK_SECONDS")
    ministry_exports_max_workshops: int = Field(default=2000, alias="MINISTRY_EXPORTS_MAX_WORKSHOPS")

    app_env: str = Field(default="development", alias="APP_ENV")
    next_public_app_url: AnyHttpUrl | str = Field(
        default="http://localhost:3000", alias="NEXT_PUBLIC_APP_URL"
    )
    backend_cors_origins: str = Field(default="http://localhost:3000", alias="BACKEND_CORS_ORIGINS")
    forwarded_client_hops: int = Field(default=2, ge=1, le=5, alias="FORWARDED_CLIENT_HOPS")
    web_proxy_secret: str | None = Field(default=None, alias="WEB_PROXY_SECRET")

    google_client_id: str | None = Field(default=None, alias="GOOGLE_CLIENT_ID")
    google_android_client_id: str | None = Field(default=None, alias="GOOGLE_ANDROID_CLIENT_ID")
    microsoft_client_id: str | None = Field(default=None, alias="MICROSOFT_CLIENT_ID")
    microsoft_client_secret: str | None = Field(default=None, alias="MICROSOFT_CLIENT_SECRET")
    microsoft_tenant: str = Field(default="common", alias="MICROSOFT_TENANT")
    yahoo_client_id: str | None = Field(default=None, alias="YAHOO_CLIENT_ID")
    yahoo_client_secret: str | None = Field(default=None, alias="YAHOO_CLIENT_SECRET")
    master_admin_email: str = Field(alias="MASTER_ADMIN_EMAIL")
    master_admin_name: str = Field(default="Ankit Kumar", alias="MASTER_ADMIN_NAME")
    default_signup_role: str = Field(default="CROWDSOURCE_VOLUNTEER", alias="DEFAULT_SIGNUP_ROLE")

    access_pending_max: int = Field(default=500, alias="ACCESS_PENDING_MAX")

    elevenlabs_api_key: str | None = Field(default=None, alias="ELEVENLABS_API_KEY")
    elevenlabs_stt_model: str = Field(default="scribe_v2", alias="ELEVENLABS_STT_MODEL")
    deepgram_api_key: str | None = Field(default=None, alias="DEEPGRAM_API_KEY")
    deepgram_stt_model: str = Field(default="nova-3", alias="DEEPGRAM_STT_MODEL")
    meta_model_api_key: str | None = Field(default=None, alias="META_MODEL_API_KEY")
    muse_stt_model: str = Field(default="muse-voice-transcribe-1.0", alias="MUSE_STT_MODEL")
    openai_api_key: str | None = Field(default=None, alias="OPENAI_API_KEY")
    openai_transcription_model: str = Field(default="whisper-1", alias="OPENAI_TRANSCRIPTION_MODEL")
    openai_chat_model: str = Field(default="gpt-6-luna", alias="OPENAI_CHAT_MODEL")
    anthropic_api_key: str | None = Field(default=None, alias="ANTHROPIC_API_KEY")
    ai_text_provider: str = Field(default="GEMINI", alias="AI_TEXT_PROVIDER")
    gemini_api_key: str | None = Field(default=None, alias="GEMINI_API_KEY")
    gemini_api_keys_raw: str = Field(default="", alias="GEMINI_API_KEYS")
    gemini_measurement_model: str = Field(
        default="gemini-3.8-flash", alias="GEMINI_MEASUREMENT_MODEL"
    )
    maptiler_api_key: str | None = Field(default=None, alias="NEXT_PUBLIC_MAPTILER_API_KEY")
    media_queue_worker_enabled: bool = Field(default=False, alias="MEDIA_QUEUE_WORKER_ENABLED")
    media_queue_interval_seconds: float = Field(default=5.0, alias="MEDIA_QUEUE_INTERVAL_SECONDS")
    media_queue_batch_size: int = Field(default=3, alias="MEDIA_QUEUE_BATCH_SIZE")
    media_queue_insights_concurrency: int = Field(default=2, alias="MEDIA_QUEUE_INSIGHTS_CONCURRENCY")
    media_queue_job_max_attempts: int = Field(default=3, alias="MEDIA_QUEUE_JOB_MAX_ATTEMPTS")

    media_upload_max_bytes: int = Field(default=1_073_741_824, alias="MEDIA_UPLOAD_MAX_BYTES")

    scale_cache_enabled: bool = Field(default=False, alias="SCALE_CACHE_ENABLED")
    scale_cache_backend: str = Field(default="memory", alias="SCALE_CACHE_BACKEND")
    scale_cache_ttl_seconds: float = Field(default=30.0, alias="SCALE_CACHE_TTL_SECONDS")
    scale_cache_max_entries: int = Field(default=512, alias="SCALE_CACHE_MAX_ENTRIES")
    scale_cache_max_bytes: int = Field(default=33_554_432, alias="SCALE_CACHE_MAX_BYTES")
    scale_cache_max_entry_bytes: int = Field(default=1_048_576, alias="SCALE_CACHE_MAX_ENTRY_BYTES")
    scale_cache_singleflight_timeout_seconds: float = Field(
        default=10.0, alias="SCALE_CACHE_SINGLEFLIGHT_TIMEOUT_SECONDS"
    )
    scale_redis_url: str | None = Field(default=None, alias="SCALE_REDIS_URL")
    scale_redis_timeout_seconds: float = Field(default=0.5, alias="SCALE_REDIS_TIMEOUT_SECONDS")

    scale_keyset_pagination_enabled: bool = Field(
        default=False, alias="SCALE_KEYSET_PAGINATION_ENABLED"
    )

    scale_approx_count_enabled: bool = Field(default=False, alias="SCALE_APPROX_COUNT_ENABLED")
    scale_approx_count_threshold: int = Field(default=5_000, alias="SCALE_APPROX_COUNT_THRESHOLD")

    scale_rate_limit_enabled: bool = Field(default=False, alias="SCALE_RATE_LIMIT_ENABLED")
    scale_rate_limit_requests: int = Field(default=120, alias="SCALE_RATE_LIMIT_REQUESTS")
    scale_rate_limit_window_seconds: float = Field(
        default=60.0, alias="SCALE_RATE_LIMIT_WINDOW_SECONDS"
    )

    database_read_replica_url: str | None = Field(default=None, alias="DATABASE_READ_REPLICA_URL")
    database_read_replica_use_transaction_pooler: bool | None = Field(
        default=None, alias="DATABASE_READ_REPLICA_USE_TRANSACTION_POOLER"
    )

    aadhaar_offline_verification_enabled: bool = Field(
        default=False, alias="AADHAAR_OFFLINE_VERIFICATION_ENABLED"
    )
    aadhaar_online_auth_enabled: bool = Field(default=False, alias="AADHAAR_ONLINE_AUTH_ENABLED")
    aadhaar_offline_xml_max_age_days: float = Field(
        default=3.0, alias="AADHAAR_OFFLINE_XML_MAX_AGE_DAYS"
    )
    aadhaar_secure_qr_max_age_days: float = Field(default=0.0, alias="AADHAAR_SECURE_QR_MAX_AGE_DAYS")
    aadhaar_extra_trusted_certs: str = Field(default="", alias="AADHAAR_EXTRA_TRUSTED_CERTS")

    aadhaar_env: str = Field(default="preprod", alias="AADHAAR_ENV")
    aadhaar_aua_code: str | None = Field(default=None, alias="AADHAAR_AUA_CODE")
    aadhaar_sub_aua_code: str | None = Field(default=None, alias="AADHAAR_SUB_AUA_CODE")
    aadhaar_aua_license_key: str | None = Field(default=None, alias="AADHAAR_AUA_LICENSE_KEY")
    aadhaar_asa_license_key: str | None = Field(default=None, alias="AADHAAR_ASA_LICENSE_KEY")
    aadhaar_asa_otp_url: str | None = Field(default=None, alias="AADHAAR_ASA_OTP_URL")
    aadhaar_asa_auth_url: str | None = Field(default=None, alias="AADHAAR_ASA_AUTH_URL")
    aadhaar_asa_kyc_url: str | None = Field(default=None, alias="AADHAAR_ASA_KYC_URL")
    aadhaar_aua_signing_pfx_path: str | None = Field(
        default=None, alias="AADHAAR_AUA_SIGNING_PFX_PATH"
    )
    aadhaar_aua_signing_pfx_password: str | None = Field(
        default=None, alias="AADHAAR_AUA_SIGNING_PFX_PASSWORD"
    )
    aadhaar_uidai_encryption_cert_path: str | None = Field(
        default=None, alias="AADHAAR_UIDAI_ENCRYPTION_CERT_PATH"
    )
    aadhaar_uidai_signature_cert_path: str | None = Field(
        default=None, alias="AADHAAR_UIDAI_SIGNATURE_CERT_PATH"
    )
    aadhaar_online_name_check: bool = Field(default=True, alias="AADHAAR_ONLINE_NAME_CHECK")
    aadhaar_otp_channel: str = Field(default="00", alias="AADHAAR_OTP_CHANNEL")
    aadhaar_ekyc_enabled: bool = Field(default=False, alias="AADHAAR_EKYC_ENABLED")
    aadhaar_kua_license_key: str | None = Field(default=None, alias="AADHAAR_KUA_LICENSE_KEY")
    aadhaar_asa_timeout_seconds: float = Field(default=20.0, alias="AADHAAR_ASA_TIMEOUT_SECONDS")
    aadhaar_throttle_salt: str | None = Field(default=None, alias="AADHAAR_THROTTLE_SALT")
    aadhaar_otp_limit_per_user: int = Field(default=5, alias="AADHAAR_OTP_LIMIT_PER_USER")
    aadhaar_otp_limit_per_uid: int = Field(default=3, alias="AADHAAR_OTP_LIMIT_PER_UID")
    aadhaar_otp_window_minutes: int = Field(default=60, alias="AADHAAR_OTP_WINDOW_MINUTES")
    aadhaar_fingerprint_key: str | None = Field(default=None, alias="AADHAAR_FINGERPRINT_KEY")
    artisan_duplicate_hold: bool = Field(default=True, alias="ARTISAN_DUPLICATE_HOLD")
    aadhaar_log_retention_enabled: bool = Field(default=True, alias="AADHAAR_LOG_RETENTION_ENABLED")
    aadhaar_log_archive_bucket: str | None = Field(default=None, alias="AADHAAR_LOG_ARCHIVE_BUCKET")
    aadhaar_log_archive_prefix: str = Field(
        default="aadhaar-log-archive/", alias="AADHAAR_LOG_ARCHIVE_PREFIX"
    )
    aadhaar_log_archive_kms_key_id: str | None = Field(
        default=None, alias="AADHAAR_LOG_ARCHIVE_KMS_KEY_ID"
    )
    aadhaar_log_archive_years: int = Field(default=5, ge=5, alias="AADHAAR_LOG_ARCHIVE_YEARS")
    aadhaar_signing_kms_key_id: str | None = Field(default=None, alias="AADHAAR_SIGNING_KMS_KEY_ID")
    aadhaar_aua_signing_cert_path: str | None = Field(
        default=None, alias="AADHAAR_AUA_SIGNING_CERT_PATH"
    )

    ai_answer_drafts_enabled: bool = Field(default=False, alias="AI_ANSWER_DRAFTS_ENABLED")

    questionnaire_v2: bool = Field(default=True, alias="QUESTIONNAIRE_V2")
    questionnaire_min_android_version_code_for_create: int | None = Field(
        default=None, ge=1, alias="QUESTIONNAIRE_MIN_ANDROID_VERSION_CODE_FOR_CREATE"
    )
    questionnaire_min_web_version_code_for_create: int | None = Field(
        default=None, ge=1, alias="QUESTIONNAIRE_MIN_WEB_VERSION_CODE_FOR_CREATE"
    )

    cross_portal_identity_enabled: bool = Field(
        default=False, alias="CROSS_PORTAL_IDENTITY_ENABLED"
    )
    cross_portal_token: str | None = Field(default=None, alias="CROSS_PORTAL_TOKEN")
    field_repository_api_url: str | None = Field(default=None, alias="FIELD_REPOSITORY_API_URL")

    artisan_cards_enabled: bool = Field(default=True, alias="ARTISAN_CARDS_ENABLED")
    artisan_card_key: str | None = Field(default=None, alias="ARTISAN_CARD_KEY")
    artisan_card_key_version: int = Field(default=1, ge=1, le=9, alias="ARTISAN_CARD_KEY_VERSION")
    artisan_card_key_previous: str | None = Field(default=None, alias="ARTISAN_CARD_KEY_PREVIOUS")
    artisan_card_resolve_miss_limit: int = Field(
        default=20, ge=1, alias="ARTISAN_CARD_RESOLVE_MISS_LIMIT"
    )
    artisan_card_issuer: str = Field(default="D", alias="ARTISAN_CARD_ISSUER")

    unified_search_enabled: bool = Field(default=False, alias="UNIFIED_SEARCH_ENABLED")
    search_embeddings_enabled: bool = Field(default=False, alias="SEARCH_EMBEDDINGS_ENABLED")
    search_embedding_provider: str = Field(default="openai", alias="SEARCH_EMBEDDING_PROVIDER")
    search_embedding_model: str = Field(
        default="text-embedding-3-small", alias="SEARCH_EMBEDDING_MODEL"
    )
    cross_portal_search_enabled: bool = Field(default=False, alias="CROSS_PORTAL_SEARCH_ENABLED")

    cross_portal_coverage_enabled: bool = Field(
        default=False, alias="CROSS_PORTAL_COVERAGE_ENABLED"
    )
    field_repository_web_url: str | None = Field(default=None, alias="FIELD_REPOSITORY_WEB_URL")

    model_config = SettingsConfigDict(
        env_file=str(_BACKEND_ROOT / ".env"),
        env_file_encoding="utf-8",
        populate_by_name=True,
        extra="ignore",
    )

    @field_validator("jwt_algorithm")
    @classmethod
    def _normalise_jwt_algorithm(cls, value: str) -> str:
        algorithm = value.strip().upper()
        if algorithm not in _ALLOWED_JWT_ALGORITHMS:
            raise ValueError(
                f"JWT_ALGORITHM must be one of {sorted(_ALLOWED_JWT_ALGORITHMS)}; got {value!r}"
            )
        return algorithm

    @model_validator(mode="after")
    def _harden_database_url(self) -> Settings:
        self.database_url = _with_explicit_sslmode(self.database_url, self.database_require_ssl)
        if self.database_read_replica_url:
            self.database_read_replica_url = _with_explicit_sslmode(
                self.database_read_replica_url, self.database_require_ssl
            )
        return self

    @model_validator(mode="after")
    def _require_complete_online_aadhaar_config(self) -> Settings:
        if not self.aadhaar_online_auth_enabled:
            return self
        required = {
            "AADHAAR_AUA_CODE": self.aadhaar_aua_code,
            "AADHAAR_SUB_AUA_CODE": self.aadhaar_sub_aua_code,
            "AADHAAR_AUA_LICENSE_KEY": self.aadhaar_aua_license_key,
            "AADHAAR_ASA_LICENSE_KEY": self.aadhaar_asa_license_key,
            "AADHAAR_ASA_OTP_URL": self.aadhaar_asa_otp_url,
            "AADHAAR_ASA_AUTH_URL": self.aadhaar_asa_auth_url,
            "AADHAAR_THROTTLE_SALT": self.aadhaar_throttle_salt,
        }
        if (self.aadhaar_signing_kms_key_id or "").strip():
            required["AADHAAR_AUA_SIGNING_CERT_PATH"] = self.aadhaar_aua_signing_cert_path
        else:
            required["AADHAAR_AUA_SIGNING_PFX_PATH"] = self.aadhaar_aua_signing_pfx_path
            required["AADHAAR_AUA_SIGNING_PFX_PASSWORD"] = self.aadhaar_aua_signing_pfx_password
        if self.aadhaar_ekyc_enabled:
            required["AADHAAR_KUA_LICENSE_KEY"] = self.aadhaar_kua_license_key
            required["AADHAAR_ASA_KYC_URL"] = self.aadhaar_asa_kyc_url
        missing = [name for name, value in required.items() if not (value or "").strip()]
        if self.aadhaar_env not in ("preprod", "prod"):
            missing.append("AADHAAR_ENV (must be preprod or prod)")
        if missing:
            raise ValueError(
                "AADHAAR_ONLINE_AUTH_ENABLED is true but these are not configured: "
                + ", ".join(missing)
                + ". Fill them in (docs/AADHAAR.md, 'Operator runbook') or switch the flag off."
            )
        return self

    @property
    def cors_origins(self) -> list[str]:
        origins: list[str] = []
        seen: set[str] = set()
        for chunk in self.backend_cors_origins.replace("\n", ",").split(","):
            origin = chunk.strip().strip("[]").strip().strip("\"'").strip().rstrip("/")
            if not origin or origin in seen:
                continue
            seen.add(origin)
            origins.append(origin)
        return origins

    @property
    def cors_allows_wildcard(self) -> bool:
        return "*" in self.cors_origins

    @property
    def cors_allow_credentials(self) -> bool:
        return not self.cors_allows_wildcard

    @property
    def google_client_ids(self) -> list[str]:
        return [value for value in [self.google_client_id, self.google_android_client_id] if value]

    @property
    def gemini_api_keys(self) -> list[str]:
        raw_parts: list[str] = []
        if self.gemini_api_key:
            raw_parts.append(self.gemini_api_key)
        for chunk in self.gemini_api_keys_raw.replace("\n", ",").split(","):
            candidate = chunk.strip()
            if candidate:
                raw_parts.append(candidate)
        seen: set[str] = set()
        ordered: list[str] = []
        for key in raw_parts:
            if key not in seen:
                seen.add(key)
                ordered.append(key)
        return ordered


@lru_cache
def get_settings() -> Settings:
    return Settings()


_LOCAL_APP_HOSTS = frozenset({"localhost", "0.0.0.0", "::", "::1", "host.docker.internal"})


def app_url_problem(url: str) -> str | None:
    from urllib.parse import urlsplit

    raw = str(url or "").strip()
    if not raw:
        return "NEXT_PUBLIC_APP_URL is empty"
    parts = urlsplit(raw)
    host = (parts.hostname or "").lower()
    if parts.scheme != "https":
        return f"NEXT_PUBLIC_APP_URL must be https, got {raw!r}"
    if not host:
        return f"NEXT_PUBLIC_APP_URL has no host: {raw!r}"
    if host in _LOCAL_APP_HOSTS or host.endswith((".localhost", ".local")):
        return f"NEXT_PUBLIC_APP_URL points at this machine: {raw!r}"
    try:
        address = ip_address(host)
    except ValueError:
        return None
    if address.is_loopback or address.is_private or address.is_unspecified or address.is_link_local:
        return f"NEXT_PUBLIC_APP_URL points at a private address: {raw!r}"
    return None


def verify_app_url_configuration(settings: Settings | None = None) -> None:
    current = settings or get_settings()
    if str(current.app_env or "").strip().lower() != "production":
        return
    problem = app_url_problem(str(current.next_public_app_url))
    if problem:
        raise RuntimeError(
            f"Refusing to start in production: {problem}. Every e-mailed, password, credential and "
            "cross-portal link is built from it. Set NEXT_PUBLIC_APP_URL to the public web address."
        )
