import userEvent from "@testing-library/user-event";

import { screen } from "__support__/ui";
import { createMockDashboard } from "metabase-types/api/mocks";

import { setup } from "./setup";

jest.mock("metabase/dashboard/constants", () => ({
  ...jest.requireActual("metabase/dashboard/constants"),
  DASHBOARD_DESCRIPTION_MAX_LENGTH: 20,
}));

describe("DashboardInfoSidebar", () => {
  it("should render the component", () => {
    setup();

    expect(screen.getByText("Info")).toBeInTheDocument();
    expect(screen.getByTestId("sidesheet")).toBeInTheDocument();
  });

  it("should render overview tab", () => {
    setup();
    expect(screen.getByRole("tab", { name: "Overview" })).toBeInTheDocument();
  });

  it("should render history tab", () => {
    setup();
    expect(screen.getByRole("tab", { name: "History" })).toBeInTheDocument();
  });

  it("should show description when clicking on overview tab", async () => {
    await setup();
    await userEvent.click(screen.getByRole("tab", { name: "History" }));
    await userEvent.click(screen.getByRole("tab", { name: "Overview" }));

    expect(screen.getByText("Description")).toBeInTheDocument();
  });

  it("should show history when clicking on history tab", async () => {
    await setup();
    await userEvent.click(screen.getByRole("tab", { name: "History" }));

    expect(screen.getByTestId("dashboard-history-list")).toBeInTheDocument();
  });

  it("should close when clicking the close button", async () => {
    const { onClose } = await setup();
    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("should allow to set description", async () => {
    const { setDashboardAttribute } = await setup();

    await userEvent.click(screen.getByTestId("editable-text"));
    await userEvent.type(
      screen.getByPlaceholderText("Add description"),
      "some description",
    );
    await userEvent.tab();

    expect(setDashboardAttribute).toHaveBeenCalledWith(
      "description",
      "some description",
    );
  });

  it("should validate description length", async () => {
    const expectedErrorMessage = "Must be 20 characters or less";
    const { setDashboardAttribute } = await setup();

    await userEvent.click(screen.getByTestId("editable-text"));
    await userEvent.type(
      screen.getByPlaceholderText("Add description"),
      "in incididunt incididunt laboris ut elit culpa sit dolor amet",
    );
    await userEvent.tab();

    expect(screen.getByText(expectedErrorMessage)).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("editable-text"));
    expect(screen.queryByText(expectedErrorMessage)).not.toBeInTheDocument();

    await userEvent.tab();
    expect(screen.getByText(expectedErrorMessage)).toBeInTheDocument();

    expect(setDashboardAttribute).not.toHaveBeenCalled();
  });

  it("should allow to clear description", async () => {
    const { setDashboardAttribute } = await setup({
      dashboard: createMockDashboard({ description: "some description" }),
    });

    await userEvent.click(screen.getByTestId("editable-text"));
    await userEvent.clear(screen.getByPlaceholderText("Add description"));
    await userEvent.tab();

    expect(setDashboardAttribute).toHaveBeenCalledWith("description", "");
  });
});
