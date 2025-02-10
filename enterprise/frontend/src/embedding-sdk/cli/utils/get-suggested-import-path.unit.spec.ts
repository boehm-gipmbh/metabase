import { getSuggestedImportPath } from "../utils/get-suggested-import-path";

describe("CLI > getSuggestedImportPath", () => {
  it.each([
    // defaults to GENERATED_COMPONENTS_DEFAULT_PATH
    ["", "../components/metabase"],

    // user inputs
    [".", ".."],
    ["components/foo", "../components/foo"],
    ["components/foo/bar", "../components/foo/bar"],
    ["./src/components/bar", "../components/bar"],
    ["src/components/baz", "../components/baz"],
    ["modules/quux", "../modules/quux"],
    ["components", "../components"],
  ])("suggests a reasonable import path", (input, suggestion) => {
    expect(getSuggestedImportPath(input)).toBe(suggestion);
  });
});
