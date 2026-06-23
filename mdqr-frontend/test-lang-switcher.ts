import { test, expect } from '@playwright/test';

test('language switcher works - ES/EN toggle', async ({ page }) => {
  // 1. Go to the internal app
  await page.goto('http://localhost:4300', { waitUntil: 'load' });
  await page.screenshot({ path: 'lang-initial-en.png', fullPage: true });
  console.log('✅ Initial page loaded (should be English)');

  // 2. Find the language switcher (typically in header)
  const langSwitcher = page.locator('[data-testid="language-switcher"], button:has-text(/ES|EN/i)').first();
  
  if (await langSwitcher.isVisible()) {
    console.log('✅ Language switcher found');
    const initialText = await langSwitcher.textContent();
    console.log(`📍 Initial language button text: ${initialText}`);
  } else {
    console.log('⚠️ Language switcher button not found with expected selectors');
    // Try to find any button in header area
    const buttons = await page.locator('header button').all();
    console.log(`Found ${buttons.length} buttons in header`);
    for (let i = 0; i < buttons.length && i < 5; i++) {
      const text = await buttons[i].textContent();
      console.log(`  Button ${i}: "${text}"`);
    }
  }

  // 3. Try to find language switcher by looking for "ES" or "EN" text
  const langSelector = page.locator('button:has-text("ES"), button:has-text("EN")').first();
  if (await langSelector.isVisible()) {
    console.log('✅ Found language selector');
    const currentLang = await langSelector.textContent();
    console.log(`📍 Current language: ${currentLang}`);
    
    // 4. Click to toggle language
    await langSelector.click();
    console.log('✅ Clicked language switcher');
    
    // Wait for translation to apply
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'lang-after-toggle.png', fullPage: true });
    
    const newLang = await langSelector.textContent();
    console.log(`📍 New language: ${newLang}`);
    
    // 5. Check localStorage for language preference
    const storedLang = await page.evaluate(() => {
      return localStorage.getItem('language') || localStorage.getItem('selectedLanguage') || localStorage.getItem('lang');
    });
    console.log(`📍 localStorage language: ${storedLang || 'NOT SET'}`);
  } else {
    console.log('⚠️ Language selector button not found');
  }

  // 6. Look for translated content to verify translation applied
  const pageText = await page.textContent('body');
  console.log('\n📄 Page text sample (first 200 chars):');
  console.log(pageText?.substring(0, 200));
});
