import { themes as prismThemes } from 'prism-react-renderer';
import type * as Preset from '@docusaurus/preset-classic';
import type { Config } from '@docusaurus/types';
import type * as Plugin from '@docusaurus/types/src/plugin';
import type * as OpenApiPlugin from 'docusaurus-plugin-openapi-docs';

const config: Config = {
  future: {
    // Keep all "faster" wins (SWC, Lightning CSS, MDX cache) but turn OFF
    // rspackBundler — it silently skips `configureWebpack()` hooks, which
    // the @easyops-cn/docusaurus-search-local plugin depends on to emit its
    // index file. Without that, the search bar spins forever.
    faster: {
      swcJsLoader: true,
      swcJsMinimizer: true,
      swcHtmlMinimizer: true,
      lightningCssMinimizer: true,
      mdxCrossCompilerCache: true,
      rspackBundler: true,
      rspackPersistentCache: false,
      ssgWorkerThreads: false,
      gitEagerVcs: false,
    },
    v4: true,
  },

  title: 'Middleware Core API',
  tagline: 'API REST para integrar el servicio de cobros de Genesis',
  url: 'https://developers.sintesis.com.bo',
  baseUrl: '/middleware-core/',
  onBrokenLinks: 'warn',
  favicon: 'img/favicon.ico',
  organizationName: 'SintesisSA',
  projectName: 'middleware-core',

  i18n: {
    defaultLocale: 'es',
    locales: ['es'],
  },

  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          docItemComponent: '@theme/ApiItem',
          // Versioning strategy (matches https://docusaurus.io):
          //   /          → current docs (docs/ folder) while no stable release exists
          //   /<version> → frozen snapshot in versioned_docs/
          //   /next      → work-in-progress — enable once the first version is frozen
          //                via `bun run docusaurus docs:version <x.y.z>` and then
          //                add { current: { label: 'Next 🚧', path: 'next' } } here.
          includeCurrentVersion: true,
        },
        blog: false,
        theme: {
          customCss: ['./src/css/custom.css'],
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    docs: {
      sidebar: {
        hideable: true,
      },
    },
    colorMode: {
      defaultMode: 'light',
      disableSwitch: false,
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Middleware Core API',
      items: [
        {
          type: 'doc',
          docId: 'intro',
          position: 'left',
          label: 'API Reference',
        },
        // Re-enable once the first stable version is frozen:
        //   { type: 'docsVersionDropdown', position: 'right', dropdownActiveClassDisabled: true },
        { type: 'custom-PalettePicker', position: 'right' },
      ],
    },
    footer: {
      style: 'dark',
      copyright: `Copyright © ${new Date().getFullYear()} Sintesis S.A.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: [
        'ruby',
        'csharp',
        'php',
        'java',
        'powershell',
        'json',
        'bash',
        'dart',
      ],
    },
    languageTabs: [
      { highlight: 'bash', language: 'curl', logoClass: 'curl' },
      { highlight: 'javascript', language: 'nodejs', logoClass: 'nodejs' },
      { highlight: 'python', language: 'python', logoClass: 'python' },
      { highlight: 'java', language: 'java', logoClass: 'java', variant: 'unirest' },
      { highlight: 'csharp', language: 'csharp', logoClass: 'csharp' },
      { highlight: 'go', language: 'go', logoClass: 'go' },
      { highlight: 'php', language: 'php', logoClass: 'php' },
      { highlight: 'ruby', language: 'ruby', logoClass: 'ruby' },
      { highlight: 'powershell', language: 'powershell', logoClass: 'powershell' },
    ],
  } satisfies Preset.ThemeConfig,

  plugins: [
    [
      'docusaurus-plugin-openapi-docs',
      {
        id: 'openapi',
        docsPluginId: 'classic',
        config: {
          middlewareCore: {
            specPath: 'examples/middleware-core.yaml',
            outputDir: 'docs/reference',
            sidebarOptions: {
              groupPathsBy: 'tag',
              categoryLinkSource: 'tag',
            },
            hideSendButton: false,
            showSchemas: true,
          } satisfies OpenApiPlugin.Options,
        } satisfies Plugin.PluginOptions,
      },
    ],
    // FOUC prevention: restore saved palette before React hydrates
    function paletteScript() {
      return {
        name: 'palette-fouc-script',
        injectHtmlTags() {
          return {
            headTags: [
              {
                tagName: 'script',
                innerHTML: `try{var p=localStorage.getItem('openapi-demo-palette');if(p){var l=document.createElement('link');l.id='openapi-palette-link';l.rel='stylesheet';l.href='${config.baseUrl}themes/'+p+'.css';document.head.appendChild(l);}}catch(e){}`,
              },
            ],
          };
        },
      };
    },
  ],

  themes: [
    'docusaurus-theme-openapi-docs',
    '@docusaurus/theme-mermaid',
    [
      '@easyops-cn/docusaurus-search-local',
      {
        hashed: true,
        language: ['es', 'en'],
        highlightSearchTermsOnTargetPage: true,
        explicitSearchResultPath: true,
        indexDocs: true,
        indexBlog: false,
        indexPages: false,
        // Must match the Docusaurus docs plugin's `routeBasePath`. We use "/"
        // (docs served at root), so the search plugin's default of "docs" would
        // miss every page and leave the search index empty.
        docsRouteBasePath: '/',
      },
    ],
  ],

  stylesheets: [
    {
      href: 'https://use.fontawesome.com/releases/v5.11.0/css/all.css',
      type: 'text/css',
    },
  ],
};

export default async function createConfig() {
  return config;
}
