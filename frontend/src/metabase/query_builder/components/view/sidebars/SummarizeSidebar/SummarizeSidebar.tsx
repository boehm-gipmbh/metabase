import { useCallback } from "react";
import { t } from "ttag";

import { color } from "metabase/lib/colors";
import type { UpdateQueryHookProps } from "metabase/query_builder/hooks/types";
import { useDefaultQueryAggregation } from "metabase/query_builder/hooks/use-default-query-aggregation";
import { Divider } from "metabase/ui";

import {
  SummarizeAggregationItemList,
  SummarizeBreakoutColumnList,
} from "./SummarizeContent";
import { SidebarView } from "./SummarizeSidebar.styled";

type SummarizeSidebarProps = {
  className?: string;
  onClose: () => void;
} & UpdateQueryHookProps;

export function SummarizeSidebar({
  className,
  query: initialQuery,
  onQueryChange,
  onClose,
  stageIndex,
}: SummarizeSidebarProps) {
  const { query, handleUpdateQuery, handleAggregationChange, hasAggregations } =
    useDefaultQueryAggregation({
      query: initialQuery,
      onQueryChange,
      stageIndex,
    });

  const handleDoneClick = useCallback(() => {
    onQueryChange(query);
    onClose();
  }, [query, onQueryChange, onClose]);

  return (
    <SidebarView
      className={className}
      title={t`Summarize by`}
      color={color("summarize")}
      onDone={handleDoneClick}
    >
      <SummarizeAggregationItemList
        px="lg"
        query={query}
        onQueryChange={handleAggregationChange}
        stageIndex={stageIndex}
      />
      <Divider my="lg" />
      {hasAggregations && (
        <SummarizeBreakoutColumnList
          px="lg"
          query={query}
          onQueryChange={handleUpdateQuery}
          stageIndex={stageIndex}
        />
      )}
    </SidebarView>
  );
}
