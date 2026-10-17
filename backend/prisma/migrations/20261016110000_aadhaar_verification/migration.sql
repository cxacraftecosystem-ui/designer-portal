-- Aadhaar verification (docs/AADHAAR.md). Additive only: two enums, nullable result columns on
-- "User" and "Artisan", and the OTP throttle table. No existing row changes, nothing is backfilled,
-- and no column can hold an Aadhaar number, a VID, a photograph, an address, a share code, an OTP
-- or a signed payload -- only the last four digits UIDAI itself prints and the fields it signed.

-- Guarded, so a database that already has the types (a half-applied run, a restored dump) does
-- not fail the deploy on them.
DO $$
BEGIN
    CREATE TYPE "AadhaarVerifyStatus" AS ENUM ('VERIFIED', 'NAME_MISMATCH', 'REJECTED');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END
$$;

DO $$
BEGIN
    CREATE TYPE "AadhaarVerifyMethod" AS ENUM ('SECURE_QR', 'OFFLINE_XML', 'ONLINE_OTP', 'ONLINE_EKYC');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END
$$;

ALTER TABLE "User"
    ADD COLUMN "aadhaarVerifyStatus" "AadhaarVerifyStatus",
    ADD COLUMN "aadhaarVerifyMethod" "AadhaarVerifyMethod",
    ADD COLUMN "aadhaarVerifiedAt" TIMESTAMP(3),
    ADD COLUMN "aadhaarVerifyLast4" TEXT,
    ADD COLUMN "aadhaarVerifyName" TEXT,
    ADD COLUMN "aadhaarVerifyBirthYear" INTEGER,
    ADD COLUMN "aadhaarVerifyGender" TEXT,
    ADD COLUMN "aadhaarVerifySignedAt" TIMESTAMP(3),
    ADD COLUMN "aadhaarVerifyTxn" TEXT,
    ADD COLUMN "aadhaarVerifyNameMatch" BOOLEAN,
    ADD COLUMN "aadhaarVerifyById" TEXT,
    ADD COLUMN "aadhaarVerifyDecidedById" TEXT,
    ADD COLUMN "aadhaarVerifyDecidedAt" TIMESTAMP(3);

ALTER TABLE "Artisan"
    ADD COLUMN "aadhaarVerifyStatus" "AadhaarVerifyStatus",
    ADD COLUMN "aadhaarVerifyMethod" "AadhaarVerifyMethod",
    ADD COLUMN "aadhaarVerifiedAt" TIMESTAMP(3),
    ADD COLUMN "aadhaarVerifyLast4" TEXT,
    ADD COLUMN "aadhaarVerifyName" TEXT,
    ADD COLUMN "aadhaarVerifyBirthYear" INTEGER,
    ADD COLUMN "aadhaarVerifyDob" TIMESTAMP(3),
    ADD COLUMN "aadhaarVerifyGender" TEXT,
    ADD COLUMN "aadhaarVerifySignedAt" TIMESTAMP(3),
    ADD COLUMN "aadhaarVerifyTxn" TEXT,
    ADD COLUMN "aadhaarVerifyNameMatch" BOOLEAN,
    ADD COLUMN "aadhaarVerifyById" TEXT,
    ADD COLUMN "aadhaarVerifyDecidedById" TEXT,
    ADD COLUMN "aadhaarVerifyDecidedAt" TIMESTAMP(3);

-- A four-digit column can only ever hold four digits.
ALTER TABLE "User" ADD CONSTRAINT "User_aadhaarVerifyLast4_check"
    CHECK ("aadhaarVerifyLast4" IS NULL OR "aadhaarVerifyLast4" ~ '^[0-9]{4}$');
ALTER TABLE "Artisan" ADD CONSTRAINT "Artisan_aadhaarVerifyLast4_check"
    CHECK ("aadhaarVerifyLast4" IS NULL OR "aadhaarVerifyLast4" ~ '^[0-9]{4}$');

CREATE TABLE "AadhaarOtpThrottle" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "uidHash" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AadhaarOtpThrottle_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "AadhaarOtpThrottle_userId_createdAt_idx" ON "AadhaarOtpThrottle"("userId", "createdAt");
CREATE INDEX "AadhaarOtpThrottle_uidHash_createdAt_idx" ON "AadhaarOtpThrottle"("uidHash", "createdAt");
CREATE INDEX "AadhaarOtpThrottle_createdAt_idx" ON "AadhaarOtpThrottle"("createdAt");
