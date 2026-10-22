-- UNIFY U7 / A2: an answer imported from a hand-filled workbook (an UPLOAD session) carries origin WORKBOOK.
-- Additive; the same statement as documentation-portal's 20261108090000_answer_origin_workbook.
ALTER TYPE "AnswerOrigin" ADD VALUE IF NOT EXISTS 'WORKBOOK';
