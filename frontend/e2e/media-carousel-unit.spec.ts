import { expect, test } from "@playwright/test";

import { describeSubject } from "../components/media/MediaCarousel";

test.describe("the carousel names its subject once", () => {
  test("a label and a bare noun produce the same strings", () => {
    const fromLabel = describeSubject("traditional motif photographs");
    const fromNoun = describeSubject("traditional motif");
    expect(fromLabel).toEqual(fromNoun);
    expect(fromLabel.many).toBe("traditional motif photographs");
    expect(fromLabel.one).toBe("traditional motif photograph");
  });

  test("the singular is what an arrow announces, and it moves by one", () => {
    expect(describeSubject("contemporary motif photographs").one).toBe("contemporary motif photograph");
    expect(describeSubject("cluster photograph").one).toBe("cluster photograph");
  });

  test("a stem that is only the picture word degrades to the bare word, with no doubled space", () => {
    for (const input of ["Photographs", "photographs", "photograph", "", "   "]) {
      const subject = describeSubject(input);
      expect(subject.many, `many for ${JSON.stringify(input)}`).toBe("photographs");
      expect(subject.one, `one for ${JSON.stringify(input)}`).toBe("photograph");
      expect(subject.many, "no doubled space").not.toContain("  ");
    }
  });

  test("interior whitespace is collapsed, not merely trimmed", () => {
    expect(describeSubject("  traditional   motif  photographs  ").many).toBe("traditional motif photographs");
  });

  test("a word that merely ENDS in the picture word is not eaten", () => {
    expect(describeSubject("microphotographs").many).toBe("microphotographs photographs");
  });

  test("a label ending in anything else keeps all of it", () => {
    expect(describeSubject("360° capture").one).toBe("360° capture photograph");
    expect(describeSubject("mood & reference board").many).toBe("mood & reference board photographs");
  });

  test("case is never rewritten", () => {
    expect(describeSubject("Traditional motif photographs").many).toBe("Traditional motif photographs");
  });

  test("only 'photograph(s)' is recognised — no synonym guessing", () => {
    expect(describeSubject("reference images").many).toBe("reference images photographs");
    expect(describeSubject("site photos").many).toBe("site photos photographs");
  });
});
