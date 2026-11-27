/* Runs only in CI against the isolated MySQL/Tomcat instance. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');
const assert = require('node:assert/strict');
const output = 'ui-verification';
const base = process.env.SWS_BROWSER_URL || 'http://127.0.0.1:8080/studyroom/';
const check = (condition, name) => { assert.ok(condition, name); console.log('PASS browser:', name); };
(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.goto(base);
    await page.locator('.room-card').first().waitFor();
    check(await page.locator('h1').innerText() === '预约总览', 'the main URL opens the booking dashboard');
    check(await page.locator('.overview-metric strong').nth(2).innerText() === '—', 'guest credit is withheld');
    check(await page.locator('.room-card').count() >= 2, 'rooms load from real database');
    await page.screenshot({ path: `${output}/desktop-rooms.png`, fullPage: true });
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'desktop has no horizontal overflow');
    await page.locator('#header-auth [data-action=login]').click();
    await page.getByRole('button', { name: '注册账号', exact: true }).click();
    const registerForm = page.locator('#auth-form[data-mode=register]');
    await registerForm.waitFor();
    const suffix = Date.now().toString();
    const username = 'web_ci_' + suffix;
    await registerForm.locator('[name=username]').fill(username);
    await registerForm.locator('[name=contact]').fill('136' + suffix.slice(-8));
    await registerForm.locator('[name=password]').fill('Browser@123456');
    await registerForm.locator('[name=password_confirm]').fill('Browser@123456');
    const [codeResponse] = await Promise.all([
      page.waitForResponse(response => response.url().endsWith('/register/code')),
      registerForm.locator('[data-action=register-code]').click()
    ]);
    await registerForm.locator('[name=code]').fill((await codeResponse.json()).demo_code);
    check(await registerForm.evaluate(form => form.checkValidity()), 'registration inputs satisfy the form');
    const [registration] = await Promise.all([
      page.waitForResponse(response => response.url().endsWith('/api/account-rooms/register')),
      page.getByRole('button', { name: '注册并开始使用' }).click()
    ]);
    if (!registration.ok()) throw new Error(`Registration failed (${registration.status()}): ${await registration.text()}`);
    await page.locator('#modal').waitFor({ state: 'hidden' });
    check(await page.locator('[data-view=admin]').isHidden(), 'ordinary user has no management navigation');
    await page.locator('.room-card').first().getByRole('button', { name: '预约空间' }).click();
    await page.locator('#reserve-form').waitFor();
    const tomorrow = new Date(Date.now() + 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
    await page.locator('#reserve-form [name=date]').fill(tomorrow);
    await page.locator('#reserve-form [name=start]').selectOption('10:00');
    await page.locator('#reserve-form [name=end]').selectOption('11:00');
    await page.getByRole('button', { name: '确认预约', exact: true }).click();
    await page.locator('.order-card .badge.PENDING_PAYMENT').waitFor();
    check(true, 'booking form creates an actual unpaid order');
    const query = new URL('api/account-rooms/catalog', base);
    query.search = new URLSearchParams({
      start_time: `${tomorrow}T10:00`, end_time: `${tomorrow}T11:00`,
      availability: 'OCCUPIED', page_size: '50'
    }).toString();
    const occupied = await page.request.get(query.href);
    assert.equal(occupied.status(), 200, await occupied.text());
    const catalog = await occupied.json();
    check(catalog.page_size === 50 && catalog.items.some(room => room.room_id === '9101' && room.availability === 'OCCUPIED'),
      'catalog derives occupied status from the reserved slot and respects page size');
    const forbidden = await page.request.get(new URL('api/usage-feedback/admin/user-credit?user_id=1', base).href);
    check(forbidden.status() === 403, 'ordinary user cannot read another user’s credit from admin endpoint');
    await page.locator('[data-view=messages]').click();
    const bookingMessage=page.locator('.record').filter({has:page.getByRole('heading',{name:'预约订单已生成',exact:true})});
    await bookingMessage.first().getByRole('button',{name:'标为已读',exact:true}).click();
    await bookingMessage.first().getByText('已读',{exact:true}).waitFor();
    check(true, 'booking message can be read and remains marked after refresh');
    await page.locator('[data-view=overview]').click();
    await page.locator('.overview-booking .badge.PENDING_PAYMENT').waitFor();
    await page.screenshot({ path: `${output}/desktop-overview.png`, fullPage: true });
    await page.getByRole('button', { name: '模拟支付', exact: true }).click();
    await page.getByRole('button', { name: '确认操作', exact: true }).click();
    await page.locator('.overview-booking .badge.PENDING_CHECKIN').waitFor();
    check(await page.locator('.overview-track [aria-current=step]').innerText() === '已支付', 'dashboard action advances the real booking timeline');
    await page.locator('[data-view=orders]').click();
    await page.locator('.order-card .badge.PENDING_CHECKIN').waitFor();
    check(true, 'payment updates actual order');
    await page.getByRole('button', { name: '查看退款', exact: true }).click();
    await page.getByRole('button', { name: '确认操作', exact: true }).click();
    await page.locator('.order-card .badge.CANCELLED').waitFor();
    check(true, 'refund quote and confirmation complete');
    await page.screenshot({ path: `${output}/desktop-orders.png`, fullPage: true });
    await page.locator('[data-view=rooms]').click();
    await page.locator('.room-card [data-action=room-details]').first().click();
    await page.getByRole('button', { name: '历史评价', exact: true }).click();
    await page.locator('#room-review-search').waitFor();
    await page.locator('#room-review-search [name=score_1]').selectOption('5');
    await page.locator('#room-review-search [name=from]').fill('2099-01-01');
    await page.getByRole('button', { name: '筛选评价', exact: true }).click();
    await page.getByRole('heading', { name: '暂无匹配评价', exact: true }).waitFor();
    check(true, 'room detail opens historical reviews and accepts combined rating and date filters');
    await page.keyboard.press('Escape');
    await page.locator('[data-view=credit]').click();
    await page.locator('.credit-score').waitFor();
    check((await page.locator('.credit-score').innerText()).includes('300'), 'credit page reads actual initial score');
    // Seed a just-finished order in the isolated CI database to test the review window.
    const identity = await (await page.request.get(new URL('api/account-rooms/session',base).href)).json();
    assert.match(String(identity.user_id), /^\d+$/);
    const bookingId = execFileSync('mysql', ['-h','127.0.0.1','-u','root','-N','studyroom','-e',`
      SET @now=TIMESTAMPADD(HOUR,8,UTC_TIMESTAMP());
      INSERT INTO booking(user_id,room_id,start_time,end_time,total_amount,booking_status,rule_version_id,pay_deadline)
      VALUES (${identity.user_id},9101,@now-INTERVAL 2 HOUR,@now-INTERVAL 1 HOUR,10,'FINISHED',1,@now-INTERVAL 3 HOUR);
      SET @booking=LAST_INSERT_ID();
      INSERT INTO \`usage\`(booking_id,sign_in_time,actual_end_time,door_open_result)
      VALUES (@booking,@now-INTERVAL 2 HOUR,@now-INTERVAL 1 HOUR,'SUCCESS');
      SELECT @booking;
    `], { encoding:'utf8' }).trim();
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aR1sAAAAASUVORK5CYII=','base64');
    const reviewUpload = await page.request.post(new URL('api/usage-feedback/review',base).href, {
      headers:{'X-CSRF-Token':(await (await page.request.get(new URL('api/account-rooms/token',base).href)).json()).csrf},
      multipart:{booking_id:bookingId,score_1:'5',score_2:'5',comment:'浏览器上传验证',
        attachments:{name:'review.png',mimeType:'image/png',buffer:png}}
    });
    assert.equal(reviewUpload.status(),200,await reviewUpload.text());
    const history=await (await page.request.get(new URL('api/usage-feedback/room-review-history?room_id=9101',base).href)).json();
    const picture=history.data.items.find(item=>item.comment==='浏览器上传验证').attachments[0];
    const download=await page.request.get(new URL('api/usage-feedback/attachment?attachment_id='+picture.attachment_id,base).href);
    assert.equal(download.status(),200,await download.text());
    check((await download.body()).equals(png),'review image is stored and downloaded unchanged');
    await page.locator('[data-view=feedback]').click();
    await page.getByRole('button', { name: '上报故障' }).click();
    await page.locator('#fault-form [name=room_id]').fill('9101');
    await page.locator('#fault-form [name=fault_desc]').fill('浏览器集成检查：投影设备需要检查');
    await page.getByRole('button', { name: '提交报障' }).click();
    await page.locator('.record .badge.PENDING').waitFor();
    check(true, 'fault report is stored and listed');
    await page.locator('#header-auth a').click();
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await page.locator('#header-auth [data-action=login]').waitFor();
    await page.getByRole('button', { name: '登录 / 注册', exact: true }).click();
    await page.locator('#auth-form [name=username]').fill('room_admin');
    await page.locator('#auth-form [name=password]').fill('Admin@123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.locator('#modal').waitFor({ state: 'hidden' });
    await page.locator('[data-view=admin]').click();
    await page.locator('.data-table tbody tr').first().waitFor();
    check(await page.getByRole('button', { name: '规则配置', exact: true }).count() === 0, 'room administrator does not get super-admin rule controls');
    await page.locator('.data-table [data-action=room-edit][data-id="9101"]').click();
    await page.locator('#room-form [name=has_projector]').check();
    await page.locator('#room-form [name=usage_rules]').fill('离开时请关闭投影仪。');
    await page.getByRole('button',{name:'保存空间',exact:true}).click();
    await page.locator('#modal').waitFor({state:'hidden'});
    const equipmentResponse=await page.request.get(new URL('api/account-rooms/catalog?has_projector=1',base).href);
    assert.equal(equipmentResponse.status(),200,await equipmentResponse.text());
    const equipmentRooms=(await equipmentResponse.json()).items;
    check(equipmentRooms.some(room=>room.room_id==='9101' && room.usage_rules==='离开时请关闭投影仪。'),
      'administrators can edit room details and users can search by equipment');
    await page.getByRole('button', { name: '故障处理', exact: true }).click();
    await page.locator('[data-action=process-fault]').first().click();
    await page.locator('#process-record-form [name=admin_opinion]').fill('已安排检查设备');
    await page.getByRole('button', { name: '确认提交', exact: true }).click();
    await page.locator('.record .badge.REPAIRING').first().waitFor();
    await page.locator('[data-action=process-fault]').first().click();
    await page.locator('#process-record-form [name=admin_opinion]').fill('设备检查完成，恢复使用');
    await page.getByRole('button', { name: '确认提交', exact: true }).click();
    await page.locator('.record .badge.RESOLVED').first().waitFor();
    check(true, 'administrator repairs and resolves the reported fault');
    await page.getByRole('button', { name: '房间管理', exact: true }).click();
    await page.locator('.data-table tbody tr').first().waitFor();
    await page.screenshot({ path: `${output}/desktop-admin.png`, fullPage: true });
    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    await mobile.goto(base);
    await mobile.locator('.room-card').first().waitFor();
    check(await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'mobile has no horizontal overflow');
    await mobile.screenshot({ path: `${output}/mobile-rooms.png`, fullPage: true });
    await mobile.locator('#header-auth [data-action=login]').click();
    check(await mobile.locator('#modal').evaluate(node => {const r=node.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth;}), 'mobile authentication dialog fits viewport');
    check(pageErrors.length === 0, 'no JavaScript page errors: ' + pageErrors.join('; '));
    await browser.close();
  } catch (error) {
    for (const context of browser.contexts()) for (const [i, page] of context.pages().entries()) {
      console.error('Visible form feedback:', await page.locator('.form-error:visible').allTextContents().catch(() => []));
      await page.screenshot({ path: `${output}/failure-${i}.png`, fullPage: true }).catch(() => {});
      fs.writeFileSync(`${output}/failure-${i}.html`, await page.content().catch(() => ''));
    }
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
