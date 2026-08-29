# الشرطي — Architecture

## الهدف

تجربة Android مستقلة بالكامل عن VibeApp: يفتح الطفل التطبيق مباشرة على كلب شرطي سينمائي خلف مكتب، يتحدث معه بالعربية، يسمعه التطبيق، يرد عليه، ثم يعود للاستماع تلقائياً.

## هوية Android الثابتة

- namespace: `com.malik.alshurti`
- applicationId: `com.malik.alshurti`
- اسم المنتج: `الشرطي`

هذه الهوية لا تتغير في المستقبل. اختلاف applicationId عن VibeApp يسمح بتثبيت التطبيقين معاً، وثباته يسمح بتحديث الشرطي فوق نسخته السابقة.

## الوضع الحالي القابل للبناء

```text
Microphone
  -> Android SpeechRecognizer (ar-SA + partial results)
  -> PoliceBrain + child-safety contract
  -> Android TextToSpeech (best Arabic voice available for mode)
  -> Arabic viseme timing
  -> PoliceDogStage expressions / mouth animation
  -> automatic return to listening
```

المحركات مفصولة عمداً حتى لا ترتبط الواجهة بمزوّد واحد.

## الهدف العصبي

### Offline

```text
On-device VAD
  -> whisper.cpp / sherpa-onnx Arabic STT
  -> quantized Qwen-class model on Android runtime
  -> on-device Arabic neural TTS
  -> timed visemes
  -> rigged GLB character
```

### Online / self-hosted

```text
Streaming STT
  -> Qwen service with the same PoliceCharacterContract
  -> streaming Arabic neural TTS
  -> first-audio playback while synthesis continues
  -> timed visemes / facial morph targets
```

لا تعتمد المعمارية على API مدفوع؛ يمكن أن يكون Online عبارة عن backend يملكه المستخدم.

## الشخصية السينمائية

`PoliceDogStage` الحالي fallback متحرك وخفيف. الهدف النهائي أصل 3D مستقل، وليس رسومات VibeApp أو واجهاته.

عقد الأصل النهائي:

- GLB / glTF 2.0.
- PBR materials.
- mobile LODs.
- 2K textures افتراضياً، و4K اختيارياً للأجهزة القوية.
- skeleton: head, neck, jaw, ears, eyelids, brows, shoulders, forelegs.
- morph targets: jawOpen, mouthWide, mouthNarrow, smile, blinkL, blinkR, browUp, browDown.
- clips: Idle, Listen, Think, Talk, Smile, Laugh, Serious, Concerned.
- مزامنة الفم من visemes وليس مجرد فتح/إغلاق دوري.

## ميزانية التأخير

القياس المهم هو Time To First Audio:

- نهاية كلام الطفل: تقريباً 0.35–0.70 ثانية بعد الوقفة الطبيعية.
- أول token للعقل: هدف أقل من 0.5 ثانية على backend مناسب.
- أول صوت TTS: هدف أقل من 0.4 ثانية لمحرك streaming.
- الهدف الإجمالي: بدء الرد تقريباً خلال 1–1.6 ثانية على جهاز/backend مناسب.

لا يُعتبر أي رقم مضموناً قبل قياسه على جهاز حقيقي.

## الأمان

الشخصية خيالية داخل التطبيق. لا تدعي إرسال دورية حقيقية، لا تطلب عنوان الطفل أو رقم هاتفه، ولا تهدد بالسجن. عند خطر حقيقي، توجه الطفل فوراً إلى بالغ موثوق أو إلى خدمات الطوارئ الحقيقية بواسطة بالغ.
