/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.ghost

import app.crimera.bytecode.Target
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.classDefFlatMap
import app.crimera.patches.common.isAssignableTo
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.instagram.misc.downloads.USER_SESSION_DESCRIPTOR
import app.crimera.patches.instagram.misc.downloads.methodRef
import app.crimera.patches.instagram.misc.downloads.parameterBlock
import app.crimera.patches.instagram.misc.downloads.parameterRegisterStart
import app.crimera.patches.instagram.misc.downloads.registerOfParameterIndex
import app.crimera.patches.instagram.misc.downloads.registers
import app.crimera.patches.instagram.misc.downloads.sameSignatureAs
import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.misc.settings.Categories
import app.crimera.patches.instagram.misc.settings.instagramToggle
import app.crimera.patches.instagram.utils.Constants.GHOST_DESCRIPTOR
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val HIDE_STORY_VIEWS = "$GHOST_DESCRIPTOR/GhostStories;->hideStoryViews()Z"

/** The path of the request that tells Instagram which stories were watched. */
private const val STORY_SEEN_ENDPOINT = "media/seen/"

private const val HASH_MAP_DESCRIPTOR = "Ljava/util/HashMap;"
private const val LIST_DESCRIPTOR = "Ljava/util/List;"

/** `iget` and `invoke` address registers v0..v15 directly. */
private const val FOUR_BIT_REGISTER_LIMIT = 16

/**
 * The two collections of a pending seen state that carry watched stories: the reels map, and the list of
 * forced story ids. The nux maps, the skipped maps and the auxiliary cache are not in this pair.
 */
internal class WatchedSeenFields(
    val reels: FieldReference,
    val forced: FieldReference,
)

/**
 * Stops Instagram from telling the author that their story was watched.
 *
 * Watching a story only records it locally: the viewer adds each story it showed to the pending seen
 * state's reels map, and the builder turns that state into the `media/seen/` request. Watched stories also
 * reach the request through the forced list. Two gates empty those two collections before the request is
 * assembled, so no watched story is ever sent:
 *  - the dispatcher gate runs at the entry of the method that decides whether a pending state is sent. A
 *    state that held only watched stories is empty when the dispatcher checks it, so no request is built
 *    or enqueued. Its `reel=0` request therefore never goes out.
 *  - the builder gate covers the paths that build without that check: a direct build for the NUX click
 *    and the queued flush. A queued flush that carries no watched data still builds a request, as before.
 *
 * Only the reels map and the forced list are cleared. The nux maps (`nux_story`) and the skipped maps
 * keep their values, and the auxiliary seen cache and the local viewed ring are never touched. With the
 * toggle off, both gates branch straight to the original code.
 *
 * Every obfuscated member is resolved by behavior:
 *  - the builder is the only method taking a `UserSession` that holds the `media/seen/` endpoint,
 *  - the reels map is the map field the builder reads straight before measuring it with `size()`,
 *    which is what sets its `reel=` flag,
 *  - the forced list is the state's only non-static `List` field,
 *  - the emptiness method is the state's only parameterless instance method returning `Z` that asks
 *    three collections whether they are empty,
 *  - the dispatcher is the only method in the app that takes the state as its single parameter and
 *    whose first instruction is that emptiness check.
 */
@Suppress("unused")
val ghostStoriesPatch =
    bytecodePatch(
        description = "Stops Instagram from marking stories you watch as seen.",
    ) {
        dependsOn(sharedExtensionPatch)

        instagramToggle(
            id = "instagram.ghost.stories",
            category = Categories.GHOST,
            strings = settingStrings("piko_ig_ghost_stories"),
            order = 100,
            defaultValue = false,
        )

        execute {
            hideStorySeenRequest()
        }
    }

context(patchContext: BytecodePatchContext)
private fun hideStorySeenRequest() {
    val builder =
        requireExactlyOne(
            "story seen request builder",
            Fingerprint(
                parameters = listOf(USER_SESSION_DESCRIPTOR),
                filters = listOf(string(STORY_SEEN_ENDPOINT, StringComparisonType.CONTAINS)),
            ).matchAll(),
            describe = { match -> match.originalMethod.toString() },
        )
    val stateType = builder.classDef.type
    val watched = watchedSeenFields(builder.method, builder.classDef.fields, stateType)
    val emptyState = pendingStateEmptyMethod(builder.classDef.methods, stateType)
    val dispatcher = seenDispatcherMethod(stateType, emptyState)

    builder.method.insertStoryHideGate(
        index = 0,
        stateRegister = builder.method.parameterRegisterStart(),
        watched = watched,
    )

    val mutableDispatcher =
        requireExactlyOne(
            "mutable seen-state dispatcher in ${dispatcher.definingClass}",
            patchContext.mutableClassDefBy(dispatcher.definingClass).methods.filter { method ->
                method.sameSignatureAs(dispatcher)
            },
            describe = { method -> method.toString() },
        )
    mutableDispatcher.insertStoryHideGate(
        index = 0,
        stateRegister = mutableDispatcher.registerOfParameterIndex(0),
        watched = watched,
    )
}

/**
 * Runs the story toggle at [index] and, when it is on, empties the watched collections of the seen state
 * held in [stateRegister] before the original instruction runs. When the toggle is off the hook branches
 * straight to the original instruction, so the code it guards is unchanged.
 */
internal fun MutableMethod.insertStoryHideGate(
    index: Int,
    stateRegister: Int,
    watched: WatchedSeenFields,
) {
    if (stateRegister >= FOUR_BIT_REGISTER_LIMIT) {
        throw PatchException(
            "Cannot gate $definingClass->$name: the state register v$stateRegister is beyond the four-bit range " +
                "that iget can address",
        )
    }
    insertHook(index = index, excludedRegisters = parameterBlock(), relocateBranchTargets = true) {
        val enabled = scratchRegister()
        invokeStatic(methodReference(HIDE_STORY_VIEWS))
        moveResult(enabled, "Z")
        ifEqz(enabled, Target.Original)

        val collection = scratchRegister()
        iget(collection, stateRegister, watched.reels)
        invokeVirtual(methodReference("Ljava/util/AbstractMap;->clear()V"), collection)
        iget(collection, stateRegister, watched.forced)
        invokeInterface(methodReference("Ljava/util/List;->clear()V"), collection)
    }
}

/**
 * The reels map and the forced list of the pending seen state. The reels map is the map field the builder
 * reads directly before its `size()` call, which is the value that sets `reel=`. The forced list is the
 * state's only non-static `List` field. Each anchor must match exactly one field.
 */
context(patchContext: BytecodePatchContext)
private fun watchedSeenFields(
    builder: Method,
    fields: Iterable<Field>,
    stateType: String,
): WatchedSeenFields {
    val instructions = builder.implementation?.instructions?.toList().orEmpty()
    val reels =
        instructions.indices.mapNotNull { index ->
            val instruction = instructions[index]
            val isSizeCall = instruction.methodRef()?.let { it.name == "size" && it.parameterTypes.isEmpty() } == true
            if (!isSizeCall) return@mapNotNull null

            val receiver = instruction.registers().firstOrNull() ?: return@mapNotNull null
            val read = instructions.getOrNull(index - 1) ?: return@mapNotNull null
            val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNull null
            field.takeIf {
                read.opcode == Opcode.IGET_OBJECT &&
                    field.definingClass == stateType &&
                    field.type == HASH_MAP_DESCRIPTOR &&
                    read.registers().firstOrNull() == receiver
            }
        }.distinct()

    val forced =
        fields.filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && patchContext.isAssignableTo(field.type, LIST_DESCRIPTOR)
        }

    return WatchedSeenFields(
        reels =
            requireExactlyOne(
                "reels map read by the builder's size check on $stateType",
                reels,
                describe = { field -> "${field.definingClass}->${field.name}:${field.type}" },
            ),
        forced =
            requireExactlyOne(
                "forced seen-id list on $stateType",
                forced,
                describe = { field -> "${field.definingClass}->${field.name}:${field.type}" },
            ),
    )
}

/**
 * The method that reports whether the pending seen state has nothing to send. It checks the same three
 * collections (watched reels, forced IDs, and NUX state), so it is the only parameterless instance method
 * returning `Z` that asks three collections whether they are empty. The gates preserve the NUX collection.
 */
private fun pendingStateEmptyMethod(
    methods: Iterable<Method>,
    stateType: String,
): Method =
    requireExactlyOne(
        "pending seen state emptiness method on $stateType",
        methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
                !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) &&
                method.returnType == "Z" &&
                method.parameterTypes.isEmpty() &&
                method.implementation
                    ?.instructions
                    ?.toList()
                    .orEmpty()
                    .count { instruction ->
                        instruction.methodRef()?.let { it.name == "isEmpty" && it.parameterTypes.isEmpty() } == true
                    } == 3
        },
        describe = { method -> method.toString() },
    )

/**
 * The method that dispatches one pending seen state. It takes the state as its only parameter and its
 * first instruction is the state's emptiness check on that parameter, so a gate at its entry runs before the
 * state is inspected or a request is built. Exactly one such method may exist in the app.
 */
context(patchContext: BytecodePatchContext)
private fun seenDispatcherMethod(
    stateType: String,
    emptyMethod: Method,
): Method =
    requireExactlyOne(
        "seen-state dispatcher for $stateType",
        classDefFlatMap { classDef ->
            classDef.methods.filter { method ->
                method.returnType == "V" &&
                    method.parameterTypes.map { it.toString() } == listOf(stateType) &&
                    method.implementation?.instructions?.firstOrNull()?.let { instruction ->
                        val check = instruction.methodRef()
                        check != null &&
                            check.definingClass == stateType &&
                            check.name == emptyMethod.name &&
                            check.parameterTypes.isEmpty() &&
                            check.returnType == "Z" &&
                            instruction.registers().firstOrNull() == method.registerOfParameterIndex(0)
                    } == true
            }
        },
        describe = { method -> "${method.definingClass}->${method.name}" },
    )
