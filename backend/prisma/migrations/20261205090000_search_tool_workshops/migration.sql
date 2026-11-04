DROP TRIGGER IF EXISTS "search_tool_artisans" ON "ToolArtisan";
CREATE TRIGGER "search_tool_artisans" AFTER INSERT OR UPDATE OR DELETE ON "ToolArtisan"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('tools', 'toolId');
DROP TRIGGER IF EXISTS "search_artisan_workshops" ON "WorkshopArtisan";
CREATE TRIGGER "search_artisan_workshops" AFTER INSERT OR UPDATE OR DELETE ON "WorkshopArtisan"
  FOR EACH ROW EXECUTE FUNCTION search_enqueue('artisans', 'artisanId');
