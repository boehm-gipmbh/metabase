/* eslint-disable react/prop-types */
import cx from "classnames";

import CS from "metabase/css/core/index.css";
import { alpha, color } from "metabase/lib/colors";
import type { TableCellFormatter } from "metabase/visualizations/types";
import type { RowValue } from "metabase-types/api";

import type { TextAlign } from "../types";

import { BaseCell } from "./BaseCell";
import S from "./MiniBarCell.module.css";

const BAR_HEIGHT = 8;
const BAR_WIDTH = 70;
const BORDER_RADIUS = 3;

const LABEL_MIN_WIDTH = 30;

export interface MiniBarCellProps {
  value: RowValue;
  extent: [number, number];
  formatter: TableCellFormatter;
  backgroundColor?: string;
  align?: TextAlign;
}

export const MiniBarCell = ({
  value,
  extent: [min, max],
  formatter,
  backgroundColor,
  align,
}: MiniBarCellProps) => {
  if (typeof value !== "number") {
    return null;
  }

  const hasNegative = min < 0;
  const isNegative = value < 0;
  const barPercent =
    (Math.abs(value) / Math.max(Math.abs(min), Math.abs(max))) * 100;
  const barColor = isNegative ? color("error") : color("brand");

  const barStyle = !hasNegative
    ? {
        width: barPercent + "%",
        left: 0,
        borderRadius: BORDER_RADIUS,
      }
    : isNegative
      ? {
          width: barPercent / 2 + "%",
          right: "50%",
          borderTopRightRadius: 0,
          borderBottomRightRadius: 0,
          borderTopLeftRadius: BORDER_RADIUS,
          borderBottomLeftRadius: BORDER_RADIUS,
        }
      : {
          width: barPercent / 2 + "%",
          left: "50%",
          borderTopLeftRadius: 0,
          borderBottomLeftRadius: 0,
          borderTopRightRadius: BORDER_RADIUS,
          borderBottomRightRadius: BORDER_RADIUS,
        };

  return (
    <BaseCell
      className={S.root}
      backgroundColor={backgroundColor}
      align={align}
    >
      {/* TEXT VALUE */}
      <div
        className={cx(CS.textEllipsis, CS.textBold, CS.textRight, CS.flexFull)}
        style={{ minWidth: LABEL_MIN_WIDTH }}
      >
        {formatter(value)}
      </div>
      {/* OUTER CONTAINER BAR */}
      <div
        data-testid="mini-bar"
        className={CS.ml1}
        style={{
          position: "relative",
          width: BAR_WIDTH,
          height: BAR_HEIGHT,
          backgroundColor: alpha(barColor, 0.2),
          borderRadius: BORDER_RADIUS,
        }}
      >
        {/* INNER PROGRESS BAR */}
        <div
          style={{
            position: "absolute",
            top: 0,
            bottom: 0,
            backgroundColor: barColor,
            ...barStyle,
          }}
        />
        {/* CENTER LINE */}
        {hasNegative && (
          <div
            style={{
              position: "absolute",
              left: "50%",
              top: 0,
              bottom: 0,
              borderLeft: `1px solid ${color("white")}`,
            }}
          />
        )}
      </div>
    </BaseCell>
  );
};
