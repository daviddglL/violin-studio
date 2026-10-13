import { linkId } from "../../src/teacher/link-id";

describe("linkId", () => {
  test("une uids alfanumericos con guion bajo", () => {
    expect(linkId("tea", "stu")).toBe("tea_stu");
  });
  test.each([["a_b", "c"], ["a", "b_c"], ["", "c"], ["a", ""], ["a-b", "c"], ["a/b", "c"], ["a".repeat(129), "c"]])(
    "rechaza uids ambiguos o invalidos (%j, %j)",
    (t, s) => {
      expect(() => linkId(t, s)).toThrow();
    },
  );
});
