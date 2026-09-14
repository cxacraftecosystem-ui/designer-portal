-- THREE NEW VALUES ON "DesignWorkshopStatus", AND NOTHING ELSE IN THIS FILE.
--
-- Prisma sends a migration file as ONE multi-statement query, which Postgres wraps in an implicit
-- transaction, and a value added by ALTER TYPE ... ADD VALUE CANNOT BE USED in the same
-- transaction that added it. So any later statement here that mentioned 'PRE_SUBMISSION',
-- 'NEEDS_REVISION' or 'APPROVED' -- a DEFAULT, a CHECK, a backfill UPDATE, a partial index -- would
-- fail the deploy with "unsafe use of new value of enum type". The columns and the table this
-- feature needs are in 20260913130200_dw_inspection_feedback, which is a separate file for exactly
-- that reason and which deliberately names none of these tokens.
--
-- THE SHAPE IS 20260724120000_six_tier_roles', NOT 20260827140000_inspector_role'. Several values
-- may share one file as long as none of them is USED in it; one file per TYPE is the line, so the
-- "ReviewRecordType" addition is 20260913130100 and not appended here. A reader asking "when did
-- PRE_SUBMISSION arrive" should find a file whose entire content is the answer.
--
-- NO BACKFILL, AND THE ABSENCE IS THE DECISION. Every existing row keeps the status it has.
-- 'SUBMITTED' has been REDEFINED by this wave -- it used to be the designer's own forward act and
-- is now the state after the sanctioning authority approves -- and it would be trivial to relabel
-- every existing SUBMITTED row as APPROVED. That would be a lie in an audit trail: nobody approved
-- them, there is no ReviewLog row for any of them, and "reviewedById" would be NULL on a workshop
-- claiming a sign-off. They stay SUBMITTED, and the transition graph gives them a way into the
-- loop (SUBMITTED -> PRE_SUBMISSION).
--
-- IF YOU ARE LOOKING FOR THE TRANSITION GRAPH, IT IS NOT HERE. Postgres knows only that these are
-- members of the type; which value may follow which is
-- `app/schemas/design_workshop_review_loop.py::LEGAL_TRANSITIONS`, enforced in
-- `api/routes/design_workshops.py::update_design_workshop` and in the send-back route.

-- AlterEnum
ALTER TYPE "DesignWorkshopStatus" ADD VALUE IF NOT EXISTS 'PRE_SUBMISSION';
ALTER TYPE "DesignWorkshopStatus" ADD VALUE IF NOT EXISTS 'NEEDS_REVISION';
ALTER TYPE "DesignWorkshopStatus" ADD VALUE IF NOT EXISTS 'APPROVED';
