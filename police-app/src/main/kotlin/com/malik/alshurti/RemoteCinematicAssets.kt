package com.malik.alshurti

/**
 * Exact cinematic assets used while the persistent 3D office is being built.
 * Storage moves outside the APK; visual bytes are not downscaled or transcoded.
 */
object RemoteCinematicAssets {
    private const val CDN = "https://d8j0ntlcm91z4.cloudfront.net/user_3IdaZt4rxgNGbXtsJ68cilp0L0M"
    private const val MEDIA_CDN = "https://d2ol7oe51mr4n9.cloudfront.net/user_3IdaZt4rxgNGbXtsJ68cilp0L0M"

    val idle = "$CDN/hf_20260830_142010_21ee919f-e378-4db1-b7ed-66e367acc0e3.mp4"
    val talkSeated = "$CDN/hf_20260830_142018_248ecb5a-3804-457a-9a43-9b19de5f8121.mp4"
    val standUp = "$CDN/hf_20260830_141957_53df74a5-4bcb-445d-95a5-69182cd4f0d5.mp4"
    val talkStanding = "$CDN/hf_20260830_142642_29bd6e49-40ad-484b-97f2-ebed9197a923.mp4"
    val approachCamera = "$CDN/hf_20260830_142734_345318c2-5f9c-4f87-b067-8ca5bb5c5b54.mp4"
    val answerPhone = "$CDN/hf_20260830_140952_4319b02c-dc65-4750-b24f-f5459350058b.mp4"
    val walkToDoor = "$CDN/hf_20260830_142029_d4e858db-fdfa-46ce-a248-34ccf47f19de.mp4"
    val reviewFile = "$CDN/hf_20260830_145806_3f9ed13a-6db3-41a4-a89a-34a383d9f5a3.mp4"

    val sitDown = "$MEDIA_CDN/d332e06e-e71d-4a6f-bd09-25701099796e.mp4"
    val returnToDesk = "$MEDIA_CDN/d974bddb-4396-45bb-b829-2bdc0c87aa12.mp4"
    val returnFromCamera = "$MEDIA_CDN/5f6f1b84-9230-4dc9-ad56-e87a3f18ded6.mp4"

    fun sourceFor(action: DogAction): String? = when (action) {
        DogAction.SEATED_IDLE -> idle
        DogAction.TALK_SEATED -> talkSeated
        DogAction.STAND_UP -> standUp
        DogAction.TALK_STANDING -> talkStanding
        DogAction.APPROACH_CAMERA -> approachCamera
        DogAction.RETURN_FROM_CAMERA -> returnFromCamera
        DogAction.ANSWER_PHONE -> answerPhone
        DogAction.WALK_TO_DOOR -> walkToDoor
        DogAction.GREET_STAFF -> walkToDoor
        DogAction.RETURN_TO_DESK -> returnToDesk
        DogAction.REVIEW_FILE -> reviewFile
        DogAction.SIT_DOWN -> sitDown
        DogAction.WALK_AROUND_DESK,
        DogAction.WALK_TO_PHONE -> null
    }

    fun likelyNext(action: DogAction): List<String> = when (action) {
        DogAction.STAND_UP -> listOf(talkStanding, sitDown)
        DogAction.TALK_STANDING -> listOf(sitDown)
        DogAction.APPROACH_CAMERA -> listOf(returnFromCamera)
        DogAction.WALK_TO_DOOR,
        DogAction.GREET_STAFF -> listOf(returnToDesk)
        DogAction.TALK_SEATED -> listOf(idle, reviewFile)
        DogAction.ANSWER_PHONE,
        DogAction.REVIEW_FILE -> listOf(idle)
        else -> emptyList()
    }
}
