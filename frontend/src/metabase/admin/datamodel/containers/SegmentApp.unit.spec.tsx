import userEvent from "@testing-library/user-event";

import { callMockEvent } from "__support__/events";
import {
  setupCardDataset,
  setupDatabasesEndpoints,
  setupRecentViewsAndSelectionsEndpoints,
  setupSearchEndpoints,
  setupSegmentsEndpoints,
} from "__support__/server-mocks";
import {
  act,
  mockGetBoundingClientRect,
  renderWithProviders,
  screen,
  waitFor,
  waitForLoaderToBeRemoved,
} from "__support__/ui";
import { BEFORE_UNLOAD_UNSAVED_MESSAGE } from "metabase/common/hooks/use-before-unload";
import { Route } from "metabase/hoc/Title";
import { checkNotNull } from "metabase/lib/types";
import { createSampleDatabase } from "metabase-types/api/mocks/presets";

import SegmentApp from "./SegmentApp";

const TestHome = () => <div />;

const SEGMENTS_URL = "/admin/datamodel/segments";
const FORM_URL = "/admin/datamodel/segment/create";

interface SetupOpts {
  initialRoute?: string;
}

const setup = ({ initialRoute = FORM_URL }: SetupOpts = {}) => {
  mockGetBoundingClientRect();
  setupDatabasesEndpoints([createSampleDatabase()]);
  setupSearchEndpoints([]);
  setupCardDataset({
    data: {
      rows: [[null]],
    },
  });
  setupRecentViewsAndSelectionsEndpoints([], ["selections"]);
  setupSegmentsEndpoints([]);

  const { history } = renderWithProviders(
    <>
      <Route path="/" component={TestHome} />
      <Route path={SEGMENTS_URL} component={TestHome} />
      <Route path={FORM_URL} component={SegmentApp} />
    </>,
    {
      initialRoute,
      withRouter: true,
    },
  );

  const mockEventListener = jest.spyOn(window, "addEventListener");

  return {
    history: checkNotNull(history),
    mockEventListener,
  };
};

describe("SegmentApp", () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("should have beforeunload event when user makes edits to a segment", async () => {
    const { mockEventListener } = setup();

    await userEvent.type(screen.getByLabelText("Name Your Segment"), "Name");

    const mockEvent = await waitFor(() => {
      return callMockEvent(mockEventListener, "beforeunload");
    });
    expect(mockEvent.preventDefault).toHaveBeenCalled();
    expect(mockEvent.returnValue).toBe(BEFORE_UNLOAD_UNSAVED_MESSAGE);
  });

  it("should not have an beforeunload event when segment is unedited", () => {
    const { mockEventListener } = setup();

    const mockEvent = callMockEvent(mockEventListener, "beforeunload");

    expect(mockEvent.preventDefault).not.toHaveBeenCalled();
    expect(mockEvent.returnValue).toBe(undefined);
  });

  it("does not show custom warning modal when leaving with no changes via SPA navigation", () => {
    const { history } = setup({ initialRoute: "/" });

    act(() => {
      history.push(FORM_URL);
      history.goBack();
    });

    expect(screen.queryByTestId("leave-confirmation")).not.toBeInTheDocument();
  });

  it("shows custom warning modal when leaving with unsaved changes via SPA navigation", async () => {
    const { history } = setup({ initialRoute: "/" });

    act(() => {
      history.push(FORM_URL);
    });

    await userEvent.type(
      await screen.findByLabelText("Name Your Segment"),
      "Name",
    );

    act(() => {
      history.goBack();
    });

    expect(await screen.findByTestId("leave-confirmation")).toBeInTheDocument();
  });

  it("does not show custom warning modal when saving changes", async () => {
    const { history } = setup();

    await userEvent.click(screen.getByText("Select a table"));

    await waitForLoaderToBeRemoved();

    await userEvent.click(await screen.findByText("Orders"));

    await waitForLoaderToBeRemoved();

    await userEvent.click(
      screen.getByText("Add filters to narrow your answer"),
    );
    await userEvent.click(screen.getByText("ID"));
    await userEvent.type(screen.getByPlaceholderText("Enter an ID"), "1");
    await userEvent.click(screen.getByText("Add filter"));
    await userEvent.type(screen.getByLabelText("Name Your Segment"), "Name");
    await userEvent.type(
      screen.getByLabelText("Describe Your Segment"),
      "Description",
    );

    await waitFor(() => {
      expect(screen.getByText("Save changes")).toBeEnabled();
    });

    await userEvent.click(screen.getByText("Save changes"));

    await waitFor(() => {
      expect(history.getCurrentLocation().pathname).toBe(SEGMENTS_URL);
    });

    expect(screen.queryByTestId("leave-confirmation")).not.toBeInTheDocument();
  });
});
