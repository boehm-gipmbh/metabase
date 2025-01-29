import { H } from "e2e/support";
import { SAMPLE_DB_ID, WRITABLE_DB_ID } from "e2e/support/cypress_data";
import { SAMPLE_DATABASE } from "e2e/support/cypress_sample_database";
import { ORDERS_QUESTION_ID } from "e2e/support/cypress_sample_instance_data";
import { createMockParameter } from "metabase-types/api/mocks";

const { ORDERS, ORDERS_ID, REVIEWS, PRODUCTS, PRODUCTS_ID, REVIEWS_ID } =
  SAMPLE_DATABASE;

describe("issue 18067", () => {
  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
  });

  it(
    "should allow settings click behavior on boolean fields (metabase#18067)",
    { tags: "@external" },
    () => {
      const dialect = "mysql";
      const TEST_TABLE = "many_data_types";
      H.resetTestTable({ type: dialect, table: TEST_TABLE });
      H.restore(`${dialect}-writable`);
      cy.signInAsAdmin();
      H.resyncDatabase({
        dbId: WRITABLE_DB_ID,
        tableName: TEST_TABLE,
        tableAlias: "testTable",
      });

      cy.get("@testTable").then(testTable => {
        const dashboardDetails = {
          name: "18067 dashboard",
        };
        const questionDetails = {
          name: "18067 question",
          database: WRITABLE_DB_ID,
          query: { "source-table": testTable.id },
        };
        H.createQuestionAndDashboard({
          dashboardDetails,
          questionDetails,
        }).then(({ body: { dashboard_id } }) => {
          H.visitDashboard(dashboard_id);
        });
      });

      H.editDashboard();

      cy.log('Select "click behavior" option');
      H.showDashboardCardActions();
      cy.findByTestId("dashboardcard-actions-panel").icon("click").click();

      H.sidebar().within(() => {
        cy.findByText("Boolean").scrollIntoView().click();
        cy.contains("Click behavior for Boolean").should("be.visible");
      });
    },
  );
});

describe("issue 15993", () => {
  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
  });

  it("should show filters defined on a question with filter pass-thru (metabase#15993)", () => {
    H.createQuestion({
      name: "15993",
      query: {
        "source-table": ORDERS_ID,
      },
    }).then(({ body: { id: question1Id } }) => {
      H.createNativeQuestion({ native: { query: "select 0" } }).then(
        ({ body: { id: nativeId } }) => {
          H.createDashboard().then(({ body: { id: dashboardId } }) => {
            // Add native question to the dashboard
            H.addOrUpdateDashboardCard({
              dashboard_id: dashboardId,
              card_id: nativeId,
              card: {
                // Add click behavior to the dashboard card and point it to the question 1
                visualization_settings: getVisualizationSettings(question1Id),
              },
            });
            H.visitDashboard(dashboardId);
          });
        },
      );
    });

    // Drill-through
    cy.findAllByTestId("cell-data").contains("0").realClick();

    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.contains("117.03").should("not.exist"); // Total for the order in which quantity wasn't 0
    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.findByText("Quantity is equal to 0");

    const getVisualizationSettings = targetId => ({
      column_settings: {
        '["name","0"]': {
          click_behavior: {
            targetId,
            parameterMapping: {
              [`["dimension",["field",${ORDERS.QUANTITY},null]]`]: {
                source: {
                  type: "column",
                  id: "0",
                  name: "0",
                },
                target: {
                  type: "dimension",
                  id: [`["dimension",["field",${ORDERS.QUANTITY},null]]`],
                  dimension: ["dimension", ["field", ORDERS.QUANTITY, null]],
                },
                id: [`["dimension",["field",${ORDERS.QUANTITY},null]]`],
              },
            },
            linkType: "question",
            type: "link",
          },
        },
      },
    });
  });
});

describe("issue 16334", () => {
  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
    cy.intercept("POST", "/api/dataset").as("dataset");
    cy.intercept("POST", "/api/dashboard/*/dashcard/*/card/*/query").as(
      "dashcardQuery",
    );
  });

  it("should not change the visualization type in a targetted question with mapped filter (metabase#16334)", () => {
    // Question 2, that we're adding to the dashboard
    const questionDetails = {
      query: {
        "source-table": REVIEWS_ID,
      },
    };

    H.createQuestion({
      name: "16334",
      query: {
        "source-table": PRODUCTS_ID,
        aggregation: [["count"]],
        breakout: [["field", PRODUCTS.CATEGORY, null]],
      },
      display: "pie",
    }).then(({ body: { id: question1Id } }) => {
      H.createQuestionAndDashboard({ questionDetails }).then(
        ({ body: { id, card_id, dashboard_id } }) => {
          H.addOrUpdateDashboardCard({
            dashboard_id,
            card_id,
            card: {
              id,
              visualization_settings: getVisualizationSettings(question1Id),
            },
          });

          H.visitDashboard(dashboard_id);
          cy.wait("@dashcardQuery");
        },
      );
    });

    cy.findAllByTestId("cell-data").contains("5").first().click();
    cy.wait("@dataset");

    // Make sure filter is set
    cy.findByTestId("qb-filters-panel").should(
      "contain.text",
      "Rating is equal to 5",
    );

    // Make sure it's connected to the original question
    cy.findByTestId("app-bar").should("contain.text", "Started from 16334");

    // Make sure the original visualization didn't change
    H.pieSlices().should("have.length", 2);

    const getVisualizationSettings = targetId => ({
      column_settings: {
        [`["ref",["field",${REVIEWS.RATING},null]]`]: {
          click_behavior: {
            targetId,
            parameterMapping: {
              [`["dimension",["field",${PRODUCTS.RATING},null],{"stage-number":0}]`]:
                {
                  source: {
                    type: "column",
                    id: "RATING",
                    name: "Rating",
                  },
                  target: {
                    type: "dimension",
                    id: [
                      `["dimension",["field",${PRODUCTS.RATING},null],{"stage-number":0}]`,
                    ],
                    dimension: [
                      "dimension",
                      ["field", PRODUCTS.RATING, null],
                      { "stage-number": 0 },
                    ],
                  },
                  id: [
                    `["dimension",["field",${PRODUCTS.RATING},null],{"stage-number":0}]`,
                  ],
                },
            },
            linkType: "question",
            type: "link",
          },
        },
      },
    });
  });
});

describe("issue 17160", () => {
  const TARGET_DASHBOARD_NAME = "Target dashboard";
  const CATEGORY_FILTER_PARAMETER_ID = "7c9ege62";

  function assertMultipleValuesFilterState() {
    cy.findByText("2 selections").click();

    cy.findByLabelText("Doohickey").should("be.checked");
    cy.findByLabelText("Gadget").should("be.checked");
  }

  function setup() {
    H.createNativeQuestion({
      name: "17160Q",
      native: {
        query: "SELECT * FROM products WHERE {{CATEGORY}}",
        "template-tags": {
          CATEGORY: {
            id: "6b8b10ef-0104-1047-1e1b-2492d5954322",
            name: "CATEGORY",
            display_name: "CATEGORY",
            type: "dimension",
            dimension: ["field", PRODUCTS.CATEGORY, null],
            "widget-type": "category",
            default: null,
          },
        },
      },
    }).then(({ body: { id: questionId } }) => {
      // Share the question
      cy.request("POST", `/api/card/${questionId}/public_link`);

      H.createDashboard({ name: "17160D" }).then(
        ({ body: { id: dashboardId } }) => {
          // Share the dashboard
          cy.request("POST", `/api/dashboard/${dashboardId}/public_link`).then(
            ({ body: { uuid } }) => {
              cy.wrap(uuid).as("sourceDashboardUUID");
            },
          );
          cy.wrap(dashboardId).as("sourceDashboardId");

          // Add the question to the dashboard
          H.addOrUpdateDashboardCard({
            dashboard_id: dashboardId,
            card_id: questionId,
          }).then(({ body: { id: dashCardId } }) => {
            // Add dashboard filter
            cy.request("PUT", `/api/dashboard/${dashboardId}`, {
              parameters: [
                {
                  default: ["Doohickey", "Gadget"],
                  id: CATEGORY_FILTER_PARAMETER_ID,
                  name: "Category",
                  slug: "category",
                  sectionId: "string",
                  type: "string/=",
                },
              ],
            });

            createTargetDashboard().then(targetDashboardId => {
              cy.wrap(targetDashboardId).as("targetDashboardId");

              // Create a click behaviour for the question card
              cy.request("PUT", `/api/dashboard/${dashboardId}`, {
                dashcards: [
                  {
                    id: dashCardId,
                    card_id: questionId,
                    row: 0,
                    col: 0,
                    size_x: 16,
                    size_y: 10,
                    parameter_mappings: [
                      {
                        parameter_id: CATEGORY_FILTER_PARAMETER_ID,
                        card_id: 4,
                        target: ["dimension", ["template-tag", "CATEGORY"]],
                      },
                    ],
                    visualization_settings: getVisualSettingsWithClickBehavior(
                      questionId,
                      targetDashboardId,
                    ),
                  },
                ],
              });
            });
          });
        },
      );
    });
  }

  function getVisualSettingsWithClickBehavior(questionTarget, dashboardTarget) {
    return {
      column_settings: {
        '["name","ID"]': {
          click_behavior: {
            targetId: questionTarget,
            parameterMapping: {
              "6b8b10ef-0104-1047-1e1b-2492d5954322": {
                source: {
                  type: "parameter",
                  id: CATEGORY_FILTER_PARAMETER_ID,
                  name: "Category",
                },
                target: {
                  type: "variable",
                  id: "CATEGORY",
                },
                id: "6b8b10ef-0104-1047-1e1b-2492d5954322",
              },
            },
            linkType: "question",
            type: "link",
            linkTextTemplate: "click-behavior-question-label",
          },
        },

        '["name","EAN"]': {
          click_behavior: {
            targetId: dashboardTarget,
            parameterMapping: {
              dd19ec03: {
                source: {
                  type: "parameter",
                  id: CATEGORY_FILTER_PARAMETER_ID,
                  name: "Category",
                },
                target: {
                  type: "parameter",
                  id: "dd19ec03",
                },
                id: "dd19ec03",
              },
            },
            linkType: "dashboard",
            type: "link",
            linkTextTemplate: "click-behavior-dashboard-label",
          },
        },
      },
    };
  }

  function createTargetDashboard() {
    return H.createQuestionAndDashboard({
      dashboardDetails: {
        name: TARGET_DASHBOARD_NAME,
      },
      questionDetails: {
        query: {
          "source-table": PRODUCTS_ID,
        },
      },
    }).then(({ body: { id, card_id, dashboard_id } }) => {
      // Share the dashboard
      cy.request("POST", `/api/dashboard/${dashboard_id}/public_link`);

      // Add a filter
      cy.request("PUT", `/api/dashboard/${dashboard_id}`, {
        parameters: [
          {
            name: "Category",
            slug: "category",
            id: "dd19ec03",
            type: "string/=",
            sectionId: "string",
          },
        ],
      });

      // Resize the question card and connect the filter to it
      return cy
        .request("PUT", `/api/dashboard/${dashboard_id}`, {
          dashcards: [
            {
              id,
              card_id,
              row: 0,
              col: 0,
              size_x: 16,
              size_y: 10,
              parameter_mappings: [
                {
                  parameter_id: "dd19ec03",
                  card_id,
                  target: ["dimension", ["field", PRODUCTS.CATEGORY, null]],
                },
              ],
            },
          ],
        })
        .then(() => {
          return dashboard_id;
        });
    });
  }

  function visitSourceDashboard() {
    cy.get("@sourceDashboardId").then(id => {
      H.visitDashboard(id);
    });
  }

  function visitPublicSourceDashboard() {
    cy.get("@sourceDashboardUUID").then(uuid => {
      cy.visit(`/public/dashboard/${uuid}`);

      cy.findByTextEnsureVisible("Enormous Wool Car");
    });
  }

  beforeEach(() => {
    cy.intercept("POST", "/api/card/*/query").as("cardQuery");

    H.restore();
    cy.signInAsAdmin();
  });

  it("should pass multiple filter values to questions and dashboards (metabase#17160-1)", () => {
    setup();

    // 1. Check click behavior connected to a question
    visitSourceDashboard();

    cy.findAllByText("click-behavior-question-label").eq(0).click();
    cy.wait("@cardQuery");

    cy.url().should("include", "/question");

    assertMultipleValuesFilterState();

    // 2. Check click behavior connected to a dashboard
    visitSourceDashboard();

    cy.get("@targetDashboardId").then(id => {
      cy.intercept("POST", `/api/dashboard/${id}/dashcard/*/card/*/query`).as(
        "targetDashcardQuery",
      );

      cy.findAllByText("click-behavior-dashboard-label").eq(0).click();
      cy.wait("@targetDashcardQuery");
    });

    cy.url().should("include", "/dashboard");
    cy.location("search").should("eq", "?category=Doohickey&category=Gadget");
    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.findByText(TARGET_DASHBOARD_NAME);

    assertMultipleValuesFilterState();
  });

  it.skip("should pass multiple filter values to public questions and dashboards (metabase#17160-2)", () => {
    // FIXME: setup public dashboards
    setup();

    // 1. Check click behavior connected to a public question
    visitPublicSourceDashboard();

    cy.findAllByText("click-behavior-question-label").eq(0).click();

    cy.url().should("include", "/public/question");

    assertMultipleValuesFilterState();

    // 2. Check click behavior connected to a publicdashboard
    visitPublicSourceDashboard();

    cy.findAllByText("click-behavior-dashboard-label").eq(0).click();

    cy.url().should("include", "/public/dashboard");
    cy.location("search").should("eq", "?category=Doohickey&category=Gadget");

    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.findByText(TARGET_DASHBOARD_NAME);

    assertMultipleValuesFilterState();
  });
});

describe("issue 18454", () => {
  const CARD_DESCRIPTION = "CARD_DESCRIPTION";

  const questionDetails = {
    name: "18454 Question",
    description: CARD_DESCRIPTION,
    query: {
      "source-table": PRODUCTS_ID,
      aggregation: [["count"]],
      breakout: [["field", PRODUCTS.CATEGORY, null]],
    },
    display: "line",
  };

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();

    H.createQuestionAndDashboard({ questionDetails }).then(
      ({ body: { id, card_id, dashboard_id } }) => {
        H.visitDashboard(dashboard_id);
      },
    );
  });

  it("should show card descriptions (metabase#18454)", () => {
    cy.findByTestId("dashcard-container").realHover();
    cy.findByTestId("dashcard-container").within(() => {
      cy.icon("info").trigger("mouseenter", { force: true });
    });
    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.findByText(CARD_DESCRIPTION);
  });
});

describe("adding an additional series to a dashcard (metabase#20637)", () => {
  function createQuestionsAndDashboard() {
    const dashcardQuestion = {
      name: "20637 Question 1",
      query: {
        "source-table": PRODUCTS_ID,
        aggregation: [["count"]],
        breakout: [["field", PRODUCTS.CATEGORY, null]],
      },
      visualization_settings: {
        "graph.dimensions": ["CATEGORY"],
        "graph.metrics": ["count"],
      },
      display: "line",
    };

    const additionalSeriesQuestion = {
      name: "20637 Question 2",
      query: {
        "source-table": PRODUCTS_ID,
        aggregation: [["count"]],
        breakout: [["field", PRODUCTS.CATEGORY, null]],
      },
      visualization_settings: {
        "graph.dimensions": ["CATEGORY"],
        "graph.metrics": ["count"],
      },
      display: "bar",
    };

    H.createQuestion(additionalSeriesQuestion).then(
      ({ body: { id: additionalSeriesId } }) => {
        cy.intercept("POST", `/api/card/${additionalSeriesId}/query`).as(
          "additionalSeriesCardQuery",
        );

        H.createQuestionAndDashboard({
          questionDetails: dashcardQuestion,
        }).then(({ body: { id, card_id, dashboard_id } }) => {
          cy.request("PUT", `/api/dashboard/${dashboard_id}`, {
            dashcards: [
              {
                id,
                card_id,
                row: 0,
                col: 0,
                size_x: 16,
                size_y: 10,
              },
            ],
          });

          cy.visit(`/dashboard/${dashboard_id}`);

          cy.intercept(
            "POST",
            `/api/dashboard/${dashboard_id}/dashcard/*/card/${card_id}/query`,
          ).as("dashcardQuery");

          cy.intercept(
            "POST",
            `/api/dashboard/${dashboard_id}/dashcard/*/card/${additionalSeriesId}/query`,
          ).as("additionalSeriesDashcardQuery");
        });
      },
    );
  }

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
  });

  it("should use the correct query endpoints (metabase#20637)", () => {
    createQuestionsAndDashboard();
    cy.wait("@dashcardQuery");

    // edit the dashboard and open the add series modal
    cy.icon("pencil").click();
    // the button is made clickable by css using :hover so we need to force it
    cy.findByTestId("add-series-button").click({ force: true });

    // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
    cy.findByText("20637 Question 2").click();
    // make sure the card query endpoint was used
    cy.wait("@additionalSeriesCardQuery");

    cy.findByTestId("add-series-modal").button("Done").click();
    H.saveDashboard();

    // refresh the page and make sure the dashcard query endpoint was used
    cy.reload();
    cy.wait(["@dashcardQuery", "@additionalSeriesDashcardQuery"]);
  });
});

describe("issue 22265", () => {
  const baseQuestion = {
    name: "Base question",
    display: "scalar",
    native: {
      query: "SELECT 1",
    },
  };

  const invalidQuestion = {
    name: "Invalid question",
    display: "scalar",
    native: {
      query: "SELECT 1",
    },
  };

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();

    cy.intercept("GET", "/api/card/*/series?limit=*").as("seriesQuery");
  });

  it("should allow editing dashcard series when added series are broken (metabase#22265)", () => {
    H.createNativeQuestion(invalidQuestion, {
      wrapId: true,
      idAlias: "invalidQuestionId",
    });
    H.createNativeQuestionAndDashboard({ questionDetails: baseQuestion }).then(
      ({ body: { id, card_id, dashboard_id } }) => {
        cy.request("PUT", `/api/dashboard/${dashboard_id}`, {
          dashcards: [
            {
              id,
              card_id,
              row: 0,
              col: 0,
              size_x: 16,
              size_y: 10,
            },
          ],
        });

        cy.wrap(dashboard_id).as("dashboardId");
        H.visitDashboard(dashboard_id);
      },
    );

    H.editDashboard();
    cy.findByTestId("add-series-button").click({ force: true });
    cy.wait("@seriesQuery");

    cy.findByTestId("add-series-modal").within(() => {
      cy.icon("warning").should("not.exist");
      cy.findByLabelText(invalidQuestion.name).should("exist").click();
      cy.button("Done").click();
    });

    cy.button("Save").click();
    cy.button("Saving…").should("not.exist");

    cy.log("Update the added series' question so that it's broken");
    const questionDetailUpdate = {
      dataset_query: {
        type: "native",
        native: {
          query: "SELECT --2",
          "template-tags": {},
        },
        database: 1,
      },
    };
    cy.get("@invalidQuestionId").then(invalidQuestionId => {
      cy.request("PUT", `/api/card/${invalidQuestionId}`, questionDetailUpdate);
    });

    H.visitDashboard("@dashboardId");
    H.editDashboard();
    cy.findByTestId("add-series-button").click({ force: true });
    cy.wait("@seriesQuery");

    cy.findByTestId("add-series-modal").within(() => {
      cy.findByLabelText(invalidQuestion.name).should("exist");
      cy.icon("warning").should("not.exist");
    });
  });
});

describe("issue 23137", () => {
  const GAUGE_QUESTION_DETAILS = {
    display: "gauge",
    query: {
      "source-table": REVIEWS_ID,
      aggregation: [["count"]],
    },
  };

  const PROGRESS_QUESTION_DETAILS = {
    display: "progress",
    query: {
      "source-table": REVIEWS_ID,
      aggregation: [["count"]],
    },
  };

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
    cy.intercept("POST", "/api/card/*/query").as("cardQuery");
  });

  it("should navigate to a target from a gauge card (metabase#23137)", () => {
    const target_id = ORDERS_QUESTION_ID;

    H.createQuestionAndDashboard({
      questionDetails: GAUGE_QUESTION_DETAILS,
    }).then(({ body: { id, card_id, dashboard_id } }) => {
      H.addOrUpdateDashboardCard({
        card_id,
        dashboard_id,
        card: {
          id,
          visualization_settings: {
            click_behavior: {
              type: "link",
              linkType: "question",
              targetId: target_id,
              parameterMapping: {},
            },
          },
        },
      });

      H.visitDashboard(dashboard_id);
    });

    cy.findByTestId("gauge-arc-1").click();
    cy.wait("@cardQuery");
    H.queryBuilderHeader().findByDisplayValue("Orders").should("be.visible");
  });

  it("should navigate to a target from a progress card (metabase#23137)", () => {
    const target_id = ORDERS_QUESTION_ID;

    H.createQuestionAndDashboard({
      questionDetails: PROGRESS_QUESTION_DETAILS,
    }).then(({ body: { id, card_id, dashboard_id } }) => {
      H.addOrUpdateDashboardCard({
        card_id,
        dashboard_id,
        card: {
          id,
          visualization_settings: {
            click_behavior: {
              type: "link",
              linkType: "question",
              targetId: target_id,
              parameterMapping: {},
            },
          },
        },
      });

      H.visitDashboard(dashboard_id);
    });

    cy.findByTestId("progress-bar").click();
    cy.wait("@cardQuery");
    H.queryBuilderHeader().findByDisplayValue("Orders").should("be.visible");
  });
});

describe("issues 27020 and 27105: static-viz fails to render for certain date formatting options", () => {
  const questionDetails27105 = {
    name: "27105",
    native: { query: "select current_date::date, 1", "template-tags": {} },
    display: "table",
    visualization_settings: {
      column_settings: {
        '["name","CAST(CURRENT_DATE AS DATE)"]': {
          date_style: "dddd, MMMM D, YYYY",
        },
      },
      "table.pivot_column": "CAST(CURRENT_DATE AS DATE)",
      "table.cell_column": "1",
    },
  };

  const questionDetails27020 = {
    name: "27020",
    native: {
      query: 'select current_date as "created_at", 1 "val"',
      "template-tags": {},
    },
    visualization_settings: {
      column_settings: { '["name","created_at"]': { date_abbreviate: true } },
      "table.pivot_column": "created_at",
      "table.cell_column": "val",
    },
  };

  function assertStaticVizRenders(questionDetails) {
    H.createNativeQuestion(questionDetails).then(({ body: { id } }) => {
      cy.request({
        method: "GET",
        url: `/api/pulse/preview_card_png/${id}`,
        failOnStatusCode: false,
      }).then(({ status, body }) => {
        expect(status).to.eq(200);
        expect(body).to.contain("PNG");
      });
    });
  }

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
  });

  it("should render static-viz when date formatting is abbreviated (metabase#27020)", () => {
    // This is currently the default setting, anyway.
    // But we want to explicitly set it in case something changes in the future,
    // because it is a crucial step for this reproduction.
    H.updateSetting("custom-formatting", {
      "type/Temporal": {
        date_style: "MMMM D, YYYY",
      },
    });

    assertStaticVizRenders(questionDetails27020);
  });

  it("should render static-viz when date formatting contains day (metabase#27105)", () => {
    assertStaticVizRenders(questionDetails27105);
  });
});

describe("issue 29304", () => {
  // Couldn't import from `metabase/components/ExplicitSize` because dependency issue.
  // It will fail Cypress tests.
  const WAIT_TIME = 300;

  const { ORDERS, ORDERS_ID } = SAMPLE_DATABASE;

  const SCALAR_QUESTION = {
    name: "Scalar question",
    query: {
      "source-table": ORDERS_ID,
      aggregation: [["count"]],
    },
    display: "scalar",
  };

  const SCALAR_QUESTION_CARD = { size_x: 4, size_y: 3, row: 0, col: 0 };

  const SMART_SCALAR_QUESTION = {
    name: "Smart scalar question",
    query: {
      "source-table": ORDERS_ID,
      aggregation: [["count"]],
      breakout: [
        [
          "field",
          ORDERS.CREATED_AT,
          {
            "base-type": "type/DateTime",
            "temporal-unit": "month",
          },
        ],
      ],
    },
    display: "smartscalar",
  };

  const SMART_SCALAR_QUESTION_CARD = SCALAR_QUESTION_CARD;

  // Use full-app embedding to test because `ExplicitSize` checks for `isCypressActive`,
  // which checks `window.Cypress`, and will disable the refresh mode on Cypress test.
  // If we test by simply visiting the dashboard, the refresh mode will be disabled,
  // and we won't be able to reproduce the problem.
  const visitFullAppEmbeddingUrl = ({ url }) => {
    cy.visit({
      url,
      onBeforeLoad(window) {
        // cypress runs all tests in an iframe and the app uses this property to avoid embedding mode for all tests
        // by removing the property the app would work in embedding mode
        window.Cypress = undefined;
      },
    });
  };

  describe("display: scalar", () => {
    beforeEach(() => {
      H.restore();
      cy.signInAsAdmin();
      cy.intercept("api/dashboard/*/dashcard/*/card/*/query").as(
        "getDashcardQuery",
      );
      cy.intercept("api/dashboard/*").as("getDashboard");
      cy.clock();
    });

    it("should render scalar with correct size on the first render (metabase#29304)", () => {
      H.createDashboard().then(({ body: dashboard }) => {
        H.createQuestionAndAddToDashboard(
          SCALAR_QUESTION,
          dashboard.id,
          SCALAR_QUESTION_CARD,
        );

        visitFullAppEmbeddingUrl({ url: `/dashboard/${dashboard.id}` });

        cy.wait("@getDashboard");
        cy.wait("@getDashcardQuery");
        // This extra 1ms is crucial, without this the test would fail.
        cy.tick(WAIT_TIME + 1);

        const expectedWidth = 130;
        cy.findByTestId("scalar-value").should(([$scalarValue]) => {
          expect($scalarValue.offsetWidth).to.be.closeTo(
            expectedWidth,
            expectedWidth * 0.1,
          );
        });
      });
    });

    it("should render smart scalar with correct size on the first render (metabase#29304)", () => {
      H.createDashboard().then(({ body: dashboard }) => {
        H.createQuestionAndAddToDashboard(
          SMART_SCALAR_QUESTION,
          dashboard.id,
          SMART_SCALAR_QUESTION_CARD,
        );

        visitFullAppEmbeddingUrl({ url: `/dashboard/${dashboard.id}` });

        cy.wait("@getDashboard");
        cy.wait("@getDashcardQuery");
        // This extra 1ms is crucial, without this the test would fail.
        cy.tick(WAIT_TIME + 1);

        const expectedWidth = 39;
        cy.findByTestId("scalar-value").should(([$scalarValue]) => {
          expect($scalarValue.offsetWidth).to.be.closeTo(
            expectedWidth,
            expectedWidth * 0.1,
          );
        });
      });
    });
  });
});

/**
 * This test suite reduces the number of "it" calls for performance reasons.
 * Every block with JSDoc within "it" callbacks should ideally be a separate "it" call.
 * @see https://github.com/metabase/metabase/pull/31722#discussion_r1246165418
 */
describe("issue 31628", () => {
  const createCardsRow = ({ size_y }) => [
    { size_x: 6, size_y, row: 0, col: 0 },
    { size_x: 5, size_y, row: 0, col: 6 },
    { size_x: 4, size_y, row: 0, col: 11 },
    { size_x: 3, size_y, row: 0, col: 15 },
    { size_x: 2, size_y, row: 0, col: 18 },
  ];

  const CARDS_SIZE_1X = {
    cards: [
      ...createCardsRow({ size_y: 1 }),
      { size_x: 1, size_y: 1, row: 0, col: 20 },
      { size_x: 1, size_y: 2, row: 1, col: 20 },
      { size_x: 1, size_y: 4, row: 3, col: 20 },
      { size_x: 1, size_y: 3, row: 7, col: 20 },
    ],
    name: "cards 1 cell high or wide",
  };

  const VIEWPORTS = [
    { width: 375, height: 667, openSidebar: false },
    { width: 820, height: 800, openSidebar: true },
    { width: 820, height: 800, openSidebar: false },
    { width: 1200, height: 800, openSidebar: true },
    { width: 1440, height: 800, openSidebar: true },
    { width: 1440, height: 800, openSidebar: false },
  ];

  const SCALAR_QUESTION = {
    name: "31628 Question - This is a rather lengthy question name",
    description: "This is a rather lengthy question description",
    query: {
      "source-table": ORDERS_ID,
      aggregation: [["count"]],
    },
    display: "scalar",
  };

  const SCALAR_QUESTION_CARDS = [
    { cards: createCardsRow({ size_y: 2 }), name: "cards 2 cells high" },
    { cards: createCardsRow({ size_y: 3 }), name: "cards 3 cells high" },
    { cards: createCardsRow({ size_y: 4 }), name: "cards 4 cells high" },
    CARDS_SIZE_1X,
  ];

  const SMART_SCALAR_QUESTION = {
    name: "31628 Question - This is a rather lengthy question name",
    description: "This is a rather lengthy question description",
    query: {
      "source-table": ORDERS_ID,
      aggregation: [["count"]],
      breakout: [
        [
          "field",
          ORDERS.CREATED_AT,
          {
            "base-type": "type/DateTime",
            "temporal-unit": "month",
          },
        ],
      ],
    },
    display: "smartscalar",
  };

  const SMART_SCALAR_QUESTION_CARDS = [
    { cards: createCardsRow({ size_y: 2 }), name: "cards 2 cells high" },
    { cards: createCardsRow({ size_y: 3 }), name: "cards 3 cells high" },
    { cards: createCardsRow({ size_y: 4 }), name: "cards 4 cells high" },
  ];

  const setupDashboardWithQuestionInCards = (question, cards) => {
    H.createDashboard().then(({ body: dashboard }) => {
      H.cypressWaitAll(
        cards.map(card => {
          return H.createQuestionAndAddToDashboard(
            question,
            dashboard.id,
            card,
          );
        }),
      );

      H.visitDashboard(dashboard.id);
    });
  };

  const assertDescendantsNotOverflowDashcards = descendantsSelector => {
    cy.findAllByTestId("dashcard").should(dashcards => {
      dashcards.each((dashcardIndex, dashcard) => {
        const descendants = dashcard.querySelectorAll(descendantsSelector);

        descendants.forEach(descendant => {
          H.assertDescendantNotOverflowsContainer(
            descendant,
            dashcard,
            `dashcard[${dashcardIndex}] [data-testid="${descendant.dataset.testid}"]`,
          );
        });
      });
    });
  };

  describe("display: scalar", () => {
    const descendantsSelector = [
      "[data-testid='scalar-container']",
      "[data-testid='scalar-title']",
      "[data-testid='scalar-description']",
    ].join(",");

    VIEWPORTS.forEach(({ width, height, openSidebar }) => {
      SCALAR_QUESTION_CARDS.forEach(({ cards, name }) => {
        const sidebar = openSidebar ? "sidebar open" : "sidebar closed";

        describe(`${width}x${height} - ${sidebar} - ${name}`, () => {
          beforeEach(() => {
            H.restore();
            cy.viewport(width, height);
            cy.signInAsAdmin();
            setupDashboardWithQuestionInCards(SCALAR_QUESTION, cards);

            if (openSidebar) {
              cy.wait(100);
              H.openNavigationSidebar();
            }
          });

          it("should render descendants of a 'scalar' without overflowing it (metabase#31628)", () => {
            assertDescendantsNotOverflowDashcards(descendantsSelector);
          });
        });
      });
    });

    describe("1x2 card", () => {
      beforeEach(() => {
        H.restore();
        cy.signInAsAdmin();
        setupDashboardWithQuestionInCards(SCALAR_QUESTION, [
          { size_x: 1, size_y: 2, row: 0, col: 0 },
        ]);
      });

      it("should follow truncation rules", () => {
        /**
         * should truncate value and show value tooltip on hover
         */
        const scalarContainer = cy.findByTestId("scalar-container");

        scalarContainer.then($element => H.assertIsEllipsified($element[0]));
        scalarContainer.realHover();

        cy.findByRole("tooltip").findByText("18,760").should("exist");

        /**
         * should show ellipsis icon with question name in tooltip
         */
        cy.findByTestId("scalar-title-icon").realHover();

        cy.findByRole("tooltip")
          .findByText(SCALAR_QUESTION.name)
          .should("exist");

        /**
         * should not show description
         */
        cy.findByTestId("scalar-description").should("not.exist");
      });
    });

    describe("2x2 card", () => {
      beforeEach(() => {
        H.restore();
        cy.signInAsAdmin();
        setupDashboardWithQuestionInCards(SCALAR_QUESTION, [
          { size_x: 2, size_y: 2, row: 0, col: 0 },
        ]);
      });

      it("should follow truncation rules", () => {
        /**
         * should not truncate value and should not show value tooltip on hover
         */
        const scalarContainer = cy.findByTestId("scalar-container");

        scalarContainer.then($element => H.assertIsNotEllipsified($element[0]));
        scalarContainer.realHover();

        cy.findByRole("tooltip").should("not.exist");

        /**
         * should not show ellipsis icon for title
         */
        cy.findByTestId("scalar-title-icon").should("not.exist");

        /**
         * should truncate title and show title tooltip on hover
         */
        const scalarTitle = cy.findByTestId("scalar-title");

        scalarTitle.then($element => H.assertIsEllipsified($element[0]));
        scalarTitle.realHover();

        cy.findByRole("tooltip")
          .findByText(SCALAR_QUESTION.name)
          .should("exist");

        /**
         * should show description tooltip on hover
         */
        cy.findByTestId("scalar-description").realHover();

        cy.findByRole("tooltip")
          .findByText(SCALAR_QUESTION.description)
          .should("exist");
      });
    });

    describe("5x3 card", () => {
      beforeEach(() => {
        H.restore();
        cy.signInAsAdmin();
        setupDashboardWithQuestionInCards(SCALAR_QUESTION, [
          { size_x: 6, size_y: 3, row: 0, col: 0 },
        ]);
      });

      it("should follow truncation rules", () => {
        /**
         * should not truncate value and should not show value tooltip on hover
         */
        const scalarContainer = cy.findByTestId("scalar-container");

        scalarContainer.then($element => H.assertIsNotEllipsified($element[0]));
        scalarContainer.realHover();

        cy.findByRole("tooltip").should("not.exist");

        /**
         * should not show ellipsis icon for title
         */
        cy.findByTestId("scalar-title-icon").should("not.exist");

        /**
         * should not truncate title and should not show title tooltip on hover
         */
        const scalarTitle = cy.findByTestId("scalar-title");

        scalarTitle.then($element => H.assertIsNotEllipsified($element[0]));
        scalarTitle.realHover();

        cy.findByRole("tooltip").should("not.exist");

        /**
         * should show description tooltip on hover
         */
        cy.findByTestId("scalar-description").realHover();

        H.popover().findByText(SCALAR_QUESTION.description).should("exist");
      });
    });
  });

  describe("display: smartscalar", () => {
    const descendantsSelector = [
      "[data-testid='legend-caption']",
      "[data-testid='scalar-container']",
      "[data-testid='scalar-previous-value']",
    ].join(",");

    VIEWPORTS.forEach(({ width, height, openSidebar }) => {
      SMART_SCALAR_QUESTION_CARDS.forEach(({ cards, name }) => {
        const sidebar = openSidebar ? "sidebar open" : "sidebar closed";

        describe(`${width}x${height} - ${sidebar} - ${name}`, () => {
          beforeEach(() => {
            H.restore();
            cy.viewport(width, height);
            cy.signInAsAdmin();
            setupDashboardWithQuestionInCards(SMART_SCALAR_QUESTION, cards);

            if (openSidebar) {
              H.openNavigationSidebar();
            }
          });

          it("should render descendants of a 'smartscalar' without overflowing it (metabase#31628)", () => {
            assertDescendantsNotOverflowDashcards(descendantsSelector);
          });
        });
      });
    });

    describe("2x2 card", () => {
      beforeEach(() => {
        H.restore();
        cy.signInAsAdmin();
        setupDashboardWithQuestionInCards(SMART_SCALAR_QUESTION, [
          { size_x: 2, size_y: 2, row: 0, col: 0 },
        ]);
      });

      it("should follow truncation rules", () => {
        /**
         * it should not truncate value and should not show value tooltip on hover
         */
        const scalarContainer = cy.findByTestId("scalar-container");

        scalarContainer.then($element => H.assertIsNotEllipsified($element[0]));
        scalarContainer.realHover();

        cy.findByRole("tooltip").should("not.exist");

        /**
         * it should not display period because the card height is too small to fit it
         */
        cy.findByTestId("scalar-period").should("not.exist");

        /**
         * it should truncate title and show title tooltip on hover
         */
        const scalarTitle = cy.findByTestId("legend-caption-title");

        scalarTitle.then($element => H.assertIsEllipsified($element[0]));
        scalarTitle.realHover();

        cy.findByRole("tooltip")
          .findByText(SMART_SCALAR_QUESTION.name)
          .should("exist");

        /**
         * it should show previous value tooltip on hover
         */
        cy.findByTestId("scalar-previous-value").realHover();

        cy.findByRole("tooltip").within(() => {
          cy.contains("34.72%").should("exist");
          cy.contains("• vs. previous month: 527").should("exist");
        });

        /**
         * it should show previous value as a percentage only (without truncation)
         */
        const previousValue = cy.findByTestId("scalar-previous-value");

        previousValue.within(() => {
          cy.contains("34.7%").should("exist");
          cy.contains("• vs. previous month: 527").should("not.exist");
          previousValue.then($element => H.assertIsNotEllipsified($element[0]));
        });
      });

      it("should show previous value as a percentage only up to 1 decimal place (without truncation, 1200x600)", () => {
        cy.viewport(1200, 600);

        const previousValue = cy.findByTestId("scalar-previous-value");

        previousValue.within(() => {
          cy.contains("34.7%").should("exist");
          cy.contains("34.72%").should("not.exist");
          cy.contains("• vs. previous month: 527").should("not.exist");
          previousValue.then($element => H.assertIsNotEllipsified($element[0]));
        });
      });

      it("should show previous value as a percentage without decimal places (without truncation, 1000x600)", () => {
        cy.viewport(1000, 600);

        const previousValue = cy.findByTestId("scalar-previous-value");

        previousValue.within(() => {
          cy.contains("35%").should("exist");
          cy.contains("34.72%").should("not.exist");
          cy.contains("• vs. previous month: 527").should("not.exist");
          previousValue.then($element => H.assertIsNotEllipsified($element[0]));
        });
      });

      it("should truncate previous value (840x600)", () => {
        cy.viewport(840, 600);

        const previousValue = cy.findByTestId("scalar-previous-value");

        previousValue
          .findByText("35%")
          .then($element => H.assertIsEllipsified($element[0]));
      });
    });

    describe("7x3 card", () => {
      beforeEach(() => {
        H.restore();
        cy.signInAsAdmin();
        setupDashboardWithQuestionInCards(SMART_SCALAR_QUESTION, [
          { size_x: 7, size_y: 3, row: 0, col: 0 },
        ]);
      });

      it("should follow truncation rules", () => {
        /**
         * should not truncate value and should not show value tooltip on hover
         */
        let scalarContainer = cy.findByTestId("scalar-container");

        scalarContainer.then($element => H.assertIsNotEllipsified($element[0]));
        scalarContainer.realHover();

        cy.findByRole("tooltip").should("not.exist");

        /**
         * it should display the period
         */
        cy.findByTestId("scalar-period").should("have.text", "Apr 2026");

        /**
         * should truncate title and show title tooltip on hover
         */
        scalarContainer = cy.findByTestId("legend-caption-title");

        scalarContainer.then($element => H.assertIsEllipsified($element[0]));
        scalarContainer.realHover();

        cy.findByRole("tooltip")
          .findByText(SMART_SCALAR_QUESTION.name)
          .should("exist");

        /**
         * should show description tooltip on hover
         */
        cy.findByTestId("legend-caption").icon("info").realHover();

        cy.findByRole("tooltip")
          .findByText(SMART_SCALAR_QUESTION.description)
          .should("exist");

        /**
         * should show previous value in full
         */
        const previousValue = cy.findByTestId("scalar-previous-value");

        previousValue.within(() => {
          cy.contains("34.72%").should("exist");
          cy.contains("• vs. previous month: 527").should("exist");
          previousValue.then($element => H.assertIsNotEllipsified($element[0]));
        });

        /**
         * should not show previous value tooltip on hover
         */
        cy.findByTestId("scalar-previous-value").realHover();

        cy.findByRole("tooltip").should("not.exist");
      });
    });

    describe("7x4 card", () => {
      beforeEach(() => {
        H.restore();
        cy.signInAsAdmin();
        setupDashboardWithQuestionInCards(SMART_SCALAR_QUESTION, [
          { size_x: 7, size_y: 4, row: 0, col: 0 },
        ]);
      });

      it("should follow truncation rules", () => {
        /**
         * should not truncate value and should not show value tooltip on hover
         */
        let scalarContainer = cy.findByTestId("scalar-container");

        scalarContainer.then($element => H.assertIsNotEllipsified($element[0]));
        scalarContainer.realHover();

        cy.findByRole("tooltip").should("not.exist");

        /**
         * it should display the period
         */
        cy.findByTestId("scalar-period").should("have.text", "Apr 2026");

        /**
         * should truncate title and show title tooltip on hover
         */
        scalarContainer = cy.findByTestId("legend-caption-title");

        scalarContainer.then($element => H.assertIsEllipsified($element[0]));
        scalarContainer.realHover();

        cy.findByRole("tooltip")
          .findByText(SMART_SCALAR_QUESTION.name)
          .should("exist");

        /**
         * should show description tooltip on hover
         */
        cy.findByTestId("legend-caption").icon("info").realHover();

        cy.findByRole("tooltip")
          .findByText(SMART_SCALAR_QUESTION.description)
          .should("exist");

        /**
         * should show previous value in full
         */
        const previousValue = cy.findByTestId("scalar-previous-value");

        previousValue.within(() => {
          cy.contains("34.72%").should("exist");
          cy.contains("• vs. previous month: 527").should("exist");
          previousValue.then($element => H.assertIsNotEllipsified($element[0]));
        });

        /**
         * should not show previous value tooltip on hover
         */
        cy.findByTestId("scalar-previous-value").realHover();

        cy.findByRole("tooltip").should("not.exist");
      });
    });
  });
});

describe("issue 32231", () => {
  const baseQuestion = {
    name: "Base question",
    query: {
      "source-table": PRODUCTS_ID,
      aggregation: [["count"]],
      breakout: [["field", PRODUCTS.CATEGORY, null]],
    },
    visualization_settings: {
      "graph.dimensions": ["CATEGORY"],
      "graph.metrics": ["count"],
    },
    display: "bar",
  };

  const incompleteQuestion = {
    name: "Incomplete question",
    native: {
      query: "select 1;",
    },
    visualization_settings: {
      "graph.dimensions": [null],
      "graph.metrics": ["1"],
    },
    display: "bar",
  };

  const issue32231Error =
    "Cannot read properties of undefined (reading 'name')";
  const multipleSeriesError = "Unable to combine these questions";
  const defaultError = "Which fields do you want to use for the X and Y axes?";

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();

    cy.intercept("GET", "/api/card/*/series?limit=*").as("seriesQuery");
  });

  it("should show user-friendly error when combining series that cannot be visualized together (metabase#32231)", () => {
    H.createNativeQuestion(incompleteQuestion);
    H.createQuestionAndDashboard({ questionDetails: baseQuestion }).then(
      ({ body: { id, card_id, dashboard_id } }) => {
        cy.request("PUT", `/api/dashboard/${dashboard_id}`, {
          dashcards: [
            {
              id,
              card_id,
              row: 0,
              col: 0,
              size_x: 16,
              size_y: 10,
            },
          ],
        });

        H.visitDashboard(dashboard_id);
      },
    );

    H.editDashboard();
    cy.findByTestId("add-series-button").click({ force: true });
    cy.wait("@seriesQuery");

    cy.findByTestId("add-series-modal").within(() => {
      H.echartsContainer().should("exist");
      cy.findByText(issue32231Error).should("not.exist");
      cy.findByText(multipleSeriesError).should("not.exist");
      cy.findByText(defaultError).should("not.exist");

      cy.findByLabelText(incompleteQuestion.name).click();

      H.echartsContainer().should("not.exist");
      cy.findByText(issue32231Error).should("not.exist");
      cy.findByText(multipleSeriesError).should("exist");
      cy.findByText(defaultError).should("not.exist");

      cy.findByLabelText(incompleteQuestion.name).click();

      H.echartsContainer().should("exist");
      cy.findByText(issue32231Error).should("not.exist");
      cy.findByText(multipleSeriesError).should("not.exist");
      cy.findByText(defaultError).should("not.exist");
    });
  });

  it("should show default visualization error message when the only series is incomplete", () => {
    H.createNativeQuestionAndDashboard({
      questionDetails: incompleteQuestion,
    }).then(({ body: { id, card_id, dashboard_id } }) => {
      cy.request("PUT", `/api/dashboard/${dashboard_id}`, {
        dashcards: [
          {
            id,
            card_id,
            row: 0,
            col: 0,
            size_x: 16,
            size_y: 10,
          },
        ],
      });

      H.visitDashboard(dashboard_id);
    });

    cy.findByTestId("dashcard").findByText(defaultError).should("exist");

    cy.icon("pencil").click();
    cy.findByTestId("add-series-button").click({ force: true });
    cy.wait("@seriesQuery");

    cy.findByTestId("add-series-modal").within(() => {
      cy.get("[data-element-id=line-area-bar-chart]").should("not.exist");
      cy.findByText(issue32231Error).should("not.exist");
      cy.findByText(multipleSeriesError).should("not.exist");
      cy.findByText(defaultError).should("exist");
    });
  });
});

describe("issue 43219", () => {
  const questionDetails = {
    display: "line",
    query: {
      "source-table": ORDERS_ID,
      aggregation: [["count"]],
      breakout: [
        [
          "field",
          ORDERS.CREATED_AT,
          {
            "base-type": "type/DateTime",
            "temporal-unit": "month",
          },
        ],
      ],
    },
  };

  const textFilter = createMockParameter({
    name: "Text",
    slug: "string",
    id: "5aefc726",
    type: "string/=",
    sectionId: "string",
  });

  const cardsCount = 10;

  const getQuestionAlias = index => `question-${index}`;

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();

    H.cypressWaitAll(
      Array.from({ length: cardsCount }, (_value, index) => {
        const name = `Series ${index + 1}`;
        return H.createQuestion({ ...questionDetails, name }).then(
          ({ body: question }) => {
            cy.wrap(question).as(getQuestionAlias(index));
          },
        );
      }),
    );

    cy.then(function () {
      H.createDashboardWithQuestions({
        dashboardDetails: {
          parameters: [textFilter],
        },
        questions: [
          {
            ...questionDetails,
            name: "Base series",
          },
        ],
        cards: [
          {
            size_x: 4,
            size_y: 3,
            series: Array.from(
              { length: cardsCount },
              (_value, index) => this[getQuestionAlias(index)],
            ),
          },
        ],
      }).then(({ dashboard }) => {
        H.visitDashboard(dashboard.id);
      });
    });
  });

  it("is possible to map parameters to dashcards with lots of series (metabase#43219)", () => {
    H.editDashboard();
    cy.findByTestId("edit-dashboard-parameters-widget-container")
      .findByText("Text")
      .click();

    H.getDashboardCard(0).within(() => {
      cy.findByText("Series 10").should("exist").and("not.be.visible");

      cy.findByTestId("visualization-root").scrollTo("bottom");
      cy.findByTestId("parameter-mapper-container").scrollTo("right");

      cy.findByText("Series 10").should("be.visible");
    });
  });
});

describe("issue 48878", () => {
  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
    H.setActionsEnabledForDB(SAMPLE_DB_ID);

    cy.signInAsNormalUser();
    cy.intercept("POST", "/api/dataset").as("dataset");
    cy.intercept("POST", "/api/card").as("saveQuestion");
    cy.intercept("POST", "/api/action").as("createAction");
    cy.intercept("GET", "/api/dashboard/*").as("getDashboard");
    cy.intercept("PUT", "/api/dashboard/*").as("updateDashboard");

    let fetchCardRequestsCount = 0;

    cy.intercept("GET", "/api/card/*", request => {
      // we only want to simulate the race condition 4th time this request is triggered
      if (fetchCardRequestsCount === 2) {
        request.continue(
          () => new Promise(resolve => setTimeout(resolve, 2000)),
        );
      } else {
        request.continue();
      }

      ++fetchCardRequestsCount;
    }).as("fetchCard");
    setup();
  });

  // I could only reproduce this issue in Cypress when I didn't use any helpers like createQuestion, etc.
  it("does not crash the action button viz (metabase#48878)", () => {
    cy.reload();
    cy.wait("@fetchCard");
    H.getDashboardCard(0).findByText("Click Me").should("be.visible");
  });

  function setup() {
    cy.log("create dummy model");

    // Create a dummy model so that GET /api/search does not return the model want to test.
    // If we don't do this, GET /api/search will return and put card object with dataset_query
    // attribute in the redux store (entity framework) which would prevent the issue from happening.
    cy.visit("/model/new");
    createModel({
      name: "Dummy model",
      query: "select 1",
    });

    cy.log("create model");

    cy.button("New").click();
    H.popover().findByText("Model").click();
    createModel({
      name: "SQL Model",
      query: "select * from orders limit 5",
    });

    cy.log("create model action");

    cy.findByTestId("qb-header-info-button").click();
    H.sidesheet().findByText("Actions").click();

    cy.findByTestId("model-actions-header").findByText("New action").click();

    H.modal().within(() => {
      H.NativeEditor.focus().type("UPDATE orders SET plan = {{ plan ", {
        parseSpecialCharSequences: false,
      });
      cy.button("Save").click();
    });

    // eslint-disable-next-line no-unsafe-element-filtering
    H.modal()
      .last()
      .within(() => {
        cy.findByPlaceholderText("My new fantastic action").type("Test action");
        cy.button("Create").click();
        cy.wait("@createAction");
      });

    cy.visit("/");

    cy.log("create dashoard");

    cy.button("New").click();
    H.popover().findByText("Dashboard").click();

    H.modal().within(() => {
      cy.findByPlaceholderText("What is the name of your dashboard?").type(
        "Dash",
      );
      cy.button("Create").click();
      cy.wait("@getDashboard");
    });

    cy.button("Add action").click();
    cy.button("Pick an action").click();
    H.modal().within(() => {
      cy.findByText("SQL Model").click();
      cy.findByText("Test action").click();
      cy.button("Done").click();
    });
    cy.button("Save").click();
    cy.wait("@updateDashboard");
    cy.wait("@fetchCard");
  }

  function createModel({ name, query }) {
    cy.findByTestId("new-model-options")
      .findByText("Use a native query")
      .click();

    H.NativeEditor.focus().type(query);
    cy.findByTestId("native-query-editor-sidebar")
      .findByTestId("run-button")
      .click();
    cy.wait("@dataset");
    cy.button("Save").click();

    H.modal().within(() => {
      cy.findByPlaceholderText("What is the name of your model?").type(name);
      cy.button("Save").click();
      cy.wait("@saveQuestion");
    });
    cy.wait("@fetchCard");
  }
});

describe("issue 46318", () => {
  const query = `SELECT 'group_1' AS main_group, 'sub_group_1' AS sub_group, 111 AS value_sum, 'group_1__sub_group_1' AS group_name
UNION ALL
SELECT 'group_1', 'sub_group_2', 68, 'group_1__sub_group_2'
UNION ALL
SELECT 'group_2', 'sub_group_1', 79, 'group_2__sub_group_1'
UNION ALL
SELECT 'group_2', 'sub_group_2', 52, 'group_2__sub_group_2';
`;

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();

    H.createNativeQuestionAndDashboard({
      questionDetails: {
        name: "46318",
        native: { query },
        display: "row",
        visualization_settings: {
          "graph.dimensions": ["MAIN_GROUP", "SUB_GROUP"],
          "graph.series_order_dimension": null,
          "graph.series_order": null,
          "graph.metrics": ["VALUE_SUM"],
        },
      },
    }).then(response => {
      H.visitDashboard(response.body.dashboard_id);
    });

    H.editDashboard();
    H.getDashboardCard().realHover().icon("click").click();
    cy.get("aside").within(() => {
      cy.findByText("Go to a custom destination").click();
      cy.findByText("URL").click();
    });
    H.modal().within(() => {
      cy.findByPlaceholderText("e.g. http://acme.com/id/{{user_id}}").type(
        "http://localhost:4000/?q={{group_name}}",
        { parseSpecialCharSequences: false },
      );
      cy.button("Done").click();
    });
    H.saveDashboard();
  });

  it("passes values from unused columns of row visualization to click behavior (metabase#46318)", () => {
    cy.findAllByRole("graphics-symbol").eq(0).click();
    cy.location("href").should(
      "eq",
      "http://localhost:4000/?q=group_1__sub_group_1",
    );

    cy.go("back");

    cy.findAllByRole("graphics-symbol").eq(2).click(); // intentionally eq(2), not eq(1) - that's how row viz works
    cy.location("href").should(
      "eq",
      "http://localhost:4000/?q=group_1__sub_group_2",
    );

    cy.go("back");

    cy.findAllByRole("graphics-symbol").eq(1).click(); // intentionally eq(1), not eq(2) - that's how row viz works
    cy.location("href").should(
      "eq",
      "http://localhost:4000/?q=group_2__sub_group_1",
    );
    cy.go("back");

    cy.findAllByRole("graphics-symbol").eq(3).click();
    cy.location("href").should(
      "eq",
      "http://localhost:4000/?q=group_2__sub_group_2",
    );
  });
});
