/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.ghost

import app.crimera.patches.instagram.misc.downloads.methodRef
import app.crimera.patches.instagram.misc.downloads.registerOfParameterIndex
import app.crimera.patches.instagram.utils.Constants.GHOST_DESCRIPTOR
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.HiddenApiRestriction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MethodImplementationBuilder
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Emitted-bytecode coverage for the story gates. Each test guards a failure mode seen only on device:
 * an empty `reel=0` request built because the clear ran after the emptiness check, a disabled toggle that
 * still skips or changes the original check, and a gate that clears more than the watched collections
 * (the nux maps and the auxiliary cache must keep their values).
 */
class GhostStoriesHookTest {
    @Test
    fun `the hide toggle is read before the watched collections are cleared and the emptiness check runs`() {
        val instructions = insertGate(dispatcherFixture())

        assertEquals(Opcode.INVOKE_STATIC, instructions[0].opcode)
        assertEquals(HIDE_NAME, instructions[0].methodRef()?.name)
        assertEquals("$GHOST_DESCRIPTOR/GhostStories;", instructions[0].methodRef()?.definingClass)
        assertEquals(Opcode.MOVE_RESULT, instructions[1].opcode)
        assertEquals(Opcode.IF_EQZ, instructions[2].opcode)
        assertTrue(lastClearIndex(instructions) < checkIndex(instructions), "the clears must precede the check")
    }

    @Test
    fun `the gate clears only the reels map and the forced list`() {
        val instructions = insertGate(dispatcherFixture())
        val gate = instructions.take(checkIndex(instructions))

        // Field reads, in order: only the watched collections are loaded.
        val reads = gate.filter { it.opcode == Opcode.IGET_OBJECT }.map { fieldOf(it).name }
        assertEquals(listOf(REELS_NAME, FORCED_NAME), reads)

        // Calls: the hide toggle and the two clears, nothing else.
        assertEquals(
            setOf(
                "$GHOST_DESCRIPTOR/GhostStories;->$HIDE_NAME",
                "$ABSTRACT_MAP->clear",
                "$LIST_DESCRIPTOR->clear",
            ),
            gate.mapNotNull { instruction ->
                instruction.methodRef()?.let { "${it.definingClass}->${it.name}" }
            }.toSet(),
        )
    }

    @Test
    fun `a disabled toggle branches past the clears straight to the original emptiness check`() {
        val instructions = insertGate(dispatcherFixture())

        val branch = assertIs<BuilderOffsetInstruction>(instructions[2])
        assertEquals(checkIndex(instructions), branch.target.location.index)
        assertTrue(branch.target.location.index > lastClearIndex(instructions))
    }

    @Test
    fun `the gate reads the state straight from its parameter register`() {
        val method = dispatcherFixture()
        val parameter = method.registerOfParameterIndex(0)
        val instructions = insertGate(method, parameter)

        val reads = instructions.filter { it.opcode == Opcode.IGET_OBJECT }
        assertEquals(2, reads.size)
        reads.forEach { read ->
            assertEquals(parameter, assertIs<TwoRegisterInstruction>(read).registerB)
        }
    }

    /** Inserts the gate at index 0 and returns the resulting instructions. */
    private fun insertGate(
        method: MutableMethod,
        parameter: Int = method.registerOfParameterIndex(0),
    ): List<Instruction> {
        method.insertStoryHideGate(
            index = 0,
            stateRegister = parameter,
            watched =
                WatchedSeenFields(
                    reels = ImmutableFieldReference(STATE_TYPE, REELS_NAME, HASH_MAP_DESCRIPTOR),
                    forced = ImmutableFieldReference(STATE_TYPE, FORCED_NAME, LIST_DESCRIPTOR),
                ),
        )
        return method.implementation!!.instructions.toList()
    }

    private fun fieldOf(instruction: Instruction): FieldReference =
        (instruction as ReferenceInstruction).reference as FieldReference

    private fun checkIndex(instructions: List<Instruction>): Int =
        instructions.indexOfFirst { instruction -> instruction.methodRef()?.name == CHECK_NAME }

    private fun lastClearIndex(instructions: List<Instruction>): Int =
        instructions.indexOfLast { instruction -> instruction.methodRef()?.name == "clear" }

    /**
     * A dispatcher shaped like the release's: an instance method taking the state as its only parameter
     * whose first instruction is the state's emptiness check, so the gate must be emitted in front of it.
     */
    private fun dispatcherFixture(): MutableMethod {
        val registerCount = 4
        val builder = MethodImplementationBuilder(registerCount)
        builder.addInstruction(
            BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                1,
                PARAMETER_REGISTER,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(STATE_TYPE, CHECK_NAME, emptyList(), "Z"),
            ),
        )
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method =
            ImmutableMethod(
                DISPATCHER_CLASS,
                "dispatch",
                listOf(ImmutableMethodParameter(STATE_TYPE, emptySet(), null)),
                "V",
                AccessFlags.PUBLIC.value,
                emptySet(),
                emptySet<HiddenApiRestriction>(),
                builder.methodImplementation,
            )
        assertEquals(registerCount, method.implementation!!.registerCount)
        return MutableMethod(method)
    }

    private companion object {
        const val DISPATCHER_CLASS = "Lapp/crimera/test/StoryDispatcher;"
        const val STATE_TYPE = "Lapp/crimera/test/StoryState;"
        const val CHECK_NAME = "emptyCheck"
        const val HIDE_NAME = "hideStoryViews"
        const val REELS_NAME = "reels"
        const val FORCED_NAME = "forced"
        const val HASH_MAP_DESCRIPTOR = "Ljava/util/HashMap;"
        const val LIST_DESCRIPTOR = "Ljava/util/List;"
        const val ABSTRACT_MAP = "Ljava/util/AbstractMap;"

        /** The state parameter occupies v3 in a four-register instance method with two parameter words. */
        const val PARAMETER_REGISTER = 3
    }
}
