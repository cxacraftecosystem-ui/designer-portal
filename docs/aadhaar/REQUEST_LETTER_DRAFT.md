# Draft letter — request for AUA (and KUA) access

Draft prepared 17 October 2026. Everything in `[square brackets]` is to be filled in by the
Ministry. Notes for the sender, in *italics*, are to be deleted before signing.

*Before sending:* settle Blockers B1 (the artisan register stores full Aadhaar numbers), B4 (HSM),
B5 (staff accounts) and B7 (log deletion under regulation 18(3)) in GO_LIVE_CHECKLIST.md, and adjust paragraphs 6 and 7 to what was decided.
The letter must describe the system as it will actually run. Send it with the AUA/KUA application
form (Annex 1 of UIDAI's onboarding document) and the ASA's letter of consent.

*Addressee:* UIDAI's application form is addressed to the CEO, UIDAI, attention Deputy Director
(Authentication-II). If the Ministry will apply through a chosen ASA, send a copy to the ASA.

---

**[Letterhead: Office of the Development Commissioner (Handicrafts), Ministry of Textiles,
Government of India, West Block No. VII, R.K. Puram, New Delhi – 110066]**

F. No. [file number]  
Dated: [date]

To  
The Chief Executive Officer  
Unique Identification Authority of India  
[Attention: Deputy Director (Authentication-II)]  
UIDAI Head Office, Bangla Sahib Road, Behind Kali Mandir, Gole Market  
New Delhi – 110 001

[Copy to: [name of ASA], [address]]

**Subject: Request for appointment of the Office of the Development Commissioner (Handicrafts),
Ministry of Textiles, as Authentication User Agency [and e-KYC User Agency] for identity
verification of artisans participating in Design and Technology Development Workshops — reg.**

Sir/Madam,

1. **Request.** The Office of the Development Commissioner (Handicrafts), Ministry of Textiles,
   requests appointment as an Authentication User Agency (AUA) [and e-KYC User Agency (KUA)] under
   regulation 12 of the Aadhaar (Authentication and Offline Verification) Regulations, 2021, to use
   the Yes/No (OTP) authentication facility [and the e-KYC facility] through
   [name of ASA], an Authentication Service Agency. The completed application form, the ASA's letter
   of consent and the supporting documents are enclosed.

2. **Purpose.** Under the National Handicrafts Development Programme (NHDP), this office funds
   Design and Technology Development Workshops in which artisans develop new designs and prototypes
   with an empanelled designer. Each participating artisan receives wage compensation (at present
   ₹300 per day under the NHDP guidelines for 2021-22 to 2025-26) from the Consolidated Fund of
   India. The workshops are recorded on the Design Prototype Workshop portal
   ([portal web address]), operated for this office by [IIT Kharagpur / name of technology partner].
   Authentication will be used only to confirm the identity of the artisan receiving that benefit,
   so that each record and payment relates to a real, single person.

3. **Legal basis.** Section 7 of the Aadhaar (Targeted Delivery of Financial and Other Subsidies,
   Benefits and Services) Act, 2016. The Ministry of Textiles has notified under section 7 the
   schemes of NHDP, including Design & Technology Up-gradation (notification published in the
   Gazette on 29 March 2017; [S.O. number and date — to be filled from the Gazette]). A copy is
   enclosed. *[Confirm the 2017 notification still covers the present Design and Technology
   Development Workshop component and the scheme period after 2025-26; if not, a fresh section 7
   notification is needed before applying.]* As required by section 7, an artisan who does not have
   Aadhaar, or does not wish to use it, will continue to be identified by other means; Aadhaar
   verification on the portal is voluntary and no benefit is refused for want of it.

4. **Who will be authenticated, and how often.**
   - Artisans participating in workshops: about [number] artisans in about [number] workshops a
     year, [all India / states]. Each artisan is normally verified once.
   - Expected volume: about [number] authentication transactions a year (well below 5 lakh). The
     system is built to handle far more than one lakh transactions a month.
   - The portal's own staff (designers, researchers and officers) will also verify their accounts
     online, with their consent, about [number] people. *[Basis for staff to be confirmed with the
     Ministry before sending; see Question 8.]*

5. **Methods.** OTP-based Yes/No authentication [and OTP-based e-KYC, for which the name, date of
   birth and gender returned by UIDAI will be compared with the artisan's record]. No biometric
   authentication is proposed. The Virtual ID is offered to the resident in place of the Aadhaar
   number. Separately, residents may also verify the digitally signed Secure QR code on their
   Aadhaar letter or e-Aadhaar, which involves no request to UIDAI.

6. **Safeguards already built into the portal.**
   - **Consent.** Before any check, the resident is shown in plain words what will be read, what
     will be kept, what will never be kept and how to withdraw; consent must be given each time. For
     an artisan, the field worker confirms the notice was read out and agreed. A request without
     consent is refused by the server and the refusal is logged.
   - **Minimal storage.** The verification keeps only: that the check passed, the method and time,
     the name as returned by UIDAI, year of birth (full date for artisans), gender, the last four
     digits, and UIDAI's transaction id. It never stores the full Aadhaar number or VID, the OTP,
     the PID block, the photograph, the address, the QR code, the offline e-KYC file, the share code
     or any raw UIDAI response; these are processed in memory and discarded.
   - **Masking.** Wherever an Aadhaar number appears on a screen or in an export, it is masked to the
     last four digits (XXXX XXXX 1234) for anyone not entitled to see it.
   - **Signature checks.** UIDAI's digital signature is verified on the server on every response,
     using UIDAI's published certificates; an unverifiable response is refused.
   - **Encryption.** The PID block is encrypted under UIDAI's public key with a fresh session key
     for each request (AES-256-GCM), and requests are signed with SHA-256, as required from
     30 June 2026.
   - **Audit trail.** Every attempt — success, refusal with its reason, OTP sent, withdrawal — is
     recorded with the method, outcome and last four digits only. The record is append-only.
     [Retention under regulation 18: entries are kept online for two years and archived for five
     more, then deleted unless a court order or pending dispute requires otherwise / as agreed with
     UIDAI.] *Keep this sentence only once Blocker B7 is settled; today nothing deletes or archives
     the entries.*
   - **Withdrawal.** The resident can remove the result at any time; the removal is recorded.
   - **Abuse limits.** OTP requests are limited per user and per Aadhaar number, without storing
     the number.
   - **Hosting in India.** The server that forms and sends authentication requests, the database and
     file storage are in data centres in Mumbai (AWS ap-south-1).
   - **Off by default.** The feature is switched off and invisible until this office is licensed.

7. **Commitments before going live.** *[Edit to match what has been decided.]*
   - The artisan register's Aadhaar numbers, currently kept as a duplicate check, will be
     [moved into an Aadhaar Data Vault with a reference key / replaced by a one-way keyed hash and
     the last four digits] before production access.
   - The key used to sign authentication requests will be held in a FIPS 140-2 Hardware Security
     Module [of this office / provided by the ASA].
   - A CERT-In empanelled auditor will audit the system before production and every year after.
   - Any misuse or compromise will be reported to UIDAI within 72 hours.

8. **Points of contact.**
   - Management: [name], [designation], [official e-mail], [mobile].
   - Technical: [name], [designation, IIT Kharagpur / partner], [official e-mail], [mobile].
   - Grievances: [web page], [e-mail], [helpline number].

9. **Pre-production access.** This office requests pre-production access together with the
   in-principle approval, so that the mandated test transactions can be completed within the time
   UIDAI allows. We also request UIDAI's current response-signing certificates for pre-production
   and production, since those listed on UIDAI's website expired in April and May 2026.

10. This office undertakes to abide by the Aadhaar Act, 2016, the regulations made under it, and
    the AUA/KUA agreement, and to comply with UIDAI's circulars, standards and specifications.

Yours faithfully,

[Signature]  
[Name]  
[Designation]  
Office of the Development Commissioner (Handicrafts)  
Ministry of Textiles, Government of India  
[Telephone] | [Official e-mail]

**Enclosures**

1. AUA/KUA application form (Annex 1), signed, with declarations and undertakings.
2. Order/letter authorising the signatory to apply and to sign the AUA/KUA agreement (certified copy).
3. Copy of the section 7 Gazette notification for NHDP.
4. Letter of consent from [name of ASA] (original).
5. Statement and undertaking of expected transaction volume.
6. Short description of the portal's Aadhaar design and data flow (from the technical team).
7. [GSTN / TAN, if applicable.]

---

## Notes for the sender (delete before sending)

- **Legal basis checked.** UIDAI's form lets a Central Government Ministry tick section 7. Regulation
  12(1)(b) allows authentication "required by the Central Government ... under section 7". Section
  4(4)(b)(ii) is the route for non-benefit uses approved by MeitY; it is not needed if section 7
  applies. Sources: UIDAI onboarding form (Annex 1),
  https://uidai.gov.in/images/AUA_KUA_Onboarding_Process_Application_Form_and_Audit_Compliance_Checklists.pdf ;
  Regulations 2021 as on 15.12.2025,
  https://uidai.gov.in/images/The_Aadhaar_Authentication_and_Offline_Verifications_Regulations_2021-_Clean_copy-30122025.pdf ;
  section 7 list (NHDP, 29 Mar 2017), https://www.uidai.gov.in/images/resource/section7GoInotifications.pdf
  (all accessed 17 Oct 2026).
- **Wage compensation figure.** NHDP guidelines 2021-22 to 2025-26,
  https://indian.handicrafts.gov.in/static-pdf/scheme-guideline.pdf (accessed 17 Oct 2026). Use the
  current figure if the scheme has been renewed.
- **Fees.** Transaction fees do not apply to the Central Government (Payment of Fees Regulations
  2023, reg. 3(3)(c)). Whether the licence fee applies is open; see QUESTIONS_FOR_UIDAI.md, Question 10.
- **Do not** claim the portal stores no Aadhaar number anywhere until Blocker B1 is fixed: the
  verification never stores it, but the artisan register does today.
- **Do not** claim an HSM until Blocker B4 is fixed: today the key is a password-protected file.
- **Sub-AUA alternative.** If an existing government AUA (for example a State department or another
  Ministry) agrees, this office could instead be appointed its Sub-AUA (UIDAI Circular 2 of 2025,
  https://uidai.gov.in/images/Circular_2_of_2025_Amended__Sub-AUA_and_Sub-KUA_application_form__Joint_Undertaking.pdf ),
  which avoids running its own AUA onboarding. The letter would then come from that AUA.
