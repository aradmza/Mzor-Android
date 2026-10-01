# اپلیکیشن اندروید مزا (Mza AI)

این پروژه، سایت **https://mzaai.ir/** را با تکنولوژی **Trusted Web Activity (TWA)** به یک اپلیکیشن اندرویدی اختصاصی و تمام‌صفحه (بدون نوار آدرس مرورگر) تبدیل می‌کند.

- **نام اپ:** مزا
- **Package name:** `ir.mza.mzor`
- **minSdk:** 21 — **targetSdk / compileSdk:** 34
- **روش بیلد:** کاملاً از طریق GitHub Actions (نیازی به نصب Android Studio نیست)

---

## ۱. کارهایی که باید روی خود سایت (mzaai.ir) انجام بدی

داخل پوشه‌ی `website-files/` این فایل‌ها رو گذاشتم، باید آپلودشون کنی:

| فایل | مسیر روی سرور |
|---|---|
| `manifest.webmanifest` | ریشه‌ی سایت → `https://mzaai.ir/manifest.webmanifest` |
| `icon-192.png`, `icon-512.png` | ریشه‌ی سایت |
| `sw.js` | ریشه‌ی سایت → `https://mzaai.ir/sw.js` |
| `.well-known/assetlinks.json` | ریشه‌ی سایت → `https://mzaai.ir/.well-known/assetlinks.json` |

و محتوای `head-snippet.html` رو داخل فایل هدر مشترک PHP سایت (چیزی مثل `header.php` یا `layout.php`) بین تگ `<head>...</head>` اضافه کن.

⚠️ **نکته‌ی مهم درباره‌ی `assetlinks.json`:** این فایل الان یک مقدار placeholder داره (`REPLACE_WITH_SHA256...`). باید بعد از اولین بیلد در GitHub Actions، اثر انگشت (SHA256) واقعی کلید امضا رو از لاگ اکشن کپی کنی و جای اون مقدار بذاری (مرحله ۳ پایین‌تر توضیح داده شده). تا وقتی این فایل درست نشه، اپ به‌جای تمام‌صفحه، با نوار آدرس بالا باز میشه (fallback امن خود TWA).

---

## ۲. ساختار پروژه‌ی اندروید

```
app/
 ├─ build.gradle              ← تنظیمات پکیج، SDK و امضا
 ├─ src/main/AndroidManifest.xml  ← تنظیمات TWA (آدرس سایت، رنگ‌ها، اسپلش)
 └─ src/main/res/              ← آیکون‌ها (از لوگوی خودت ساخته شده) و رنگ‌ها
.github/workflows/build.yml   ← بیلد خودکار در GitHub Actions
```

آیکون‌ها (mipmap-*، آیکون adaptive) مستقیماً از عکسی که فرستادی ساخته شدن، رنگ نوار وضعیت/اسپلش هم از رنگ پس‌زمینه‌ی همون لوگو (#191F16) گرفته شده.

---

## ۳. آماده‌سازی کلید امضا (Keystore) و Secrets گیت‌هاب

برای اینکه هم بیلد release قابل انتشار در Play Store باشه، هم `assetlinks.json` کار کنه، باید یک کلید امضای ثابت بسازی (فقط یک‌بار، و همیشه همونو استفاده کن):

روی هر سیستمی که Java نصبه (حتی خود Codespaces گیت‌هاب) این دستور رو بزن:

```bash
keytool -genkeypair -v -keystore mza-release.keystore \
  -alias mza -keyalg RSA -keysize 2048 -validity 10000
```

چند تا رمز و اطلاعات ازت می‌پرسه؛ رمزها رو جایی یادداشت کن.

بعد فایل رو به base64 تبدیل کن:

```bash
base64 -w0 mza-release.keystore > mza-release.keystore.b64
```

حالا برو توی ریپوی گیت‌هاب:
**Settings → Secrets and variables → Actions → New repository secret**

و این ۴ secret رو بساز:

| نام Secret | مقدار |
|---|---|
| `MZA_KEYSTORE_BASE64` | محتوای فایل `mza-release.keystore.b64` |
| `MZA_KEYSTORE_PASSWORD` | رمز keystore |
| `MZA_KEY_ALIAS` | `mza` (یا هر alias دیگه‌ای که دادی) |
| `MZA_KEY_PASSWORD` | رمز کلید (key password) |

---

## ۴. بیلد کردن

بعد از push کردن ریپو به GitHub (یا اجرای دستی از تب **Actions**)، ورک‌فلو `build.yml` به‌صورت خودکار:

1. پروژه رو با Gradle می‌سازه.
2. اگر Secrets بالا رو تنظیم کرده باشی، APK و AAB رو با کلید release امضا می‌کنه.
3. اثر انگشت SHA256 کلید رو توی لاگ استپ «Print SHA256 fingerprint» چاپ می‌کنه — این مقدار رو کپی کن و جای placeholder توی `assetlinks.json` بذار (مرحله ۱).
4. خروجی نهایی (`.apk` و `.aab`) رو به‌عنوان **Artifact** در همون صفحه‌ی اجرای اکشن، قابل دانلود می‌ذاره.

اگر Secrets رو تنظیم نکنی، بیلد باز هم انجام میشه ولی با کلید debug (فقط برای تست نصب روی گوشی، نه برای انتشار یا کار کردن deep-link).

---

## ۵. تست

بعد از نصب APK روی گوشی (و درست بودن `assetlinks.json` روی سرور)، اپ باید کاملاً تمام‌صفحه و بدون نوار آدرس مرورگر، مستقیم سایت mzaai.ir رو نشون بده.

اگه نوار آدرس مرورگر بالای اپ دیده شد، یعنی assetlinks.json هنوز درست verify نشده (چند دقیقه صبر کن، یا مطمئن شو فایل دقیقاً در `https://mzaai.ir/.well-known/assetlinks.json` در دسترسه و SHA256 درسته).

---

## سؤال‌هایی که ممکنه پیش بیاد

- **چرا از Bubblewrap استفاده نکردی؟** پروژه از همون کتابخونه‌ای استفاده می‌کنه که Bubblewrap هم پشت صحنه ازش استفاده می‌کنه (`androidbrowserhelper`)، ولی این‌جا دستی و کامل توی یک پروژه‌ی Gradle عادی نوشته شده تا مستقیم روی GitHub Actions بیلد بشه.
- **می‌خوام بعداً لوگو یا رنگ‌ها رو عوض کنم:** فایل‌های آیکون توی `app/src/main/res/mipmap-*` و رنگ‌ها توی `app/src/main/res/values/colors.xml` هستن.
- **می‌خوام اسم پکیج یا نام اپ رو عوض کنم:** `applicationId` و `namespace` توی `app/build.gradle`، اسم اپ توی `app/src/main/res/values/strings.xml`.
