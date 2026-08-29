# Contributing to Al-Shorti / الشرطي

## المنتج

هذا المستودع مخصص لتطبيق Android واحد فقط: **الشرطي**. لا تعِد إدخال موديولات VibeApp أو نظام بناء التطبيقات القديم.

## الفروع

- `main`: إصدارات مستقرة.
- `dev`: دمج التطوير.
- الميزات تبدأ من `dev` وتعود إليه عبر Pull Request.

## قواعد لا تتغير

1. لا تغيّر `applicationId = "com.malik.alshurti"`.
2. زد `VERSION_CODE` لكل إصدار قابل للتثبيت فوق إصدار سابق.
3. لا تحفظ keystore إنتاجي أو كلمات مرور في Git.
4. حافظ على الفصل بين STT وBrain وTTS وLip Sync وCharacter Renderer.
5. وضع Offline لا يستخدم الشبكة بشكل خفي.
6. لا تجعل الشخصية تدّعي أنها شرطة حقيقية أو تطلب بيانات الطفل الحساسة.

## التحقق قبل الدمج

```bash
./gradlew :police-app:assembleDebug
./gradlew :police-app:testDebugUnitTest
./gradlew :police-app:lintDebug
```

إذا تغير محرك الصوت أو الاستماع، اختبر على جهاز Android حقيقي ودوّن زمن الاستجابة ومصدر الصوت المستخدم.

## الإصدارات

اقرأ `docs/release.md`. تحديث Android فوق النسخة الحالية يتطلب ثلاثية ثابتة:

- نفس `applicationId`
- نفس مفتاح التوقيع
- `versionCode` أعلى
