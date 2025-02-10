import { OPERATOR, TOKEN, type Token, tokenize } from "./index";

describe("tokenize", () => {
  it("tokenizes valid expression", () => {
    const { tokens } = tokenize('case([Total] > 200, [T], "Nothing")');

    expect(tokens).toEqual([
      { type: TOKEN.Identifier, start: 0, end: 4, isReference: false }, // case
      { type: TOKEN.Operator, op: "(", start: 4, end: 5 }, // (
      { type: TOKEN.Identifier, start: 5, end: 12, isReference: true }, // [Total]
      { type: TOKEN.Operator, op: ">", start: 13, end: 14 }, // >
      { type: TOKEN.Number, start: 15, end: 18 }, // 200
      { type: TOKEN.Operator, op: ",", start: 18, end: 19 }, // ,
      { type: TOKEN.Identifier, start: 20, end: 23, isReference: true }, // [T]
      { type: TOKEN.Operator, op: ",", start: 23, end: 24 }, // ,
      { type: TOKEN.String, start: 25, end: 34, value: "Nothing" }, // "Nothing"
      { type: TOKEN.Operator, op: ")", start: 34, end: 35 }, // )
    ]);
  });

  it("takes operators into account when dealing with incomplete bracket identifier tokens", () => {
    const { tokens } = tokenize('case([Total] > 200, [To, "Nothing")');

    expect(tokens).toEqual([
      { type: TOKEN.Identifier, start: 0, end: 4, isReference: false }, // case
      { type: TOKEN.Operator, op: "(", start: 4, end: 5 }, // (
      { type: TOKEN.Identifier, start: 5, end: 12, isReference: true }, // [Total]
      { type: TOKEN.Operator, op: ">", start: 13, end: 14 }, // >
      { type: TOKEN.Number, start: 15, end: 18 }, // 200
      { type: TOKEN.Operator, op: ",", start: 18, end: 19 }, // ,
      { type: TOKEN.Identifier, start: 20, end: 23, isReference: true }, // [To <-- that's the incomplete token
      { type: TOKEN.Operator, op: ",", start: 23, end: 24 }, // ,
      { type: TOKEN.String, start: 25, end: 34, value: "Nothing" }, // "Nothing"
      { type: TOKEN.Operator, op: ")", start: 34, end: 35 }, // )
    ]);
  });

  it("tokenizes incomplete bracket identifier followed by whitespace (metabase#50925)", () => {
    const { tokens } = tokenize("[Pr [Price]");

    expect(tokens).toEqual([
      { type: TOKEN.Identifier, start: 0, end: 4, isReference: true }, // [Pr
      { type: TOKEN.Identifier, start: 4, end: 11, isReference: true }, // [Price]
    ]);
  });

  it("tokenizes incomplete bracket identifier followed by bracket identifier (metabase#50925)", () => {
    const { tokens } = tokenize("[Pr[Price]");

    expect(tokens).toEqual([
      { type: TOKEN.Identifier, start: 0, end: 3, isReference: true }, // [Pr
      { type: TOKEN.Identifier, start: 3, end: 10, isReference: true }, // [Price]
    ]);
  });

  it("should be case insensitive to certain literals and operators", () => {
    const cases: [string, Partial<Token>, number][] = [
      ["true", { type: TOKEN.Boolean }, 0],
      ["false", { type: TOKEN.Boolean }, 0],
      ["A or B", { type: TOKEN.Operator, op: OPERATOR.Or }, 1],
      ["A and B", { type: TOKEN.Operator, op: OPERATOR.And }, 1],
      ["not A", { type: TOKEN.Operator, op: OPERATOR.Not }, 0],
    ];

    /**
     * Takes a string and returns a list of all possible cases of the string.
     */
    function casePermutations(str: string): string[] {
      let results = [""];

      for (const char of str) {
        const newResults = [];
        for (const perm of results) {
          newResults.push(perm + char.toLowerCase());
          newResults.push(perm + char.toUpperCase());
        }
        results = newResults;
      }

      return results;
    }

    const permutations = cases.flatMap(
      ([input, token, index]: [string, Partial<Token>, number]) =>
        casePermutations(input).map((str): [string, Partial<Token>, number] => [
          str,
          token,
          index,
        ]),
    );

    for (const [input, token, index] of permutations) {
      const { tokens } = tokenize(input);
      expect(tokens[index]).toEqual(
        expect.objectContaining({
          ...token,
        }),
      );
    }
  });

  it("should tokenize numbers correctly", () => {
    const cases = [
      "1",
      "1e2",
      "1E2",
      "1e-2",
      "1E-2",
      ".1e2",
      ".1E2",
      ".1e-2",
      ".1E-2",
      "1.2",
      "1.2e3",
      "1.2E3",
      "1.2e-3",
      "1.2E-3",
      "1.2e03",
      "1.2E03",
      "1.2e-03",
      "1.2E-03",
      ".2e3",
      ".2E3",
      ".2e-3",
      ".2E-3",
      ".1",
      ".1e2",
      "1e99999",
      "1E99999",
      ".1e99999",
      ".1E99999",
      "1e-99999",
      "1E-99999",
      ".1e-99999",
      ".1E-99999",
    ];

    for (const input of cases) {
      const { tokens } = tokenize(input);
      expect(tokens).toHaveLength(1);
      expect(tokens[0].type).toBe(TOKEN.Number);
      expect(tokens[0].start).toBe(0);
      expect(tokens[0].end).toBe(input.length);
    }
  });
});
