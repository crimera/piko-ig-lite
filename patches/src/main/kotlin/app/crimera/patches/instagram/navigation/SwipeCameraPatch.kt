/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.navigation

import app.crimera.bytecode.Target
import app.crimera.bytecode.fieldReference
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.instagram.misc.downloads.methodRef
import app.crimera.patches.instagram.misc.downloads.parameterBlock
import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.misc.settings.Categories
import app.crimera.patches.instagram.misc.settings.instagramToggle
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.SWIPE_CAMERA_DESCRIPTOR
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val SWIPE_CONTAINER = "Lcom/instagram/ui/swipenavigation/container/SwipeNavigationContainer;"
private const val POSITION_CONFIG = "Lcom/instagram/ui/swipenavigation/container/PositionConfig;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val STRING = "Ljava/lang/String;"

private const val FEED_BOUND = "$SWIPE_CAMERA_DESCRIPTOR;->feedBound(${STRING}F)F"
private const val CLAMP_AT_FEED = "$SWIPE_CAMERA_DESCRIPTOR;->clampAtFeed(FFF)F"
private const val HOLD_FEED_RELEASE = "$SWIPE_CAMERA_DESCRIPTOR;->holdFeedRelease(FFF)Z"

/**
 * Stops a swipe right on the home feed from opening the camera.
 *
 * The swipe container moves the feed for three inputs: a finger drag, a horizontal scroll that a child
 * hands back when it cannot scroll further (a carousel already on its first slide, for example), and the
 * release that settles the feed. All three set the position with the reason `swipe`. The first hook clamps
 * such a move so it cannot cross from the feed into the camera panel. The second keeps a release that
 * starts at rest on the feed from snapping to the camera. Every other reason (the camera button, deep
 * links, story recovery), swipes that start on the camera, and the Direct panel are left alone.
 *
 * Every obfuscated member is resolved from its body, so the resolvers hold across releases:
 *  - the release handler is the container's only `(MotionEvent, float, long)` method,
 *  - the centre panel is the second panel field `getStartMostEnabledPanel` reads (the first is the camera),
 *  - the reason is the object field `setInternalPosition` reads first from its argument,
 *  - the panel offset is the only float field of the panel class.
 */
@Suppress("unused")
val swipeCameraPatch =
    bytecodePatch(
        name = "Disable swipe to camera",
        description = "Stops a swipe right on the home feed from opening the camera. The camera button and deep links still open it.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(sharedExtensionPatch)

        instagramToggle(
            id = "instagram.navigation.swipe_camera",
            category = Categories.NAVIGATION,
            strings = settingStrings("piko_ig_swipe_camera"),
            order = 100,
            defaultValue = true,
        )

        execute {
            disableSwipeToCamera()
        }
    }

context(patchContext: BytecodePatchContext)
private fun disableSwipeToCamera() {
    val container = patchContext.mutableClassDefBy(SWIPE_CONTAINER)
    val clampedPosition =
        requireExactlyOne(
            "getClampedPosition on the swipe container",
            container.methods.filter { method ->
                method.name == "getClampedPosition" && method.parameterTypeNames().isEmpty() && method.returnType == "F"
            },
        )
    val setInternalPosition =
        requireExactlyOne(
            "setInternalPosition on the swipe container",
            container.methods.filter { method ->
                method.name == "setInternalPosition" && method.parameterTypeNames() == listOf(POSITION_CONFIG)
            },
        )
    val release =
        requireExactlyOne(
            "release handler on the swipe container",
            container.methods.filter { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.returnType == "V" &&
                    method.parameterTypeNames() == listOf(MOTION_EVENT, "F", "J")
            },
        )
    val startMostPanel =
        requireExactlyOne(
            "getStartMostEnabledPanel on the swipe container",
            container.methods.filter { method ->
                method.name == "getStartMostEnabledPanel" && method.parameterTypeNames().isEmpty()
            },
        )

    val clampedPositionReference =
        methodReference("${clampedPosition.definingClass}->${clampedPosition.name}()F")
    val centreField = centrePanelField(startMostPanel)
    val panelClass = patchContext.classDefBy(startMostPanel.returnType)
    val offsetField =
        requireExactlyOne(
            "float offset field of ${startMostPanel.returnType}",
            panelClass.fields.filter { field -> field.type == "F" && !AccessFlags.STATIC.isSet(field.accessFlags) },
            describe = { field -> field.name },
        )
    val offsetReference = fieldReference("${startMostPanel.returnType}->${offsetField.name}:F")

    // setInternalPosition: the reason is read first, the clamp of the target runs just before the spring call.
    val setInstructions = setInternalPosition.implementation!!.instructions.toList()
    val reasonRead = setInstructions.first()
    // resolver-lint: allow instruction-order raw-first because the reason is the first field the method reads
    val reasonField = (reasonRead as? ReferenceInstruction)?.reference as? FieldReference
    if (reasonRead.opcode != Opcode.IGET_OBJECT || reasonField == null || reasonField.type != STRING) {
        fail("setInternalPosition must start by reading its reason String, found ${reasonRead.opcode.name}")
    }
    val positionConfigRegister = (reasonRead as TwoRegisterInstruction).registerB
    val clampIndex =
        requireExactlyOne(
            "target clamp call in setInternalPosition",
            setInstructions.indices.filter { index ->
                setInstructions[index].isInvokeOf(SWIPE_CONTAINER, returnType = "F", parameterTypes = listOf("F"))
            },
            describe = { index -> "#$index ${setInstructions[index].opcode.name}" },
        )
    val clampCall = setInstructions[clampIndex] as FiveRegisterInstruction
    val clampReceiver = clampCall.registerC
    val clampTarget = clampCall.registerD

    // release: `getSpring()`, then the spring velocity setter fed by a float-to-double of the release velocity.
    val releaseInstructions = release.implementation!!.instructions.toList()
    val velocityFetch =
        requireExactlyOne(
            "spring velocity write in the release handler",
            releaseInstructions.indices.filter { index -> releaseInstructions.isSpringVelocityWrite(index) },
            describe = { index -> "#$index" },
        )
    val springReceiver = (releaseInstructions[velocityFetch] as FiveRegisterInstruction).registerC
    val velocityRegister = (releaseInstructions[velocityFetch + 2] as TwoRegisterInstruction).registerB

    // Two registers the original code writes before it reads, from the clamp on. The reason is read into the
    // first one, which then carries the current position; the centre is read into the second, which then
    // carries the bound the target is held to. Both are back to original use before the spring call reads them.
    val (reasonOrCurrent, centreOrBound) =
        setInternalPosition.scratchRegistersFrom(
            from = clampIndex,
            exclude = setInternalPosition.parameterBlock() + clampTarget,
            count = 2,
        )
    setInternalPosition.insertHook(index = clampIndex, relocateBranchTargets = true) {
        iget(centreOrBound, clampReceiver, centreField)
        iget(centreOrBound, centreOrBound, offsetReference)
        iget(reasonOrCurrent, positionConfigRegister, reasonField)
        invokeStatic(methodReference(FEED_BOUND), reasonOrCurrent, centreOrBound)
        moveResult(centreOrBound, "F")
        invokeDirect(clampedPositionReference, clampReceiver)
        moveResult(reasonOrCurrent, "F")
        invokeStatic(methodReference(CLAMP_AT_FEED), reasonOrCurrent, clampTarget, centreOrBound)
        moveResult(clampTarget, "F")
    }

    // Two registers the original code writes before it reads, from the velocity fetch on.
    val (flag, centre) =
        release.scratchRegistersFrom(
            from = velocityFetch,
            exclude = release.parameterBlock() + listOf(springReceiver, velocityRegister),
            count = 2,
        )
    release.insertHook(index = velocityFetch, relocateBranchTargets = true) {
        iget(centre, springReceiver, centreField)
        iget(centre, centre, offsetReference)
        invokeDirect(clampedPositionReference, springReceiver)
        moveResult(flag, "F")
        invokeStatic(methodReference(HOLD_FEED_RELEASE), flag, centre, velocityRegister)
        moveResult(flag, "Z")
        ifEqz(flag, Target.Local("keep"))
        constInt(velocityRegister, 0)
        label("keep")
    }
}

/**
 * The panel `getStartMostEnabledPanel` falls back to. It reads the camera panel first and the centre panel
 * second, so the centre is the second distinct panel field.
 */
private fun centrePanelField(startMostPanel: Method): FieldReference {
    val panelType = startMostPanel.returnType
    val panelReads =
        startMostPanel.implementation!!.instructions.toList()
            .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
            .map { instruction -> (instruction as ReferenceInstruction).reference as FieldReference }
            .filter { field -> field.type == panelType }
            .distinctBy { field -> field.name }
    if (panelReads.size != 2) {
        fail("getStartMostEnabledPanel reads ${panelReads.size} panel fields, expected the camera and the centre panel")
    }
    return panelReads[1]
}

/** `getSpring()` followed by a float-to-double of the velocity and the spring's `(D)V` setter. */
private fun List<Instruction>.isSpringVelocityWrite(index: Int): Boolean {
    if (index + 3 >= size) return false
    val fetch = this[index]
    val fetched = fetch.methodRef() ?: return false
    if (fetch.opcode != Opcode.INVOKE_DIRECT || fetched.name != "getSpring" || fetched.definingClass != SWIPE_CONTAINER) {
        return false
    }
    val setter = this[index + 3].methodRef() ?: return false
    return this[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        this[index + 2].opcode == Opcode.FLOAT_TO_DOUBLE &&
        this[index + 3].opcode == Opcode.INVOKE_VIRTUAL &&
        setter.parameterTypes.map { type -> type.toString() } == listOf("D")
}

private fun Instruction.isInvokeOf(
    owner: String,
    returnType: String,
    parameterTypes: List<String>,
): Boolean {
    val reference = methodRef() ?: return false
    return reference.definingClass == owner &&
        reference.returnType == returnType &&
        reference.parameterTypes.map { type -> type.toString() } == parameterTypes
}

private fun Method.parameterTypeNames(): List<String> = parameterTypes.map { type -> type.toString() }

/** Opcodes whose first register is a destination the instruction writes, not reads. */
private val DESTINATION_FIRST =
    setOf(
        Opcode.MOVE,
        Opcode.MOVE_FROM16,
        Opcode.MOVE_16,
        Opcode.MOVE_WIDE,
        Opcode.MOVE_WIDE_FROM16,
        Opcode.MOVE_WIDE_16,
        Opcode.MOVE_OBJECT,
        Opcode.MOVE_OBJECT_FROM16,
        Opcode.MOVE_OBJECT_16,
        Opcode.MOVE_RESULT,
        Opcode.MOVE_RESULT_WIDE,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CONST_4,
        Opcode.CONST_16,
        Opcode.CONST,
        Opcode.CONST_HIGH16,
        Opcode.IGET,
        Opcode.IGET_WIDE,
        Opcode.IGET_OBJECT,
        Opcode.IGET_BOOLEAN,
        Opcode.IGET_BYTE,
        Opcode.IGET_CHAR,
        Opcode.IGET_SHORT,
        Opcode.FLOAT_TO_DOUBLE,
    )

/**
 * Scratch registers for a hook at [from]: the lowest registers the 4-bit encodings reach, not in [exclude],
 * that the code writes before it reads from [from] on. Such a register may hold anything the hook leaves in
 * it. Fails the patch when fewer than [count] qualify.
 */
private fun MutableMethod.scratchRegistersFrom(
    from: Int,
    exclude: List<Int>,
    count: Int,
): List<Int> {
    val instructions = implementation!!.instructions.toList()
    val found = mutableListOf<Int>()
    for (register in 0..15) {
        if (register in exclude) continue
        if (writtenBeforeRead(instructions, from, register)) found.add(register)
        if (found.size == count) return found
    }
    fail("Found ${found.size} of $count scratch registers from index $from of $this: $found")
}

/**
 * True when the first instruction from [from] that names [register] writes it. A branch or a label met before
 * that write makes the answer unknown, so the register does not qualify.
 */
private fun writtenBeforeRead(
    instructions: List<BuilderInstruction>,
    from: Int,
    register: Int,
): Boolean {
    for (index in from until instructions.size) {
        val instruction = instructions[index]
        if (index > from && instruction.location.labels.any()) return false
        if (register in instruction.namedRegisters()) return instruction.writesOnly(register)
        if (instruction.isBranch()) return false
    }
    return false
}

/** Every register the instruction names, its destination (where it has one) first. */
private fun Instruction.namedRegisters(): List<Int> =
    when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
        is TwoRegisterInstruction -> listOf(registerA, registerB)
        is OneRegisterInstruction -> listOf(registerA)
        else -> emptyList()
    }

/** True when the instruction writes [register] as its destination and does not also read it. */
private fun Instruction.writesOnly(register: Int): Boolean {
    if (opcode !in DESTINATION_FIRST) return false
    val named = namedRegisters()
    return named.first() == register && register !in named.drop(1)
}

private fun Instruction.isBranch(): Boolean =
    opcode.name.startsWith("IF_") ||
        opcode.name.startsWith("GOTO") ||
        opcode == Opcode.PACKED_SWITCH ||
        opcode == Opcode.SPARSE_SWITCH

private fun fail(message: String): Nothing = throw PatchException(message)
