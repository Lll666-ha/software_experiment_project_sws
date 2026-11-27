/* Shared UI. Business validation, pricing, permissions and transitions remain on the server. */
'use strict';
const base = new URL('./', document.currentScript.src);
const $ = (selector, within = document) => within.querySelector(selector);
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const e = escapeHtml;
const icon = name => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const labels = {USER:'普通用户',ROOM_ADMIN:'房间管理员',SUPER_ADMIN:'超级管理员',PENDING_PAYMENT:'待支付',PENDING_CHECKIN:'待签到',IN_USE:'使用中',CANCELLED:'已取消',FINISHED:'已结束',ACTIVE:'正常',DISABLED:'已停用',LISTED:'已上架',UNLISTED:'已下架',NORMAL:'正常',MAINTENANCE:'维护中',PENDING:'待处理',REPAIRING:'维修中',RESOLVED:'已完成',APPROVED:'已通过',REJECTED:'已驳回',TIMEOUT:'超时未签到',EARLY_RELEASE:'提前结束',APPEAL_RETURN:'申诉信用返还',NO_SHOW:'超时未签到'};
const label = value => labels[value] || value || '—';
const badge = status => `<span class="badge ${e(status)}">${e(label(status))}</span>`;
const money = value => Number(value ?? 0).toFixed(2);
const dateText = value => value ? String(value).replace('T',' ').replace(/\.0+$/,'').slice(0,16) : '—';
const state = {identity:{authenticated:false},csrf:'',rooms:[],orders:[],rule:null,credit:null,adminRooms:[],faults:[],appeals:[],view:'rooms',orderTab:'all',feedbackTab:'faults',adminTab:'rooms'};
let toastTimer, renderId = 0;
function toast(message, error = false) { const node=$('#toast'); node.className=`toast${error?' error':''}`;node.textContent=message;node.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>node.hidden=true,5000); }
function empty(title, description='', action='') { return `<div class="empty">${icon('room')}<h3>${e(title)}</h3><p>${e(description)}</p>${action}</div>`; }
function heading(title, description, right='') { return `<div class="page-heading"><div><h1>${e(title)}</h1>${description ? `<p>${e(description)}</p>` : ''}</div>${right}</div>`; }
function button(text, action, extra='', primary=false) { return `<button class="btn${primary?' primary':''}" data-action="${e(action)}" ${extra}>${text}</button>`; }
async function api(module, path, {method='GET',data,format='json'}={}) {
    const url = new URL(`api/${module}${path}`,base);
    const options={method,credentials:'same-origin',headers:{}};
    if (method==='POST') { const params=new URLSearchParams(data || {});params.set('csrf',state.csrf);options.body=params; }
    else if (data) for (const [key,value] of Object.entries(data)) if(value!==''&&value!=null) url.searchParams.set(key,value);
    const res=await fetch(url,options);const text=await res.text();let result;
    if(format==='text') result=text;else {try {result=JSON.parse(text);}catch {throw new Error(res.status===404?'页面接口暂不可用，请检查部署版本':'暂时无法读取服务端结果，请稍后重试');}}
    if(!res.ok) { if(res.status===401 || res.status===403&&String(result?.message||text).includes('账号已停用')) {state.identity={authenticated:false};syncIdentity();}throw new Error(typeof result==='string'?result.trim():result.message||result.msg||'操作失败，请稍后重试'); }
    return result;
}
const account = (path,options) => api('account-rooms',path,options);
const bookings = (path,options={}) => api('booking-rules',path,{format:'text',...options});
const feedback = async (path,options) => (await api('usage-feedback',path,options)).data;
const postAccount = (path,data) => account(path,{method:'POST',data});
const postBooking = (path,data) => bookings(path,{method:'POST',data});
const postFeedback = (path,data) => feedback(path,{method:'POST',data});
function syncIdentity() {
    const id=state.identity;const admin=id.authenticated&&id.role!=='USER';
    $('[data-view="admin"]').hidden=!admin;
    $('#header-auth').innerHTML=id.authenticated?`<a class="btn quiet" href="#account">${icon('user')}我的账户</a>`:button('登录 / 注册','login','',true);
    $('#side-user').innerHTML=`<div class="avatar">${icon('user')}</div><div><b>${id.authenticated?e(label(id.role)):'我的账户'}</b><small>${id.authenticated?'账号 '+e(id.user_id):'登录后开始预约'}</small></div>${icon('arrow')}`;
}
async function refreshIdentity() { state.identity=await account('/session');state.csrf=(await account('/token')).csrf;syncIdentity(); }
async function loadOrders() { state.orders=await bookings('/order-data',{format:'json'}); }
async function loadRule() { state.rule=await bookings('/rule-data',{format:'json'}); }
function mustLogin() { if(state.identity.authenticated)return true;openAuth('login');return false; }
function showModal(title,body) { $('#modal-title').textContent=title;$('#modal-body').innerHTML=body;if(!$('#modal').open)$('#modal').showModal(); }
function closeModal() { $('#modal').close(); }
function formError(message) { const body=$('#modal-body');let box=$('.form-error',body);if(!box){box=document.createElement('div');box.className='form-error';box.setAttribute('role','alert');body.prepend(box);}box.textContent=message; }
function fields(form) { return Object.fromEntries(new FormData(form).entries()); }
function requireIdentityPage(title) { return heading(title,'登录后查看与你有关的记录。')+`<div class="panel">${empty('登录，继续你的研讨之旅','预约、信用与反馈记录都会保存在你的账户中。',button('登录 / 注册','login','',true))}</div>`; }
async function render() {
    const version=++renderId;
    const requested=location.hash.slice(1)||new URLSearchParams(location.search).get('view')||'overview';
    state.view=['overview','rooms','orders','credit','feedback','messages','admin','account','help','usage'].includes(requested)?requested:'overview';
    if(state.view==='usage')state.view='orders';
    const titles={overview:'预约总览',rooms:'探索空间',orders:'我的预约',credit:'信用记录',feedback:'反馈与申诉',messages:'站内信',admin:'管理工作台',account:'我的账户',help:'使用指南'};
    $('#breadcrumb').textContent=titles[state.view];document.title=`${titles[state.view]} · 研间`;
    document.querySelectorAll('.nav a').forEach(a=>{
        const active=a.dataset.view===state.view;
        a.classList.toggle('active',active);
        if(active) a.setAttribute('aria-current','page');
        else a.removeAttribute('aria-current');
    });
    $('#content').innerHTML='<div class="loading"><span class="spinner"></span>正在加载</div>';
    try {
        let html;
        if(['orders','credit','feedback','messages','admin','account'].includes(state.view)&&!state.identity.authenticated)html=requireIdentityPage(titles[state.view]);
        else html=await ({overview:renderOverview,rooms:renderRooms,orders:renderOrders,credit:renderCredit,feedback:renderFeedback,messages:renderMessages,admin:renderAdmin,account:renderAccount,help:renderHelp}[state.view])();
        if(version!==renderId)return;$('#content').innerHTML=html;
    }catch(error){if(version!==renderId)return;$('#content').innerHTML=heading(titles[state.view],'')+`<div class="panel">${empty('暂时无法加载',error.message,button('重新加载','refresh'))}</div>`;}
}
const activeBookingStatuses = ['PENDING_PAYMENT','PENDING_CHECKIN','IN_USE'];
function overviewMetric(name, value, symbol, href) {
    return `<a class="overview-metric" href="${href}">
        <span class="metric-symbol">${icon(symbol)}</span>
        <span><span class="metric-label">${name}</span><strong>${e(value)}</strong></span>
        ${icon('arrow')}
    </a>`;
}
function overviewBooking(orders, signedIn, ordersUnavailable) {
    const active = orders.filter(order=>activeBookingStatuses.includes(order.booking_status));
    const inUse = active.find(order=>order.booking_status==='IN_USE');
    const next = inUse || active.sort((a,b)=>String(a.start_time).localeCompare(String(b.start_time)))[0];
    if (!next) {
        if (!signedIn) return empty('登录后查看预约进度','在这里查看待支付、待签到和使用中的预约。',button('登录 / 注册','login','',true));
        if (ordersUnavailable) return empty('暂时无法读取预约','请稍后刷新。',button('重新加载','refresh'));
        return empty('暂无进行中的预约','选一间研讨室，安排下一次讨论。',button('查找研讨间','explore','',true));
    }
    const step = activeBookingStatuses.indexOf(next.booking_status);
    const action = [
        ['pay','模拟支付','请在订单的支付截止时间前完成支付。'],
        ['checkin','签到开门','请在订单允许的签到时间内操作。'],
        ['finish','结束使用','结束后，剩余时段会释放。']
    ][step];
    const track = ['已提交','已支付','已签到','已结束'].map((name,index)=>{
        const css = index < step ? 'done' : index === step ? 'current' : '';
        return `<li class="${css}" ${index===step?'aria-current="step"':''}>${name}</li>`;
    }).join('');
    return `<div class="overview-booking">
        <div class="overview-booking-title"><div><small>预约 #${e(next.booking_id)}</small><h3>${e(next.room_name||'研讨间 '+next.room_id)}</h3></div>${badge(next.booking_status)}</div>
        <p class="overview-time">${icon('clock')} ${e(dateText(next.start_time))}–${e(String(next.end_time).slice(11,16))}</p>
        <ol class="overview-track" aria-label="预约进度">${track}</ol>
        <div class="overview-booking-actions"><small>${action[2]}</small>${button(action[1],action[0],`data-id="${e(next.booking_id)}"`,true)}</div>
    </div>`;
}
function compactRoomCard(room) {
    return `<article class="room-card compact-space">
        <span class="metric-symbol">${icon('room')}</span>
        <div class="compact-space-name"><h3>${e(room.room_name)}</h3><p>${e(room.location)}</p></div>
        <span class="compact-space-fact">${icon('users')} ${e(room.capacity)} 人</span>
        <span class="compact-space-fact">${icon('clock')} ${e(room.opening_hours)}</span>
        <button class="btn primary small" data-action="reserve" data-id="${e(room.room_id)}">预约空间</button>
    </article>`;
}
async function renderOverview() {
    const signedIn = state.identity.authenticated;
    const [rooms, orders, credit] = await Promise.allSettled([
        account('/rooms'),
        signedIn ? bookings('/order-data',{format:'json'}) : Promise.resolve([]),
        signedIn ? feedback('/me/credit') : Promise.resolve(null)
    ]);
    state.rooms = rooms.status==='fulfilled' ? rooms.value.rooms : [];
    state.orders = orders.status==='fulfilled' ? orders.value : [];
    const activeCount=state.orders.filter(o=>activeBookingStatuses.includes(o.booking_status)).length;
    const creditScore=credit.status==='fulfilled' ? credit.value?.credit_score : null;
    const recent=state.orders.slice(0,4);
    return `${heading('预约总览','',button(`${icon('plus')}查找研讨间`,'explore','',true))}
        <div class="overview-metrics" aria-label="预约数据">
            ${overviewMetric('开放预约的房间',rooms.status==='fulfilled'?state.rooms.length:'—','room','#rooms')}
            ${overviewMetric('进行中的预约',signedIn&&orders.status==='fulfilled'?activeCount:'—','calendar','#orders')}
            ${overviewMetric('我的信用分',creditScore==null?'—':creditScore,'shield','#credit')}
        </div>
        <div class="overview-grid">
            <section class="panel overview-panel"><div class="overview-panel-head"><h2>预约进度</h2><a class="link-button" href="#orders">我的预约 ${icon('arrow')}</a></div>${overviewBooking(state.orders,signedIn,orders.status==='rejected')}</section>
            <section class="panel overview-panel"><div class="overview-panel-head"><h2>最近预约</h2></div>
                ${recent.length?`<ul class="overview-recent">${recent.map(o=>`<li><div><strong>${e(o.room_name||'研讨间 '+o.room_id)}</strong><small>${e(dateText(o.start_time))}</small></div>${badge(o.booking_status)}</li>`).join('')}</ul>`:empty(signedIn?(orders.status==='rejected'?'暂时无法读取预约':'暂无预约记录'):'登录后查看','')}
            </section>
        </div>
        <section class="panel overview-panel overview-spaces"><div class="overview-panel-head"><h2>开放预约的房间</h2><a class="link-button" href="#rooms">全部空间 ${icon('arrow')}</a></div>
            ${state.rooms.length?state.rooms.slice(0,5).map(compactRoomCard).join(''):empty(rooms.status==='rejected'?'暂时无法加载房间':'暂无开放预约的房间','')}
        </section>`;
}
const availabilityLabels = {AVAILABLE:'空闲',OCCUPIED:'已占用',MAINTENANCE:'维护中',CLOSED:'未开放',UNLISTED:'已下架'};
function filterSelect(name, title, options, value) {
    return `<label>${title}<select name="${name}">${options.map(([key, title]) =>
        `<option value="${e(key)}" ${String(key)===String(value??'')?'selected':''}>${e(title)}</option>`).join('')}</select></label>`;
}
function pageSize(value) {
    return filterSelect('page_size','每页',[[20,'20 条'],[50,'50 条'],[100,'100 条']],value??20);
}
function paging(result, action) {
    const pages = Math.max(1, Math.ceil(result.total/result.page_size));
    return `<div class="catalog-paging"><small>共 ${e(result.total)} 条 · 第 ${e(result.page)} / ${e(pages)} 页</small>
        ${button('上一页',action,`data-id="${result.page-1}" ${result.page<=1?'disabled':''}`)}
        ${button('下一页',action,`data-id="${result.page+1}" ${result.page>=pages?'disabled':''}`)}</div>`;
}
function catalogFilters(id, params, admin=false) {
    return `<form class="panel catalog-filter" id="${id}"><div class="form-grid">
        ${admin?`<label>房间名称<input name="name" value="${e(params.name)}"></label><label>位置<input name="location" value="${e(params.location)}"></label>`:
        `<label>空间或位置<input name="keyword" value="${e(params.keyword)}"></label>`}
        <label>最少人数<input name="min_capacity" type="number" min="1" max="10000" value="${e(params.min_capacity)}"></label>
        ${filterSelect('availability','状态',[['','全部'],...Object.entries(availabilityLabels).filter(([key])=>admin||key!=='UNLISTED')],params.availability)}
        ${filterSelect('sort','排序',[['id','房间编号'],['name','名称'],['capacity','容量从小到大'],['location','位置']],params.sort)}
        ${admin?'':`<label>开始时间<input name="start_time" type="datetime-local" value="${e(params.start_time)}"></label><label>结束时间<input name="end_time" type="datetime-local" value="${e(params.end_time)}"></label>`}
        ${pageSize(params.page_size)}
        </div><div class="form-actions"><button class="btn primary">查询</button></div></form>`;
}
async function renderRooms() {
    const params = state.roomFilters || {};
    const [result] = await Promise.all([account('/catalog',{data:params}),state.identity.authenticated?loadRule():Promise.resolve()]);
    state.rooms = result.items;
    const cards = state.rooms.map(room=>{
        const id = `data-id="${e(room.room_id)}"`;
        const book = room.maintenance_status === 'NORMAL'
            ? button('预约空间','reserve',id,true) : '';
        return `<article class="room-card"><div class="room-visual"><img src="${new URL('room-scene.svg',base)}" alt="研讨间空间示意图"></div><div class="room-body">
            <span class="badge ${e(room.availability)}">${e(availabilityLabels[room.availability])}</span>
            <h3>${e(room.room_name)}</h3><div class="room-location">${icon('pin')}${e(room.location)} · ${e(room.room_id)}</div>
            <div class="room-details"><span>${icon('users')}${e(room.capacity)} 人</span><span>${icon('clock')}${e(room.opening_hours)}</span></div>
            <div class="room-bottom">${button('房间详情','room-details',id)}${book}</div></div></article>`;
    }).join('');
    const timeHint = params.start_time ? '状态按所选时段计算。' : '状态按当前时间计算，也可查询指定时段。';
    return heading('探索空间',timeHint)+catalogFilters('search-form',params)+
        `<section class="room-grid" aria-label="研讨间列表">${cards||empty('暂时没有匹配的空间','试试调整查询条件。')}</section>`+
        paging(result,'catalog-page');
}

async function roomDetails(roomId) {
    if (!mustLogin()) return;
    const room = state.rooms.find(item=>item.room_id===roomId);
    if (!room) throw new Error('房间列表已变化，请刷新后再试。');
    if (!state.rule) await loadRule();
    const id = `data-id="${e(roomId)}"`;
    showModal(room.room_name,`<div class="profile-fields">
        <div><span>位置</span><b>${e(room.location)}</b></div>
        <div><span>房间编号</span><b>${e(roomId)}</b></div>
        <div><span>容量</span><b>${e(room.capacity)} 人</b></div>
        <div><span>开放时间</span><b>${e(room.opening_hours)}</b></div>
        <div><span>当前查询状态</span><b>${e(availabilityLabels[room.availability])}</b></div>
        <div><span>预约费用</span><b>¥${money(state.rule.hourly_price)} / 小时</b></div>
        </div><p class="notice">可提前 ${e(state.rule.advance_days)} 天预约。每人每天最多 ${e(state.rule.daily_slots)} 个时段。</p>
        <div class="form-actions">${button('历史评价','room-reviews',id)}
        ${room.maintenance_status==='NORMAL'?button('预约空间','reserve',id,true):''}</div>`);
}

async function roomReviewHistory(roomId, params = {}) {
    if (!mustLogin()) return;
    const room = state.rooms.find(room=>room.room_id===roomId);
    const data = await feedback('/room-review-history',{data:{...params,room_id:roomId}});
    state.reviewSearch = {roomId, params};
    const scores = [['','全部'],...[1,2,3,4,5].map(n=>[n,`${n} 分`])];
    const reviews = data.items.map(review=>`<article class="record">
        <div class="record-head"><strong>整洁度 ${e(review.score_1)} · 设备 ${e(review.score_2)}</strong><small>${e(dateText(review.created_at))}</small></div>
        ${review.comment?`<p>${e(review.comment)}</p>`:''}</article>`).join('');
    showModal(`${room?.room_name || '研讨间'} · 历史评价`,`<form id="room-review-search" class="form-grid">
        ${filterSelect('score_1','整洁度',scores,params.score_1)}
        ${filterSelect('score_2','设备完好度',scores,params.score_2)}
        <label>起始日期<input name="from" type="date" value="${e(params.from)}"></label>
        <label>截止日期<input name="to" type="date" value="${e(params.to)}"></label>
        ${pageSize(params.page_size)}<button class="btn primary">筛选评价</button></form>
        <div class="room-review-results">${reviews||empty('暂无匹配评价','')}</div>${paging(data,'review-page')}`);
}
async function renderOrders() {
    await loadOrders();const tabs=[['all','全部预约'],['PENDING_PAYMENT','待支付'],['PENDING_CHECKIN','待签到'],['IN_USE','使用中'],['FINISHED','已结束'],['CANCELLED','已取消']];
    const list=state.orders.filter(o=>state.orderTab==='all'||o.booking_status===state.orderTab);
    return heading('我的预约','从预约到结束，随时掌握你的空间安排。',button(`${icon('plus')}预约空间`,'explore'))+`<div class="tabs">${tabs.map(([v,l])=>`<button class="tab ${state.orderTab===v?'active':''}" data-action="order-tab" data-id="${v}">${l} ${state.orders.filter(o=>v==='all'||o.booking_status===v).length}</button>`).join('')}</div><div class="order-list">${list.map(orderCard).join('')||`<div class="panel">${empty('这里还没有预约','选一个合适的空间，安排下一次讨论吧。',button('去看看空间','explore','',true))}</div>`}</div>`;
}
function orderCard(o) {
    const id=`data-id="${e(o.booking_id)}"`;let actions='';let note=`房间编号 ${o.room_id} · 预约规则以订单生成时的版本为准`;
    if(o.booking_status==='PENDING_PAYMENT'){actions=button('取消预约','cancel',id)+button('模拟支付','pay',id,true);note=`请在 ${dateText(o.pay_deadline)} 前完成支付`;}
    if(o.booking_status==='PENDING_CHECKIN'){actions=button('查看退款','refund',id)+button('签到开门','checkin',id,true);note='请在允许的签到时间内操作，时间条件由服务端校验';}
    if(o.booking_status==='IN_USE'){actions=button('重试开门','door',id)+button('结束使用','finish',id,true);note='使用结束后请及时释放空间，并带走个人物品';}
    if(o.booking_status==='FINISHED'){
        if(o.review_state==='OPEN')actions=button('评价空间','review',id,true);
        const reviewStatus={OPEN:`评价截止 ${dateText(o.review_deadline)}`,SUBMITTED:'已提交评价',CLOSED:'评价已关闭'};
        note=o.actual_end_time?`实际结束于 ${dateText(o.actual_end_time)} · ${reviewStatus[o.review_state]||'—'}`:'使用已结束';
    }
    if(o.booking_status==='CANCELLED'){note=o.refund_amount!=null?`已退款 ¥${money(o.refund_amount)}`:'订单已取消；如有异议，可在信用记录中查看违约并申诉';}
    return `<article class="order-card"><div class="order-head"><span class="order-id">预约编号 ${e(o.booking_id)}</span>${badge(o.booking_status)}</div><div class="order-main"><div class="order-room-icon">${icon('room')}</div><div><h3>${e(o.room_name||'研讨间 '+o.room_id)}</h3><p class="order-time">房间 ${e(o.room_id)} · ${e(dateText(o.start_time))} — ${e(String(o.end_time).slice(11,16))}</p></div><div class="order-amount">¥${money(o.total_amount)}<small>${o.pay_amount!=null?'已支付':'应付金额'}</small></div></div><div class="order-end"><p>${e(note)}</p><div class="actions">${actions}</div></div></article>`;
}
async function renderCredit() {
    const data=await feedback('/me/credit');state.credit=data;const score=Number(data.credit_score);
    const ledger=data.ledger.map(item=>`<tr><td>${e(dateText(item.created_at))}</td><td>${e(label(item.reason))}</td><td class="${Number(item.delta_score)>=0?'positive':'negative'}">${Number(item.delta_score)>0?'+':''}${e(item.delta_score)}</td><td>${e(item.score_after??'—')}</td></tr>`).join('');
    const violations=data.violations.map(v=>`<div class="record"><div class="record-head"><h3>${e(label(v.violation_type))}</h3>${Number(v.is_revoked)?badge('APPROVED'):'<span class="badge">扣 '+e(v.credit_deduct)+' 分</span>'}<small>${e(dateText(v.created_at))}</small></div><p>关联预约 ${e(v.booking_id)}${Number(v.is_revoked)?' · 该违约认定已撤销':''}</p>${!Number(v.is_revoked)?`<div class="actions">${button('提交申诉','appeal',`data-id="${e(v.violation_id)}"`)}</div>`:''}</div>`).join('');
    return heading('每一次守约，都有记录','查看信用变化，了解与你有关的违约与处理记录。')+`<div class="split"><div><section class="credit-panel"><div class="credit-label">${icon('shield')}我的信用分</div><div class="credit-score">${score}<small> / 300</small></div><div class="credit-track"><div style="width:${Math.max(0,Math.min(100,score/3))}%"></div></div><p>初始信用为 300 分，信用变化以实际业务记录为准。</p><div class="credit-foot"><p>账号状态：${e(label(data.account_status))}</p>${data.credit_suspended_until?`<p>信用暂停截止：${e(dateText(data.credit_suspended_until))}</p>`:'<p>暂无信用暂停记录</p>'}</div></section></div><section class="panel"><div class="card-top"><h2>信用变动明细</h2><small>最近 100 条</small></div>${ledger?`<div class="table-scroll"><table class="data-table"><thead><tr><th>时间</th><th>变动原因</th><th>变动分值</th><th>变动后</th></tr></thead><tbody>${ledger}</tbody></table></div>`:empty('信用记录从这里开始','履约、违约和人工调整的信用变化会显示在这里。')}</section></div><section class="panel" style="margin-top:23px"><div class="card-top"><h2>违约与申诉</h2><a class="link-button" href="#feedback">查看申诉进度 ${icon('arrow')}</a></div>${violations||empty('暂无违约记录','感谢你共同维护有序的研讨环境。')}</section>`;
}
async function renderFeedback() {
    const tab=state.feedbackTab;const data=await feedback(tab==='appeals'?'/me/appeals':'/me/faults');
    const records=data.map(item=>{const isAppeal=tab==='appeals';return `<article class="record"><div class="record-head"><h3>${isAppeal?'预约 '+e(item.booking_id)+' 的申诉':'房间 '+e(item.room_id)+' 的报障'}</h3>${badge(isAppeal?item.appeal_status:item.fault_status)}<small>${e(dateText(item.created_at))}</small></div><p>${e(isAppeal?item.appeal_reason:item.fault_desc)}</p>${item.admin_opinion?`<p><b>处理意见：</b>${e(item.admin_opinion)}</p>`:''}</article>`;}).join('');
    return heading('你的反馈，让空间更好','上报使用中的问题，查看故障处理与申诉进度。',button(`${icon('plus')}上报故障`,'fault','',true))+`<div class="tabs"><button class="tab ${tab==='faults'?'active':''}" data-action="feedback-tab" data-id="faults">我的报障</button><button class="tab ${tab==='appeals'?'active':''}" data-action="feedback-tab" data-id="appeals">我的申诉</button></div><div class="panel">${records||empty(tab==='appeals'?'暂无申诉记录':'暂无报障记录',tab==='appeals'?'如对违约记录有异议，可从信用记录发起申诉。':'如果使用中遇到设备或环境问题，可以在这里反馈。',tab==='appeals'?'<a class="btn" href="#credit">查看信用记录</a>':'')}</div>`;
}
async function renderMessages() {
    const result=await feedback('/messages',{data:{page:state.messagePage||1}});
    const messages=result.items.map(message=>`<article class="record">
        <div class="record-head"><h3>${e(message.title)}</h3><small>${e(dateText(message.sent_at))}</small></div>
        <p>${e(message.content)}</p>
        ${message.read_at?'<small>已读</small>':button('标为已读','message-read',`data-id="${e(message.message_id)}"`)}
        </article>`).join('');
    return heading('站内信','')+`<section class="panel">${messages||empty('暂无站内信','')}${paging(result,'message-page')}</section>`;
}
async function renderAdmin() {
    if(state.identity.role==='USER')return heading('管理工作台','')+`<div class="panel">${empty('当前账号无管理权限','请使用管理员账号登录。')}</div>`;
    const superAdmin=state.identity.role==='SUPER_ADMIN';
    const options=[['rooms','房间管理'],['faults','故障处理'],['appeals','申诉复核'],...(superAdmin?[['rules','规则配置'],['users','信用与账号']]:[])];
    if(!options.some(([v])=>v===state.adminTab))state.adminTab='rooms';
    let content='';
    if(state.adminTab==='rooms'){
        const params=state.adminRoomFilters||{};
        const result=await account('/admin/catalog',{data:params});
        state.adminRooms=result.items;
        content=`<div class="card-top"><h2>管理范围内的房间</h2>${button(`${icon('plus')}新增房间`,'room-edit','',true)}</div>`+`<div class="table-scroll"><table class="data-table"><thead><tr><th>空间名称</th><th>位置 / 开放时间</th><th>人数</th><th>上架状态</th><th>维护状态</th><th>操作</th></tr></thead><tbody>${state.adminRooms.map(r=>`<tr><td><div class="compact-room">${icon('room')}<div>${e(r.room_name)}<br><small>编号 ${e(r.room_id)}</small></div></div></td><td>${e(r.location)}<br><small>${e(r.opening_hours)}</small></td><td>${e(r.capacity)} 人</td><td>${badge(r.listing_status)}</td><td>${badge(r.maintenance_status)}</td><td><div class="actions"><button class="link-button" data-action="room-edit" data-id="${e(r.room_id)}">编辑</button><button class="link-button" data-action="room-status" data-id="${e(r.room_id)}">状态</button></div></td></tr>`).join('')}</tbody></table></div>`;
        content=catalogFilters('admin-room-search',params,true)+content+paging(result,'admin-catalog-page');
        if(!state.adminRooms.length)content+=empty('暂无匹配房间','请调整筛选条件。');
    }else if(state.adminTab==='faults'||state.adminTab==='appeals'){
        const isAppeal=state.adminTab==='appeals';const data=await feedback(isAppeal?'/admin/appeals':'/admin/faults');state[isAppeal?'appeals':'faults']=data;
        content=`<div class="card-top"><h2>${isAppeal?'申诉复核':'故障处理'}</h2><small>最近 100 条 · 按管理范围显示</small></div>`+data.map(v=>`<article class="record"><div class="record-head"><h3>${isAppeal?'申诉 '+e(v.appeal_id):'故障 '+e(v.fault_id)}</h3>${badge(isAppeal?v.appeal_status:v.fault_status)}<small>${e(dateText(v.created_at))}</small></div><p>${isAppeal?'预约 '+e(v.booking_id)+' · 用户 '+e(v.user_id):'房间 '+e(v.room_id)+' · 用户 '+e(v.user_id)}<br>${e(isAppeal?v.appeal_reason:v.fault_desc)}</p>${v.admin_opinion?`<p>处理意见：${e(v.admin_opinion)}</p>`:''}${(isAppeal?v.appeal_status==='PENDING':v.fault_status!=='RESOLVED')?`<div class="actions">${button(isAppeal?'复核申诉':'处理故障',isAppeal?'process-appeal':'process-fault',`data-id="${e(isAppeal?v.appeal_id:v.fault_id)}"`,true)}</div>`:''}</article>`).join('');
        if(!data.length)content+=empty('暂无待查看记录','新的报障或申诉会出现在这里。');
    }else if(state.adminTab==='rules'){
        await loadRule();const r=state.rule;
        const definitions=[['advance_days','可提前预约天数',1,7],['daily_slots','单人每日时段上限',1,24],['hourly_price','每小时价格（元）',0,10000],['refund_percent','24小时内退款比例（%）',0,100],['checkin_early_minutes','可提前签到（分钟）',0,60],['checkin_late_minutes','可迟到签到（分钟）',0,60],['timeout_deduct','未签到扣分',0,300],['early_release_deduct','提前结束扣分',0,300],['credit_suspend_days','信用暂停天数',3,7],['review_window_hours','评价窗口（小时）',1,168]];
        content=`<div class="card-top"><h2>预约与使用规则</h2><small>当前版本 ${e(r.rule_version_id)}</small></div><p class="notice">发布后生成新版本，已生成的订单仍按原规则执行。</p><form id="rules-form"><input type="hidden" name="rule_version_id" value="${e(r.rule_version_id)}"><div class="form-grid">${definitions.map(([key,title,min,max])=>`<label>${title}<input type="number" name="${key}" value="${e(r[key])}" min="${min}" max="${max}" step="${key==='hourly_price'?'0.01':'1'}" required></label>`).join('')}</div><div class="form-actions"><button class="btn primary">发布新规则</button></div></form>`;
    }else{
        content=`<div class="card-top"><h2>信用与账号管理</h2></div><form id="user-query" class="toolbar"><input name="user_id" placeholder="输入用户编号" aria-label="用户编号" required pattern="[0-9]+"><button class="btn primary">查询用户</button></form><div id="user-result" aria-live="polite"><p class="muted">查询用户后，可查看信用、冻结或解冻账号，并调整信用分。</p></div>`;
    }
    return heading('管理工作台','维护空间，处理反馈，让共享服务有序运转。',button(`${icon('refresh')}刷新`,'refresh'))+`<div class="tabs">${options.map(([v,l])=>`<button class="tab ${state.adminTab===v?'active':''}" data-action="admin-tab" data-id="${v}">${l}</button>`).join('')}</div><section class="panel">${content}</section>`;
}
function adminCreditDetails(data) {
    const id=`data-id="${e(data.user_id)}"`;
    const ledger=data.ledger.map(row=>`<li>${e(dateText(row.created_at))} ·
        ${e(label(row.reason))} · ${e(row.delta_score)} 分 · 余额 ${e(row.score_after??'—')}</li>`).join('');
    const violations=data.violations.map(row=>`<li>${e(dateText(row.created_at))} ·
        ${e(label(row.violation_type))} · 预约 ${e(row.booking_id)} · 扣 ${e(row.credit_deduct)} 分
        ${Number(row.is_revoked)?' · 已撤销':''}</li>`).join('');
    return `<p class="notice">用户 ${e(data.user_id)} · 信用分 ${e(data.credit_score)} · ${e(label(data.account_status))}
        ${data.credit_suspended_until?` · 预约暂停截止 ${e(dateText(data.credit_suspended_until))}`:''}</p>
        <div class="admin-links">${data.account_status==='ACTIVE'?button('冻结账号','freeze',id):button('解冻账号','unfreeze',id)}
        ${button('调整信用分','adjust-credit',id,true)}</div>
        <div class="admin-credit-records"><h3>信用变动明细</h3>${ledger?`<ul>${ledger}</ul>`:'<p>暂无变动明细</p>'}
        <h3>违规历史</h3>${violations?`<ul>${violations}</ul>`:'<p>暂无违规记录</p>'}</div>`;
}
function renderAccount() { const id=state.identity;return heading('我的账户','管理登录状态与账户安全。')+`<section class="panel profile-card"><div class="compact-room"><div class="avatar">${icon('user')}</div><h2>${e(label(id.role))}</h2></div><div class="profile-fields"><div><span>用户编号</span><b>${e(id.user_id)}</b></div><div><span>账号角色</span><b>${e(label(id.role))}</b></div></div><div class="form-actions">${button('找回 / 重置密码','reset')}${button('退出登录','logout')}</div></section>`; }
function renderHelp() { return heading('从这里，开始一次好讨论','预约、使用与反馈的简明指南。')+`<section class="panel"><div class="step-list">${[['登录与选房','注册或登录账号后，按位置、人数搜索空间，选择房间并查看开放时间。'],['预约与支付','选择允许预约日期内的整点时段。提交后在付款截止前完成模拟支付，未支付可直接取消。'],['签到与使用','到达约定时间后，从“我的预约”签到开门。门禁失败可重试；使用结束后点击“结束使用”，释放空间。'],['退款与信用','已支付且尚未开始的订单可先查看退款金额，再确认退款。信用变动与违约详情可在“信用记录”查看。'],['评价与反馈','已完成的订单可在评价期限内提交一次评价。遇到故障可报修；如对违约有异议，可在对应记录上提交申诉并跟踪进度。']].map(([t,p],i)=>`<div><span class="step-number">${i+1}</span><div><h3>${t}</h3><p>${p}</p></div></div>`).join('')}</div></section>`; }

async function openAuth(tab='login') {
    try { state.csrf=(await account('/token')).csrf; }
    catch(error) { toast(error.message,true);return; }
    const tabs=`<div class="modal-tabs">${[['login','账号登录'],['register','注册账号'],['reset','找回密码']].map(([v,l])=>`<button class="tab ${v===tab?'active':''}" data-action="auth-tab" data-id="${v}">${l}</button>`).join('')}</div>`;
    let form;
    if(tab==='reset')form=`<form id="reset-request-form" class="form-stack"><label>用户名<input name="username" autocomplete="username" required></label><label>绑定手机号<input name="phone" type="tel" autocomplete="tel" pattern="1[3-9][0-9]{9}" required></label><button class="btn primary">获取模拟验证码</button></form><div id="reset-code" class="notice" hidden style="margin-top:17px"></div><form id="reset-confirm-form" class="form-stack" style="margin-top:20px"><label>验证码<input name="code" inputmode="numeric" pattern="[0-9]{6}" required></label><label>新密码<input name="new_password" type="password" autocomplete="new-password" minlength="8" maxlength="72" placeholder="8—72位，同时包含字母和数字" required></label><button class="btn">确认重置密码</button></form>`;
    else form=`<form id="auth-form" class="form-stack" data-mode="${tab}"><label>用户名<input name="username" autocomplete="username" placeholder="3—32位字母、数字或下划线" pattern="[A-Za-z0-9_]{3,32}" required></label>${tab==='register'?'<label>手机号<input name="phone" type="tel" autocomplete="tel" pattern="1[3-9][0-9]{9}" placeholder="用于找回密码" required></label>':''}<label>密码<input name="password" type="password" autocomplete="${tab==='register'?'new-password':'current-password'}" ${tab==='register'?'minlength="8" maxlength="72"':''} placeholder="${tab==='register'?'8—72位，同时包含字母和数字':'请输入密码'}" required></label><button class="btn primary">${tab==='register'?'注册并开始使用':'登录'}</button><p class="form-note">${tab==='register'?'注册成功后将自动登录。':'普通用户与管理员共用此登录入口。'}</p></form>`;
    showModal(tab==='register'?'欢迎加入研间':tab==='reset'?'找回你的账户':'欢迎回到研间',tabs+form);
}
async function reserve(roomId) {
    if(!mustLogin())return;await loadRule();const room=state.rooms.find(r=>r.room_id===roomId);if(!room)throw new Error('房间信息已变化，请刷新');state.selectedRoom=room;
    const now=new Date();const localDate=now.toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'});const maxDate=new Date(now);maxDate.setDate(maxDate.getDate()+Number(state.rule.advance_days));
    const hours=room.opening_hours.split('-');const minutes=h=>Number(h.split(':')[0])*60+Number(h.split(':')[1]);const first=Math.ceil(minutes(hours[0])/60);const last=Math.floor(minutes(hours[1])/60);
    const nextHour=Number(new Intl.DateTimeFormat('en-GB',{hour:'2-digit',hour12:false,timeZone:'Asia/Shanghai'}).format(now))+1;
    const initialHour=Math.max(first,nextHour)<last?Math.max(first,nextHour):first;
    const selectedDate=Math.max(first,nextHour)<last?localDate:new Date(now.getTime()+86400000).toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'});
    const selectHours=(name,end=false)=>`<select name="${name}" required>${Array.from({length:Math.max(0,last-first)},(_,i)=>first+i+(end?1:0)).map(n=>`<option value="${String(n).padStart(2,'0')}:00" ${n===initialHour+(end?1:0)?'selected':''}>${String(n).padStart(2,'0')}:00</option>`).join('')}</select>`;
    showModal('预约研讨空间',`<div class="summary">${icon('room')}<div><h3>${e(room.room_name)}</h3><p>${e(room.location)} · ${e(room.capacity)} 人<br>开放时间 ${e(room.opening_hours)}</p></div></div><form id="reserve-form"><input type="hidden" name="room_id" value="${e(roomId)}"><div class="form-grid"><label class="full">预约日期<input name="date" type="date" min="${localDate}" max="${maxDate.toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'})}" value="${selectedDate}" required></label><label>开始时间${selectHours('start')}</label><label>结束时间${selectHours('end',true)}</label></div><p class="form-note" style="margin-top:16px">每人每天最多 ${e(state.rule.daily_slots)} 个时段，需选择整点时间。付款期限、冲突与金额均由服务端确定。</p><div class="price-preview"><span>时段费用预估</span><strong id="price-preview">¥${money(state.rule.hourly_price)}</strong></div><div class="form-actions"><button type="button" class="btn" data-action="close-modal">取消</button><button class="btn primary">确认预约</button></div></form>`);
}
async function orderAction(action,id) {
    if(!mustLogin())return;const order=state.orders.find(o=>o.booking_id===id);if(!order)throw new Error('订单信息已变化，请刷新');const summary=`<div class="summary">${icon('calendar')}<div><h3>${e(order.room_name)}</h3><p>${e(dateText(order.start_time))} — ${e(String(order.end_time).slice(11,16))}</p></div></div>`;
    if(action==='review'){
        showModal('评价这次研讨空间',summary+`<form id="review-form"><input type="hidden" name="booking_id" value="${e(id)}"><div class="form-grid"><label>空间整洁度<select name="score_1">${[5,4,3,2,1].map(v=>`<option>${v}</option>`).join('')}</select></label><label>设备完好度<select name="score_2">${[5,4,3,2,1].map(v=>`<option>${v}</option>`).join('')}</select></label><label class="full">使用感受<textarea name="comment" maxlength="500" placeholder="分享你的使用体验（选填）"></textarea></label></div><p class="rating-note">每项 1—5 分，一单可评价一次，须在评价期限内提交。</p><div class="form-actions"><button class="btn primary">提交评价</button></div></form>`);return;
    }
    let description={pay:`将模拟支付 ¥${money(order.total_amount)}，支付成功后即可按规则签到。`,cancel:'取消后将立即释放尚未支付的预约时段。',checkin:'确认你已到达研讨间。签到时间条件由服务端校验，课程门禁将模拟开门。',door:'仅对失败的开门请求重试，不重复签到或变更信用。',finish:'结束后将释放预约空间，你可以在评价期限内分享使用感受。'}[action];
    if(action==='refund')description=(await bookings('/quote',{data:{booking_id:id}})).trim()+'。确认后执行模拟退款并释放预约时段。';
    const title={pay:'确认模拟支付',cancel:'取消这次预约',checkin:'签到并开门',door:'重试开门',finish:'结束本次使用',refund:'确认退款'}[action];
    showModal(title,summary+`<p class="notice">${e(description)}</p><form id="order-action-form" data-mode="${action}"><input type="hidden" name="booking_id" value="${e(id)}"><div class="form-actions"><button type="button" class="btn" data-action="close-modal">返回</button><button class="btn primary">${action==='cancel'?'确认取消':'确认操作'}</button></div></form>`);
}
function faultModal() {
    if(!mustLogin())return;
    showModal('上报空间故障',`<form id="fault-form"><div class="form-grid"><label class="full">房间编号<input name="room_id" inputmode="numeric" pattern="[0-9]+" placeholder="填写需要报修的房间编号" required><small>可在房间管理或对应预约中查看房间编号。</small></label><label class="full">故障描述<textarea name="fault_desc" maxlength="500" placeholder="请描述设备、环境或网络问题，方便房管处理。" required></textarea></label></div><div class="form-actions"><button class="btn primary">提交报障</button></div></form>`);
}
function appealModal(id) {
    const v=state.credit?.violations.find(v=>v.violation_id===id);if(!v)throw new Error('请刷新信用记录');
    showModal('提交违约申诉',`<div class="notice">预约 ${e(v.booking_id)} · ${e(label(v.violation_type))} · 记录扣分 ${e(v.credit_deduct)}</div><form id="appeal-form"><input type="hidden" name="booking_id" value="${e(v.booking_id)}"><input type="hidden" name="violation_id" value="${e(id)}"><div class="form-grid"><label class="full">申诉理由<textarea name="appeal_reason" maxlength="500" placeholder="请说明发生的情况和申请复核的原因。" required></textarea></label></div><div class="form-actions"><button class="btn primary">提交申诉</button></div></form>`);
}
function editRoom(id) {
    const room=state.adminRooms.find(r=>r.room_id===id)||{};
    showModal(id?'编辑研讨空间':'新增研讨空间',`<form id="room-form"><div class="form-grid"><input type="hidden" name="room_id" value="${e(room.room_id||'')}"><label class="full">空间名称<input name="room_name" maxlength="80" value="${e(room.room_name)}" required></label><label class="full">位置<input name="location" maxlength="160" value="${e(room.location)}" required></label><label>容纳人数<input name="capacity" type="number" min="1" max="10000" value="${e(room.capacity||4)}" required></label><label>开放时间<input name="opening_hours" pattern="[0-9]{2}:[0-9]{2}-[0-9]{2}:[0-9]{2}" value="${e(room.opening_hours||'08:00-22:00')}" required></label>${state.identity.role==='SUPER_ADMIN'?`<label class="full">负责管理员编号<input name="manager_user_id" inputmode="numeric" pattern="[0-9]+" value="${e(room.manager_user_id)}" placeholder="填写可用管理员编号；可留空"></label>`:''}</div><div class="form-actions"><button class="btn primary">保存空间</button></div></form>`);
}
function statusRoom(id) { const room=state.adminRooms.find(r=>r.room_id===id);if(!room)throw new Error('请刷新房间列表');showModal('调整空间状态',`<div class="summary">${icon('room')}<h3>${e(room.room_name)}</h3></div><form id="room-status-form"><input type="hidden" name="room_id" value="${e(id)}"><div class="form-grid"><label>上架状态<select name="listing_status"><option value="LISTED" ${room.listing_status==='LISTED'?'selected':''}>已上架</option><option value="UNLISTED" ${room.listing_status==='UNLISTED'?'selected':''}>已下架</option></select></label><label>维护状态<select name="maintenance_status"><option value="NORMAL" ${room.maintenance_status==='NORMAL'?'selected':''}>正常</option><option value="MAINTENANCE" ${room.maintenance_status==='MAINTENANCE'?'selected':''}>维护中</option></select></label></div><p class="form-note" style="margin-top:15px">上架和维护状态分别保存，已有预约将保留。存在故障维护时，恢复正常仍需完成故障处理。</p><div class="form-actions"><button class="btn primary">保存状态</button></div></form>`); }
function processRecord(kind,id) {
    const appeal=kind==='appeal';const item=state[appeal?'appeals':'faults'].find(v=>String(v[appeal?'appeal_id':'fault_id'])===id);if(!item)throw new Error('请刷新管理记录');
    showModal(appeal?'复核用户申诉':'处理空间故障',`<p class="notice">${e(appeal?item.appeal_reason:item.fault_desc)}</p><form id="process-record-form" data-kind="${kind}"><input type="hidden" name="${appeal?'appeal_id':'fault_id'}" value="${e(id)}"><div class="form-grid"><label class="full">处理结论<select name="${appeal?'decision':'fault_status'}">${appeal?'<option value="APPROVED">通过申诉</option><option value="REJECTED">驳回申诉</option>':item.fault_status==='PENDING'?'<option value="REPAIRING">标记维修中</option>':'<option value="RESOLVED">维修完成</option>'}</select></label><label class="full">处理意见<textarea name="admin_opinion" maxlength="500" required placeholder="请填写可向用户说明的处理意见。"></textarea></label></div>${appeal?'<p class="form-note" style="margin-top:15px">通过后将撤销对应违约，按实际扣分冲正信用，并办理应退的模拟费用。</p>':''}<div class="form-actions"><button class="btn primary">确认提交</button></div></form>`);
}

document.addEventListener('click',async event=>{
    const node=event.target.closest('[data-action]');if(!node||node.disabled)return;const action=node.dataset.action,id=node.dataset.id;
    try {
        if(action==='login')openAuth();else if(action==='reset')openAuth('reset');else if(action==='auth-tab')openAuth(id);else if(action==='close-modal')closeModal();
        else if(action==='refresh')await render();else if(action==='explore')location.hash='rooms';
        else if(action==='reserve')await reserve(id);
        else if(action==='catalog-page'){state.roomFilters={...state.roomFilters,page:id};await render();}
        else if(action==='admin-catalog-page'){state.adminRoomFilters={...state.adminRoomFilters,page:id};await render();}
        else if(action==='room-reviews')await roomReviewHistory(id);
        else if(action==='room-details')await roomDetails(id);
        else if(action==='message-page'){state.messagePage=id;await render();}
        else if(action==='message-read'){await postFeedback('/messages/read',{message_id:id});await render();}
        else if(action==='review-page'&&state.reviewSearch)await roomReviewHistory(state.reviewSearch.roomId,{...state.reviewSearch.params,page:id});
        else if(['pay','cancel','refund','checkin','door','finish','review'].includes(action))await orderAction(action,id);
        else if(action==='order-tab'){state.orderTab=id;await render();}
        else if(action==='feedback-tab'){state.feedbackTab=id;await render();}
        else if(action==='admin-tab'){state.adminTab=id;await render();}
        else if(action==='fault')faultModal();else if(action==='appeal')appealModal(id);
        else if(action==='room-edit')editRoom(id);else if(action==='room-status')statusRoom(id);
        else if(action==='process-appeal')processRecord('appeal',id);else if(action==='process-fault')processRecord('fault',id);
        else if(action==='logout'){await postAccount('/logout',{});await refreshIdentity();state.orders=[];state.credit=null;location.hash='rooms';await render();toast('已退出登录');}
        else if(action==='freeze'||action==='unfreeze'){
            const title=action==='freeze'?'确认冻结账号':'确认解冻账号';
            const impact=action==='freeze'?'冻结后该用户无法登录或提交新预约。':'解冻后仍需满足信用资格和暂停期限。';
            showModal(title,`<p class="notice">用户 ${e(id)}。${impact}</p><form id="freeze-confirm">
                <input type="hidden" name="user_id" value="${e(id)}"><input type="hidden" name="action" value="${action}">
                <div class="form-actions"><button type="button" class="btn" data-action="close-modal">返回</button><button class="btn primary">${title}</button></div></form>`);
        }
        else if(action==='adjust-credit')showModal('人工调整信用',`<form id="adjust-credit-form"><input name="user_id" type="hidden" value="${e(id)}"><input name="event_id" type="hidden" value="${e(crypto.randomUUID())}"><div class="form-grid"><label class="full">调整分值<input name="delta" type="number" min="-300" max="300" placeholder="正数加分，负数扣分" required></label><label class="full">调整原因<textarea name="reason" maxlength="500" required></textarea></label></div><div class="form-actions"><button class="btn primary">确认调整</button></div></form>`);
    }catch(error){if($('#modal').open)formError(error.message);else toast(error.message,true);}
});
document.addEventListener('submit',async event=>{
    const form=event.target;if(!(form instanceof HTMLFormElement))return;event.preventDefault();const data=fields(form);const controls=[...form.querySelectorAll('button')];controls.forEach(b=>b.disabled=true);
    try {
        if(form.id==='search-form'){
            if(Boolean(data.start_time)!==Boolean(data.end_time)||data.start_time&&data.start_time>=data.end_time)
                throw new Error('请同时填写开始和结束时间，结束时间须晚于开始时间。');
            state.roomFilters=data;await render();return;
        }
        if(form.id==='admin-room-search'){state.adminRoomFilters=data;await render();return;}
        if(form.id==='room-review-search'&&state.reviewSearch){await roomReviewHistory(state.reviewSearch.roomId,data);return;}
        if(form.id==='auth-form'){const result=await postAccount('/'+form.dataset.mode,data);await refreshIdentity();closeModal();await render();toast(result.message);return;}
        if(form.id==='reset-request-form'){const result=await postAccount('/password-reset/request',data);$('#reset-code').hidden=false;$('#reset-code').textContent=`${result.message} · 本次模拟验证码：${result.demo_code}`;return;}
        if(form.id==='reset-confirm-form'){const result=await postAccount('/password-reset/confirm',data);openAuth('login');toast(result.message);return;}
        if(form.id==='reserve-form'){
            if(data.end<=data.start)throw new Error('结束时间必须晚于开始时间');
            const result=await postBooking('/orders',{room_id:data.room_id,start_time:`${data.date}T${data.start}:00`,end_time:`${data.date}T${data.end}:00`});closeModal();state.orderTab='all';location.hash='orders';await render();toast(result.trim());return;
        }
        if(form.id==='order-action-form'){
            const action=form.dataset.mode;const result=['pay','cancel','refund'].includes(action)?await postBooking('/'+action,data):await postFeedback('/'+(action==='door'?'door/retry':action),data);
            closeModal();await render();toast(typeof result==='string'?result.trim():{checkin:'签到成功，模拟门禁已开门',door:'开门状态已确认',finish:'使用已结束，空间已释放'}[action]);return;
        }
        if(form.id==='review-form'){await postFeedback('/review',data);closeModal();toast('评价已提交，感谢你的反馈');return;}
        if(form.id==='fault-form'){await postFeedback('/fault',data);closeModal();state.feedbackTab='faults';location.hash='feedback';await render();toast('报障已提交，请留意处理进度');return;}
        if(form.id==='appeal-form'){await postFeedback('/appeal',data);closeModal();state.feedbackTab='appeals';location.hash='feedback';await render();toast('申诉已提交，请留意复核结果');return;}
        if(form.id==='room-form'){await postAccount('/admin/rooms/save',data);closeModal();await render();toast('空间信息已保存');return;}
        if(form.id==='room-status-form'){
            const room=state.adminRooms.find(item=>item.room_id===data.room_id);
            if (!room) throw new Error('房间列表已变化，请刷新后再试。');
            const impact=data.listing_status==='UNLISTED'||data.maintenance_status==='MAINTENANCE'
                ? '此状态将阻止新预约，已有预约保留。' : '房间可接受新预约，实际空闲时段以订单校验为准。';
            showModal('确认调整房间状态',`<p class="notice">${e(room.room_name)} · ${e(label(data.listing_status))}
                · ${e(label(data.maintenance_status))}。${impact}</p><form id="room-status-confirm">
                ${Object.entries(data).map(([key,value])=>`<input type="hidden" name="${e(key)}" value="${e(value)}">`).join('')}
                <div class="form-actions"><button type="button" class="btn" data-action="room-status" data-id="${e(room.room_id)}">返回</button>
                <button class="btn primary">确认更新</button></div></form>`);
            return;
        }
        if(form.id==='room-status-confirm'){await postAccount('/admin/rooms/status',data);closeModal();await render();toast('空间状态已更新');return;}
        if(form.id==='process-record-form'){await postFeedback('/admin/'+form.dataset.kind,data);closeModal();await render();toast('处理结果已保存');return;}
        if(form.id==='rules-form'){await postBooking('/admin/rules',data);await render();toast('新规则已发布');return;}
        if(form.id==='user-query'){
            const details=await feedback('/admin/user-credit',{data});
            const result=$('#user-result');
            if(result&&form.isConnected)result.innerHTML=adminCreditDetails(details);
            return;
        }
        if(form.id==='freeze-confirm'){await postBooking('/admin/freeze',data);closeModal();toast('账号状态已更新');$('#user-query')?.requestSubmit();return;}
        if(form.id==='adjust-credit-form'){await postBooking('/admin/credit',data);closeModal();toast('信用调整已记账');if($('#user-query'))$('#user-query').requestSubmit();return;}
    }catch(error){if($('#modal').open)formError(error.message);else toast(error.message,true);}
    finally{controls.forEach(b=>b.disabled=false);}
});
document.addEventListener('change',event=>{const form=event.target.closest('#reserve-form');if(form){const data=fields(form);const duration=Number(data.end?.slice(0,2))-Number(data.start?.slice(0,2));$('#price-preview').textContent=duration>0?'¥'+money(duration*Number(state.rule.hourly_price)):'请选择有效时段';}});
$('#modal').addEventListener('click',event=>{if(event.target===$('#modal')){const rect=$('#modal').getBoundingClientRect();if(event.clientX<rect.left||event.clientX>rect.right||event.clientY<rect.top||event.clientY>rect.bottom)closeModal();}});
window.addEventListener('hashchange',()=>render());
$('#today').textContent=new Intl.DateTimeFormat('zh-CN',{year:'numeric',month:'long',day:'numeric',weekday:'long',timeZone:'Asia/Shanghai'}).format(new Date());
(async()=>{try{await refreshIdentity();}catch(error){toast(error.message,true);}await render();})();
