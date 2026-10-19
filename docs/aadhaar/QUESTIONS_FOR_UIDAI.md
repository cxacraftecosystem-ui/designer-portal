# Questions for UIDAI (and the ASA)

Prepared 17 October 2026 for the Office of DC (Handicrafts) to send with, or before, the AUA request.
Each question says why it matters and what we will do with each answer. Send questions 1–5
to `ovse.support@uidai.net.in`; the rest to the Authentication-II division (or through the ASA).

**About the system, for context.** The Design Prototype Workshop portal can verify an artisan's or
staff member's identity in three ways: (a) reading the digitally signed **Secure QR** on the Aadhaar
letter, PVC card or e-Aadhaar, from a camera or uploaded picture; (b) checking an **Aadhaar Paperless
Offline e-KYC** ZIP file that the resident downloads from UIDAI and uploads with the share code; (c)
**OTP authentication** (Auth API 2.5) through an ASA, once licensed. UIDAI's signature is checked on
the server. The full number, VID, photo, address, file and share code are never stored by the
verification.

---

## A. Offline verification and Regulation 13A

**1. Does Regulation 13A require us to register as an OVSE to verify an offline e-KYC ZIP that the
resident uploads to our portal (not shared through the Aadhaar App)?**

Regulation 13A(1), inserted on 9 December 2025, says: "An entity desirous of undertaking Aadhaar
Paperless Offline e-KYC verification or Aadhaar Verifiable Credential verification through Aadhaar
Application shall apply to the Authority for registration". It is not clear whether "through Aadhaar
Application" qualifies both methods or only the Verifiable Credential. UIDAI's January 2026 offline
verification handbook says registration is "not mandatory" for Secure QR and e-Aadhaar, and describes
registration only for Verifiable Credential verification through the Aadhaar App.

*If yes:* we register before turning on the ZIP method. *If no:* we turn it on with Secure QR.

**2. Is any registration or permission needed to verify the Secure QR code (from a printed card or
an uploaded picture of it)?** We read the handbook as "no" and ask UIDAI to confirm in writing.

**3. Regulation 14A(1)(b) says an OVSE "shall not collect, use or store Aadhaar number". Our artisan
register keeps the Aadhaar number entered by field staff as a duplicate check. If we are both an AUA
and an offline verifier, which rule applies, and what is acceptable?**
- (a) An Aadhaar Data Vault with reference keys (as for AUAs), or
- (b) storing only a one-way keyed hash of the number plus the last four digits, or
- (c) neither: the number must not be kept at all.

*We intend to change this before going live and want to build the right thing once.*

**4. Under Regulation 16A(3), may we keep the name, year (or date) of birth, gender and last four
digits from a successful offline verification, with the resident's consent, as described?** Are
there UIDAI guidelines on storing offline Aadhaar data securely that we should follow? Is our
withdrawal (all stored fields cleared, the removal recorded and shown to the resident) enough as
"deletion in a verifiable manner" with acknowledgement under Regulation 16A(4)?

**5. Regulation 16A(2) says no entity shall perform offline verification on behalf of another entity
or person.** Our field staff verify an artisan's card while recording that artisan for this office.
Please confirm this counts as this office verifying, not "on behalf of another".

## B. Online authentication

**6. Response-signing certificate.** The newest UIDAI digital-signature certificates on the Data and
Download page expired on 28 April 2026 (production, `uidai_auth_sign_Prod_2026.cer`) and 3 May 2026
(pre-production, `uidai_auth_sign_Pre-Prod_2026.cer`); the page was last updated 2 July 2026.
- (a) Which certificate does UIDAI now use to sign Auth 2.5, OTP 2.5 and e-KYC 2.5 responses, in
  pre-production and production?
- (b) Where will it be published, and how will we be told of the next change?
- (c) What is the certificate `Auth_Private_Key_11082030.cer` ("Latest Staging Certificate", to 11
  Aug 2030) used for?

*We refuse any response whose signature we cannot verify, so without this every check fails.*

**7. HSM.** The onboarding checklist requires the signing key to be in a dedicated, on-premise,
FIPS 140-2 HSM. Our servers are in AWS Mumbai. Is a cloud HSM in India (for example AWS CloudHSM,
FIPS 140-2/3 validated) acceptable? Or may the ASA's HSM sign on our behalf, as the 22 June 2017
circular "Implementation of HSM by Entity/ASA" suggests?

**8. Staff accounts.** Our legal basis is section 7 (NHDP notification, 29 March 2017) for
artisans receiving wage compensation. May the same AUA licence be used to authenticate the
portal's own staff (designers, researchers, officers) who are not beneficiaries? We intend to
offer online authentication to staff as well, with their consent. If that is not allowed, we will
limit it to artisans.

**9. Section 7 notification.** The NHDP notification of 29 March 2017 names "Design & Technology
Up-gradation". The current NHDP guidelines (2021-22 to 2025-26) place the Design and Technology
Development Workshop under "Skill Development in Handicraft Sector". Is the 2017 notification
enough, or does the Ministry need a fresh or amended notification? Does it remain valid after the
2025-26 scheme period?

**10. Fees for a Central Government office.** Transaction fees do not apply to us (Payment of Fees
Regulations 2023, regulation 3(3)(c)). Do the AUA/KUA licence fee and the pre-onboarding audit fee
apply to a Central Government Ministry? If so, what are the amounts now?

**11. Pre-production access and test data.**
- (a) Can pre-production access be issued with the in-principle approval?
- (b) Will UIDAI or the ASA supply test residents (numbers or VIDs) with OTPs that reach us?
- (c) How many test transactions per method are mandated before the audit?
- (d) Circular 3 of 2026 sets 90 days and 500 transactions per method. Do these count from the
  first transaction or from the issue of the key?

**12. Technical points only a pre-production run can settle.** We will test these there, and ask
UIDAI or the ASA to confirm:
- (a) In the encrypted PID `Data` element, is the timestamp placed before the encrypted bytes, as
  UIDAI's reference clients do (the specification says "appended")?
- (b) For OTP-only requests, which `<Device>` attributes are required?
- (c) Request XML carries no namespace, as in the 2.5 templates. Is that right?
- (d) For e-KYC, will the ASA decrypt the response for us (`de="Y"`)? UIDAI has not published the
  byte layout of a KUA-key-encrypted response.

**13. Audit and logs.**
- (a) Is the annual audit by a CERT-In empanelled auditor the only recurring audit, and is there a
  set format?
- (b) Our authentication log is the record's own history (method, outcome, refusal reason, last four
  digits, UIDAI transaction id, time, who verified). Consent is enforced (no consent, no request)
  but the exact consent text shown is not saved with each entry. Is that enough "record of consent"
  under Regulations 6(2) and 18(1)(d), or must each entry carry the notice version?
- (c) Regulation 18(3) says logs "shall be deleted" once the 2-year live period and the archive
  period are over. Our log is the record's own history and today nothing deletes or archives it.
  How does 18(3) apply to it: must we archive entries after 2 years and delete them after 5 more
  (or longer if another law requires it), and should the deletion remove the whole entry or only
  its Aadhaar details (last four digits, transaction id) while keeping the bare fact that a check
  happened?

**14. Grievances and the resident's access to logs.** Regulation 18(2) gives the resident the right
to access authentication logs. A resident with an account sees their own history; an artisan
usually has no account. Is a request through the grievance contact enough?

---

## Sources (accessed 17 October 2026)

- Regulation 13A, 9 December 2025 Gazette:
  https://backend.uidai.gov.in/get/files/media/document/2026-06/Gazette_dated_9_dec_2025.pdf
- Regulations 2021, updated as on 15.12.2025 (Regs. 6, 14A, 16A, 18, 22):
  https://uidai.gov.in/images/The_Aadhaar_Authentication_and_Offline_Verifications_Regulations_2021-_Clean_copy-30122025.pdf
- Offline verification handbook, January 2026:
  https://backend.uidai.gov.in/get/files/media/document/2026-05/OVSE_Playbook.pdf
- OVSE registration page (updated 6 Oct 2026): https://uidai.gov.in/en/ovse
- Certificates, Data and Download (updated 2 Jul 2026): https://uidai.gov.in/en/data-and-download
- Onboarding process, form and checklists (March 2024):
  https://uidai.gov.in/images/AUA_KUA_Onboarding_Process_Application_Form_and_Audit_Compliance_Checklists.pdf
- Section 7 notifications list (as on 31 Jan 2024):
  https://www.uidai.gov.in/images/resource/section7GoInotifications.pdf
- NHDP guidelines 2021-22 to 2025-26: https://indian.handicrafts.gov.in/static-pdf/scheme-guideline.pdf
- Payment of Fees Regulations 2023 (as amended 20 Aug 2025):
  https://uidai.gov.in/images/The_Aadhaar_payment_of_fees_for_performance_of_Authentication_Regulations_2023.pdf
- Circular 3 of 2026 (23 Mar 2026): https://uidai.gov.in/images/Circular_3_of_2026_dated_23_3_2026.pdf
- Circular 4 of 2026 (15 Apr 2026):
  https://uidai.gov.in/images/Circular_4_of_2026_reg_SHA-1_SHA-2_SHA-256_migration.pdf
