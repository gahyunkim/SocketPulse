package com.github.gahyunkim.socketpulse.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.github.gahyunkim.socketpulse.MyBundle

/**
 * 프로젝트 단위 템플릿 예제 서비스입니다. 생성 시 프로젝트 이름을 로그에 기록합니다.
 */
@Service(Service.Level.PROJECT)
class MyProjectService(project: Project) {

    init {
        thisLogger().info(MyBundle.message("projectService", project.name))
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    /**
     * 템플릿 테스트용 1~100 임의 정수를 반환합니다. 연속 호출값은 같을 수 있습니다.
     */
    fun getRandomNumber() = (1..100).random()
}
