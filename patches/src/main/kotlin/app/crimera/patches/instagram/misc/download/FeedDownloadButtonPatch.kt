/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download

import app.crimera.bytecode.Target
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.entity.dialogbox.instagramDialogBoxEntity
import app.crimera.patches.instagram.entity.mediadata.mediaDataEntity
import app.crimera.patches.instagram.entity.originalSoundDataIntf.originalSoundDataIntfEntity
import app.crimera.patches.instagram.entity.trackDataIntf.trackDataIntfEntity
import app.crimera.patches.instagram.entity.videoData.videoDataEntity
import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.misc.settings.addSettingsActivityPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val VIEW_DESCRIPTOR = "Landroid/view/View;"
private const val MEDIA_DESCRIPTOR = "Lcom/instagram/feed/media/Media;"
private const val USER_SESSION_DESCRIPTOR = "Lcom/instagram/common/session/UserSession;"
private const val EXTENSION_METHOD =
    "$DOWNLOAD_DESCRIPTOR/DownloadUtils;->addFeedDownloadButton" +
        "(Landroid/view/View;Ljava/lang/Object;Lcom/instagram/common/session/UserSession;)V"

private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val INTEGER_DESCRIPTOR = "Ljava/lang/Integer;"
private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

/** The main feed module. The UFI variant selector is only consulted for this module. */
private const val MAIN_FEED_MODULE = "feed_timeline"

/** MobileConfig value that selects the classic view based UFI row. */
private const val VIEW_UFI_VARIANT = "view"

private const val LITHO_UFI_VARIANT = "litho"

/**
 * The feed post action row (like / comment / repost / share / save) is a plain view holder whose
 * constructor resolves `row_feed_button_save` with `requireViewById`. Matching that resource literal
 * finds the holder class without depending on obfuscated names. The root view is the only `View`
 * field assigned directly from the constructor's `View` parameter.
 */
private fun Method.resolveRootViewField(): FieldReference {
    val parameterRegister = registerOfParameter(VIEW_DESCRIPTOR)
    val instructions = implementation?.instructions?.toList() ?: emptyList()
    val candidates =
        instructions
            .mapNotNull { instruction ->
                if (instruction.opcode != Opcode.IPUT_OBJECT) return@mapNotNull null
                val twoRegister = instruction as TwoRegisterInstruction
                if (twoRegister.registerA != parameterRegister) return@mapNotNull null
                twoRegister.getReference<FieldReference>()?.takeIf { it.definingClass == definingClass }
            }.distinctBy { it.toString() }

    return requireExactlyOne("feed UFI root view field in $this", candidates)
}

private fun Method.parameterWords(): Int {
    var words = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    parameterTypes.forEach { words += if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return words
}

private fun Method.parameterRegisterStart(): Int =
    (implementation?.registerCount ?: 0) - parameterWords()

private fun Method.registerOfParameter(descriptor: String): Int {
    var register = parameterRegisterStart() + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    parameterTypes.forEach { type ->
        val value = type.toString()
        if (value == descriptor) return register
        register += if (value == "J" || value == "D") 2 else 1
    }
    throw PatchException("Method $this has no $descriptor parameter")
}

private fun Method.sameSignatureAs(other: Method): Boolean =
    name == other.name &&
        returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

/**
 * The feed row type decides how the UFI row is rendered:
 * `MEDIA_UFI` (view), `LITHO_MEDIA_UFI` or `COMPOSE_MEDIA_UFI`. Only the view renderer creates the
 * `LX/00uM` holder this patch extends, so the main feed is pinned to `view`. The mock value is the
 * same `Integer` the selector would return for the `view` MobileConfig value, so the row type
 * contract is unchanged. Non feed modules keep the original selector result.
 */
private fun Method.resolveViewVariantField(): FieldReference {
    val instructions = implementation?.instructions?.toList() ?: emptyList()
    val viewStringIndex =
        instructions.indexOfFirst { instruction ->
            instruction.getReference<StringReference>()?.string == VIEW_UFI_VARIANT
        }
    if (viewStringIndex < 0) {
        throw PatchException("No \"$VIEW_UFI_VARIANT\" variant in UFI selector $this")
    }

    val candidates =
        instructions
            .drop(viewStringIndex + 1)
            .take(8)
            .mapNotNull { instruction ->
                if (instruction.opcode != Opcode.SGET_OBJECT) return@mapNotNull null
                instruction.getReference<FieldReference>()?.takeIf { it.type == INTEGER_DESCRIPTOR }
            }.distinctBy { it.toString() }

    return requireExactlyOne("view UFI variant field in $this", candidates)
}

@Suppress("unused")
val feedDownloadButtonPatch =
    bytecodePatch(
        name = "Download button on feed posts",
        description = "Adds a download button beside the save icon on feed posts.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(
            sharedExtensionPatch,
            // The download options dialog is an `InstagramDialogBox` wrapper. Without this
            // entity patch its placeholder class/method names are never resolved, so the
            // click handler dies in `addDialogMenuItems` with "Invoke failed: A0T".
            instagramDialogBoxEntity,
            mediaDataEntity,
            videoDataEntity,
            originalSoundDataIntfEntity,
            trackDataIntfEntity,
            decoderEntity,
            resourceMappingPatch,
            // Download folder selection runs through the shared FolderPickerActivity, which has
            // to be declared in the manifest, and every dialog/toast label uses the bundled piko
            // string resources. Neither bootstrap is part of the base extension patch.
            addSettingsActivityPatch,
            addResourcesPatch,
        )

        execute {
            addAppResources("shared")
            addAppResources("instagram")

            val saveButtonId = getResourceId(ResourceType.ID, "row_feed_button_save")

            val holderMatches =
                Fingerprint(
                    name = "<init>",
                    returnType = "V",
                    parameters = listOf(VIEW_DESCRIPTOR),
                    filters = listOf(literal(saveButtonId)),
                ).matchAll()

            if (holderMatches.size != 1) {
                throw PatchException(
                    "Expected one feed UFI row holder, found ${holderMatches.size}: " +
                        holderMatches.joinToString { it.originalMethod.toString() },
                )
            }

            val holderMatch = holderMatches.single()
            val holderClass = holderMatch.classDef
            val holderDescriptor = holderClass.type
            val rootViewField = holderMatch.method.resolveRootViewField()

            // The feed item state is the holder field type that exposes exactly one Media.
            val stateType =
                requireExactlyOne(
                    "feed UFI media state type for $holderDescriptor",
                    holderClass.fields
                        .map { it.type }
                        .filter { type ->
                            classDefByOrNull(type)?.fields?.count { it.type == MEDIA_DESCRIPTOR } == 1
                        }.distinct(),
                )

            val stateClass: ClassDef = classDefBy(stateType)
            val mediaField = requireExactlyOne("Media field on $stateType", stateClass.fields.filter { it.type == MEDIA_DESCRIPTOR })

            // The binder method receives both the holder and the media state.
            val bindCandidates = mutableListOf<Method>()
            classDefForEach { classDef ->
                classDef.methods.forEach { method ->
                    if (method.returnType != "V") return@forEach
                    val parameters = method.parameterTypes.map { it.toString() }
                    if (holderDescriptor in parameters && stateType in parameters) {
                        bindCandidates.add(method)
                    }
                }
            }

            val bindMethod = requireExactlyOne("feed UFI bind method", bindCandidates)

            val binderClass: ClassDef = classDefBy(bindMethod.definingClass)
            val userSessionField =
                requireExactlyOne(
                    "UserSession field on ${binderClass.type}",
                    binderClass.fields.filter { it.type == USER_SESSION_DESCRIPTOR },
                )

            val holderParameterRegister = bindMethod.registerOfParameter(holderDescriptor)
            val stateParameterRegister = bindMethod.registerOfParameter(stateType)
            val thisRegister = bindMethod.parameterRegisterStart()

            val mutableBind =
                requireExactlyOne(
                    "feed UFI bind method to patch",
                    mutableClassDefBy(bindMethod.definingClass).methods.filter { it.sameSignatureAs(bindMethod) },
                )

            mutableBind.insertHook(
                index = 0,
                excludedRegisters = (thisRegister until thisRegister + bindMethod.parameterWords()).toList(),
                relocateBranchTargets = false,
            ) {
                val holder = scratchRegister()
                move(holder, holderParameterRegister, holderDescriptor)
                val rootView = scratchRegister()
                iget(rootView, holder, rootViewField)

                val state = scratchRegister()
                move(state, stateParameterRegister, stateType)
                val media = scratchRegister()
                iget(media, state, mediaField)

                val binder = scratchRegister()
                move(binder, thisRegister, binderClass.type)
                val userSession = scratchRegister()
                iget(userSession, binder, userSessionField)

                invokeStatic(methodReference(EXTENSION_METHOD), rootView, media, userSession)
            }

            // The same feed post can render its UFI row as a view, a Litho component or a Compose
            // component, selected by a MobileConfig value. Only the view renderer creates the
            // `LX/00uM` holder extended above, so pin the main feed to it. Modules other than the
            // main feed keep the original selector result.
            val variantSelectorMatches =
                Fingerprint(
                    returnType = INTEGER_DESCRIPTOR,
                    strings = listOf(MAIN_FEED_MODULE, LITHO_UFI_VARIANT, VIEW_UFI_VARIANT),
                ).matchAll()

            if (variantSelectorMatches.size != 1) {
                throw PatchException(
                    "Expected one feed UFI variant selector, found ${variantSelectorMatches.size}: " +
                        variantSelectorMatches.joinToString { it.originalMethod.toString() },
                )
            }

            val selectorMethod = variantSelectorMatches.single().method
            val viewVariantField = selectorMethod.resolveViewVariantField()
            val moduleRegister = selectorMethod.registerOfParameter(STRING_DESCRIPTOR)
            val selectorThisRegister = selectorMethod.parameterRegisterStart()

            val mutableSelector =
                requireExactlyOne(
                    "feed UFI variant selector to patch",
                    mutableClassDefBy(selectorMethod.definingClass)
                        .methods
                        .filter { it.sameSignatureAs(selectorMethod) },
                )

            mutableSelector.insertHook(
                index = 0,
                excludedRegisters =
                    (selectorThisRegister until selectorThisRegister + selectorMethod.parameterWords()).toList(),
                relocateBranchTargets = false,
            ) {
                val expectedModule = scratchRegister()
                constString(expectedModule, MAIN_FEED_MODULE)
                val isMainFeed = scratchRegister()
                invokeVirtual(methodReference(STRING_EQUALS), moduleRegister, expectedModule)
                moveResult(isMainFeed, "Z")
                ifEqz(isMainFeed, Target.Original)

                val variant = scratchRegister()
                sget(variant, viewVariantField)
                returnObject(variant)
            }
        }
    }
