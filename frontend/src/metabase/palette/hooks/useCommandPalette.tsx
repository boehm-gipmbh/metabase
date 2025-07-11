import type { Query } from "history";
import { Priority, useKBar, useRegisterActions } from "kbar";
import { useMemo, useState } from "react";
import { useDebounce } from "react-use";
import { jt, t } from "ttag";

import { getAdminPaths } from "metabase/admin/app/selectors";
import { getPerformanceAdminPaths } from "metabase/admin/performance/constants/complex";
import { useListRecentsQuery, useSearchQuery } from "metabase/api";
import { useSetting } from "metabase/common/hooks";
import { ROOT_COLLECTION } from "metabase/entities/collections/constants";
import Search from "metabase/entities/search";
import { SEARCH_DEBOUNCE_DURATION } from "metabase/lib/constants";
import { getIcon } from "metabase/lib/icon";
import { getName } from "metabase/lib/name";
import { useDispatch, useSelector } from "metabase/lib/redux";
import * as Urls from "metabase/lib/urls";
import { PLUGIN_CACHING, PLUGIN_METABOT } from "metabase/plugins";
import { trackSearchClick } from "metabase/search/analytics";
import {
  getDocsSearchUrl,
  getDocsUrl,
  getSettings,
} from "metabase/selectors/settings";
import { canAccessSettings, getUserIsAdmin } from "metabase/selectors/user";
import { getShowMetabaseLinks } from "metabase/selectors/whitelabel";
import { Icon, type IconName } from "metabase/ui";
import {
  type RecentItem,
  isRecentCollectionItem,
  isRecentTableItem,
} from "metabase-types/api";

import { getAdminSettingsSections } from "../constants";
import type { PaletteAction } from "../types";
import { filterRecentItems } from "../utils";

export const useCommandPalette = ({
  locationQuery,
}: {
  locationQuery: Query;
}) => {
  const dispatch = useDispatch();
  const docsUrl = useSelector((state) => getDocsUrl(state, {}));
  const showMetabaseLinks = useSelector(getShowMetabaseLinks);

  const isAdmin = useSelector(getUserIsAdmin);
  const canUserAccessSettings = useSelector(canAccessSettings);

  const isSearchTypeaheadEnabled = useSetting("search-typeahead-enabled");

  // Used for finding actions within the list
  const { searchQuery } = useKBar((state) => ({
    searchQuery: state.searchQuery,
  }));
  const trimmedQuery = searchQuery.trim();

  // Used for finding objects across the Metabase instance
  const [debouncedSearchText, setDebouncedSearchText] = useState(trimmedQuery);

  useDebounce(
    () => {
      setDebouncedSearchText(trimmedQuery);
    },
    SEARCH_DEBOUNCE_DURATION,
    [trimmedQuery],
  );

  const hasQuery = searchQuery.length > 0;

  const {
    currentData: searchResults,
    isFetching: isSearchLoading,
    error: searchError,
  } = useSearchQuery(
    {
      q: debouncedSearchText,
      context: "command-palette",
      include_dashboard_questions: true,
      limit: 20,
    },
    {
      skip: !debouncedSearchText || !isSearchTypeaheadEnabled,
      refetchOnMountOrArgChange: true,
    },
  );

  const { data: recentItems } = useListRecentsQuery(undefined, {
    refetchOnMountOrArgChange: true,
  });

  const adminPaths = useSelector(getAdminPaths);
  const settingValues = useSelector(getSettings);

  const docsAction = useMemo<PaletteAction[]>(() => {
    const link = debouncedSearchText
      ? getDocsSearchUrl({ query: debouncedSearchText })
      : docsUrl;
    const ret: PaletteAction[] = [
      {
        id: "search_docs",
        name: debouncedSearchText
          ? t`Search documentation for "${debouncedSearchText}"`
          : t`View documentation`,
        section: "docs",
        keywords: debouncedSearchText, // Always match the debouncedSearchText string
        icon: "document",
        extra: {
          href: link,
        },
      },
    ];
    return ret;
  }, [debouncedSearchText, docsUrl]);

  const showDocsAction = showMetabaseLinks && hasQuery;

  useRegisterActions(showDocsAction ? docsAction : [], [
    docsAction,
    showDocsAction,
  ]);

  const metabotActions = PLUGIN_METABOT.useMetabotPalletteActions(trimmedQuery);
  useRegisterActions(metabotActions, [metabotActions]);

  const searchResultActions = useMemo<PaletteAction[]>(() => {
    const searchLocation = {
      pathname: "search",
      query: {
        ...locationQuery,
        q: debouncedSearchText,
      },
    };
    if (!isSearchTypeaheadEnabled) {
      return [
        {
          id: `search-without-typeahead`,
          name: t`View search results for "${debouncedSearchText}"`,
          section: "search",
          keywords: debouncedSearchText,
          icon: "link" as const,
          priority: Priority.HIGH,
          extra: {
            href: searchLocation,
          },
        },
      ];
    } else if (isSearchLoading) {
      return [
        {
          id: "search-is-loading",
          name: t`Loading...`,
          keywords: searchQuery,
          section: "search",
          disabled: true,
        },
      ];
    } else if (searchError) {
      return [
        {
          id: "search-error",
          name: t`Could not load search results`,
          section: "search",
          disabled: true,
        },
      ];
    } else if (debouncedSearchText) {
      if (searchResults?.data.length) {
        return [
          {
            id: `search-results-metadata`,
            name: t`View and filter all ${searchResults?.total} results`,
            section: "search",
            keywords: debouncedSearchText,
            icon: "link" as IconName,
            perform: () => {
              trackSearchClick("view_more", 0, "command-palette");
            },
            priority: Priority.HIGH,
            extra: {
              href: searchLocation,
            },
          },
        ].concat(
          searchResults.data.map((result, index) => {
            const wrappedResult = Search.wrapEntity(result, dispatch);
            const icon = getIcon(wrappedResult);
            return {
              id: `search-result-${result.model}-${result.id}`,
              name: result.name,
              subtitle: result.description || "",
              icon: icon.name,
              section: "search",
              keywords: debouncedSearchText,
              priority: Priority.NORMAL - index,
              perform: () => {
                trackSearchClick("item", index, "command-palette");
              },
              extra: {
                moderatedStatus: result.moderated_status,
                href: wrappedResult.getUrl(),
                iconColor: icon.color,
                subtext: getSearchResultSubtext(wrappedResult),
              },
            };
          }),
        );
      } else {
        return [
          {
            id: "no-search-results",
            name: t`No results for “${debouncedSearchText}”`,
            keywords: debouncedSearchText,
            section: "search",
            disabled: true,
          },
        ];
      }
    }
    return [];
  }, [
    dispatch,
    debouncedSearchText,
    searchQuery,
    isSearchLoading,
    searchError,
    searchResults,
    locationQuery,
    isSearchTypeaheadEnabled,
  ]);

  useRegisterActions(searchResultActions, [searchResultActions]);

  const recentItemsActions = useMemo<PaletteAction[]>(() => {
    return (
      filterRecentItems(recentItems ?? []).map((item) => {
        const icon = getIcon(item);
        return {
          id: `recent-item-${getName(item)}-${item.model}-${item.id}`,
          name: getName(item),
          icon: icon.name,
          section: "recent",
          perform: () => {},
          extra: {
            moderatedStatus: isRecentCollectionItem(item)
              ? item.moderated_status
              : null,
            href: Urls.modelToUrl(item),
            iconColor: icon.color,
            subtext: getRecentItemSubtext(item),
          },
        };
      }) || []
    );
  }, [recentItems]);

  useRegisterActions(hasQuery ? [] : recentItemsActions, [
    recentItemsActions,
    hasQuery,
  ]);

  const adminActions = useMemo<PaletteAction[]>(() => {
    // Subpaths - i.e. paths to items within the main Admin tabs - are needed
    // in the command palette but are not part of the main list of admin paths
    const adminSubpaths = isAdmin
      ? getPerformanceAdminPaths(PLUGIN_CACHING.getTabMetadata())
      : [];

    const paths = [...adminPaths, ...adminSubpaths];
    return paths.map((adminPath) => ({
      id: `admin-page-${adminPath.key}`,
      name: `${adminPath.name}`,
      icon: "gear",
      perform: () => {},
      section: "admin",
      extra: {
        href: adminPath.path,
      },
    }));
  }, [isAdmin, adminPaths]);

  const settingsActions = useMemo<PaletteAction[]>(() => {
    if (!canUserAccessSettings) {
      return [];
    }

    return Object.entries(getAdminSettingsSections(settingValues))
      .filter(([_slug, section]) => {
        if (section.hidden) {
          return false;
        }

        if (section.adminOnly && !isAdmin) {
          return false;
        }

        return true;
      })
      .map(([slug, section]) => ({
        id: `admin-settings-${slug}`,
        name: `${t`Settings`} - ${section.name}`,
        icon: "gear",
        perform: () => {},
        section: "admin",
        extra: {
          href: `/admin/settings/${slug}`,
        },
      }));
  }, [canUserAccessSettings, isAdmin, settingValues]);

  useRegisterActions(hasQuery ? [...adminActions, ...settingsActions] : [], [
    adminActions,
    settingsActions,
    hasQuery,
  ]);
};

export const getSearchResultSubtext = (wrappedSearchResult: any) => {
  if (wrappedSearchResult.model === "indexed-entity") {
    return jt`a record in ${(
      <Icon
        key="icon"
        name="model"
        style={{
          verticalAlign: "bottom",
          marginInlineStart: "0.25rem",
        }}
      />
    )} ${wrappedSearchResult.model_name}`;
  } else if (wrappedSearchResult.model === "table") {
    return wrappedSearchResult.table_schema
      ? `${wrappedSearchResult.database_name} (${wrappedSearchResult.table_schema})`
      : wrappedSearchResult.database_name;
  } else if (
    wrappedSearchResult.model === "card" &&
    wrappedSearchResult.dashboard
  ) {
    return (
      <>
        <Icon
          name="dashboard"
          style={{
            verticalAlign: "bottom",
            marginInline: "0.25rem",
          }}
        />
        {wrappedSearchResult.dashboard.name}
      </>
    );
  } else {
    return wrappedSearchResult.getCollection().name;
  }
};

export const getRecentItemSubtext = (item: RecentItem) => {
  if (isRecentTableItem(item)) {
    return item.table_schema
      ? `${item.database.name} (${item.table_schema})`
      : item.database.name;
  } else if (item.dashboard) {
    return (
      <>
        <Icon name="dashboard" size={12} style={{ marginInline: "0.25rem" }} />
        {item.dashboard.name}
      </>
    );
  } else if (item.parent_collection.id === null) {
    return (
      <>
        <Icon name="collection" size={12} style={{ marginInline: "0.25rem" }} />
        {ROOT_COLLECTION.name}
      </>
    );
  } else {
    return (
      <>
        <Icon name="collection" size={12} style={{ marginInline: "0.25rem" }} />
        {item.parent_collection.name}
      </>
    );
  }
};
