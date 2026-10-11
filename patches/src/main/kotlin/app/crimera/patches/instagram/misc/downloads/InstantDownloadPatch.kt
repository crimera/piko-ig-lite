/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.downloads

import app.crimera.bytecode.Target
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.instagram.misc.extension.hooks.instagramInitHook
import app.crimera.patches.instagram.misc.extension.hooks.instagramInitInsertIndex
import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.misc.settings.Categories
import app.crimera.patches.instagram.misc.settings.instagramToggle
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val INSTANT_DESCRIPTOR = "$DOWNLOAD_DESCRIPTOR/InstantDownloadButton;"
private const val INSTANT_INSTALL = "$INSTANT_DESCRIPTOR->install()V"
private const val INSTANT_MEDIA_SEEN = "$INSTANT_DESCRIPTOR->onInstantMediaSeen(Ljava/lang/Object;)V"
private const val QUICKSNAP_PACKAGE = "Lcom/instagram/quicksnap/"
private const val MEDIA_DESCRIPTOR = "Lcom/instagram/feed/media/Media;"
private const val USER_DESCRIPTOR = "Lcom/instagram/user/model/User;"

/**
 * Download button on the Instants viewer, placed left of the grid icon.
 *
 * Every obfuscated member is resolved from shape:
 *  - an Instant item is a class with both a `Media` and a `User` instance field that QuickSnap code
 *    references (the reference check is what separates it from feed, reel and direct models),
 *  - the viewer state is the one class whose no-argument getter returns that item,
 *  - the `Media` is the item's one `Media` field.
 * Each void method that takes the viewer state gets an entry hook that reads the current item's `Media`
 * and hands it to the extension. A null item (an empty list) skips the hook.
 */
@Suppress("unused")
val instantDownloadPatch =
    bytecodePatch(
        description = "Adds a download button left of the grid icon on the Instants viewer.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(sharedExtensionPatch)

        instagramToggle(
            id = "instagram.downloads.instant_button",
            category = Categories.DOWNLOADS,
            strings = settingStrings("piko_ig_instant_download_button"),
            order = 350,
            defaultValue = true,
        )

        execute {
            val viewer = resolveInstantViewer()
            viewerStateMethods(viewer.stateType).forEach { method ->
                hookViewerStateMethod(method, viewer)
            }

            val initMethod = instagramInitHook.fingerprint.method
            initMethod.insertHook(
                index = instagramInitInsertIndex(initMethod),
                relocateBranchTargets = false,
            ) {
                invokeStatic(methodReference(INSTANT_INSTALL))
            }
        }
    }

/** The resolved shape of the Instants viewer: its state class, the item getter and the item's `Media` field. */
private class InstantViewer(
    val stateType: String,
    val itemType: String,
    val itemGetter: Method,
    val mediaField: FieldReference,
)

context(patchContext: BytecodePatchContext)
private fun resolveInstantViewer(): InstantViewer {
    val mediaUserTypes = mutableSetOf<String>()
    patchContext.classDefForEach { classDef ->
        val fieldTypes = classDef.fields.map { it.type.toString() }
        if (MEDIA_DESCRIPTOR in fieldTypes && USER_DESCRIPTOR in fieldTypes) mediaUserTypes.add(classDef.type)
    }

    // Every type a QuickSnap class mentions: an item is one of the Media-and-User classes it references.
    val quickSnapReferences = mutableSetOf<String>()
    patchContext.classDefForEach { classDef ->
        if (!classDef.type.startsWith(QUICKSNAP_PACKAGE)) return@classDefForEach
        classDef.methods.forEach { method ->
            quickSnapReferences.add(method.returnType.toString())
            method.parameterTypes.forEach { quickSnapReferences.add(it.toString()) }
            method.implementation?.instructions?.forEach { instruction ->
                (instruction as? ReferenceInstruction)?.reference?.toString()?.let { quickSnapReferences.add(it) }
            }
        }
    }
    val itemTypes = mediaUserTypes.filterTo(mutableSetOf()) { type -> quickSnapReferences.any { it.contains(type) } }

    val stateClasses = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classDef ->
        val returnsItem =
            classDef.methods.any { method ->
                method.parameterTypes.isEmpty() && method.returnType.toString() in itemTypes
            }
        if (returnsItem) stateClasses.add(classDef)
    }
    val stateClass = requireExactlyOne("Instants viewer state class", stateClasses)

    val itemGetter =
        requireExactlyOne(
            "Instants item getter of ${stateClass.type}",
            stateClass.methods.filter { method ->
                method.parameterTypes.isEmpty() && method.returnType.toString() in itemTypes
            },
        )
    val itemType = itemGetter.returnType.toString()

    val mediaField =
        requireExactlyOne(
            "Media field of $itemType",
            patchContext.classDefBy(itemType).fields.filter { it.type.toString() == MEDIA_DESCRIPTOR },
        )
    return InstantViewer(stateClass.type, itemType, itemGetter, mediaField)
}

/** Every concrete void method that takes the viewer state. Constructors are excluded: no code may precede `super`. */
context(patchContext: BytecodePatchContext)
private fun viewerStateMethods(stateType: String): List<Method> {
    val methods = mutableListOf<Method>()
    patchContext.classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.implementation == null || method.name == "<init>") return@forEach
            if (method.returnType.toString() != "V") return@forEach
            if (method.parameterTypes.none { it.toString() == stateType }) return@forEach
            methods.add(method)
        }
    }
    if (methods.isEmpty()) throw PatchException("No void method takes the Instants viewer state $stateType")
    return methods
}

context(_: BytecodePatchContext)
private fun hookViewerStateMethod(
    method: Method,
    viewer: InstantViewer,
) {
    val stateRegister = method.registerOfParameter(viewer.stateType)
    method.toMutable("Instants download ${method.name}").insertHook(
        index = 0,
        excludedRegisters = method.parameterBlock(),
        relocateBranchTargets = true,
    ) {
        val state = scratchRegister()
        move(state, stateRegister, viewer.stateType)
        val media = scratchRegister()
        invokeVirtual(viewer.itemGetter, state)
        moveResult(media, viewer.itemType)
        ifEqz(media, Target.Original)
        iget(media, media, viewer.mediaField)
        ifEqz(media, Target.Original)
        invokeStatic(methodReference(INSTANT_MEDIA_SEEN), media)
    }
}
