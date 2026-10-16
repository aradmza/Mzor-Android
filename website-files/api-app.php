<?php
/**
 * API اپ بومی مزور
 * این فایل را در مسیر api/app.php روی سرور بگذار.
 * قبلش در includes/backend.php شرط هندلر وب باید با MZA_APP_API محافظت شده باشد.
 */
define('MZA_APP_API', true);
require __DIR__ . '/../includes/config.php';
require __DIR__ . '/../includes/backend.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function appOut(array $data, int $code = 200): void {
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}
function appBody(): array {
    $raw = file_get_contents('php://input');
    $json = json_decode((string)$raw, true);
    return is_array($json) ? $json : $_POST;
}
function appEnsureTokens(): void {
    getDB()->exec("CREATE TABLE IF NOT EXISTS app_tokens (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL,
        token_hash CHAR(64) NOT NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        last_used DATETIME DEFAULT NULL,
        UNIQUE KEY uniq_token (token_hash),
        KEY idx_user (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
}
function appUserFromToken(): ?array {
    $header = (string)($_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '');
    if (!preg_match('/Bearer\s+([A-Za-z0-9._-]{20,})/', $header, $m)) return null;
    appEnsureTokens();
    $hash = hash('sha256', $m[1]);
    $stmt = getDB()->prepare('SELECT user_id FROM app_tokens WHERE token_hash = ? LIMIT 1');
    $stmt->execute([$hash]);
    $uid = (int)$stmt->fetchColumn();
    if ($uid <= 0) return null;
    getDB()->prepare('UPDATE app_tokens SET last_used = NOW() WHERE token_hash = ?')->execute([$hash]);
    $u = getUserById($uid);
    if (!$u || !empty($u['blocked'])) return null;
    return $u;
}
function appIssueToken(int $uid): string {
    appEnsureTokens();
    $token = bin2hex(random_bytes(32));
    getDB()->prepare('INSERT INTO app_tokens (user_id, token_hash) VALUES (?, ?)')->execute([$uid, hash('sha256', $token)]);
    return $token;
}
function appProfile(array $u): array {
    checkAndResetDailyLimit((int)$u['id']);
    $u = getUserById((int)$u['id']) ?: $u;
    $plan = getUserPlan($u);
    $limit = (int)($plan['daily_limit'] ?? 0);
    $used = (int)($u['daily_count'] ?? 0);
    return [
        'id' => (int)$u['id'],
        'username' => (string)($u['username'] ?? ''),
        'plan' => (string)($plan['ai_tier'] ?? ($u['plan'] ?? 'free')),
        'plan_name' => (string)($plan['name'] ?? 'رایگان'),
        'expires' => (string)($u['plan_expires'] ?? ''),
        'daily_limit' => $limit,
        'daily_used' => $used,
        'remaining' => max(0, $limit - $used),
    ];
}

$action = trim((string)($_GET['action'] ?? $_POST['action'] ?? ''));
$body = appBody();
if ($action === '' && isset($body['action'])) $action = trim((string)$body['action']);

if ($action === 'login') {
    $username = trim((string)($body['username'] ?? ''));
    $password = (string)($body['password'] ?? '');
    if ($username === '' || $password === '') appOut(['error' => 'نام کاربری و رمز را بنویس.'], 400);
    if (isLoginLocked($username)) appOut(['error' => 'حساب ۱۵ دقیقه قفل است.'], 429);
    $u = getUserByUsername($username);
    if (!$u || !password_verify($password, (string)$u['password_hash'])) {
        recordLoginAttempt($username, false);
        appOut(['error' => 'نام کاربری یا رمز اشتباه است.'], 401);
    }
    recordLoginAttempt($username, true);
    appOut(['success' => true, 'token' => appIssueToken((int)$u['id']), 'user' => appProfile($u)]);
}

$user = appUserFromToken();
if (!$user) appOut(['error' => 'وارد نشده‌ای. دوباره وارد شو.'], 401);
$uid = (int)$user['id'];

if ($action === 'me') appOut(['success' => true, 'user' => appProfile($user)]);

if ($action === 'conversations') {
    appOut(['success' => true, 'items' => loadConvs($uid)]);
}

if ($action === 'create_conversation') {
    $conv = createConv($uid);
    appOut(['success' => true, 'conversation' => $conv]);
}

if ($action === 'delete_conversation') {
    $cid = (int)($body['conv_id'] ?? 0);
    if ($cid > 0) deleteConv($uid, $cid);
    appOut(['success' => true]);
}

if ($action === 'messages') {
    $cid = (int)($body['conv_id'] ?? $_GET['conv_id'] ?? 0);
    if ($cid <= 0 || !getConvById($uid, $cid)) appOut(['error' => 'گفتگو پیدا نشد.'], 404);
    appOut(['success' => true, 'items' => getMessages($uid, $cid)]);
}

if ($action === 'confirm_myket') {
    $sku = trim((string)($body['sku'] ?? ''));
    $token = trim((string)($body['token'] ?? ''));
    $orderIdIn = trim((string)($body['order_id'] ?? ''));
    $catalog = mzaMyketCatalog();
    if (!isset($catalog[$sku])) appOut(['error' => 'شناسه خرید نامعتبر است.'], 400);
    if (!mzaRateAllow('myket_' . $uid, 10, 3600)) appOut(['error' => 'تلاش خرید زیاد است.'], 429);
    $item = $catalog[$sku];
    global $PLAN_RANK;
    $cur = getUserPlan($user);
    if (($PLAN_RANK[$item['plan']] ?? 0) < ($PLAN_RANK[$cur['ai_tier'] ?? 'free'] ?? 0)) {
        appOut(['error' => 'پلن فعلی بالاتر است.'], 400);
    }
    $verify = mzaMyketVerify($sku, $token);
    if (empty($verify['skipped']) && empty($verify['ok'])) appOut(['error' => $verify['error'] ?? 'خرید تایید نشد.'], 402);
    ensurePaymentsTable();
    $tokenKey = $token !== '' ? substr($token, 0, 180) : '';
    if ($tokenKey !== '') {
        $dup = getDB()->prepare('SELECT id FROM payments WHERE track_id = ? AND status = "success" LIMIT 1');
        $dup->execute([$tokenKey]);
        if ($dup->fetch()) appOut(['success' => true, 'already' => true, 'user' => appProfile(getUserById($uid))]);
    }
    $orderId = 'myket_' . $uid . '_' . substr(hash('sha256', $sku . '|' . $tokenKey . '|' . microtime(true)), 0, 24);
    $stmt = getDB()->prepare('INSERT INTO payments (uid, plan_id, duration_days, amount_toman, order_id, track_id, status, gateway) VALUES (?, ?, ?, ?, ?, ?, "pending", "myket")');
    $stmt->execute([$uid, $item['plan'], $item['days'], $item['amount'], $orderId, $tokenKey !== '' ? $tokenKey : null]);
    $paymentId = (int)getDB()->lastInsertId();
    $expires = applyPlanPurchase($uid, $item['plan'], (int)$item['days']);
    getDB()->prepare('UPDATE payments SET status = "success", ref_number = ?, paid_at = NOW() WHERE id = ?')->execute([substr($orderIdIn, 0, 50), $paymentId]);
    appOut(['success' => true, 'expires' => $expires, 'user' => appProfile(getUserById($uid))]);
}

if ($action === 'send') {
    $cid = (int)($body['conv_id'] ?? 0);
    $message = trim((string)($body['message'] ?? ''));
    if ($cid <= 0 || $message === '') appOut(['error' => 'پیام یا گفتگو ناقص است.'], 400);
    if (!getConvById($uid, $cid)) appOut(['error' => 'گفتگو پیدا نشد.'], 404);
    if (!mzaRateAllow('app_send_' . $uid, 12, 60)) appOut(['error' => 'در هر دقیقه حداکثر ۱۲ پیام.'], 429);
    checkAndResetDailyLimit($uid);
    $fresh = getUserById($uid);
    $plan = getUserPlan($fresh);
    $remaining = max(0, (int)$plan['daily_limit'] - (int)($fresh['daily_count'] ?? 0));
    if ($remaining < 1 || !atomicIncrementDailyCount($uid, 1)) appOut(['error' => 'سقف پیام امروز تمام شده.'], 402);

    $model = mzaSlotApi('mzor');
    if ($model === '') $model = 'gpt-5.6-luna';
    $system = function_exists('mzaSystemPrompt') ? mzaSystemPrompt('mzor') : 'تو مدل Mzor روی سایت MZA هستی.';
    $history = getMessages($uid, $cid);
    $apiMessages = [['role' => 'system', 'content' => $system]];
    foreach (array_slice($history, -8) as $m) {
        $role = ($m['role'] ?? '') === 'bot' ? 'assistant' : 'user';
        $apiMessages[] = ['role' => $role, 'content' => (string)($m['content'] ?? '')];
    }
    $apiMessages[] = ['role' => 'user', 'content' => $message];
    addMsg($uid, $cid, 'user', $message);

    $payload = json_encode([
        'model' => $model,
        'messages' => $apiMessages,
        'stream' => false,
    ], JSON_UNESCAPED_UNICODE);
    $ch = curl_init('https://api.gapgpt.app/v1/chat/completions');
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => $payload,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 120,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . API_KEY,
            'Content-Type: application/json',
        ],
    ]);
    $res = curl_exec($ch);
    $err = curl_error($ch);
    curl_close($ch);
    $json = json_decode((string)$res, true);
    $reply = trim((string)($json['choices'][0]['message']['content'] ?? ''));
    if ($reply === '') {
        refundDailyCount($uid, 1);
        appOut(['error' => $err !== '' ? 'ارتباط با مدل قطع شد.' : 'مدل جواب نداد.'], 502);
    }
    $usage = function_exists('mzaReadUsage') ? mzaReadUsage(is_array($json) ? $json : []) : ['cost' => 0, 'total' => 0];
    $units = !empty($usage['cost']) ? mzaUnitsFromCost((float)$usage['cost']) : 1;
    if ($units > 1) atomicIncrementDailyCount($uid, $units - 1);
    addMsg($uid, $cid, 'bot', $reply);
    appOut(['success' => true, 'reply' => $reply, 'cost' => $units, 'user' => appProfile(getUserById($uid))]);
}

appOut(['error' => 'درخواست ناشناخته است.'], 404);
