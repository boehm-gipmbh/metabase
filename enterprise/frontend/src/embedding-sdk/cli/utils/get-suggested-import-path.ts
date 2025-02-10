import path from "path";

import { GENERATED_COMPONENTS_DEFAULT_PATH } from "../constants/config";

export const getSuggestedImportPath = (componentDir?: string): string => {
  const input = componentDir || GENERATED_COMPONENTS_DEFAULT_PATH;

  if (input === ".") {
    return "..";
  }

  const normalized = path.normalize(input).replace(/^\.\//, "");
  const parts = normalized.split("/");

  if (parts.length === 1) {
    return `../${parts[0]}`;
  }

  // We do not want to include the "src" directory in the import path.
  if (parts[0] === "src") {
    parts.shift();
  }

  return `../${parts.join("/")}`;
};
