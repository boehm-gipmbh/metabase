import { parser } from "./parser";

export const enum TOKEN {
  Operator = 1,
  Number = 2,
  String = 3,
  Identifier = 4,
  Boolean = 5,
}

export enum OPERATOR {
  Comma = ",",
  OpenParenthesis = "(",
  CloseParenthesis = ")",
  Plus = "+",
  Minus = "-",
  Star = "*",
  Slash = "/",
  Equal = "=",
  NotEqual = "!=",
  LessThan = "<",
  GreaterThan = ">",
  LessThanEqual = "<=",
  GreaterThanEqual = ">=",
  Not = "not",
  And = "and",
  Or = "or",
  True = "true",
  False = "false",
}

const OPERATORS = new Set(Object.values(OPERATOR));

type ParseError = {
  message: string;
  pos: number;
  len: number;
};

export type Token =
  | {
      type: TOKEN.Operator;
      start: number;
      end: number;
      op: OPERATOR;
    }
  | {
      type: TOKEN.Number;
      start: number;
      end: number;
    }
  | {
      type: TOKEN.String;
      start: number;
      end: number;
      value: string;
    }
  | {
      type: TOKEN.Identifier;
      start: number;
      end: number;
      isReference: boolean;
    }
  | {
      type: TOKEN.Boolean;
      start: number;
      end: number;
    };

function isValidOperator(
  op: string,
): op is (typeof OPERATOR)[keyof typeof OPERATOR] {
  return OPERATORS.has(op as OPERATOR);
}

export function tokenize(expression: string) {
  const tokens: Token[] = [];
  const errors: ParseError[] = [];

  const tree = parser.parse(expression);
  const cursor = tree.cursor();

  cursor.iterate(function (node) {
    if (node.type.name === "Identifier") {
      tokens.push({
        type: TOKEN.Identifier,
        start: node.from,
        end: node.to,
        isReference: false,
      });
      return;
    }
    if (node.type.name === "Reference") {
      tokens.push({
        type: TOKEN.Identifier,
        start: node.from,
        end: node.to,
        isReference: true,
      });
      return;
    }
    if (node.type.name === "Number") {
      tokens.push({
        type: TOKEN.Number,
        start: node.from,
        end: node.to,
      });
      return;
    }
    if (node.type.name === "String") {
      tokens.push({
        type: TOKEN.String,
        start: node.from,
        end: node.to,
        value: expression.slice(node.from + 1, node.to - 1),
      });
      return;
    }
    if (node.type.name === "Boolean") {
      tokens.push({
        type: TOKEN.Boolean,
        start: node.from,
        end: node.to,
      });
      return;
    }

    const op = node.type.name.toLowerCase();
    if (isValidOperator(op)) {
      tokens.push({
        type: TOKEN.Operator,
        op,
        start: node.from,
        end: node.to,
      });
    }
  });

  return { tokens, errors };
}
