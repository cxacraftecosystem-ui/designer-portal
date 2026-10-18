-- One search across both portals: an interview's artisan links reach its search document.
--
-- Additive and safe on live data: one trigger on "QuestionnaireInterviewArtisan", keyed on the
-- interview, so linking or unlinking an artisan rebuilds the interview (and, through the worker,
-- the files hanging off it), whose "shareable" depends on that artisan's cross-portal link. The
-- Field Repository has had the same trigger since its search migration.

DROP TRIGGER IF EXISTS "search_interview_artisans" ON "QuestionnaireInterviewArtisan";
CREATE TRIGGER "search_interview_artisans" AFTER INSERT OR UPDATE OR DELETE ON "QuestionnaireInterviewArtisan"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('interviews', 'interviewId');
