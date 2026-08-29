# الشرطي

تطبيق Android مستقل لمحادثة صوتية عربية مع شخصية كلب شرطي تفاعلية.

هذا المشروع **ليس VibeApp** ولا يُبنى فوق واجهاته أو نظام مشاريعه. موديول المنتج الوحيد هو `police-app`، وله هوية Android مستقلة وثابتة.

## هوية التطبيق

- اسم التطبيق: **الشرطي**
- Android application ID: `com.malik.alshurti`
- الموديول: `police-app`
- الحد الأدنى: Android 10 / API 29
- الهدف: API 36

`com.malik.alshurti` هو معرف دائم. لا تغيّره في الإصدارات القادمة. اختلافه عن `com.vibe.app` يسمح بتثبيت **الشرطي** وVibeApp على نفس الجهاز في الوقت نفسه.

## التحديث فوق النسخة السابقة

الإصدارات موجودة في `version.properties`:

```properties
VERSION_CODE=2
VERSION_NAME=0.2.0
```

عند كل إصدار جديد:

1. زد `VERSION_CODE` دائماً: 2 ثم 3 ثم 4...
2. حدّث `VERSION_NAME` حسب رقم الإصدار المرئي.
3. لا تغيّر `applicationId`.
4. وقّع APK بنفس مفتاح التوقيع المستخدم في النسخة السابقة.

Android سيعتبر APK الجديد تحديثاً للتطبيق الموجود بدلاً من طلب حذف النسخة القديمة فقط عندما يكون **applicationId نفسه + مفتاح التوقيع نفسه + versionCode أعلى**.

راجع `docs/release.md` قبل أي إصدار فعلي.

## البنية الحالية

```text
police-app/
  src/main/kotlin/com/malik/alshurti/
    MainActivity.kt
    PoliceCallScreen.kt
    PoliceCallViewModel.kt
    PoliceBrain.kt
    PoliceVoiceEngine.kt
    PoliceDogStage.kt
```

المحادثة الحالية تعمل كـ vertical slice قابل للتبديل:

```text
الميكروفون
  -> STT عربي
  -> PoliceBrain + قواعد الطفل
  -> TTS عربي
  -> حركة الوجه / Arabic visemes
  -> رجوع تلقائي للاستماع
```

هدف النسخة المتقدمة هو استبدال المحركات الأساسية تدريجياً بـ STT/LLM/TTS عصبي مع الحفاظ على نفس واجهات التطبيق.

## البناء

```bash
./gradlew :police-app:assembleDebug
./gradlew :police-app:testDebugUnitTest
./gradlew :police-app:lintDebug
```

الناتج:

`police-app/build/outputs/apk/debug/`

## توقيع إصدار قابل للتحديث

لا يتم حفظ مفتاح إصدار خاص داخل المستودع العام. عند إنشاء نسخة Release مرّر:

- `ALSHORTI_KEYSTORE_FILE`
- `ALSHORTI_KEYSTORE_PASSWORD`
- `ALSHORTI_KEY_ALIAS`
- `ALSHORTI_KEY_PASSWORD`

ويجب الاحتفاظ بنفس المفتاح لكل الإصدارات المستقبلية.

## ملاحظة المنتج

الشخصية داخل التطبيق شخصية خيالية موجهة للطفل وليست اتصالاً حقيقياً بجهة شرطية. لا يجوز لها الادعاء بإرسال دورية أو طلب معلومات شخصية حساسة من الطفل.
