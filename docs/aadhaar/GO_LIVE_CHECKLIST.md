# Aadhaar go-live checklist — Design Prototype Workshop portal

Prepared 17 October 2026 for the designer portal (designer-repository.vercel.app, API on the
Mumbai EC2 box behind CloudFront). It covers every step from the Ministry's approval to switching
each Aadhaar feature on, and how to switch it off again.

Sources are numbered like **[S3]** and listed at the end with their links and dates.

---

## 0. Where things stand today

| | State on 17 Oct 2026 |
|---|---|
| Code | Built and tested. Both features sit behind switches that are **off**. |
| Live server | No Aadhaar setting is present in the live `.env`, so every switch is at its default: off. Checked on the box today. |
| What users see | Nothing. While off, every Aadhaar web address answers "not found" and neither the website nor the Android app shows any Aadhaar screen. |
| Production data | 21 accounts, 0 artisan records, 2 design workshops (read-only count, 17 Oct 2026). |

There are two separate switches:

| Switch | What it turns on | Licence needed |
|---|---|---|
| `AADHAAR_OFFLINE_VERIFICATION_ENABLED` | Checking the **Secure QR** printed on an Aadhaar card or e-Aadhaar, and the **Paperless Offline e-KYC** file (a ZIP plus a share code) the person downloads from UIDAI. Nothing is sent to UIDAI. | None, but see Blocker B2 (Regulation 13A). |
| `AADHAAR_ONLINE_AUTH_ENABLED` | One-time-password (OTP) check against UIDAI, through an ASA. Optionally e-KYC with `AADHAAR_EKYC_ENABLED`. | **AUA** licence (and **KUA** for e-KYC) held by the Ministry. |

---

## 1. Blockers to settle before either switch goes on in production

These are honest gaps between what UIDAI requires and what the system does today. Each needs an
owner decision. B1, B4 and B7 were decided on 17 October 2026 and are **built (pending release)**:
the code is on the `feature/aadhaar-fingerprint` branches of both portals and ships with the next
release; B1 is complete on each box only once the backfill and the drop migration have run.

| # | Blocker | Why it matters | What would settle it |
|---|---|---|---|
| **B1** — **built (pending release)** | **The artisan register stores the full 12-digit Aadhaar number.** `Artisan.aadhaarNumber` is required when an artisan is created and is the duplicate check. It is masked (`XXXX XXXX 9012`) on every screen and export for anyone not entitled to see it, but it is held in full in the database. | An AUA may store Aadhaar numbers **only in an Aadhaar Data Vault** with a reference key (application undertaking (f) and the UIDAI vault circular of 25 Jul 2017, [S2]). An offline verifier "shall not collect, use or store Aadhaar number except in accordance with the Act and Regulations framed thereunder" (Regulation 14A(1)(b), [S1]); the vault rule above applies either way. The verification feature itself never stores the number; the register does. | Ask UIDAI which is acceptable (Question 3). Either (a) build an Aadhaar Data Vault (number encrypted under an HSM, the rest of the system holds only a reference key), or (b) stop storing the full number and keep only a keyed hash for the duplicate check plus the last four digits. (b) is smaller. Decide before applying, because the application asks about it. **Decided (b), 17 Oct 2026, built (pending release), both portals:** the register keeps `aadhaarFingerprint` (HMAC-SHA256 under one key both portals share, SSM `/iit/shared/aadhaar-fingerprint-key`) and `aadhaarLast4`, nothing else; every screen, export and edit-history entry shows only the last four; Android seals a typed number under the Keystore until it is sent. After the release, run the backfill on each box, then ship the guarded drop migration (designer-portal `backend/DEPLOY_AWS.md`, "Aadhaar fingerprint"). Until then numbers recorded earlier are still in the old column. After the drop they stay in the database dumps far longer than 30 days: about 125 days in the nightly dumps (30 days current plus 90 noncurrent in each media bucket; 35 days under Object Lock plus 90 noncurrent in each off-site replica) and about 455 days in the weekly copies (`backups/<portal>/weekly/`, kept 365 days plus 90 noncurrent in both buckets since 18 Oct 2026), plus the database provider's own backup / point-in-time-recovery window (see docs/ENVIRONMENT.md, "The database"). An erasure request can end that sooner by deleting the older dump versions by hand (both portals' `backend/DEPLOY_AWS.md`, "Aadhaar fingerprint", step 3). Do not roll either backend back past this release once it has saved an artisan: older code would miss duplicates and store full numbers again; fix forward. |
| **B2** | **Regulation 13A may require OVSE registration for the offline e-KYC file.** | Since 9 Dec 2025, an entity "desirous of undertaking Aadhaar Paperless Offline e-KYC verification or Aadhaar Verifiable Credential verification through Aadhaar Application" must register as an OVSE [S1][S3]. UIDAI's own handbook says registration is not mandatory for **Secure QR** and **e-Aadhaar**, and describes registration only for the Aadhaar App credential [S4]. Whether "through Aadhaar Application" also covers a ZIP the person uploads is not said. | Ask UIDAI (Question 1 in QUESTIONS_FOR_UIDAI.md). Until answered, the safe course is: switch offline on, but if UIDAI says the ZIP needs registration, register or remove that one method first. |
| **B3** | **UIDAI's response-signing certificate has expired with no successor published.** | The server checks UIDAI's signature on every online answer and refuses any it cannot check. The newest production signing certificate on UIDAI's download page expired **28 Apr 2026**; the pre-production one expired **3 May 2026** (page last updated 2 Jul 2026, checked 17 Oct 2026) [S5]. With only those, every online answer would be refused. | Get the current certificate from the ASA or UIDAI and set `AADHAAR_UIDAI_SIGNATURE_CERT_PATH` (step 3.6). Question 6. |
| **B4** — **built (pending release)** | **The AUA signing key is a password-protected file, not an HSM.** | UIDAI's onboarding checklist requires the keys used to sign authentication requests to be held only in a Hardware Security Module (FIPS 140-2), dedicated to the entity [S2]. The code signs with a `.pfx` file on the server. | Either use an HSM service the ASA offers (the circular is titled "Implementation of HSM by Entity/ASA"), or build signing through an HSM (for example AWS CloudHSM, if UIDAI accepts a cloud HSM — Question 7). Online only; offline needs no key. **Built (pending release):** the request signature can be made by an AWS KMS key (`RSA_2048`, `SIGN_VERIFY`; generated and used only inside KMS's FIPS 140 validated HSMs) by setting `AADHAAR_SIGNING_KMS_KEY_ID` and `AADHAAR_AUA_SIGNING_CERT_PATH` in place of the PFX, behind the existing online switch; the server refuses to start unless the key matches the certificate and signs. The signer fetches the key's public half once, at the boot check, and keeps it; each request then costs one `kms:Sign` over a 32-byte digest. Still open: whether UIDAI accepts a cloud HSM (Question 7); creating the key, getting the CA certificate for it, and the grant. **The grant**, an inline policy on the designer portal's API user `designrepo-media` (the server signs with that user's static key, `kms_signer.kms_client`), naming that one key and nothing else, as two statements: `{"Sid":"AuaSignDigest","Effect":"Allow","Action":"kms:Sign","Resource":"arn:aws:kms:ap-south-1:626159998512:key/<key-id>","Condition":{"StringEquals":{"kms:SigningAlgorithm":"RSASSA_PKCS1_V1_5_SHA_256","kms:MessageType":"DIGEST"}}}` and `{"Sid":"AuaKeyBootCheck","Effect":"Allow","Action":["kms:GetPublicKey","kms:DescribeKey"],"Resource":"arn:aws:kms:ap-south-1:626159998512:key/<key-id>"}`. `kms:Sign` is the per-request call, bound by the two conditions to the only algorithm and message type the code uses; `kms:DescribeKey` and `kms:GetPublicKey` are made once at boot (key state, spec and usage; the public key compared with the certificate) and stay in their own statement because those requests carry neither condition key, so the conditions would refuse them. The key's own key policy must keep the default statement that lets IAM policies in the account grant use of it. Nothing else: no `kms:Decrypt`, no `kms:*`, no wildcard key. Put it in `infra/terraform/main.tf` beside `aws_iam_user_policy.media` when the key exists. |
| **B5** | **Online OTP is also open to staff accounts, not only artisans.** | The legal basis proposed (section 7, the Ministry of Textiles notification for NHDP, [S6]) covers artisans who receive a benefit. Designers, researchers and admins verifying their own account are not beneficiaries. | Owner decides: either ask UIDAI to cover staff too (Question 8), or restrict online OTP to artisan records (a small code change). Offline checks for staff are voluntary use by the holder (Regulation 16B), so are not affected. **Decided 18 Oct 2026: online OTP is for everyone, artisans and staff.** No code change. The request letter asks UIDAI to cover staff (paragraph 4), and Question 8 asks for confirmation. Only if UIDAI refuses would online OTP be limited to artisan records. |
| **B6** | **The image-reading helper for Aadhaar cards must stay off.** | `IDENTITY_OCR_ENABLED` (off today) sends a photo of an artisan's Aadhaar card to Gemini or OpenAI, which are outside India. Aadhaar request servers must be in India (Regulation 22) and identity information must not be shared beyond the purpose [S1]. | Keep `IDENTITY_OCR_ENABLED` unset or `false` in production for as long as Aadhaar is in use. |
| **B7** — **built (pending release)** | **Aadhaar log entries are never deleted.** Every attempt is kept in the record's history for good, and nothing separates live entries from archived ones. | Regulation 18(3) [S1]: logs are kept live for 2 years, then archived for 5 more (or longer if another law requires it), and after that they "shall be deleted", unless a court order or a pending dispute requires keeping them. Keeping them forever breaks that rule, and the UIDAI auditor is likely to raise it. | Either build a scheduled job that archives Aadhaar history entries after 2 years and deletes them when the archive period ends (skipping any record under a legal hold), or get a written answer from UIDAI that the current approach is acceptable (Question 13(c)). **Built (pending release):** the queue worker, once a day, archives entries older than two years to the designer portal's media bucket (`aadhaar-log-archive/`, read back before anything is deleted; that bucket is versioned and replicated off-site, so a deleted archive's versions remain about 90 more days in both regions) and deletes archives five years later (`AADHAAR_LOG_ARCHIVE_YEARS`, at least 5); a record under a legal hold (`scripts/aadhaar_legal_hold.py`) is skipped. **IAM granted 18 Oct 2026 (live):** the API's IAM user `designrepo-media` now holds `s3:PutObject`, `s3:GetObject` (the read-back is a `head_object`) and `s3:DeleteObject` on `designrepo-media-626159998512/aadhaar-log-archive/*` only, as statement `AadhaarLogArchive` of `aws_iam_user_policy.media` (designer-portal `infra/terraform/main.tf`, branch `feature/backup-hardening`, commit `d7096d07`; the plan was that one in-place update). It has no `s3:ListBucket` and nothing on `backups/` or `tfstate/`, which the bucket policy's `ApiKeyNeverTouchesBackupsOrState` deny also blocks; the IAM simulator confirms Put/Get/Delete allowed on the archive prefix and denied on both others. The documentation portal keeps no Aadhaar verification log, so its API user gets no such grant. Still open: nothing for the archive itself. Optional: a lifecycle rule on that prefix expiring noncurrent versions sooner; and if `AADHAAR_LOG_ARCHIVE_KMS_KEY_ID` is ever set, `kms:GenerateDataKey` and `kms:Decrypt` on that key for the same user (SSE-KMS archives then also have no off-site copy, because replication skips SSE-KMS objects). |

---

## 2. The steps, in order

"Owner" is the portal owner at IIT Kharagpur. "Ministry" is the Office of the Development
Commissioner (Handicrafts), Ministry of Textiles.

### Stage A — Offline verification (no licence)

| # | Step | Who | Documents / inputs |
|---|---|---|---|
| A1 | Decide Blocker B1 (vault or hash) and get it built and released. **Decided (hash) and built; pending release, then the backfill and the drop migration on each box.** | Owner | — |
| A2 | Ask UIDAI the Regulation 13A questions (Questions 1–5). If UIDAI says registration is needed, apply at ovse.uidai.gov.in or by e-mail to `ovse.registration@uidai.net.in`: registration number, registered domain, callback URL, Class 3 public certificate (2-year validity), logo as SVG under 10 KB [S4][S7]. | Ministry (owner drafts) | UIDAI's written answer |
| A3 | Confirm `IDENTITY_OCR_ENABLED` is not on (B6). | Owner | — |
| A4 | Run the offline smoke test (section 4.1) on a staging copy or at a quiet time. | Owner | One consenting staff member's Aadhaar card or e-Aadhaar, and a freshly downloaded offline e-KYC ZIP |
| A5 | Switch on (section 3.2) and repeat the smoke test on production. | Owner | — |

### Stage B — Online OTP authentication (needs the AUA licence)

UIDAI's published process [S2], with the timelines it sets:

| # | Step | Who | Documents / inputs |
|---|---|---|---|
| B1 | Settle Blockers B1, B4, B5 and B7. | Owner, Ministry | — |
| B2 | Choose an ASA (a licensed Authentication Service Agency) and get its letter agreeing to connect the Ministry. Agree its charges. | Ministry | ASA's letter "in original" |
| B3 | Send UIDAI the request letter (REQUEST_LETTER_DRAFT.md) with the AUA/KUA application form (Annex 1 of [S2]). | Ministry | See the document list below |
| B4 | UIDAI issues an application number, then an **in-principle approval** with the AUA/KUA agreement (now version 6.1 [S8]), invoices for licence fee and pre-onboarding audit fee, and checklists. Everything must be completed **within 90 days** of that approval. | UIDAI | — |
| B5 | Pre-onboarding IS audit by a CERT-In empanelled auditor UIDAI assigns, against the March 2024 compliance checklist [S2]. Audit cost is borne by the entity (Regulation 21(3), [S1]). | Ministry pays; owner supports | Security documents, architecture, logs |
| B6 | Sign the AUA/KUA agreement. (A bank guarantee is required only from non-government entities [S2].) | Ministry | — |
| B7 | UIDAI issues the **pre-production** AUA code and licence key; the ASA issues its licence key and pre-production web addresses. | UIDAI, ASA | — |
| B8 | Get a Class II/III signing certificate from an Indian Certifying Authority in the Ministry's name, with the key in an HSM (B4). | Ministry, owner | — |
| B9 | Fill in the settings (section 3.3) and run the pre-production smoke test (section 4.2). UIDAI requires a set number of test transactions per method, validated by its Tech Centre, **within 30 days** [S2]. Pre-production stops working after **90 days** (error 1605) or **500 transactions per method** (error 1606) [S9]. | Owner | Test identities from the ASA |
| B10 | Second IS audit by a CERT-In empanelled auditor; UIDAI approves. | Ministry, auditor | — |
| B11 | UIDAI moves the Ministry to **production** (one month, extendable to three [S9]); production licence keys and web addresses issued. | UIDAI, ASA | — |
| B12 | Switch to production settings (section 3.4) and do one real check with a consenting staff member. | Owner | — |
| B13 | From then on: annual audit by a CERT-In empanelled auditor; report any misuse or compromise to UIDAI within 72 hours; keep authentication logs online 2 years and archived 5 more [S1][S2]. | Ministry, owner | — |

**Documents the application asks for** (Annex 1 of [S2]):

- Name and addresses of the Ministry/office; GSTN and TAN, if any.
- A letter or order authorising the officer to apply and sign the AUA agreement (the government
  equivalent of a board resolution), certified copy.
- The legal basis ticked: **section 7**, with a copy of the Gazette notification.
- Management and technical points of contact (name, designation, official e-mail, mobile).
- Data centre and disaster-recovery addresses (here: AWS Mumbai, ap-south-1, for the servers, files and the managed PostgreSQL database).
- Grievance contact: website, e-mail, helpdesk number.
- The ASA's name and its letter agreeing to connect.
- Undertakings, including infrastructure in India able to handle at least one lakh
  authentications a month, and storing Aadhaar numbers only in a vault.
- Statement and undertaking of expected transaction volume.

**Fees**

| Fee | Applies to the Ministry? | Source |
|---|---|---|
| Per-transaction charges (₹3 per e-KYC, 50 paise per yes/no) | **No** — "not chargeable in respect of use of Authentication facility by or on behalf of the Central Government" (Regulation 3(3)(c)). | [S10] |
| AUA/KUA licence fee and pre-onboarding audit fee | UIDAI invoices both with the in-principle approval. A secondary source reports ₹5 lakh for two years for up to 5 lakh transactions a year (from 1 Jul 2023). Whether a Central Government office pays it is **not stated** on UIDAI's site — Question 10. | [S2], [S11] |
| Audit costs | Yes, borne by the entity. | [S1] Reg. 21(3) |
| ASA charges | Commercial; agreed with the ASA. | — |

---

## 3. The settings

All are environment variables on the API server. **Every value goes into two places**: the
`BACKEND_ENV` GitHub Actions secret (the whole `.env` file, rewritten on every deploy — it cannot be
read back, so keep a private copy of what you paste) and the live file
`/home/ubuntu/app/current/backend/.env` on the box. Then restart both services that read that file,
`sudo systemctl restart fieldrepo fieldrepo-queue`, and check both are running
(`systemctl is-active fieldrepo fieldrepo-queue` prints `active` twice). Files (the signing key and certificates) are copied to the box
once, outside the release folders, so deploys do not remove them.

### 3.1 Every Aadhaar setting

| Setting | Default | Secret | Meaning |
|---|---|---|---|
| `AADHAAR_OFFLINE_VERIFICATION_ENABLED` | `false` | No | Switch for Secure QR and offline e-KYC file checks. |
| `AADHAAR_OFFLINE_XML_MAX_AGE_DAYS` | `3` | No | Refuse an offline e-KYC file older than this many days (`0` = no limit). Three days follows the RBI KYC rule; UIDAI sets none. |
| `AADHAAR_SECURE_QR_MAX_AGE_DAYS` | `0` | No | The same for a Secure QR. `0` accepts any age, because the QR is printed on a card. |
| `AADHAAR_EXTRA_TRUSTED_CERTS` | empty | No | Extra UIDAI offline-signing certificate files (comma-separated paths), for when UIDAI issues a new one before the next release. |
| `AADHAAR_ONLINE_AUTH_ENABLED` | `false` | No | Switch for online OTP. **With it on, the server will not start** unless every required value below is present, the key file opens, and the certificates load. |
| `AADHAAR_ENV` | `preprod` | No | `preprod` or `prod`. Picks UIDAI's matching certificates. |
| `AADHAAR_AUA_CODE` | — | No | The AUA code UIDAI issues. Required with online. |
| `AADHAAR_SUB_AUA_CODE` | — | No | The sub-AUA code; with no sub-AUA, the AUA code again. Required with online. |
| `AADHAAR_AUA_LICENSE_KEY` | — | **Yes** | UIDAI's licence key for the AUA. Required with online. |
| `AADHAAR_ASA_LICENSE_KEY` | — | **Yes** | The ASA's licence key. Required with online. |
| `AADHAAR_ASA_OTP_URL` | — | No | The ASA's OTP address up to the version, e.g. `https://<asa-host>/otp/2.5`. Required with online. |
| `AADHAAR_ASA_AUTH_URL` | — | No | The ASA's authentication address, e.g. `https://<asa-host>/auth/2.5`. Required with online. |
| `AADHAAR_ASA_KYC_URL` | — | No | The ASA's e-KYC address. Required with e-KYC. |
| `AADHAAR_AUA_SIGNING_PFX_PATH` | — | No (the file is) | Path on the box to the signing key and certificate, e.g. `/etc/design-workshop/aua-signing.pfx`. Required with online. See Blocker B4. |
| `AADHAAR_AUA_SIGNING_PFX_PASSWORD` | — | **Yes** | Password of that file. Required with online. |
| `AADHAAR_THROTTLE_SALT` | — | **Yes** | A long random secret used to rate-limit OTP requests without storing numbers. Never reuse `JWT_SECRET`. Required with online. |
| `AADHAAR_UIDAI_ENCRYPTION_CERT_PATH` | built-in | No | Override UIDAI's encryption certificate. The built-in ones are valid to Aug 2028; leave unset unless UIDAI rotates. |
| `AADHAAR_UIDAI_SIGNATURE_CERT_PATH` | built-in | No | UIDAI's current response-signing certificate. **Needed now** (Blocker B3). |
| `AADHAAR_ONLINE_NAME_CHECK` | `true` | No | Send the record's name so UIDAI also checks the name with the OTP. |
| `AADHAAR_OTP_CHANNEL` | `00` | No | Where UIDAI sends the OTP: `00` SMS and e-mail, `01` SMS, `02` e-mail. |
| `AADHAAR_ASA_TIMEOUT_SECONDS` | `20` | No | How long to wait for the ASA. |
| `AADHAAR_OTP_LIMIT_PER_USER` | `5` | No | OTP requests one signed-in account may make per window. |
| `AADHAAR_OTP_LIMIT_PER_UID` | `3` | No | OTP requests per Aadhaar number or VID per window. |
| `AADHAAR_OTP_WINDOW_MINUTES` | `60` | No | The window for both limits. |
| `AADHAAR_EKYC_ENABLED` | `false` | No | Ask UIDAI for name, birth date and gender (e-KYC) instead of yes/no. Needs KUA licence, and the ASA must agree to decrypt the answer. |
| `AADHAAR_KUA_LICENSE_KEY` | — | **Yes** | KUA licence key. Required with e-KYC. |

Related, keep off: `IDENTITY_OCR_ENABLED` (Blocker B6).

### 3.2 Offline — values to set

```
AADHAAR_OFFLINE_VERIFICATION_ENABLED=true
# leave the three below at their defaults unless there is a reason
# AADHAAR_OFFLINE_XML_MAX_AGE_DAYS=3
# AADHAAR_SECURE_QR_MAX_AGE_DAYS=0
# AADHAAR_EXTRA_TRUSTED_CERTS=
```

### 3.3 Online, pre-production — values to set

```
AADHAAR_ONLINE_AUTH_ENABLED=true
AADHAAR_ENV=preprod
AADHAAR_AUA_CODE=<pre-prod AUA code from UIDAI>
AADHAAR_SUB_AUA_CODE=<same as AUA code, unless a sub-AUA is used>
AADHAAR_AUA_LICENSE_KEY=<pre-prod AUA licence key>
AADHAAR_ASA_LICENSE_KEY=<pre-prod ASA licence key>
AADHAAR_ASA_OTP_URL=https://<asa pre-prod host>/otp/2.5
AADHAAR_ASA_AUTH_URL=https://<asa pre-prod host>/auth/2.5
AADHAAR_AUA_SIGNING_PFX_PATH=/etc/design-workshop/aua-signing.pfx
AADHAAR_AUA_SIGNING_PFX_PASSWORD=<password>
AADHAAR_THROTTLE_SALT=<python -c "import secrets; print(secrets.token_urlsafe(48))">
AADHAAR_UIDAI_SIGNATURE_CERT_PATH=/etc/design-workshop/uidai-response-signing-preprod.cer
# for e-KYC as well:
# AADHAAR_EKYC_ENABLED=true
# AADHAAR_KUA_LICENSE_KEY=<pre-prod KUA licence key>
# AADHAAR_ASA_KYC_URL=https://<asa pre-prod host>/kyc/2.5
```

### 3.4 Online, production — what changes

`AADHAAR_ENV=prod`; the production AUA code, licence keys and ASA addresses; the production
response-signing certificate in `AADHAAR_UIDAI_SIGNATURE_CERT_PATH`. Everything else stays.

### 3.5 Putting the files on the box

The folder `/etc/design-workshop` does not exist yet (checked 17 Oct 2026). Create it once, put
the signing file and UIDAI's certificate in it, owned by the service user `ubuntu`, readable only
by it (`chmod 700` on the folder, `chmod 600` on the files). Copy by SSM or SCP, never through the
repository. Back up the signing file and its password the same way the 2FA keys are backed up
(GitHub secret plus SSM SecureString).

### 3.6 The response-signing certificate

Save the certificate the ASA or UIDAI gives you as `/etc/design-workshop/uidai-response-signing-<env>.cer`
and point `AADHAAR_UIDAI_SIGNATURE_CERT_PATH` at it. Before using it, check it really is UIDAI's:
compare its fingerprint with one UIDAI or the ASA states in writing.

---

## 4. Smoke tests

### 4.1 Offline

1. Switch on (3.2) and restart. The service should start normally.
2. Sign in as a staff member who agrees to help. Open **Settings → Aadhaar verification** on the
   website. The panel should now appear.
3. Read the consent text, tick the box, upload a clear photo of the Secure QR from their Aadhaar
   card or e-Aadhaar. Expect "Aadhaar verified" with their name, the last four digits and the date.
4. Remove the result (the same screen). Expect the badge to disappear.
5. Repeat with the offline e-KYC ZIP and share code downloaded that day from UIDAI.
6. Repeat once on the Android app (no app update needed; it reads the switch from the server).
7. Check that an admin sees the badge on the Users screen, and that the record's history shows each
   attempt with method, outcome and last four digits only.
8. Check that the consent screen comes before every attempt and that you cannot go on without
   ticking it. (The server also refuses any request without consent; the automated tests cover that.)

### 4.2 Online, pre-production

1. Switch on (3.3) and restart both services (`sudo systemctl restart fieldrepo fieldrepo-queue`),
   then check both are `active`. If anything is missing or a file does not open, **the services
   refuse to start and the log names what is wrong** — fix it and restart both. While it refuses, the
   whole portal is down, so do this at a quiet time with the rollback (section 5) ready.
2. With a test identity from the ASA, on the website: Settings → Aadhaar verification → enter the
   VID or number → request OTP → enter it. Expect "Aadhaar verified".
3. Do the same on an artisan record (if the owner restricted online to artisans, only there).
4. Try a wrong OTP: expect a plain refusal, not a crash.
5. Watch the `app.aadhaar.online` log for UIDAI's transaction id and any error code.
6. Things only this test can confirm (from the code's own notes): the exact layout UIDAI expects in
   the encrypted block, the device fields for a non-biometric request, and that UIDAI's answers
   verify against the certificate from 3.6. If any fails, stop and send the log to the developer.
7. Complete UIDAI's mandated number of test transactions per method within 30 days and well under
   500 per method, then tell UIDAI's Tech Centre.

### 4.3 Online, production

One real check by a consenting staff member, then one artisan record, then watch the logs for a day.

---

## 5. Rollback

Either switch can be turned off at any time, with no data loss:

1. Set the switch to `false` (or delete the line) in **both** the `BACKEND_ENV` secret and the live
   `.env`.
2. `sudo systemctl restart fieldrepo fieldrepo-queue` (by SSM), then
   `systemctl is-active fieldrepo fieldrepo-queue` should print `active` twice.
3. Check: the Aadhaar panel disappears from Settings, and `/api/aadhaar/self` answers 404.

Results already stored stay in the database but are hidden from every screen until a switch is on
again. To remove them for good, that is a separate, deliberate database change.

If the server will not start after switching online on, the fastest fix is step 1 with
`AADHAAR_ONLINE_AUTH_ENABLED=false`, then restart both services as in step 2.

If the problem came with a new release rather than a setting, roll back the release:
`ln -sfn /home/ubuntu/app/releases/<previous release> /home/ubuntu/app/current && sudo systemctl restart fieldrepo fieldrepo-queue`.
Each release folder keeps the `.env` it was deployed with, so this also brings back that release's
Aadhaar settings. After rolling back, check the `AADHAAR_` lines in the restored `.env` and set them
again if they are not what you want, then restart both services.

To stop using UIDAI for good, the AUA must ask UIDAI to surrender access and keep its logs for the
retention period (Regulations 23 and 23A, [S1]).

---

## 6. What the code does (so nothing above over-promises)

Checked against `backend/app/services/aadhaar/`, `backend/app/api/routes/aadhaar.py`,
`backend/app/core/config.py` and `docs/AADHAAR.md` on `origin/main` (commit `89fc7e69`, 0.0.18).

- UIDAI's signature is always checked on the server, against UIDAI certificates pinned in the code.
- Consent is shown in full and must be ticked; the server refuses without it and records the refusal.
- The verification **keeps**: status, method, date, the name as UIDAI signed it, year of birth
  (full date for an artisan), gender, the last four digits, UIDAI's transaction id (online), who
  verified, and any admin decision.
- The verification **never keeps**: the full number, VID, photo, address, QR text, the ZIP/XML, the
  share code, the OTP or any raw UIDAI answer.
- Every attempt is written to the record's history. Built (pending release): entries are archived
  after two years and deleted after the archive period, except under a legal hold (Blocker B7).
- The holder (or whoever may edit the artisan) can remove the result at any time.
- A VID is offered in place of the number for online checks.
- Exception, not part of this feature: the artisan register stores the full number (Blocker B1) —
  built (pending release) to keep only a keyed fingerprint and the last four digits.

---

## Sources

All accessed 17 October 2026.

- **[S1]** UIDAI, *Aadhaar (Authentication and Offline Verification) Regulations, 2021*, consolidated
  "updated as on 15.12.2025" — Regs. 6, 12, 13A, 14A, 16A–16C, 17, 18, 20A, 21, 22, 23A.
  https://uidai.gov.in/images/The_Aadhaar_Authentication_and_Offline_Verifications_Regulations_2021-_Clean_copy-30122025.pdf
- **[S2]** UIDAI, *AUA/KUA onboarding process, application form (Annex 1) and compliance checklists*
  (checklist Version 1.0, March 2024) — 12-step process, 90-day and 30-day timelines, undertakings,
  HSM (circular 22 Jun 2017) and Aadhaar Data Vault (circular 25 Jul 2017) controls, log retention.
  https://uidai.gov.in/images/AUA_KUA_Onboarding_Process_Application_Form_and_Audit_Compliance_Checklists.pdf
- **[S3]** Gazette of India, *Aadhaar (Authentication and Offline Verification) Amendment Regulations,
  2025*, 9 December 2025 (inserts Reg. 13A).
  https://backend.uidai.gov.in/get/files/media/document/2026-06/Gazette_dated_9_dec_2025.pdf
- **[S4]** UIDAI, *Aadhaar offline verification Handbook*, January 2026 (pp. 18–20).
  https://backend.uidai.gov.in/get/files/media/document/2026-05/OVSE_Playbook.pdf
- **[S5]** UIDAI, *Data and Download* (certificates), page last updated 2 July 2026.
  https://uidai.gov.in/en/data-and-download
- **[S6]** UIDAI, *List of Notifications issued under Section 7 of the Aadhaar Act, 2016* (as on
  31 Jan 2024) — Ministry of Textiles, NHDP (11 schemes incl. Design & Technology Up-gradation),
  published 29 Mar 2017. https://www.uidai.gov.in/images/resource/section7GoInotifications.pdf
- **[S7]** UIDAI, *OVSE Registration*, page last updated 6 October 2026. https://uidai.gov.in/en/ovse
- **[S8]** UIDAI, *AUA/KUA Agreement* (Version 6.0 published; Circular 3 of 2026 cites Version 6.1).
  https://uidai.gov.in/images/AUA-KUA_Agreements_Versions_60.pdf
- **[S9]** UIDAI, *Circular 3 of 2026*, 23 March 2026 — pre-production limits, errors 1605/1606.
  https://uidai.gov.in/images/Circular_3_of_2026_dated_23_3_2026.pdf
- **[S10]** UIDAI, *Aadhaar (Payment of Fees for Performance of Authentication) Regulations, 2023*, as
  amended 20 August 2025 — Reg. 3. https://uidai.gov.in/images/The_Aadhaar_payment_of_fees_for_performance_of_Authentication_Regulations_2023.pdf
- **[S11]** Finahub blog, *UIDAI reduces licence fees for eKYC (KUA) services*, April 2023 (secondary
  source; confirm with UIDAI). https://blog.finahub.com/2023/04/uidai-reduces-license-fees-for-ekyc-kua.html
- **[S12]** UIDAI, *Circular 4 of 2026*, 15 April 2026 — SHA-1 signatures refused after 30 June 2026
  (the code already signs with SHA-256).
  https://uidai.gov.in/images/Circular_4_of_2026_reg_SHA-1_SHA-2_SHA-256_migration.pdf
- **[S13]** Office of DC (Handicrafts), *Guidelines for National Handicrafts Development Programme
  (2021-22 to 2025-26)* — Design and Technology Development Workshop, wage compensation ₹300 per
  day per artisan. https://indian.handicrafts.gov.in/static-pdf/scheme-guideline.pdf
