# الشرطي — Architecture

## الهدف

تجربة Android مستقلة بالكامل عن VibeApp: يفتح الطفل التطبيق مباشرة على كلب شرطي واقعي خلف مكتب، يتحدث معه بالعربية، يسمعه التطبيق، يرد عليه، ثم يعود للاستماع تلقائياً.

## هوية Android الثابتة

- namespace: `com.malik.alshurti`
- applicationId: `com.malik.alshurti`
- اسم المنتج: `الشرطي`

هذه الهوية لا تتغير في المستقبل. اختلاف applicationId عن VibeApp يسمح بتثبيت التطبيقين معاً، وثباته يسمح بتحديث الشرطي فوق نسخته السابقة.

## مسار الصوت الحالي

تم حذف Android TextToSpeech من مسار الرد. الرد الصوتي الآن مبني على Supertonic 3 عبر ONNX Runtime Android:

```text
Microphone
  -> Android SpeechRecognizer (ar-SA + partial results)
  -> PoliceBrain + child-safety contract
  -> Supertonic 3 neural Arabic TTS (ONNX, local after first download)
  -> chunked synthesis + overlapped playback
  -> Arabic viseme cursor
  -> real GLB animation clips / fallback during development
  -> automatic return to listening
```

### تنزيل النموذج

ملفات النموذج كبيرة ولذلك لا تدخل داخل APK. في وضع الإنترنت يتم تنزيلها مرة واحدة إلى app-private storage. بعد اكتمال التنزيل، نفس TTS يعمل محلياً في وضع الإنترنت وبدون الإنترنت ولا توجد API مدفوعة أو حصة دقائق.

وضع Offline لا يسمح بتنزيل النموذج خفية. إذا لم يكن النموذج موجوداً، يطلب من المستخدم تشغيل Online مرة واحدة.

### تقليل التأخير

`NeuralArabicVoice` يقسم الرد إلى مقطع أول قصير ثم مقاطع تالية. أول مقطع يبدأ تشغيله فور انتهاء توليده، وفي الوقت نفسه يستمر توليد المقطع التالي على thread منفصل عن AudioTrack. الهدف هو تحسين Time To First Audio بدون خفض جودة الصوت إلى preset سريع روبوتي.

لا يعتبر رقم التأخير مضموناً قبل القياس على هاتف Android حقيقي.

## الاستماع

STT ما زال حالياً عبر Android SpeechRecognizer:

- Online: recognizer المتاح على الجهاز.
- Offline: on-device recognizer فقط، بدون fallback سري للشبكة.

المرحلة التالية للاستقلال الكامل عن حزم الجهاز هي whisper.cpp أو sherpa-onnx Arabic STT.

## الشخصية الواقعية

واجهة الإنتاج أصبحت `RealPoliceDogStage` المبنية على SceneView + Google Filament. عندما يوجد:

`police-app/src/main/assets/models/police_dog.glb`

يتم تشغيله مباشرة. الرسم القديم `PoliceDogStage` أصبح fallback تطوير فقط ولا يمثل الشكل النهائي.

### عقد الأصل النهائي

التفاصيل الكاملة في:

`police-app/src/main/assets/models/README.md`

أهم المتطلبات:

- كلب Belgian Malinois أو German Shepherd واقعي، وليس mascot/cartoon.
- زي شرطة PBR حقيقي بصرياً، مكتب ومشهد واقعيان.
- rig للوجه والفك والأذن والعين والجسم.
- clips للحالات: Idle / Listen / Think / Smile / Serious.
- clips للفم: TalkOpen / TalkWide / TalkRound / TalkClosed / TalkRest.
- محرك الصوت يغير clips الفم أثناء العربية بناء على visemes.
- 2K PBR baseline وميزانية mobile واضحة.

SceneView/Filament يفصل أصل الـGLB عن منطق المحادثة؛ يمكن استبدال الكلب لاحقاً بأصل أعلى جودة بدون إعادة كتابة الصوت أو العقل.

## العقل

`PoliceBrain` ما زال abstraction مستقلاً. النسخة الحالية baseline محلية ومقيدة بقواعد أمان الطفل. الهدف التالي هو Qwen-class model محلي/ذاتي الاستضافة مع نفس contract، بحيث لا تتغير الواجهة أو TTS عند تبديل العقل.

## الأمان

الشخصية خيالية داخل التطبيق. لا تدعي إرسال دورية حقيقية، لا تطلب عنوان الطفل أو رقم هاتفه، ولا تهدد بالسجن. عند خطر حقيقي، توجه الطفل فوراً إلى بالغ موثوق أو إلى خدمات الطوارئ الحقيقية بواسطة بالغ.
