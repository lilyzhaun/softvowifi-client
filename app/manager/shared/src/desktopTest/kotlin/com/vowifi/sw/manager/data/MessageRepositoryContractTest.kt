package com.vowifi.sw.manager.data

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

class MessageRepositoryContractTest {
    @Test
    fun unreadGroupingMustBeImplementedRatherThanInheritingAStorageFailure() {
        val contract = MessageRepository::class.java
        val method = contract.declaredMethods.single { it.name == "countUnreadByOwner" }
        assertTrue(Modifier.isAbstract(method.modifiers), "分组计数必须由仓库实现，不能继承默认行为")
        // 同时兼容 JVM default 与旧 DefaultImpls 编译模式，防止后者把默认体藏在静态 helper。
        val defaults = contract.declaredClasses.singleOrNull { it.simpleName == "DefaultImpls" }
        assertTrue(defaults?.declaredMethods.orEmpty().none { it.name == "countUnreadByOwner" },
            "接口不得为未实现的分组查询提供抛异常或空表兜底")
    }
}
