const API = 'https://mzaai.ir/api/app.php';
let token = '';
let convId = 0;
let sending = false;
function $(id){ return document.getElementById(id); }
function showError(text){ const el = $('authError'); el.textContent = text || ''; el.classList.toggle('visible', !!text); }
function api(action, body){
  if (window.MzaApi) return JSON.parse(MzaApi.call(action, JSON.stringify(body || {}), token || ''));
  return fetch(API + '?action=' + action, {method:'POST', headers:{'Content-Type':'application/json','Authorization': token ? 'Bearer ' + token : ''}, body: JSON.stringify(body || {})}).then(r => r.json());
}
function setLogged(on){ document.body.classList.toggle('logged', on); document.body.classList.toggle('auth-guest-body', !on); document.body.classList.toggle('app-mode', on); }
function renderUser(user){ if (!user) return; $('planName').textContent = user.plan_name || 'رایگان'; $('planName2').textContent = user.plan_name || 'رایگان'; $('planSub').textContent = (user.remaining || 0) + ' واحد'; $('remCount').textContent = user.remaining || 0; $('remMax').textContent = user.daily_limit || 0; const pct = user.daily_limit ? Math.min(100, ((user.daily_used || 0) / user.daily_limit) * 100) : 0; $('remProgressBar').style.width = pct + '%'; }
function renderConvs(items){ const box = $('convList'); box.innerHTML = ''; (items || []).forEach(item => { const div = document.createElement('div'); div.className = 'conv-item' + (item.id === convId ? ' active' : ''); div.textContent = item.title || 'گفتگو'; div.onclick = () => openChat(item.id); box.appendChild(div); }); }
function bubble(role, text){ const div = document.createElement('div'); div.className = 'message ' + (role === 'bot' ? 'bot-msg' : 'user-msg'); div.textContent = text || ''; return div; }
function renderMessages(items){ const box = $('chatMessages'); box.innerHTML = ''; if (!items || !items.length){ box.innerHTML = '<div class="welcome"><h2>سلام! من مزور هستم</h2><p>یک پیام بنویس.</p></div>'; return; } items.forEach(item => box.appendChild(bubble(item.role, item.content))); box.scrollTop = box.scrollHeight; }
async function refresh(){ const me = await api('me'); if (me.user) renderUser(me.user); const conv = await api('conversations'); renderConvs(conv.items || []); }
async function login(){ showError(''); const res = await api('login', {username: $('loginUsername').value.trim(), password: $('loginPassword').value}); if (!res.success){ showError(res.error || 'ورود نشد'); return; } token = res.token; if (window.MzaStore) MzaStore.save(token); setLogged(true); renderUser(res.user); await refresh(); }
async function openChat(id){ convId = id; $('sidebar').classList.remove('open'); $('sidebarOverlay').classList.remove('active'); const res = await api('messages', {conv_id: id}); renderMessages(res.items || []); await refresh(); }
async function createChat(){ const res = await api('create_conversation'); if (res.conversation) await openChat(res.conversation.id); }
async function send(){ const input = $('messageInput'); const message = input.value.trim(); if (!message || sending) return; if (!convId) await createChat(); if (!convId) return; sending = true; input.value = ''; const box = $('chatMessages'); box.appendChild(bubble('user', message)); const wait = bubble('bot', 'در حال نوشتن...'); box.appendChild(wait); box.scrollTop = box.scrollHeight; try { const res = await api('send', {conv_id: convId, message}); wait.textContent = res.success ? res.reply : (res.error || 'جواب نیامد'); if (res.user) renderUser(res.user); await refresh(); } catch (e) { wait.textContent = 'خطای شبکه'; } sending = false; box.scrollTop = box.scrollHeight; }
$('loginBtn').onclick = login;
$('loginPassword').addEventListener('keydown', e => { if (e.key === 'Enter') login(); });
$('sendButton').onclick = send;
$('messageInput').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey){ e.preventDefault(); send(); } });
$('newChatBtn').onclick = createChat;
$('logoutBtn').onclick = () => { token = ''; if (window.MzaStore) MzaStore.clear(); setLogged(false); };
$('menuToggle').onclick = () => { $('sidebar').classList.add('open'); $('sidebarOverlay').classList.add('active'); };
$('sidebarOverlay').onclick = () => { $('sidebar').classList.remove('open'); $('sidebarOverlay').classList.remove('active'); };
$('buyBtn').onclick = () => { if (window.MzaAndroid) MzaAndroid.purchase('pup_plan'); };
if (window.MzaStore && MzaStore.token()) { token = MzaStore.token(); setLogged(true); refresh().catch(() => setLogged(false)); }
