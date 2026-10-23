package io.aequicor.heartbeat.core.bundles.impl

import io.aequicor.heartbeat.core.bundles.BindingKey
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type

/** Key of a cross-bundle accessor: generic return type without variance wildcards (`Set<? extends X>` = `Set<X>`). */
internal fun bindingKeyOf(type: Type): BindingKey =
    BindingKey(type.typeName.replace("? extends ", "").replace("? super ", ""))

/** Accessors of an imports or exports interface: argument-free, non-static, non-default methods with a result. */
internal fun accessorsOf(type: Class<*>): List<Method> = type.methods.filter { method ->
    method.parameterCount == 0 &&
        method.returnType != Void.TYPE &&
        !method.isDefault &&
        !Modifier.isStatic(method.modifiers)
}

/** Sets are merged across bundles instead of having a single exporter. */
internal fun Method.isSetAccessor(): Boolean = Set::class.java.isAssignableFrom(returnType)
