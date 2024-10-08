import type {
  DatePickerOperator,
  DatePickerShortcut,
  ShortcutOption,
} from "../types";

import { SHORTCUT_OPTION_GROUPS, TYPE_OPTIONS } from "./constants";
import type { TypeOption } from "./types";

export function getShortcutOptions(
  availableShortcuts: ReadonlyArray<DatePickerShortcut>,
): ShortcutOption[] {
  return getShortcutOptionGroups(availableShortcuts).flat();
}

export function getShortcutOptionGroups(
  availableShortcuts: ReadonlyArray<DatePickerShortcut>,
): ShortcutOption[][] {
  return SHORTCUT_OPTION_GROUPS.map(options =>
    options.filter(option => availableShortcuts.includes(option.shortcut)),
  ).filter(options => options.length > 0);
}

export function getTypeOptions(
  availableOperators: ReadonlyArray<DatePickerOperator>,
): TypeOption[] {
  return TYPE_OPTIONS.filter(
    option =>
      option.operators.length === 0 ||
      option.operators.some(operator => availableOperators.includes(operator)),
  );
}
