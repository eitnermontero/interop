import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebar: SidebarsConfig = {
  apisidebar: [
    {
      type: "doc",
      id: "reference/middleware-core-api",
    },
    {
      type: "category",
      label: "Auth",
      link: {
        type: "doc",
        id: "reference/auth",
      },
      items: [
        {
          type: "doc",
          id: "reference/authenticate-partner",
          label: "Authenticate Partner",
          className: "api-method post",
        },
      ],
    },
    {
      type: "category",
      label: "Catalog",
      link: {
        type: "doc",
        id: "reference/catalog",
      },
      items: [
        {
          type: "doc",
          id: "reference/get-departments",
          label: "Get Departments",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/get-categories",
          label: "Get Categories",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/get-providers",
          label: "Get Providers",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/get-provider-groups",
          label: "Get Provider Groups",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/get-group-criteria",
          label: "Get Group Criteria",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/get-criteria-fields",
          label: "Get Criteria Fields",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/get-list-values",
          label: "Get List Values",
          className: "api-method get",
        },
      ],
    },
    {
      type: "category",
      label: "Accounts",
      link: {
        type: "doc",
        id: "reference/accounts",
      },
      items: [
        {
          type: "doc",
          id: "reference/search-account",
          label: "Search Account",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/get-account-items",
          label: "Get Account Items",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/generate-item-subitems",
          label: "Generate Item Subitems",
          className: "api-method post",
        },
      ],
    },
    {
      type: "category",
      label: "Payments",
      link: {
        type: "doc",
        id: "reference/payments",
      },
      items: [
        {
          type: "doc",
          id: "reference/init-payment",
          label: "Init Payment",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/confirm-payment",
          label: "Confirm Payment",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/get-receipt",
          label: "Get Receipt",
          className: "api-method get",
        },
      ],
    },
  ],
};

export default sidebar.apisidebar;
