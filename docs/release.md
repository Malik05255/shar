# Release and update policy

هذه الوثيقة تمنع أهم مشكلة في Android: الاضطرار لحذف التطبيق القديم قبل تثبيت الجديد.

## قاعدة التحديث

Android يسمح بالتحديث فوق نسخة مثبتة فقط إذا اجتمعت الشروط التالية:

1. `applicationId` مطابق تماماً.
2. APK الجديد موقّع بنفس شهادة/مفتاح النسخة المثبتة.
3. `versionCode` في النسخة الجديدة أعلى من النسخة المثبتة.

في الشرطي:

```text
applicationId = com.malik.alshurti
```

هذا المعرف **دائم ولا يغيّر**.

VibeApp يستخدم هوية مختلفة، لذلك يمكن وجود التطبيقين على الهاتف في الوقت نفسه.

## أرقام الإصدارات

المصدر الوحيد للأرقام هو `version.properties`:

```properties
VERSION_CODE=2
VERSION_NAME=0.2.0
```

قبل أي نسخة جديدة:

- ارفع `VERSION_CODE` بمقدار واحد على الأقل.
- حدّث `VERSION_NAME` للعرض للمستخدم.
- لا تعيد استخدام versionCode سبق توزيعه.

## توقيع Release محلياً

لا تحفظ مفتاح التوقيع الخاص في هذا المستودع العام.

Gradle يدعم توقيع Release عند توفير المتغيرات التالية في بيئة البناء:

```text
ALSHORTI_KEYSTORE_FILE
ALSHORTI_KEYSTORE_PASSWORD
ALSHORTI_KEY_ALIAS
ALSHORTI_KEY_PASSWORD
```

ثم:

```bash
./gradlew :police-app:assembleRelease
```

## GitHub Actions — APK موقّع وقابل للتحديث

الملف `.github/workflows/release.yml` يبني النسخة الموقعة يدوياً عبر **Run workflow**. يحتاج GitHub Secrets التالية:

```text
ALSHORTI_KEYSTORE_BASE64
ALSHORTI_KEYSTORE_PASSWORD
ALSHORTI_KEY_ALIAS
ALSHORTI_KEY_PASSWORD
```

`ALSHORTI_KEYSTORE_BASE64` هو ملف الـ keystore بعد تحويله إلى Base64. لا ترفع ملف keystore نفسه إلى المستودع.

طالما تستخدم نفس الأسرار/المفتاح وتزيد `VERSION_CODE`، فإن APK الناتج من هذا workflow يمكنه تحديث النسخة السابقة مباشرة بدلاً من حذفها.

إذا لم تتوفر بيانات التوقيع، لا تعتبر APK الناتج إصداراً نهائياً لسلسلة تحديثات الإنتاج.

## حفظ المفتاح

احتفظ بالـ keystore في مكان آمن وبنسخة احتياطية مشفرة. فقدان مفتاح التوقيع يعني عادة عدم القدرة على إصدار تحديث مباشر لنفس التطبيق خارج منظومة إدارة مفاتيح المتجر.

## Debug

نسخ Debug مناسبة للاختبار فقط. إمكانية تحديث Debug فوق Debug تعتمد على استخدام نفس debug signing certificate. لا تعتمد على APKs مبنية في runners مختلفة كسلسلة إصدار نهائية.

## قائمة فحص الإصدار

- [ ] لم يتغير `com.malik.alshurti`.
- [ ] `VERSION_CODE` أعلى من الإصدار السابق.
- [ ] نفس keystore ونفس key alias.
- [ ] `:police-app:testDebugUnitTest` ناجح.
- [ ] `:police-app:lintDebug` ناجح.
- [ ] تم اختبار الميكروفون والصوت على جهاز Android حقيقي.
- [ ] تم تجربة تثبيت الإصدار الجديد فوق الإصدار السابق بدون حذف البيانات.
