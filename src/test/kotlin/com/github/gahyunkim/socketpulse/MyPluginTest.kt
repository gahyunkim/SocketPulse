package com.github.gahyunkim.socketpulse

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.components.service
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import com.github.gahyunkim.socketpulse.services.MyProjectService

/** XML PSI, 이름 변경 및 프로젝트 서비스의 템플릿 예제를 검증합니다. */
@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {

    /** XML 문자열의 구문 오류 유무, 루트 태그 이름과 내용을 확인합니다. */
    fun testXMLFile() {
        val psiFile = myFixture.configureByText(XmlFileType.INSTANCE, "<foo>bar</foo>")
        val xmlFile = assertInstanceOf(psiFile, XmlFile::class.java)

        assertFalse(PsiErrorElementUtil.hasErrors(project, xmlFile.virtualFile))

        assertNotNull(xmlFile.rootTag)

        xmlFile.rootTag?.let {
            assertEquals("foo", it.name)
            assertEquals("bar", it.value.text)
        }
    }

    /** caret 위치의 XML 태그를 a2로 변경한 결과를 기대 파일과 비교합니다. */
    fun testRename() {
        myFixture.testRename("foo.xml", "foo_after.xml", "a2")
    }

    /** 예제 서비스 조회와 난수 호출을 확인합니다. 난수값이 같으면 실패할 수 있습니다. */
    fun testProjectService() {
        val projectService = project.service<MyProjectService>()

        assertNotSame(projectService.getRandomNumber(), projectService.getRandomNumber())
    }

    /** 이름 변경 테스트의 입력·기대 XML 파일 경로를 반환합니다. */
    override fun getTestDataPath() = "src/test/testData/rename"
}
