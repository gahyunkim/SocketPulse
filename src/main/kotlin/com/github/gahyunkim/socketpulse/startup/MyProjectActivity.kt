package com.github.gahyunkim.socketpulse.startup

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 샘플 코드 정리 안내를 출력하는 시작 활동입니다. 현재 plugin.xml에는 등록되지 않았습니다.
 */
class MyProjectActivity : ProjectActivity {

    /**
     * 실행 시 템플릿 코드 정리 안내를 로그에 기록합니다.
     */
    override suspend fun execute(project: Project) {
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }
}
