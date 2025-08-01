import _ from "underscore";

import { loginCache } from "e2e/support/commands/user/authentication";
import {
  METABASE_SECRET_KEY,
  SAMPLE_DB_ID,
  SAMPLE_DB_TABLES,
  USERS,
  USER_GROUPS,
} from "e2e/support/cypress_data";
import {
  createQuestion,
  createQuestionAndDashboard,
  restore,
  snapshot,
  updateSetting,
  withSampleDatabase,
} from "e2e/support/helpers";

const {
  STATIC_ORDERS_ID,
  STATIC_PRODUCTS_ID,
  STATIC_REVIEWS_ID,
  STATIC_PEOPLE_ID,
  STATIC_ACCOUNTS_ID,
  STATIC_ANALYTIC_EVENTS_ID,
  STATIC_FEEDBACK_ID,
  STATIC_INVOICES_ID,
} = SAMPLE_DB_TABLES;

const {
  ALL_USERS_GROUP,
  COLLECTION_GROUP,
  DATA_GROUP,
  READONLY_GROUP,
  NOSQL_GROUP,
} = USER_GROUPS;
const { admin } = USERS;

describe("snapshots", () => {
  describe("default", () => {
    it("default", () => {
      snapshot("blank");
      setup();
      updateSettings();
      snapshot("setup");
      addUsersAndGroups();
      createCollections();
      withSampleDatabase((SAMPLE_DATABASE) => {
        ensureTableIdsAreCorrect(SAMPLE_DATABASE);
        hideNewSampleTables(SAMPLE_DATABASE);
        createQuestionsAndDashboards(SAMPLE_DATABASE);
        snapshot("without-models");
        createModels(SAMPLE_DATABASE);
        cy.writeFile(
          "e2e/support/cypress_sample_database.json",
          SAMPLE_DATABASE,
        );
      });

      snapshot("default");

      // we need to do this after the snapshot because hitting the API populates the audit log
      const instanceData = getDefaultInstanceData();
      cy.writeFile(
        "e2e/support/cypress_sample_instance_data.json",
        instanceData,
      );

      restore("blank");
    });
  });

  function setup() {
    cy.request("GET", "/api/session/properties").then(
      ({ body: properties }) => {
        cy.request("POST", "/api/setup", {
          token: properties["setup-token"],
          user: admin,
          prefs: {
            site_name: "Epic Team",
            allow_tracking: false,
          },
          database: null,
        });
      },
    );

    cy.request("GET", "/api/user/current").then(({ body: { id } }) => {
      // Dismiss `it's ok to play around` modal for admin
      cy.request("PUT", `/api/user/${id}/modal/qbnewb`);
    });

    cy.signIn("admin", { setupCache: true }); // cache admin credentials
  }

  function updateSettings() {
    // Asynchronous updates greatly contribute to the non-determinism of our e2e tests,
    // significantly increasing their flakiness. These failures hardly reflect real-life
    // scenarios, as users do not interact with the UI as quickly as Cypress does.
    // To mitigate this type of flakiness, we use synchronous updates by default in e2e tests.
    //
    // The most frequently flaky tests were the dashboard filter tests, often involving last_used_param_values.
    updateSetting("synchronous-batch-updates", true);

    updateSetting("enable-public-sharing", true);
    // interactive is not enabled in the snapshots as it requires a premium feature
    // updateSetting("enable-embedding-interactive", true);
    updateSetting("enable-embedding-sdk", true);
    updateSetting("enable-embedding-static", true).then(() => {
      updateSetting("embedding-secret-key", METABASE_SECRET_KEY);
    });
    // dismiss the license token missing banner, not necessary to render it in every test
    updateSetting(
      "license-token-missing-banner-dismissal-timestamp",
      new Date().toISOString(),
    );
    updateSetting("store-url", "https://test-store.metabase.com");
  }

  function addUsersAndGroups() {
    // groups
    cy.request("POST", "/api/permissions/group", { name: "collection" }).then(
      ({ body }) => {
        expect(body.id).to.eq(COLLECTION_GROUP); // 3
      },
    );
    cy.request("POST", "/api/permissions/group", { name: "data" }).then(
      ({ body }) => {
        expect(body.id).to.eq(DATA_GROUP); // 4
      },
    );
    cy.request("POST", "/api/permissions/group", { name: "readonly" }).then(
      ({ body }) => {
        expect(body.id).to.eq(READONLY_GROUP); // 5
      },
    );
    cy.request("POST", "/api/permissions/group", { name: "nosql" }).then(
      ({ body }) => {
        expect(body.id).to.eq(NOSQL_GROUP); // 6
      },
    );

    // Create all users except admin, who was already created in one of the previous steps
    Object.keys(_.omit(USERS, "admin")).forEach((user) => {
      cy.createUser(user);
    });

    // Make a call to `/api/user` because some things (personal collections) get created there
    cy.request("GET", "/api/user");

    Object.keys(USERS).forEach((user) => {
      if (user === "admin") {
        // we already cached admin user credentials during setup
        return;
      }
      cy.signIn(user, { setupCache: true });
    });

    cy.signInAsAdmin();

    cy.updatePermissionsGraph({
      [ALL_USERS_GROUP]: {
        [SAMPLE_DB_ID]: {
          // set the data permission so the UI doesn't warn us that "all users has higher permissions than X"
          "view-data": "unrestricted",
          "create-queries": "no",
        },
      },
      [DATA_GROUP]: {
        [SAMPLE_DB_ID]: {
          "view-data": "unrestricted",
          "create-queries": "query-builder-and-native",
        },
      },
      [NOSQL_GROUP]: {
        [SAMPLE_DB_ID]: {
          "view-data": "unrestricted",
          "create-queries": "query-builder",
        },
      },
      [COLLECTION_GROUP]: {
        [SAMPLE_DB_ID]: {
          "view-data": "unrestricted",
          "create-queries": "no",
        },
      },
      [READONLY_GROUP]: {
        [SAMPLE_DB_ID]: {
          "view-data": "unrestricted",
          "create-queries": "no",
        },
      },
    });

    cy.updateCollectionGraph({
      [ALL_USERS_GROUP]: { root: "none" },
      [DATA_GROUP]: { root: "none" },
      [NOSQL_GROUP]: { root: "none" },
      [COLLECTION_GROUP]: { root: "write" },
      [READONLY_GROUP]: { root: "read" },
    });
  }

  function logSelectModel(model_id, model) {
    console.log("select collection:", model_id);
    cy.request("Post", "/api/activity/recents", {
      model_id,
      model,
      context: "selection",
    });
  }

  function createCollections() {
    function postCollection(name, parent_id, callback) {
      cy.request("POST", "/api/collection", {
        name,
        description: `Collection ${name}`,
        parent_id,
      }).then(({ body }) => callback && callback(body));
    }

    postCollection("First collection", undefined, (firstCollection) => {
      logSelectModel(firstCollection.id, "collection");
      postCollection(
        "Second collection",
        firstCollection.id,
        (secondCollection) => {
          logSelectModel(secondCollection.id, "collection");
          postCollection(
            "Third collection",
            secondCollection.id,
            (thirdCollection) =>
              logSelectModel(thirdCollection.id, "collection"),
          );
        },
      );
    });
  }

  function createQuestionsAndDashboards({ ORDERS, ORDERS_ID }) {
    // question 1: Orders
    const questionDetails = {
      name: "Orders",
      query: { "source-table": ORDERS_ID },
    };

    // dashboard 1: Orders in a dashboard
    const dashboardDetails = { name: "Orders in a dashboard" };

    createQuestionAndDashboard({
      questionDetails,
      dashboardDetails,
      cardDetails: { size_x: 16, size_y: 8 },
    }).then(({ body: { dashboard_id } }) => {
      logSelectModel(dashboard_id, "dashboard");
    });

    // question 2: Orders, Count
    createQuestion({
      name: "Orders, Count",
      query: { "source-table": ORDERS_ID, aggregation: [["count"]] },
    });

    // question 3: Orders, Count, Grouped by Created At (year)
    createQuestion({
      name: "Orders, Count, Grouped by Created At (year)",
      query: {
        "source-table": ORDERS_ID,
        aggregation: [["count"]],
        breakout: [["field", ORDERS.CREATED_AT, { "temporal-unit": "year" }]],
      },
      display: "line",
    });
  }

  function createModels({ ORDERS_ID }) {
    // Model 1
    createQuestion({
      name: "Orders Model",
      query: { "source-table": ORDERS_ID },
      type: "model",
    });
  }

  function ensureTableIdsAreCorrect({
    ORDERS_ID,
    PRODUCTS_ID,
    REVIEWS_ID,
    PEOPLE_ID,
    ACCOUNTS_ID,
    ANALYTIC_EVENTS_ID,
    FEEDBACK_ID,
    INVOICES_ID,
  }) {
    expect(ORDERS_ID).to.eq(STATIC_ORDERS_ID);
    expect(PEOPLE_ID).to.eq(STATIC_PEOPLE_ID);
    expect(REVIEWS_ID).to.eq(STATIC_REVIEWS_ID);
    expect(PRODUCTS_ID).to.eq(STATIC_PRODUCTS_ID);
    expect(ACCOUNTS_ID).to.eq(STATIC_ACCOUNTS_ID);
    expect(ANALYTIC_EVENTS_ID).to.eq(STATIC_ANALYTIC_EVENTS_ID);
    expect(FEEDBACK_ID).to.eq(STATIC_FEEDBACK_ID);
    expect(INVOICES_ID).to.eq(STATIC_INVOICES_ID);
  }

  function hideNewSampleTables({
    ACCOUNTS_ID,
    ANALYTIC_EVENTS_ID,
    FEEDBACK_ID,
    INVOICES_ID,
  }) {
    [ACCOUNTS_ID, ANALYTIC_EVENTS_ID, FEEDBACK_ID, INVOICES_ID].forEach(
      (id) => {
        cy.request("PUT", `/api/table/${id}`, { visibility_type: "hidden" });
      },
    );
  }
});

function getDefaultInstanceData() {
  const instanceData = {};

  instanceData.loginCache = loginCache;

  cy.request("/api/card").then(({ body: cards }) => {
    instanceData.questions = cards;
  });

  cy.request("/api/user").then(({ body: { data: users } }) => {
    instanceData.users = users;
  });

  cy.request("/api/database").then(({ body: { data: databases } }) => {
    instanceData.databases = databases;
  });

  cy.request("/api/permissions/group").then(({ body: groups }) => {
    instanceData.groups = groups;
  });

  cy.request("/api/collection").then(({ body: collections }) => {
    instanceData.collections = collections;

    instanceData.dashboards = [];
    for (const collection of collections) {
      cy.request(
        `/api/collection/${collection.id}/items?models=dashboard`,
      ).then(({ body: { data: dashboards } }) => {
        for (const dashboard of dashboards) {
          if (!instanceData.dashboards.find((d) => d.id === dashboard.id)) {
            cy.request(`/api/dashboard/${dashboard.id}`).then((response) => {
              instanceData.dashboards.push(response.body);
            });
          }
        }
      });
    }
  });

  return instanceData;
}
