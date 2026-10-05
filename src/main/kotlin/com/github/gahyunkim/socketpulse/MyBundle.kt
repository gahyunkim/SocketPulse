package com.github.gahyunkim.socketpulse

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.MyBundle"

/**
 * messages/MyBundle.properties의 지역화 메시지를 조회합니다.
 */
object MyBundle : DynamicBundle(BUNDLE) {

    /**
     * [key]의 자리표시자를 [params]로 치환한 메시지를 반환합니다.
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)

    /**
     * 호출 시점에 [key]의 메시지를 계산하는 지연 조회 함수를 반환합니다.
     */
    @Suppress("unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)
}
