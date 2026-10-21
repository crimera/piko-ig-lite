/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.ghost

import app.crimera.bytecode.Target
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.instagram.misc.downloads.methodRef
import app.crimera.patches.instagram.misc.downloads.parameterBlock
import app.crimera.patches.instagram.misc.downloads.registerOfParameterIndex
import app.crimera.patches.instagram.misc.downloads.sameSignatureAs
import app.crimera.patches.instagram.misc.downloads.toMutable
import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.misc.settings.Categories
import app.crimera.patches.instagram.misc.settings.instagramToggle
import app.crimera.patches.instagram.utils.Constants.GHOST_DESCRIPTOR
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val HIDE_MESSAGE_VIEWS = "$GHOST_DESCRIPTOR/GhostMessages;->hideMessageViews()Z"

/** Disappearing photos and videos: the batched and the single-item seen requests. */
private const val VISUAL_ITEM_SEEN_BATCH = "direct_v2/visual_threads/%s/item_seen/"
private const val VISUAL_ITEM_SEEN = "direct_v2/visual_threads/%s/visual_items/%s/seen/"

/** Opening a thread tells the sender their message was seen, through this GraphQL mutation. */
private const val DIRECT_ITEM_SEEN_MUTATION = "IGDirectItemSeenMutation"

private const val STRING_DESCRIPTOR = "Ljava/lang/String;"

/**
 * The queue's success marker: the string the queue's own completion logs once a mutation succeeds. It appears
 * in that completion only, on the path a null error takes. It is the anchor for the completion.
 */
internal const val QUEUE_SUCCESS_MARKER = "mutation_success"

private val CONST_STRINGS = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

private val INVOKES =
    setOf(
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL_RANGE,
        Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_DIRECT_RANGE,
        Opcode.INVOKE_STATIC,
        Opcode.INVOKE_STATIC_RANGE,
        Opcode.INVOKE_INTERFACE,
        Opcode.INVOKE_INTERFACE_RANGE,
    )

/**
 * Stops Instagram from telling the sender that a message was seen.
 *
 * Direct sends nothing from the UI. A viewed message becomes a queued mutation, and a mutation handler
 * (one class per mutation type, all sharing the shape `(context, callback, mutation) -> void`) builds a request
 * and enqueues it with its callback. The queue removes the mutation's pending entry, and advances to the next
 * mutation, when the callback's success completion runs.
 *
 * When the toggle is on, the hook invokes that same completion on the handler's callback with a null error and a
 * null item id, then returns. The completion is the callback implementation that names [QUEUE_SUCCESS_MARKER]
 * (see [queueCompletion]). A null error takes its success path, and a null item id is safe for the listeners that
 * read it, which check for null before use.
 *
 * Three mutation types report a message as seen:
 *  - the batched `item_seen` request for disappearing media,
 *  - the single `visual_items/.../seen/` request for the same media,
 *  - the thread seen marker, which is the `IGDirectItemSeenMutation`.
 *
 * The first two build their request in the handler. Depending on the release the third builds it in the
 * handler or in a static helper the handler calls, so a match that is not handler-shaped is walked to its
 * single handler caller. Typing and read receipts of the inbox list are other mutation types and untouched.
 */
@Suppress("unused")
val ghostMessagesPatch =
    bytecodePatch(
        description = "Stops Instagram from marking messages you read as seen.",
    ) {
        dependsOn(sharedExtensionPatch)

        instagramToggle(
            id = "instagram.ghost.messages",
            category = Categories.GHOST,
            strings = settingStrings("piko_ig_ghost_messages"),
            order = 300,
            defaultValue = false,
        )

        execute {
            val handlers = mutableListOf<MutableMethod>()
            handlers += seenHandlers(VISUAL_ITEM_SEEN_BATCH, 1..3)
            handlers += seenHandlers(VISUAL_ITEM_SEEN, 1..2)
            handlers += seenHandlers(DIRECT_ITEM_SEEN_MUTATION, 1..2)

            // Every selected handler receives the same callback interface, so one queue completion serves all of them.
            val callbackType =
                requireExactlyOne(
                    "callback interface shared by the seen mutation handlers",
                    handlers.map { handler -> handler.parameterTypes[1].toString() }.distinct(),
                )
            val completion = queueCompletion(callbackType)
            handlers.distinctBy { it.toString() }.forEach { completeInsteadOfSending(it, completion) }
        }
    }

/** `(context, callback, mutation) -> void`, the entry point of a mutation handler. */
private fun Method.isMutationHandler(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" && parameterTypes.size == 3

/** The handlers that contain [anchor], resolving a match inside a helper to the handler calling it. */
context(patchContext: BytecodePatchContext)
private fun seenHandlers(
    anchor: String,
    expected: IntRange,
): List<MutableMethod> =
    Fingerprint(filters = listOf(string(anchor))).matchAll(expected).flatMap { match ->
        val method = match.originalMethod
        if (method.isMutationHandler()) {
            listOf(match.method)
        } else {
            listOf(handlerCalling(method, anchor))
        }
    }

context(patchContext: BytecodePatchContext)
private fun handlerCalling(
    helper: Method,
    anchor: String,
): MutableMethod {
    val callers = mutableListOf<Method>()
    patchContext.classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (!method.isMutationHandler()) return@forEach
            val calls =
                method.implementation
                    ?.instructions
                    ?.toList()
                    .orEmpty()
                    .any { instruction ->
                        instruction.methodRef()?.let { reference ->
                            reference.definingClass == helper.definingClass && reference.sameSignatureAs(helper)
                        } == true
                    }
            if (calls) callers.add(method)
        }
    }
    return requireExactlyOne(
        "mutation handler calling the \"$anchor\" helper $helper",
        callers,
        describe = { method -> method.toString() },
    ).toMutable("mutation handler of \"$anchor\"")
}

/** What the completion search reads from one method: its signature, and whether it names the marker or calls a helper that does. */
internal data class MethodFacts(
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val namesMarker: Boolean,
    val callsMarkerHelper: Boolean,
)

/** A class with the interfaces it implements and the facts of its methods. */
internal class ClassFacts(
    val type: String,
    val interfaces: Set<String>,
    val methods: List<MethodFacts>,
)

/**
 * The queue's completion for [callbackType]: the one method of a class implementing that interface that names
 * [QUEUE_SUCCESS_MARKER], directly or in one helper it calls. The method must be a method of the interface, must take
 * an object error and a `String` item id, and must return void. Anything else fails closed.
 */
internal fun queueCompletionOf(
    callbackType: String,
    interfaceMethods: List<MethodFacts>,
    classes: List<ClassFacts>,
): MethodReference {
    val candidates =
        classes.filter { callbackType in it.interfaces }.flatMap { implementer ->
            implementer.methods.filter { it.namesMarker || it.callsMarkerHelper }.map { implementer.type to it }
        }
    val (implementer, method) =
        requireExactlyOne(
            "queue completion of $callbackType naming \"$QUEUE_SUCCESS_MARKER\"",
            candidates,
            describe = { (type, candidate) -> "$type.${candidate.name}" },
        )
    val isInterfaceMethod =
        interfaceMethods.any { signature ->
            signature.name == method.name &&
                signature.parameterTypes == method.parameterTypes &&
                signature.returnType == method.returnType
        }
    if (!isInterfaceMethod) {
        throw PatchException("$implementer.${method.name} is not a method of $callbackType")
    }
    val takesErrorAndItem =
        method.parameterTypes.size == 2 &&
            method.parameterTypes[0].startsWith("L") &&
            method.parameterTypes[1] == STRING_DESCRIPTOR
    if (method.returnType != "V" || !takesErrorAndItem) {
        throw PatchException("completion ${method.name} of $callbackType must take an error and an item id and return void")
    }
    return ImmutableMethodReference(callbackType, method.name, method.parameterTypes, method.returnType)
}

/** Whether a method body loads [QUEUE_SUCCESS_MARKER] as a string constant. */
internal fun List<Instruction>.namesMarker(): Boolean =
    any { instruction ->
        instruction.opcode in CONST_STRINGS &&
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == QUEUE_SUCCESS_MARKER
    }

/** The methods a body invokes. */
internal fun List<Instruction>.invokedMethods(): List<MethodReference> =
    mapNotNull { instruction -> if (instruction.opcode in INVOKES) instruction.methodRef() else null }

context(patchContext: BytecodePatchContext)
private fun requireCallbackInterface(callbackType: String) {
    if (!AccessFlags.INTERFACE.isSet(patchContext.classDefBy(callbackType).accessFlags)) {
        throw PatchException("callback $callbackType is not an interface")
    }
}

/**
 * The queue's completion for [callbackType], resolved from the APK. Helpers are read one hop deep: a helper names the
 * marker when its own body does, and the method calling it is the completion candidate.
 */
context(patchContext: BytecodePatchContext)
private fun queueCompletion(callbackType: String): MethodReference {
    requireCallbackInterface(callbackType)
    val interfaceMethods =
        patchContext
            .classDefBy(callbackType)
            .methods
            .filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) }
            .map { method -> method.signature() }
    val implementers = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classDef ->
        if (callbackType in classDef.interfaces) implementers.add(classDef)
    }
    return queueCompletionOf(callbackType, interfaceMethods, implementers.map { classDef -> classDef.facts() })
}

context(patchContext: BytecodePatchContext)
private fun ClassDef.facts(): ClassFacts = ClassFacts(type, interfaces.toSet(), methods.map { method -> method.facts() })

context(patchContext: BytecodePatchContext)
private fun Method.facts(): MethodFacts {
    val body = implementation?.instructions?.toList().orEmpty()
    return MethodFacts(
        name = name,
        parameterTypes = parameterTypes.map { it.toString() },
        returnType = returnType,
        namesMarker = body.namesMarker(),
        callsMarkerHelper = body.invokedMethods().any { helper -> helperNamesMarker(helper) },
    )
}

/** A method's signature alone, for the interface side of the search. */
private fun Method.signature(): MethodFacts =
    MethodFacts(
        name = name,
        parameterTypes = parameterTypes.map { it.toString() },
        returnType = returnType,
        namesMarker = false,
        callsMarkerHelper = false,
    )

/** Whether the helper a body invokes names the marker in its own body, one hop deep. */
context(patchContext: BytecodePatchContext)
private fun helperNamesMarker(helper: MethodReference): Boolean {
    val helperParameters = helper.parameterTypes.map { it.toString() }
    return patchContext.classDefByOrNull(helper.definingClass)?.methods?.any { method ->
        method.name == helper.name &&
            method.parameterTypes.map { it.toString() } == helperParameters &&
            method.returnType == helper.returnType &&
            method.implementation?.instructions?.toList().orEmpty().namesMarker()
    } == true
}

/**
 * Makes [handler] invoke [completion] on its callback with a null error and a null item id, then return, when the
 * toggle is on. The disabled branch runs the original code.
 */
private fun completeInsteadOfSending(
    handler: MutableMethod,
    completion: MethodReference,
) {
    val callbackType = handler.parameterTypes[1].toString()
    val callbackRegister = handler.registerOfParameterIndex(1)
    handler.insertHook(
        index = 0,
        excludedRegisters = handler.parameterBlock(),
        relocateBranchTargets = true,
    ) {
        val enabled = scratchRegister()
        invokeStatic(methodReference(HIDE_MESSAGE_VIEWS))
        moveResult(enabled, "Z")
        ifEqz(enabled, Target.Original)

        // The handler keeps its arguments in high registers, which 4-bit `invoke` cannot address.
        val callback = scratchRegister()
        move(callback, callbackRegister, callbackType)
        val error = scratchRegister()
        constInt(error, 0)
        val item = scratchRegister()
        constInt(item, 0)
        invokeInterface(completion, callback, error, item)
        returnVoid()
    }
}
