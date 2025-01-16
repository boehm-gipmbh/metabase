import type { ReactNode } from "react";
import { useCallback } from "react";

import { useTranslateContent } from "metabase/i18n/components/ContentTranslationContext";
import type { IconProps } from "metabase/ui";
import type { OnChangeCardAndRun } from "metabase/visualizations/types";
import type {
  Series,
  TransformedSeries,
  VisualizationSettings,
} from "metabase-types/api";

import { ChartCaptionRoot } from "./ChartCaption.styled";

interface ChartCaptionProps {
  series: Series;
  settings: VisualizationSettings;
  icon?: IconProps;
  actionButtons?: ReactNode;
  width?: number;
  getHref?: () => string | undefined;
  onChangeCardAndRun: OnChangeCardAndRun;
}

const ChartCaption = ({
  series,
  settings,
  icon,
  actionButtons,
  onChangeCardAndRun,
  getHref,
  width,
}: ChartCaptionProps) => {
  const tc = useTranslateContent();

  const localizedTitle =
    tc(settings, "card.title") ?? tc(series[0].card, "name");
  const description = settings["card.description"];
  const data = (series as TransformedSeries)._raw || series;
  const card = data[0].card;
  const cardIds = new Set(data.map(s => s.card.id));
  const canSelectTitle = cardIds.size === 1 && onChangeCardAndRun;

  const handleSelectTitle = useCallback(() => {
    onChangeCardAndRun({
      nextCard: card,
    });
  }, [card, onChangeCardAndRun]);

  return (
    <ChartCaptionRoot
      title={localizedTitle}
      description={description}
      getHref={getHref}
      icon={icon}
      actionButtons={actionButtons}
      onSelectTitle={canSelectTitle ? handleSelectTitle : undefined}
      width={width}
    />
  );
};

// eslint-disable-next-line import/no-default-export -- deprecated usage
export default ChartCaption;
