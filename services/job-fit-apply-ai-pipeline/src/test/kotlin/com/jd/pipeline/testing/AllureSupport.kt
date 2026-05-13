package com.jd.pipeline.testing

import io.qameta.allure.Allure
import java.io.ByteArrayInputStream

object AllureSupport {

    fun step(name: String, block: () -> Unit) = Allure.step(name, block)

    fun attach(name: String, content: String, mimeType: String = "text/plain") {
        Allure.addAttachment(name, mimeType, ByteArrayInputStream(content.toByteArray()), "txt")
    }
}
