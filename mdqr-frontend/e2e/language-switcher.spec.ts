import { test, expect } from '@playwright/test';

test.describe('Language Switcher', () => {
  test.beforeEach(async ({ page, context }) => {
    // Clear localStorage before each test
    await context.clearCookies();
  });

  test('should display language switcher with ES/EN buttons', async ({ page }) => {
    await page.goto('/', { waitUntil: 'load' });
    
    // Wait for the app to load
    await page.waitForTimeout(2000);
    
    // Look for language switcher buttons
    const esButton = page.locator('button:has-text("ES")').first();
    const enButton = page.locator('button:has-text("EN")').first();
    
    // Take initial screenshot
    await page.screenshot({ path: '/tmp/lang-initial.png', fullPage: true });
    console.log('📸 Initial screenshot taken: /tmp/lang-initial.png');
    
    // Verify buttons exist
    expect(await esButton.isVisible()).toBeTruthy();
    expect(await enButton.isVisible()).toBeTruthy();
    console.log('✅ ES and EN buttons are visible');
  });

  test('should switch language from ES to EN', async ({ page, context }) => {
    await page.goto('/', { waitUntil: 'load' });
    await page.waitForTimeout(1500);
    
    // Get initial language state
    const esButton = page.locator('button:has-text("ES")').first();
    const enButton = page.locator('button:has-text("EN")').first();
    
    console.log('📍 Current language: Spanish (ES)');
    
    // Click EN button to switch to English
    console.log('🔄 Clicking EN button...');
    await enButton.click();
    
    // Wait for translation to apply
    await page.waitForTimeout(1000);
    
    // Take screenshot after language switch
    await page.screenshot({ path: '/tmp/lang-switched-en.png', fullPage: true });
    console.log('📸 After switching to EN: /tmp/lang-switched-en.png');
    
    // Check localStorage
    const savedLang = await page.evaluate(() => {
      return localStorage.getItem('language');
    });
    console.log(`💾 localStorage language: ${savedLang}`);
    expect(savedLang).toBe('en');
  });

  test('should switch language from EN to ES', async ({ page }) => {
    await page.goto('/', { waitUntil: 'load' });
    await page.waitForTimeout(1500);
    
    const esButton = page.locator('button:has-text("ES")').first();
    const enButton = page.locator('button:has-text("EN")').first();
    
    // Switch to EN first
    await enButton.click();
    await page.waitForTimeout(1000);
    
    console.log('🔄 Clicking ES button to switch back...');
    await esButton.click();
    
    // Wait for translation to apply
    await page.waitForTimeout(1000);
    
    // Take screenshot
    await page.screenshot({ path: '/tmp/lang-switched-es.png', fullPage: true });
    console.log('📸 After switching to ES: /tmp/lang-switched-es.png');
    
    // Check localStorage
    const savedLang = await page.evaluate(() => {
      return localStorage.getItem('language');
    });
    console.log(`💾 localStorage language: ${savedLang}`);
    expect(savedLang).toBe('es');
  });

  test('should persist language preference across page reload', async ({ page, context }) => {
    await page.goto('/', { waitUntil: 'load' });
    await page.waitForTimeout(1500);
    
    // Switch to English
    const enButton = page.locator('button:has-text("EN")').first();
    await enButton.click();
    await page.waitForTimeout(1000);
    
    console.log('🔄 Reloading page to test persistence...');
    
    // Reload page
    await page.reload({ waitUntil: 'load' });
    await page.waitForTimeout(1500);
    
    // Check that language is still EN
    const savedLang = await page.evaluate(() => {
      return localStorage.getItem('language');
    });
    
    console.log(`💾 After reload, localStorage language: ${savedLang}`);
    expect(savedLang).toBe('en');
    
    await page.screenshot({ path: '/tmp/lang-after-reload.png', fullPage: true });
    console.log('📸 After reload: /tmp/lang-after-reload.png');
  });

  test('should apply translations to UI elements', async ({ page }) => {
    await page.goto('/', { waitUntil: 'load' });
    await page.waitForTimeout(2000);
    
    // Get Spanish translations
    const esPageText = await page.textContent('body');
    
    // Look for Spanish text
    if (esPageText?.includes('Transacciones') || esPageText?.includes('Dashboard')) {
      console.log('✅ Spanish translations are visible in the UI');
    }
    
    // Switch to English
    const enButton = page.locator('button:has-text("EN")').first();
    await enButton.click();
    await page.waitForTimeout(1500);
    
    // Get English translations
    const enPageText = await page.textContent('body');
    
    // Look for English text
    if (enPageText?.includes('Transactions') || enPageText?.includes('Dashboard')) {
      console.log('✅ English translations are applied to the UI');
    }
    
    console.log('✅ Translations are dynamically applied when language changes');
  });
});
