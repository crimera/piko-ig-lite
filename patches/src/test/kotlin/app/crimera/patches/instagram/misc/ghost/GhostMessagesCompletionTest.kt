/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.ghost

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Behavior fixtures for the queue completion the ghost-messages hook invokes.
 *
 * They would have caught the 2026-10-20 incident, in which the analytics channel writer (`0BMm.A00` in 449,
 * `0AqG.A00` in 451) was accepted as the completion. That writer never invokes the callback, so the queue stayed
 * pending. The completion is the callback implementation that names the queue's success marker, directly or in one
 * helper. These fixtures cover that resolution, and the cases that must fail closed.
 */
class GhostMessagesCompletionTest {
    private val callbackType = "Lapp/Callback;"
    private val errorType = "Lapp/Error;"
    private val itemType = "Ljava/lang/String;"

    private val interfaceMethods =
        listOf(
            MethodFacts("complete", listOf(errorType, itemType), "V", namesMarker = false, callsMarkerHelper = false),
            MethodFacts("context", emptyList(), "Lapp/Context;", namesMarker = false, callsMarkerHelper = false),
        )

    private val completion = ImmutableMethodReference(callbackType, "complete", listOf(errorType, itemType), "V")

    private fun method(
        name: String,
        parameters: List<String> = listOf(errorType, itemType),
        namesMarker: Boolean = false,
        callsMarkerHelper: Boolean = false,
    ) = MethodFacts(name, parameters, "V", namesMarker, callsMarkerHelper)

    private fun implementer(
        type: String,
        vararg methods: MethodFacts,
        interfaces: Set<String> = setOf(callbackType),
    ) = ClassFacts(type, interfaces, methods.toList())

    @Test
    fun `the implementation naming the marker is the completion`() {
        val classes =
            listOf(
                implementer(
                    "Lapp/Queue;",
                    method("complete", namesMarker = true),
                    MethodFacts("context", emptyList(), "Lapp/Context;", namesMarker = false, callsMarkerHelper = false),
                ),
            )
        assertEquals(completion, queueCompletionOf(callbackType, interfaceMethods, classes))
    }

    @Test
    fun `a marker one helper away resolves to the method calling the helper`() {
        // The 448 shape: the completion calls a helper whose own body names the marker.
        val classes = listOf(implementer("Lapp/Queue;", method("complete", callsMarkerHelper = true)))
        assertEquals(completion, queueCompletionOf(callbackType, interfaceMethods, classes))
    }

    @Test
    fun `a class that does not implement the callback is not a candidate`() {
        val classes =
            listOf(
                implementer("Lapp/Other;", method("complete", namesMarker = true), interfaces = setOf("Lapp/Else;")),
                implementer("Lapp/Queue;", method("complete", namesMarker = true)),
            )
        assertEquals(completion, queueCompletionOf(callbackType, interfaceMethods, classes))
    }

    @Test
    fun `only a non-implementer naming the marker fails closed`() {
        val classes =
            listOf(implementer("Lapp/Other;", method("complete", namesMarker = true), interfaces = setOf("Lapp/Else;")))
        assertFailsWith<PatchException> { queueCompletionOf(callbackType, interfaceMethods, classes) }
    }

    @Test
    fun `two completions naming the marker fail closed`() {
        val classes =
            listOf(
                implementer("Lapp/First;", method("complete", namesMarker = true)),
                implementer("Lapp/Second;", method("complete", namesMarker = true)),
            )
        assertFailsWith<PatchException> { queueCompletionOf(callbackType, interfaceMethods, classes) }
    }

    @Test
    fun `no completion naming the marker fails closed`() {
        val classes = listOf(implementer("Lapp/Queue;", method("complete")))
        assertFailsWith<PatchException> { queueCompletionOf(callbackType, interfaceMethods, classes) }
    }

    @Test
    fun `a marker method the interface does not declare fails closed`() {
        val classes = listOf(implementer("Lapp/Queue;", method("finish", namesMarker = true)))
        assertFailsWith<PatchException> { queueCompletionOf(callbackType, interfaceMethods, classes) }
    }

    @Test
    fun `a completion without an item id fails closed`() {
        val interfaceWithoutItem = listOf(MethodFacts("complete", listOf(errorType), "V", namesMarker = false, callsMarkerHelper = false))
        val classes = listOf(implementer("Lapp/Queue;", method("complete", listOf(errorType), namesMarker = true)))
        assertFailsWith<PatchException> { queueCompletionOf(callbackType, interfaceWithoutItem, classes) }
    }

    @Test
    fun `the marker is read as a string constant`() {
        val marked = listOf<Instruction>(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("mutation_success")))
        val other = listOf<Instruction>(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("mutation_failure")))
        assertEquals(true, marked.namesMarker())
        assertEquals(false, other.namesMarker())
    }

    @Test
    fun `invoked methods include helper calls`() {
        val helper = ImmutableMethodReference("Lapp/Helper;", "A00", listOf(errorType), "V")
        val body = listOf<Instruction>(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 0, 0, 0, 0, helper))
        assertEquals(listOf(helper), body.invokedMethods())
    }
}
