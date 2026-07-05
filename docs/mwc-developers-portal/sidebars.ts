import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  apiSidebar: [
    'intro',
    {
      type: 'category',
      label: 'HUB – QR Decrypt API',
      items: ['hub', 'hub-partner'],
    },
    {
      type: 'category',
      label: 'API Reference',
      link: {
        type: 'generated-index',
        title: 'Middleware Core API',
        description: 'Documentacion completa de todos los endpoints del servicio.',
        slug: '/reference',
      },
      items: require('./docs/reference/sidebar.ts'),
    },
  ],
};

export default sidebars;
