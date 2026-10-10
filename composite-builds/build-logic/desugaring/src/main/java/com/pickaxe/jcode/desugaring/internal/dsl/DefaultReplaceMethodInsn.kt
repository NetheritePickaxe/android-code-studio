package com.pickaxe.jcode.desugaring.internal.dsl

import com.pickaxe.jcode.desugaring.dsl.MethodOpcode
import com.pickaxe.jcode.desugaring.dsl.ReplaceMethodInsn
import java.io.Serializable

/** @author Akash Yadav */
data class DefaultReplaceMethodInsn(
    override var fromClass: String,
    override var methodName: String,
    override var methodDescriptor: String,
    override var requireOpcode: MethodOpcode?,
    override var toClass: String,
    override var toMethod: String,
    override var toMethodDescriptor: String,
    override var toOpcode: MethodOpcode,
) : ReplaceMethodInsn, Serializable {

  @JvmField val serialVersionUID = 1L
}
