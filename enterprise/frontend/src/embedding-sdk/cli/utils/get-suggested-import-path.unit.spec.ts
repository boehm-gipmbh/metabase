import { getSuggestedImportPath } from "../utils/get-suggested-import-path";

describe("CLI > getSuggestedImportPath", () => {
  it.each([
    ["", "./metabase"],
    [".", "."],
    ["./src/components", "./components"],
  ])("suggests a reasonable import path", (input, suggestion) => {
    expect(
      getSuggestedImportPath({ isNextJs: false, componentDir: input }),
    ).toBe(suggestion);
  });

  it("suggests a reasonable default import path for Next.js", () => {
    expect(getSuggestedImportPath({ isNextJs: true })).toBe(
      "../components/metabase",
    );

    expect(
      getSuggestedImportPath({ isNextJs: true, componentDir: "foo/bar" }),
    ).toBe("../foo/bar");
  });
});
