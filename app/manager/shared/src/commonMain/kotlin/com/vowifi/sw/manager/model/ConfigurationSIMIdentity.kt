package com.vowifi.sw.manager.model

enum class SIMAttributeReadState(val wireValue: String) {
    KNOWN("known"), ABSENT("absent"), DENIED("denied"), INVALID("invalid"), UNREADABLE("unreadable"), UNKNOWN("unknown");
}

data class SIMAttributeReadStatus(
    val spn: SIMAttributeReadState,
    val gid1: SIMAttributeReadState,
    val gid2: SIMAttributeReadState,
)
