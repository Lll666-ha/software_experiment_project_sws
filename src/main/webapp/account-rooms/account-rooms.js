const api = '../api/account-rooms';
let csrf = '';
let identity = { authenticated: false };
const messageBox = document.querySelector('#message');

async function request(path, options = {}) {
    const response = await fetch(`${api}${path}`, options);
    let body;
    try { body = await response.json(); } catch { body = { message: `服务器返回内容无效（HTTP ${response.status}）` }; }
    if (!response.ok) throw new Error(body.message || `请求失败（HTTP ${response.status}）`);
    return body;
}

function show(text, ok = true) { messageBox.textContent = text; messageBox.className = ok ? 'ok' : 'danger'; }
async function post(path, form) {
    const data = form instanceof FormData ? form : new FormData(form);
    data.set('csrf', csrf);
    return request(path, { method: 'POST', body: new URLSearchParams(data) });
}

async function refreshSession() {
    identity = await request('/session');
    document.querySelector('#session').textContent = identity.authenticated ? `已登录：用户编号 ${identity.user_id}，角色 ${identity.role}` : '当前未登录';
    const admin = identity.role === 'ROOM_ADMIN' || identity.role === 'SUPER_ADMIN';
    document.querySelector('#admin').classList.toggle('hidden', !admin);
    document.querySelectorAll('.super-only').forEach(node => node.classList.toggle('hidden', identity.role !== 'SUPER_ADMIN'));
    if (admin) await loadAdminRooms();
}

function roomRows(target, rooms, admin = false) {
    const tbody = document.querySelector(target); tbody.replaceChildren();
    for (const room of rooms) {
        const row = document.createElement('tr');
        const values = [room.room_id, room.room_name, room.location, room.capacity, room.opening_hours];
        if (admin) values.push(room.listing_status, room.maintenance_status, room.manager_user_id ?? '未分配');
        for (const value of values) { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); }
        tbody.append(row);
    }
    if (!rooms.length) { const row = document.createElement('tr'), cell = document.createElement('td'); cell.colSpan = admin ? 8 : 5; cell.textContent = '没有符合条件的房间'; row.append(cell); tbody.append(row); }
}

async function loadRooms(form = document.querySelector('#search')) {
    roomRows('#rooms', (await request(`/rooms?${new URLSearchParams(new FormData(form))}`)).rooms);
}
async function loadAdminRooms() { roomRows('#admin-rooms', (await request('/admin/rooms')).rooms, true); }

document.querySelectorAll('form[data-api]').forEach(form => form.addEventListener('submit', async event => {
    event.preventDefault(); try { show((await post(`/${form.dataset.api}`, form)).message); await refreshSession(); } catch (error) { show(error.message, false); }
}));
document.querySelector('#logout').addEventListener('click', async () => {
    try { show((await post('/logout', new FormData())).message); csrf = (await request('/token')).csrf; await refreshSession(); } catch (error) { show(error.message, false); }
});
document.querySelector('#reset-request').addEventListener('submit', async event => {
    event.preventDefault(); try { const body = await post('/password-reset/request', event.target); show(`${body.message}；演示验证码：${body.demo_code}`); } catch (error) { show(error.message, false); }
});
document.querySelector('#reset-confirm').addEventListener('submit', async event => {
    event.preventDefault(); try { show((await post('/password-reset/confirm', event.target)).message); } catch (error) { show(error.message, false); }
});
document.querySelector('#search').addEventListener('submit', async event => {
    event.preventDefault(); try { await loadRooms(event.target); show('查询完成'); } catch (error) { show(error.message, false); }
});
document.querySelector('#room-save').addEventListener('submit', async event => {
    event.preventDefault(); try { show((await post('/admin/rooms/save', event.target)).message); event.target.reset(); await loadAdminRooms(); await loadRooms(); } catch (error) { show(error.message, false); }
});
document.querySelector('#room-status').addEventListener('submit', async event => {
    event.preventDefault(); try { show((await post('/admin/rooms/status', event.target)).message); await loadAdminRooms(); await loadRooms(); } catch (error) { show(error.message, false); }
});
document.querySelector('#admin-refresh').addEventListener('click', () => loadAdminRooms().catch(error => show(error.message, false)));

(async () => { try { csrf = (await request('/token')).csrf; await refreshSession(); await loadRooms(); } catch (error) { show(error.message, false); } })();
