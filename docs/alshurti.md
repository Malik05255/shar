# الشرطي — Architecture

## الهدف

تجربة Android مستقلة بالكامل عن VibeApp: يفتح الطفل التطبيق مباشرة على كلب شرطي واقعي خلف مكتب، يتحدث معه بالعربية، يسمعه التطبيق، يرد عليه، ثم يعود للاستماع تلقائياً.

## هوية Android الثابتة

- namespace: `com.malik.alshurti`
- applicationId: `com.malik.alshurti`
- اسم المنتج: `الشرطي`

هذه الهوية لا تتغير في المستقبل. اختلاف applicationId عن VibeApp يسمح بتثبيت التطبيقين معاً، وثباته يسمح بتحديث الشرطي فوق نسخته السابقة.

## عقد الصوت الإنتاجي — سعودي بشري فقط

الصوت المقبول في الإنتاج يجب أن يكون صوت رجل سعودي طبيعي وبشري في الإيقاع والنطق، وليس مجرد محرك يدعم العربية.

المسار الإنتاجي الحالي:

```text
Microphone
  -> Android SpeechRecognizer (ar-SA + partial results)
  -> PoliceBrain + child-safety contract
  -> ElevenLabs native Saudi male voice (Multilingual v2)
  -> MP3 playback
  -> Arabic viseme cursor
  -> real GLB animation clips / fallback during development
  -> automatic return to listening
```

### ممنوع fallback روبوتي

`PoliceVoiceEngine` لا يعود إلى Android TextToSpeech ولا إلى Supertonic عند فشل الصوت السعودي. إذا تعذر تشغيل الخدمة أو غاب مفتاحها، يظهر خطأ واضح ولا يتم إخراج صوت بديل أقل جودة.

هذا قرار مقصود لأن تجربة المنتج تعتبر الصوت الروبوتي أو العربية المكسرة فشلاً، حتى لو كان البديل يعمل محلياً.

### الصوت الافتراضي

الصوت الافتراضي هو صوت سعودي رجالي Native (Jeddawi / Jeddah accent). يمكن استبداله أثناء البناء عبر:

```text
ALSHORTI_ELEVENLABS_VOICE_ID
```

ويجب أن يبقى البديل صوتاً سعودياً Native، لا صوتاً إنجليزياً متعدد اللغات يقرأ العربية بلكنة أجنبية.

نموذج النطق هو:

```text
eleven_multilingual_v2
```

تم اختياره لأن الأولوية هنا للطبيعية والثبات وليس لأقل latency ممكنة.

### إعداد مفتاح الصوت

لا يتم حفظ مفتاح ElevenLabs داخل المستودع. أثناء البناء استخدم أحد الخيارين:

```text
ELEVENLABS_API_KEY=<key>
```

كـ environment variable، أو:

```text
./gradlew :police-app:assembleDebug -PELEVENLABS_API_KEY=<key>
```

ملاحظة أمنية: أي مفتاح API يوضع داخل APK يمكن استخراجه من التطبيق. هذا المسار مناسب للاختبار والتوزيع الخاص. قبل نشر عام واسع يجب نقل استدعاء ElevenLabs إلى backend/proxy مملوك للمشروع بحيث لا يحتوي تطبيق Android على المفتاح السري.

## الاستماع

STT حالياً عبر Android SpeechRecognizer بلغة `ar-SA` وبـ partial results. مسار الإنتاج الصوتي يعمل ONLINE لأن شرط جودة الصوت السعودي مقدم على شرط الأوفلاين.

لا يتم تشغيل وضع Offline في واجهة الإنتاج الحالية حتى لا يعود التطبيق إلى صوت محلي لا يحقق معيار الجودة.

المرحلة التالية للاستقلال الكامل عن حزم الجهاز في STT هي whisper.cpp أو sherpa-onnx Arabic STT، لكن أي تغيير في STT لا يسمح بتخفيض جودة TTS.

## الشخصية الواقعية

واجهة الإنتاج هي `RealPoliceDogStage` المبنية على SceneView + Google Filament. عندما يوجد:

`police-app/src/main/assets/models/police_dog.glb`

يتم تشغيله مباشرة. الرسم القديم `PoliceDogStage` هو fallback تطوير فقط ولا يمثل الشكل النهائي.

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

## البيئة الحية داخل المكتب

الشكل النهائي ليس خلفية ثابتة. المشهد المستهدف يتضمن حركة محيطية صامتة ومحدودة لا تشتت الطفل: موظفون يتحركون في الخلفية، باب يفتح ويغلق، شخص يمر أو يخاطب الشرطي عند الحاجة، هاتف مكتبي يرن، ورد فعل بصري من الكلب قبل أن يستأنف الحديث.

يجب أن تكون هذه الأحداث scenario-driven وليست loop واضحاً ومتكرراً. الشخصيات الخلفية لا تتحدث فوق الطفل إلا في حدث مقصود، وعند الحديث يجب أن تستخدم نبرة مختلفة عن صوت الشرطي.

## العقل

`PoliceBrain` abstraction مستقل. النسخة الحالية baseline محلية ومقيدة بقواعد أمان الطفل. الهدف التالي هو Qwen-class model محلي/ذاتي الاستضافة مع نفس contract، بحيث لا تتغير الواجهة أو TTS عند تبديل العقل.

## الأمان

الشخصية خيالية داخل التطبيق. لا تدعي إرسال دورية حقيقية، لا تطلب عنوان الطفل أو رقم هاتفه، ولا تهدد بالسجن. عند خطر حقيقي، توجه الطفل فوراً إلى بالغ موثوق أو إلى خدمات الطوارئ الحقيقية بواسطة بالغ.
