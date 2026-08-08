import { test, expect } from "@playwright/test";
import { MIN_SAMPLE_FOR_QUANTILES } from "@/lib/marketAnalysis";
test("alias import works in node", () => { expect(MIN_SAMPLE_FOR_QUANTILES).toBe(5); });
