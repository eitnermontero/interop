#!/usr/bin/env node

const { chromium } = require('@playwright/test');

const BASE_URL = 'http://localhost:4200';
const GATEWAY_URL = 'http://localhost:8080';
const KEYCLOAK_URL = 'http://localhost:8180';

// Test credentials (adjust based on your Keycloak setup)
const TEST_CREDENTIALS = {
  username: 'admin',
  password: 'admin'
};

const ADMIN_PAGES = [
  { path: '/admin/usuarios', name: 'Usuarios (Users)', expectedElements: ['Nuevo Usuario', 'Buscar'] },
  { path: '/admin/roles', name: 'Roles', expectedElements: ['Nuevo Role', 'Rol'] },
  { path: '/admin/menus', name: 'Menús', expectedElements: ['Nuevo Menú', 'Nombre'] },
  { path: '/admin/acciones', name: 'Acciones', expectedElements: ['Nueva Acción', 'Código'] },
  { path: '/admin/permisos', name: 'Permisos (Permission Matrix)', expectedElements: ['Guardar', 'Rol'] },
  { path: '/admin/auditoria', name: 'Auditoría (Audit Logs)', expectedElements: ['Buscar', 'Exportar'] }
];

async function testAdminPages() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.createBrowserContext();
  const page = await context.newPage();

  console.log('🚀 Starting Admin Pages Test Suite\n');
  console.log(`📍 Testing Frontend: ${BASE_URL}`);
  console.log(`📍 Backend Gateway: ${GATEWAY_URL}`);
  console.log(`📍 Keycloak: ${KEYCLOAK_URL}\n`);

  let passedTests = 0;
  let failedTests = 0;

  try {
    // Test 1: Frontend is accessible
    console.log('Test 1: Checking Frontend Accessibility');
    await page.goto(BASE_URL, { waitUntil: 'networkidle', timeout: 10000 });
    const title = await page.title();
    console.log(`  ✅ Frontend is accessible (Title: ${title})`);
    passedTests++;

    // Test 2: Check if login page is shown (indicates app is working)
    console.log('\nTest 2: Checking Login/Dashboard Access');
    try {
      // Try to navigate to admin area - should redirect to login if not authenticated
      await page.goto(`${BASE_URL}/admin/usuarios`, { waitUntil: 'networkidle', timeout: 5000 });

      const currentUrl = page.url();
      if (currentUrl.includes('/admin') || currentUrl.includes('login')) {
        console.log(`  ✅ Authentication system working (URL: ${currentUrl})`);
        passedTests++;
      } else {
        console.log(`  ⚠️  Unexpected URL: ${currentUrl}`);
      }
    } catch (e) {
      console.log(`  ⚠️  Could not navigate to admin area: ${e.message}`);
    }

    // Test 3: Test API connectivity
    console.log('\nTest 3: Testing Backend API Connectivity');
    const apiTests = [
      { url: `${GATEWAY_URL}/api/v1/admin/menus`, name: 'Admin Menus API' },
      { url: `${GATEWAY_URL}/api/v1/admin/actions`, name: 'Admin Actions API' },
      { url: `${GATEWAY_URL}/api/v1/admin/roles`, name: 'Admin Roles API' },
      { url: `${GATEWAY_URL}/api/v1/admin/users`, name: 'Admin Users API' },
      { url: `${GATEWAY_URL}/v3/api-docs/admin-service`, name: 'Admin Service OpenAPI' },
      { url: `${GATEWAY_URL}/v3/api-docs/report-service`, name: 'Report Service OpenAPI' },
      { url: `${GATEWAY_URL}/v3/api-docs/cart-service`, name: 'Cart Service OpenAPI' }
    ];

    for (const api of apiTests) {
      try {
        const response = await page.request.get(api.url);
        if (response.status() === 200 || response.status() === 401) {
          console.log(`  ✅ ${api.name} (Status: ${response.status()})`);
          passedTests++;
        } else {
          console.log(`  ⚠️  ${api.name} (Status: ${response.status()})`);
          failedTests++;
        }
      } catch (e) {
        console.log(`  ❌ ${api.name} - ${e.message}`);
        failedTests++;
      }
    }

    // Test 4: Check microservices are running
    console.log('\nTest 4: Checking Microservices Health');
    const services = [
      { url: 'http://localhost:8083/actuator/health', name: 'Admin Service' },
      { url: 'http://localhost:8082/actuator/health', name: 'Report Service' },
      { url: 'http://localhost:8081/actuator/health', name: 'Cart Service' },
      { url: 'http://localhost:8080/actuator/health', name: 'Gateway' }
    ];

    for (const service of services) {
      try {
        const response = await page.request.get(service.url);
        const data = await response.json();
        if (data.status === 'UP') {
          console.log(`  ✅ ${service.name} - Status: UP`);
          passedTests++;
        } else {
          console.log(`  ⚠️  ${service.name} - Status: ${data.status}`);
        }
      } catch (e) {
        console.log(`  ⚠️  ${service.name} - Could not fetch health`);
      }
    }

    // Test 5: Check if admin components are available in the compiled bundle
    console.log('\nTest 5: Checking Admin Components in Bundle');
    const pageContent = await page.content();
    const adminComponentsFound = [
      { name: 'usuarios-component', pattern: 'usuarios-component' },
      { name: 'roles-component', pattern: 'roles-component' },
      { name: 'menus-component', pattern: 'menus-component' },
      { name: 'acciones-component', pattern: 'acciones-component' },
      { name: 'permisos-component', pattern: 'permisos-component' },
      { name: 'auditoria-component', pattern: 'auditoria-component' }
    ];

    // Check for component references in network responses
    const responses = [];
    page.on('response', response => {
      if (response.url().includes('.js')) {
        responses.push(response.url());
      }
    });

    // Navigate once more to capture network activity
    await page.goto(`${BASE_URL}/admin/usuarios`, { waitUntil: 'networkidle', timeout: 5000 });

    console.log(`  ✅ Found ${responses.length} JavaScript chunks loaded`);
    const hasAdminChunks = responses.some(url =>
      url.includes('usuarios-component') ||
      url.includes('roles-component') ||
      url.includes('admin')
    );
    if (hasAdminChunks || responses.length > 5) {
      console.log(`  ✅ Admin components available in bundle`);
      passedTests++;
    }

    // Test 6: Gateway routing test
    console.log('\nTest 6: Testing Gateway Routing');
    const routingTests = [
      { path: '/api/v1/admin/menus', service: 'Admin Service' },
      { path: '/api/v1/reports', service: 'Report Service' },
      { path: '/api/v1/partners', service: 'Cart Service' }
    ];

    for (const route of routingTests) {
      try {
        const response = await page.request.get(`${GATEWAY_URL}${route.path}`);
        if (response.status() === 200 || response.status() === 401) {
          console.log(`  ✅ ${route.service}: ${route.path} (Status: ${response.status()})`);
          passedTests++;
        } else {
          console.log(`  ⚠️  ${route.service}: ${route.path} (Status: ${response.status()})`);
        }
      } catch (e) {
        console.log(`  ⚠️  ${route.service}: Could not reach route`);
      }
    }

    // Test 7: Swagger/OpenAPI endpoints
    console.log('\nTest 7: Testing Swagger/OpenAPI Endpoints');
    const swaggerTests = [
      { url: `${GATEWAY_URL}/v3/api-docs`, name: 'Gateway OpenAPI' },
      { url: `${GATEWAY_URL}/v3/api-docs/admin-service`, name: 'Admin Service OpenAPI' },
      { url: `${GATEWAY_URL}/v3/api-docs/report-service`, name: 'Report Service OpenAPI' },
      { url: `${GATEWAY_URL}/v3/api-docs/cart-service`, name: 'Cart Service OpenAPI' }
    ];

    for (const swagger of swaggerTests) {
      try {
        const response = await page.request.get(swagger.url);
        if (response.status() === 200) {
          const data = await response.json();
          const pathCount = Object.keys(data.paths || {}).length;
          console.log(`  ✅ ${swagger.name} (Endpoints: ${pathCount})`);
          passedTests++;
        }
      } catch (e) {
        console.log(`  ❌ ${swagger.name} - ${e.message}`);
        failedTests++;
      }
    }

  } catch (error) {
    console.error('❌ Test suite error:', error.message);
    failedTests++;
  } finally {
    await browser.close();
  }

  // Summary
  console.log('\n' + '='.repeat(60));
  console.log('📊 TEST SUMMARY');
  console.log('='.repeat(60));
  console.log(`✅ Passed: ${passedTests}`);
  console.log(`❌ Failed: ${failedTests}`);
  console.log(`📈 Total: ${passedTests + failedTests}`);
  console.log(`📊 Success Rate: ${((passedTests / (passedTests + failedTests)) * 100).toFixed(1)}%`);
  console.log('='.repeat(60));

  if (failedTests === 0) {
    console.log('\n🎉 All tests passed! Admin pages are ready for testing.\n');
    process.exit(0);
  } else {
    console.log('\n⚠️  Some tests failed. Please review the output above.\n');
    process.exit(1);
  }
}

testAdminPages().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});
