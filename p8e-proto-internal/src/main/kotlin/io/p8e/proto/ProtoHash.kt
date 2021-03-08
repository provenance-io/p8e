package io.p8e.proto

interface ProtoHash {
    fun getClasses(): Map<String, Boolean>
    fun getHash(): String
}