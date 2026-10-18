# Aadhaar verification

How an account holder, or a field worker on an artisan's behalf, proves a person's identity against
UIDAI — what is built, what is stored and why, the certificates it trusts, and how the owner turns
on online OTP authentication once the Ministry holds an AUA licence.

**Both features ship OFF.** Nothing in either client mentions Aadhaar verification until the owner
switches a flag on (§2).

| | |
|---|---|
| Backend | `backend/app/services/aadhaar/` (package header lists the modules), `backend/app/api/routes/aadhaar.py` |
| Web | `frontend/components/aadhaar/`, `frontend/lib/aadhaar.ts`, `frontend/lib/aadhaarQrImage.ts` |
| Android | `android/app/src/main/java/com/designprototype/workshop/ui/aadhaar/AadhaarVerification.kt`, `android/app/src/main/java/com/designprototype/workshop/data/AadhaarApi.kt` |
| Certificates | `backend/app/data/uidai/` + `manifest.json` |
| Schema | `20261016110000_aadhaar_verification` |
| Tests | `backend/tests/test_aadhaar_offline.py`, `test_aadhaar_online.py`, `test_aadhaar_api.py`, `test_aadhaar_names.py`; `frontend/e2e/aadhaar-unit.spec.ts`; `AadhaarVerificationTest.kt` |

---

## 1. What is built

| Method | Who may use it | Licence needed | Flag |
|---|---|---|---|
| **Secure QR** — the large QR on the Aadhaar letter, PVC card and e-Aadhaar. Android scans it with the camera (CameraX + ML Kit, ZXing fallback — the scanner the app already ships) or reads it from a picture; the web reads it from an uploaded picture (zxing-cpp compiled to WebAssembly, loaded only on that screen). | anyone, with the resident's consent | none | `AADHAAR_OFFLINE_VERIFICATION_ENABLED` |
| **Paperless Offline e-KYC** — the share-code-protected ZIP the resident downloads from UIDAI, plus the share code; optionally the mobile number / e-mail, checked against the hashes in the file. Web and Android. | same | none | same |
| **Online OTP authentication** (Auth API 2.5) and **e-KYC** (e-KYC API 2.5) as an AUA/KUA through an ASA. | the deployment, once licensed | **AUA** (and KUA for e-KYC) | `AADHAAR_ONLINE_AUTH_ENABLED` (+ `AADHAAR_EKYC_ENABLED`) |

Uses:

1. **An account** — any signed-in account, from Settings (web `/settings`; Android Settings →
   "Aadhaar verification"). Admins see a badge on the Users screen and decide name mismatches there.
2. **An artisan record** — from the artisan's edit page on the web and the artisan's detail screen on
   Android, by somebody who may edit that artisan (its recorder, an admin, a professor+ outranking the
   recorder, or an EDIT grantee — the bar `PATCH /artisans/{id}` sets), and not by an inspector or
   director of the workshop it is filed under.

**UIDAI's signature is always checked on the server**, never on a client: the client sends the QR
text, or the ZIP and share code, over TLS.

### Not built, and why

* **The new Aadhaar app's "Verifiable Credential" (2025)** — selective disclosure and offline face
  verification shared from the `in.gov.uidai.pehchaan` app. It is not the Secure QR format, has no
  public specification or signing certificate, and needs registration as an **OVSE** (Offline
  Verification Seeking Entity) at `ovse.uidai.gov.in`: a domain, callback URL and a Class 2/3
  certificate. Source: UIDAI OVSE Handbook, January 2026. Nothing about it can be verified offline
  with a published certificate, so it is not implemented.
* ⚠ **Regulation 13A** (Aadhaar (Authentication and Offline Verification) Regulations, 2021, as
  amended 9 December 2025) requires registration for "Aadhaar Paperless Offline e-KYC verification or
  Aadhaar Verifiable Credential verification through Aadhaar Application". It is ambiguous whether
  that covers verifying an offline e-KYC **file** as built here; UIDAI's handbook says Secure QR and
  e-Aadhaar verification need no registration. **The owner should confirm this with UIDAI before
  switching the offline flag on in production.**

---

## 2. The flags, and what "off" means

`AADHAAR_OFFLINE_VERIFICATION_ENABLED` and `AADHAAR_ONLINE_AUTH_ENABLED`, both `false` by default
(every variable: [ENVIRONMENT.md](ENVIRONMENT.md), "Aadhaar verification").

While a flag is off:

* its routes answer **404**, decided by a route dependency that runs before authentication and
  before the body is validated — an unflagged deployment is indistinguishable from one without the
  code (`test_with_both_flags_off_every_route_is_a_404_even_unauthenticated`);
* the `aadhaarVerif*` columns are stripped from `/me`, `GET /users`, and — always, flag or not —
  from every record payload (`records._AADHAAR_RESULT_KEYS`), so a User embedded as `createdBy`
  never carries somebody's verification;
* the clients learn the state from `features` on the session payload (`auth.serialize_user`; also
  `GET /api/features`), draw nothing while it is off, and Android caches it with the account, so a
  handset with no signal keeps the last answer and a handset never told reads "off".

`AADHAAR_ONLINE_AUTH_ENABLED` additionally **refuses to boot** unless every credential is present
(`Settings._require_complete_online_aadhaar_config`) and the PFX opens, the UIDAI encryption
certificate loads and has not expired, and a response-signing certificate is available
(`online/config.load_online_config`, called in `main.lifespan`).

---

## 3. Compliance: what is stored, and what never is

Under the Aadhaar Act, 2016 and the Aadhaar (Authentication and Offline Verification) Regulations,
2021.

**Stored** — nullable columns on `User` and `Artisan`, written only by `services/aadhaar/records.py`:

| Column | Meaning |
|---|---|
| `aadhaarVerifyStatus` | `VERIFIED`, `NAME_MISMATCH` (waiting for an admin) or `REJECTED` |
| `aadhaarVerifyMethod` | `SECURE_QR`, `OFFLINE_XML`, `ONLINE_OTP`, `ONLINE_EKYC` |
| `aadhaarVerifiedAt` | when this server verified it |
| `aadhaarVerifyLast4` | the last four digits, as UIDAI prints them in the reference id (or of the number typed for online auth; none for a VID) — `CHECK` constrained to four digits |
| `aadhaarVerifyName` | the name exactly as UIDAI signed it |
| `aadhaarVerifyBirthYear` | year of birth |
| `aadhaarVerifyDob` | **Artisan only** — the full date, because the artisan row already stores `dateOfBirth`; an account stores no date of birth, so keeps the year only |
| `aadhaarVerifyGender` | `M`/`F`/`T` |
| `aadhaarVerifySignedAt` | the document's own timestamp (reference id), or UIDAI's response time |
| `aadhaarVerifyTxn` | UIDAI's transaction id, online only |
| `aadhaarVerifyNameMatch` | the name comparison's verdict |
| `aadhaarVerifyById`, `aadhaarVerifyDecidedById`, `aadhaarVerifyDecidedAt` | who verified; which admin decided a mismatch, and when |

**Never stored or logged by verification**: the full Aadhaar number, the VID, the photograph, the
address, the QR text, the ZIP or XML, the share code, the mobile number or e-mail typed for the hash
check, the OTP, the PID block, the session key, any raw UIDAI response. Everything is processed in
memory and discarded when the request returns. The OTP throttle (`AadhaarOtpThrottle`) holds an HMAC
of the number under a server secret, the caller's id and a time — not the number. What the artisan
register keeps of a typed number is the next paragraph, not this one.

**The artisan register keeps no Aadhaar number (since 2026-10-17).** The number a researcher types
is the deduplication key, and it is stored only as `Artisan.aadhaarFingerprint` —
HMAC-SHA256 under `AADHAAR_FINGERPRINT_KEY`, unique — and `Artisan.aadhaarLast4`
(`services/aadhaar_fingerprint.py`; the same key and formula on the Field Repository). Every
response, export and screen shows `XXXX XXXX 9012` built from the last four, to every viewer; the
fingerprint is never served. Rows written before that date still hold the number in the legacy
`aadhaarNumber` column until the backfill and the drop migration run (`backend/DEPLOY_AWS.md`,
"Aadhaar fingerprint"). A verification whose last four digits disagree with the register's is
**refused** (`last4_mismatch`), because the card is somebody else's.

**Consent** is shown in full before any scan, upload or number entry — what is read, what is kept,
what is never kept, how to undo it — and must be ticked; the server refuses a request whose
`consent` is not `true` and audits the refusal. The words are one text on both clients
(`frontend/lib/aadhaar.ts` `consentPoints`; `AadhaarCopy.consentPoints`, held equal by
`AadhaarVerificationTest`). For an artisan, the box says the artisan heard it read out and agreed.

**Age.** An offline e-KYC file older than `AADHAAR_OFFLINE_XML_MAX_AGE_DAYS` (default 3) is refused.
UIDAI publishes no maximum; three days is the RBI KYC Master Direction's rule for an XML or QR
("generation date is not older than 3 days"), adopted here. A Secure QR is printed on a card and is
accepted at any age by default, with its date recorded.

**Name match** (`services/aadhaar/names.py`): case, accents, punctuation, honorifics
(Shri/Smt/Mohd …), word order, initials ("R. Meena" / "Rajesh Meena"), a dropped middle or family
name (when two words still match), and romanisation variants ("Lakshmi"/"Laxmi") are tolerated. A
mismatch does not refuse — the signature held — it parks the record at `NAME_MISMATCH` and shows both
names; an **admin** accepts or rejects it (never on their own account). Online OTP without e-KYC
returns no name: with `AADHAAR_ONLINE_NAME_CHECK` on, the record's name is sent for UIDAI's own exact
match (`Pi ms="E" mv="100"`); a UIDAI name failure (error 100) is refused with that reason.

**Withdrawal.** The account holder (or whoever may edit the artisan) can remove the result at any
time; every column is cleared and the removal is audited.

**Audit.** Every attempt — verified, mismatched, refused (with its code), OTP sent, accepted,
rejected, withdrawn — is a `RecordRevision` row (`recordType` `user` or `artisan`) under the key
`aadhaarVerification`, in the `{old, new}` shape both clients' history panels render, carrying the
method, outcome, refusal code and last four digits and nothing else; plus one `app.aadhaar` log line.
**Retention (Regulation 18(3)).** Entries older than two years are archived, a month at a time, to
`AADHAAR_LOG_ARCHIVE_BUCKET` under `aadhaar-log-archive/` (SSE-KMS when
`AADHAAR_LOG_ARCHIVE_KMS_KEY_ID` is set, SSE-S3 otherwise) and removed from the live table once the
object reads back; an archive is deleted `AADHAAR_LOG_ARCHIVE_YEARS` (at least 5) after its newest
entry. A record under an open `AadhaarLegalHold` (`scripts/aadhaar_legal_hold.py`) keeps its entries
live and its archives undeleted. The queue worker runs this once a day
(`services/aadhaar/log_retention.py`); the ledger is `AadhaarLogArchive`.

*Where the archive lives.* The bucket defaults to the **media bucket** (`AWS_S3_BUCKET`,
`designrepo-media-626159998512`), not a separate backup bucket. That bucket is versioned and
replicates every object to `designrepo-offsite-626159998512` (ap-south-2, Object Lock GOVERNANCE 35
days), and both keep noncurrent versions for 90 days. So "deleted after the archive period" means the
current object goes on the due date and its versions follow about 90 days later in both regions (up
to 125 if the object was rewritten in its last 35 days). With `AADHAAR_LOG_ARCHIVE_KMS_KEY_ID` set
the objects are SSE-KMS, and the replication rule does not copy SSE-KMS objects, so they then have
no off-site copy at all.

*The grant* (applied 2026-10-18): the API's IAM user (`designrepo-media`) holds `s3:PutObject`,
`s3:GetObject` (the read-back is a `head_object`) and `s3:DeleteObject` on `aadhaar-log-archive/*`
and nothing else there: no `s3:ListBucket`, nothing on `backups/` or `tfstate/` (the bucket policy
denies those to this user outright). It is statement `AadhaarLogArchive` of
`aws_iam_user_policy.media` in `infra/terraform/main.tf`, which arrives with the backup-hardening
change. If the grant is ever missing, each daily run is refused at the upload, deletes nothing and
logs the failure. With `AADHAAR_LOG_ARCHIVE_KMS_KEY_ID` set, the same user also needs
`kms:GenerateDataKey` and `kms:Decrypt` on that key. Optionally add a lifecycle rule on that prefix
that expires noncurrent versions sooner, to shorten the tail above.

**Duplicates and merges** ([ARTISAN_DUPLICATES.md](ARTISAN_DUPLICATES.md)). The same fingerprint
is a definitive duplicate, and different fingerprints rule a pair out. The duplicate check takes a typed
number in the request body only, fingerprints it, and names an Aadhaar reason only to the caller who
typed it. A merge is refused while either record still holds the legacy `aadhaarNumber`
(`aadhaar_backfill_pending`) or is named by an `AadhaarLegalHold` (`aadhaar_legal_hold`). The 30-day
merge snapshot holds the fingerprint and last four only. Moved revision rows keep their `createdAt`,
so log retention is unaffected.

---

## 4. The certificates

Downloaded 2026-10-16 from `backend.uidai.gov.in`, linked from
<https://uidai.gov.in/en/data-and-download>. Pinned as PEM in `backend/app/data/uidai/` with
`manifest.json`; `certs.py` refuses any file whose SHA-256 (over the DER) differs from its entry.

| File | Purpose | Valid to | SHA-256 (DER) |
|---|---|---|---|
| `uidai_offline_publickey_2026` | offline signature (current) | 2029-02-03 | `e0304b9e61ee3640ecddae2db4b617f2e2678f57dbc2826c2f86ac5c04f277df` |
| `uidai_offline_publickey_17022026` | offline signature | 2026-02-16 | `00573c692cf04fbee113ae5fd52f2a654d361bf8ee4d06c46008906b36df8169` |
| `uidai_offline_publickey_26022021` | offline signature (UIDAI's QR reader ships it as `UIDAI1.cer`) | 2024-02-27 | `e0f0f869d32efc7e80fae2223717a56dcf8b616f820b542a49e5bd5abf1c0f7d` |
| `uidai_offline_publickey_29032019` | offline signature | 2021-02-28 | `e0f5596d2c48e19f9cc184bccde7129e2d8987f905c73b87d1851c35c622d852` |
| `uidai_offline_publickey_26022019` | offline signature | 2019-04-09 | `35575c63c72106e803511004f94bfda54952b2cf2184c3ce1aad61f1964c58d0` |
| `uidai_prod_cdup` | Secure QR (UIDAI's Secure QR page) | 2020-06-07 | `fcf31a3f89211615ebd5d449ac33d8d381f654d2e2d15037d5cc8d4aeb72f836` |
| `uidai_12_06_18_cer` | Secure QR (UIDAI's Secure QR page) | 2021-06-07 | `49e298532243ce28e2633a7e4f403d9ca78cbfda8b3bbde70324837273ba4d31` |
| `uidai_auth_sign_prod_2023` | offline signature (signed offline XML around 2020) | 2023-05-27 | `7f7217582a4d7d711c4ca4168eb222573bdf084a7aded8f8d0c85c47a545923b` |
| `uidai_auth_prod` | online: production PID encryption | 2028-08-13 | `ddba7ac9273ee3550b7a08e2fa792f95237f7182b1ef95bc4ebab510a2f2198c` |
| `UIDAI_Pre-Prod_Encryption_20280825` (leaf only) | online: pre-production PID encryption | 2028-08-25 | `cf753afe3c6e6f2dbb820aaaa13a653751932d320f6c891bfb519293747e9f04` |
| `uidai_auth_sign_Prod_2026` | online: production response signature — **expired 2026-04-28** | 2026-04-28 | `be774abf3de06e5cfba682d3e06d000d95ff6cc3ce72b39c18a19fe57dc817fd` |
| `uidai_auth_sign_Pre-Prod_2026` | online: pre-production response signature — **expired 2026-05-03** | 2026-05-03 | `a4e459f8a3b5209714424f68b3d9b0884d626d94d5675970f9a540beab98a910` |

Source URLs are in the manifest. **Expired signing certificates stay trusted on purpose**: a QR
printed on a card in 2019 is signed with the key UIDAI used then, and a signature check does not
depend on a validity date. A verification is tried against every certificate of the purpose.

⚠ **Do not trust `okyc-publickey.cer`.** The live offline e-KYC page links every certificate to
`2026-05/okyc-publickey.cer`, which is a self-signed 2018 "hcl-aua" test certificate.

**Rotation.** When UIDAI publishes a new signing certificate, an operator adds its file to
`AADHAAR_EXTRA_TRUSTED_CERTS` (no deploy), and the next release adds it to the manifest with its
source URL and fingerprint.

---

## 5. The formats, and the checks

**Secure QR.** UIDAI's only specification is "Secure QR Code Specification, Version 0, March 2019"
(archived; no longer on the live site): the QR text is a decimal integer; as big-endian bytes it is
GZIP; decompressed, fields are `0xFF`-separated ISO-8859-1 — indicator, referenceId, name, dob,
gender, care-of, district, landmark, house, location, pincode, post office, state, street,
sub-district, VTC — then the JPEG 2000 photo, the e-mail/mobile hashes the indicator announces, and
the **last 256 bytes are an RSA PKCS#1 v1.5 SHA-256 signature over every byte before them**. Every
QR printed since about 2020 starts `V2`/`V3`/`V5`; UIDAI has **not published** that layout. Open
readers agree it is the marker, the same fields, the last four digits of the mobile, then the photo.
This server reads only the five leading fields, which come before the photo in every layout, so the
unpublished tail does not matter; the signature check does not depend on the layout at all. The
referenceId is the last four digits of the Aadhaar number and the IST time of generation,
`YYYYMMDDHHMMSSsss`. Decimal-to-bytes is chunked (Python refuses `int()` past 4,300 digits);
decompression is bounded at 64 KB.

**Offline e-KYC.** A ZIP holding one XML, encrypted with the share code — ZipCrypto (read by
`zipfile`) or WinZip AES (decrypted in `offline_xml._aes_member`: PBKDF2-HMAC-SHA1 ×1000, AES-CTR,
HMAC-SHA1-80). Root `OfflinePaperlessKyc referenceId=…`, `UidData/Poi name dob gender e m`, `Poa`,
`Pht`, and an **enveloped XMLDSig**: genuine files are RSA-SHA1 over a SHA-256 digest with inclusive
C14N. The mobile/e-mail hash: `sha256(value + shareCode)` as hex, re-hashed to `max(1, last digit
of the Aadhaar)` rounds in all.

**The XML signature check** (`xmldsig.py`) is deliberately narrow, against signature wrapping:
exactly one `Signature`, a direct child of the root; exactly one `Reference`, `URI=""` (the whole
document) — so the data read afterwards is the data signed; only the enveloped-signature transform
and a C14N; RSA with SHA-1 or SHA-256; and **the key is never taken from the document's `KeyInfo`**,
only from the pinned certificates. The parser resolves no entities, loads no DTD, touches no
network, and a `DOCTYPE` is refused before parsing.

⚠ **libxml2 quirk**: canonicalizing `SignedInfo` *in place* emits a spurious `xmlns=""` on
`<Transforms>` (lxml 6.1.3 / libxml2 2.11.9), so genuine UIDAI files fail — the reason `signxml`
rejects them. `xmldsig.canonicalize` canonicalizes a deep copy. Confirmed against three genuine files
on 2026-10-16 (read in memory, not kept).

---

## 6. API

All under `/api/aadhaar`; every body carries `consent: true`.

| Route | Flag | Who |
|---|---|---|
| `GET /self`, `DELETE /self` | either | the account |
| `POST /self/secure-qr` `{qrText}` | offline | the account |
| `POST /self/offline-xml` `{zipBase64, shareCode, mobile?, email?}` | offline | the account |
| `POST /self/online/otp` `{uid}` → `{txn, token, expiresAt, uidType}` | online | the account |
| `POST /self/online/verify` `{uid, otp, txn, token}` | online | the account |
| the same five under `/artisans/{id}` | as above | may edit the artisan (§1) |
| `GET /artisans/{id}` | either | signed in; the UIDAI-signed fields only to its recorder and professor+, others see status/method/date |
| `POST /users/{id}/decision`, `POST /artisans/{id}/decision` `{accept}` | either | admin; never on their own account |
| `GET /api/features` | — | signed in |

Refusals are `{"detail": {"code", "message"}}`; `message` is the sentence a person reads.

---

## 7. Online authentication — how it works

Aadhaar Authentication API 2.5 Revision 1 (January 2022), OTP Request API 2.5, e-KYC API 2.5 —
still current as of October 2026 (changes since are circulars: SHA-256 signatures only after 30 June
2026, Circular 4/2026; pre-production quotas, Circular 3/2026).

1. **OTP** (`online/messages.otp_request`): `<Otp uid ac sa ver="2.5" txn ts lk type="A|V"><Opts
   ch/></Otp>`, signed by the AUA key, POSTed to `<AADHAAR_ASA_OTP_URL>/<ac>/<uid[0]>/<uid[1]>/<asalk>`
   (`0/0` for a VID). The server answers the client with UIDAI's `txn` and a ten-minute HMAC token
   binding (caller, record, hashed number, txn) — so nothing is held between the two steps.
2. **Auth** (`online/messages.auth_request`): the PID `<Pid ts ver="2.0" wadh=""><Demo><Pi ms="E"
   mv="100" name/></Demo><Pv otp/></Pid>` is encrypted (`online/crypto.py`): a fresh 32-byte session
   key; `Skey` = RSA-PKCS#1 v1.5 under UIDAI's encryption certificate, `ci` = its expiry `YYYYMMDD`;
   `Data` = `ts ‖ AES-256-GCM(pid, iv=ts[-12:], aad=ts[-16:]) ‖ tag`; `Hmac` = the same over
   `SHA-256(pid)`. `<Auth uid rc="Y" tid="" ac sa ver="2.5" txn lk><Uses … otp="y"/><Device/><Skey/>
   <Hmac/><Data type="X"/></Auth>`, signed (enveloped, inclusive C14N, RSA-SHA256/SHA-256, KeyInfo
   with the certificate), POSTed to the auth URL. The answer's UIDAI signature is verified (fail
   closed), then `ret`/`err` is mapped to a plain sentence (`online/errors.py`; operator-side codes
   — licence, signature, AUA code — tell the person to use another method and log the code).
3. **e-KYC** (when `AADHAAR_EKYC_ENABLED`): the signed Auth XML (txn prefixed `UKC:`) inside
   `<Kyc ver="2.5" ra="O" rc="Y" lr="N" de="Y" pfr="N"><Rad/></Kyc>`. **`de="Y"` asks the ASA to
   decrypt the response**, because UIDAI has not published the byte layout of the KUA-key encryption;
   the decrypted `KycRes` is accepted only under UIDAI's signature, and its `Poi` name, date of birth
   and gender go through the same name comparison as an offline document.

**Rate limits**: per account and per number (salted hash), `AADHAAR_OTP_LIMIT_PER_USER` /
`_PER_UID` per `AADHAAR_OTP_WINDOW_MINUTES`, answered with 429. UIDAI itself blocks an Aadhaar for 30
minutes after five unsubmitted OTPs (error 953) and refuses a second OTP inside 30 s (952).

**The UI recommends the VID** (a UIDAI requirement for AUAs: offer VID in place of the number).

### What only a pre-production run can settle

Tested here against a fake ASA with test keys; not yet against UIDAI:

* the position of `ts` in `Data` — the spec says "appended", UIDAI's reference clients put it first,
  as this does — and the Hmac reusing the ts-derived IV/AAD;
* the `<Device>` attributes for a non-biometric request (the spec mentions an undefined `udc`);
* request documents carry no XML namespace, as UIDAI's 2.5 templates show;
* **the response-signing certificate**: both pinned 2026 certificates have expired and UIDAI has
  published no successor — obtain the current one through the ASA and set
  `AADHAAR_UIDAI_SIGNATURE_CERT_PATH`, or every answer is refused as unverified.

---

## 8. Operator runbook — switching on online authentication

1. **Licence.** The Ministry (the government owner) applies to UIDAI to become an **AUA** (and a
   **KUA** for e-KYC), choosing a licensed **ASA** (Authentication Service Agency) to connect through
   — the application, agreements and fees are UIDAI's process (uidai.gov.in, "Authentication
   ecosystem"). UIDAI issues the **AUA code** and **licence key**; the ASA issues its **licence key**
   and its endpoint URLs. Note UIDAI's AUA obligations — consent, VID, no storage of the number or PID
   (an Aadhaar Data Vault is required only to store the number), an HSM for the signing key, log
   retention — in the compliance checklist for requesting entities. The verification feature never
   stores the number. The artisan register DID, in full, until 2026-10-17; it now keeps only a keyed
   fingerprint and the last four digits (§3), and the number leaves the database entirely once the
   backfill and the drop migration have run on the box (`backend/DEPLOY_AWS.md`). Until then the
   legacy column still holds the numbers recorded before that date.
2. **Signing key and certificate.** UIDAI requires the key in an HSM for production. Two ways:
   * **AWS KMS (the HSM option).** Create an asymmetric key, key spec `RSA_2048`, key usage
     `SIGN_VERIFY` (`aws kms create-key --key-spec RSA_2048 --key-usage SIGN_VERIFY`); its private
     half is generated and used only inside KMS's FIPS 140 validated HSMs. Export the public key
     (`aws kms get-public-key`), have an Indian CA issue a Class II/III certificate for it with `O=`
     matching the AUA (the CSR is signed through KMS), and set `AADHAAR_SIGNING_KMS_KEY_ID` and
     `AADHAAR_AUA_SIGNING_CERT_PATH`. Grant the API's IAM user `kms:Sign`, `kms:GetPublicKey` and
     `kms:DescribeKey` on that key only. At boot the server refuses to start unless the key is
     enabled, RSA_2048/SIGN_VERIFY, matches the certificate and signs (`online/kms_signer.py`).
     Whether UIDAI accepts a cloud HSM is Question 7; if not, AWS CloudHSM fits behind the same
     signer.
   * **A PFX file.** Package key + certificate as a PKCS#12 (`.pfx`) with a strong password and set
     `AADHAAR_AUA_SIGNING_PFX_PATH` / `AADHAAR_AUA_SIGNING_PFX_PASSWORD`. Not an HSM; pre-production
     only.
3. **Fill in the box's `.env`** (and the GitHub Actions secret that renders it — every secret goes to
   both): `AADHAAR_ENV=preprod`, `AADHAAR_AUA_CODE`, `AADHAAR_SUB_AUA_CODE`,
   `AADHAAR_AUA_LICENSE_KEY`, `AADHAAR_ASA_LICENSE_KEY`, `AADHAAR_ASA_OTP_URL`,
   `AADHAAR_ASA_AUTH_URL`, `AADHAAR_AUA_SIGNING_PFX_PATH` (outside the repository, readable only by
   the service user), `AADHAAR_AUA_SIGNING_PFX_PASSWORD`, `AADHAAR_THROTTLE_SALT`
   (`python -c "import secrets; print(secrets.token_urlsafe(48))"`), and
   `AADHAAR_UIDAI_SIGNATURE_CERT_PATH` (see §7). For e-KYC also `AADHAAR_EKYC_ENABLED=true`,
   `AADHAAR_KUA_LICENSE_KEY`, `AADHAAR_ASA_KYC_URL`, and confirm with the ASA that it decrypts
   (`de="Y"`). Placeholders are in `backend/.env.example`.
4. **Pre-production smoke test.** Set `AADHAAR_ONLINE_AUTH_ENABLED=true` and restart. If anything is
   missing or a file does not open, **the service refuses to start and names it** — fix and restart.
   With UIDAI's pre-production test Aadhaar numbers (from the ASA, or UIDAI's staging consent form),
   verify a test account from the web Settings page: request an OTP, enter it, and check the account
   shows "Aadhaar verified". Watch the `app.aadhaar.online` log for the txn and any UIDAI error code.
   Pre-production allows 90 days and 500 transactions per modality (Circular 3/2026, errors
   1605/1606). Settle the items in §7.
5. **Production.** Set `AADHAAR_ENV=prod` and the production licence keys and URLs, restart, and
   repeat one real verification by a consenting member of staff.
6. **Off again** at any time: set the flag to `false` and restart. Results already stored stay in
   the database but are hidden from every payload and screen until a flag is on again.

Switching on the **offline** methods is step 6 in reverse for `AADHAAR_OFFLINE_VERIFICATION_ENABLED`
— no licence, no credentials — after the Regulation 13A question in §1 is answered.

---

## 9. How this document is kept true

| Claim | Kept true by |
|---|---|
| Every route is 404 while its flag is off | `test_aadhaar_api.py::test_with_both_flags_off_every_route_is_a_404_even_unauthenticated` |
| Every route refuses a stranger when on | `test_anonymous_route_sweep.py` (runs with both flags on) |
| Only the listed columns exist and are written | `test_aadhaar_api.py` (stored-column sets against `records.USER_COLUMNS` / `ARTISAN_COLUMNS`) |
| The pinned fingerprints | `test_aadhaar_offline.py::test_every_pinned_certificate_matches_its_manifest_fingerprint_and_names_its_source` |
| The consent words are one text | `AadhaarVerificationTest` (Android reads the web file) |
| The variables are documented | [ENVIRONMENT.md](ENVIRONMENT.md)'s set-difference command |
| The register keeps no number; no response, export, history entry or log carries it | `test_aadhaar_fingerprint.py` |
| Logs archived after 2 years, deleted after the archive period, legal holds respected | `test_aadhaar_log_retention.py` |
| The KMS signature verifies; the boot check refuses a wrong key | `test_aadhaar_kms_signing.py` |

**Review triggers:** `backend/app/services/aadhaar/`, `backend/app/data/uidai/`, a UIDAI circular
changing an API or a certificate, and the owner's answer on Regulation 13A.
