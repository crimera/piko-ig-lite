/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.entity.mediadata

import app.crimera.patches.common.isAssignableTo
import app.crimera.patches.common.requireAtMostOne
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.instagram.entity.decoder.EditMediaInfoGetCurrentMediaIdFingerprint
import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.MEDIAEXT_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.utils.changeFirstString
import app.crimera.utils.changeStringAt
import app.crimera.utils.classNameToExtension
import app.crimera.utils.fieldExtractor
import app.crimera.utils.methodExtractor
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val LIST_DESCRIPTOR = "Ljava/util/List;"

val mediaDataEntity =
    bytecodePatch(
        description = "This patch is used for decoding obfuscated code of the media data",
    ) {
        dependsOn(decoderEntity)
        execute {
            GetHelperClassExtensionFingerprint.changeFirstString(classNameToExtension(MEDIAEXT_CLASS_NAME))

            // Extracting get original sound info data using media and user session.
            GetOriginalSoundDataIntfExtensionFingerprint.changeFirstString(GetOriginalSoundDataIntfFromMediaFingerprint.method.name)

            // Extracting get user data using media and user session.
            GetUserDataWithUserSessionExtensionFingerprint.changeFirstString(GetUserDataFromMediaFingerprint.method.name)

            // Extracting the get mention set method used media helper class.
            GetMentionSetExtensionFingerprint.changeFirstString(LiveTreeMediaDictReelsMentionFingerprint.method.name)

            InstagramMainActivityNotificationRelatedFingerprint.apply {
                // resolver-lint: allow instruction-order raw-last because the last anchor string marks the mention/user boundary.
                val strIndex = stringMatches.last().index
                method.apply {
                    val getUserDataInvokeIndex =
                        instructions.indexOfLast {
                            it.opcode == Opcode.INVOKE_INTERFACE &&
                                it.location.index < strIndex
                        }
                    val methodName = getInstruction(getUserDataInvokeIndex).methodExtractor().name
                    GetMentionSetExtensionFingerprint.changeStringAt(1, methodName)
                }
            }

            // Extraction of extended media data field, still read reflectively by the non-download accessors
            // (post id, post type, mentions, session-less user data).
            var foundExtendedData = false
            EditMediaInfoFragmentMediaSizeFingerprint.method.apply {
                val firstReturnIndex = indexOfFirstInstruction(Opcode.RETURN)

                val extendedDataFieldIndex = indexOfFirstInstruction(firstReturnIndex, Opcode.IGET_OBJECT)
                // If iget-object is found after return instruction.
                if (extendedDataFieldIndex > 0) {
                    val extendedDataFieldName =
                        getInstruction(
                            extendedDataFieldIndex,
                        ).fieldExtractor().name

                    GetExtendedDataExtensionFingerprint.changeFirstString(extendedDataFieldName)
                    foundExtendedData = true
                }
            }

            // Backup for media list extraction if the first fingerprint fails.
            if (!foundExtendedData) {
                GetAndroidLinkFromMediaObject.matchOrNull()?.method?.apply {
                    val firstIfNeIndex = indexOfFirstInstruction(Opcode.IF_NE)

                    val extendedDataFieldIndex = indexOfFirstInstruction(firstIfNeIndex, Opcode.IGET_OBJECT)
                    val extendedDataFieldName =
                        getInstruction(
                            extendedDataFieldIndex,
                        ).fieldExtractor().name

                    GetExtendedDataExtensionFingerprint.changeFirstString(extendedDataFieldName)
                    foundExtendedData = true
                }
            }

            // Flat media model: the media object itself exposes the extended-data accessors.
            // The extension build constant-folds a source-level sentinel away, so redirect the
            // compiled extension method to return the wrapped media object instead.
            if (!foundExtendedData) {
                val mediaListMethods =
                    EditMediaInfoFragmentMediaSizeFingerprint.method.instructions
                        .mapNotNull { it.getReference<MethodReference>() }
                        .filter { reference ->
                            reference.definingClass == MEDIA_CLASS_NAME &&
                                isAssignableTo(reference.returnType, LIST_DESCRIPTOR) &&
                                reference.parameterTypes.isEmpty()
                        }.map { it.name }
                        .distinct()
                if (mediaListMethods.size != 1) {
                    throw PatchException(
                        "Expected one flat media list method, found ${mediaListMethods.size}: $mediaListMethods",
                    )
                }
                GetExtendedDataExtensionFingerprint.method.addInstructions(
                    0,
                    """
                    iget-object p0, p0, $EXTENSION_ENTITY_DESCRIPTOR->obj:Ljava/lang/Object;
                    return-object p0
                    """.trimIndent(),
                )
                foundExtendedData = true
            }

            // Extraction of user data used in extended media class. Optional: only the session-less
            // fallback needs it, and newer releases dropped the anchor.
            LiveTreeMediaDictGetUserFingerprint.matchOrNull()?.method?.let { getUserMethod ->
                GetUserDataWithoutUserSessionExtensionFingerprint.changeFirstString(getUserMethod.name)
            }

            // Extraction of description
            val commentObjectClassName: String
            CommentToStringFingerprint.apply {
                commentObjectClassName = classDef.type
                method.apply {
                    val getCommentTextFieldName = instructions.last { it.opcode == Opcode.IGET_OBJECT }.fieldExtractor().name
                    GetDescriptionTextExtensionFingerprint.changeStringAt(1, getCommentTextFieldName)
                }
            }

            val getCommentDataFromMediaMethodName =
                mutableClassDefBy { it.type == MEDIAEXT_CLASS_NAME }
                    .methods
                    // resolver-lint: allow instruction-order raw-first because the first comment-data accessor is the description accessor.
                    .first {
                        it.returnType ==
                            commentObjectClassName
                    }.name
            GetDescriptionTextExtensionFingerprint.changeFirstString(getCommentDataFromMediaMethodName)

            // Extraction of trackInfo. Optional (audio metadata).
            if (GetDisplayArtistFromMusicInfoAndOriginalSoundDataFingerprint.matchOrNull() != null) {
                MusicAudioTypeEnumStringFingerprint.matchOrNull()?.method?.apply {
                    instructions.filter { it.opcode == Opcode.INVOKE_STATIC }.firstOrNull {
                        val methodExt = it.methodExtractor()
                        if (methodExt.returnType != "V") {
                            GetTrackDataIntfExtensionFingerprint.changeFirstString(methodExt.name)
                            true
                        } else {
                            false
                        }
                    }
                }
            }

            // Message audio. Optional.
            IgPlayerControllerRelatedFingerprint.matchOrNull()?.method?.apply {
                instructions.filter { it.opcode == Opcode.INVOKE_INTERFACE }.firstOrNull {
                    val methodExt = it.methodExtractor()
                    if (methodExt.returnType.endsWith("AudioIntf")) {
                        GetMessageAudioUrlExtensionFingerprint.changeFirstString(methodExt.name)
                        true
                    } else {
                        false
                    }
                }
            }

            AudioIntfMapperFingerprint.matchOrNull()?.let { audioMapper ->
                val audioSrcMatches = audioMapper.stringMatches.filter { it.string == AUDIO_SRC_KEY }
                val strIndex = requireExactlyOne("audio_src anchor", audioSrcMatches).index
                audioMapper.method.apply {
                    val getAudioSrcInvokeIndex = indexOfFirstInstruction(strIndex, Opcode.INVOKE_INTERFACE)
                    val methodName = getInstruction(getAudioSrcInvokeIndex).methodExtractor().name
                    GetMessageAudioUrlExtensionFingerprint.changeStringAt(1, methodName)
                }
            }

            // More extended data.
            ExtMediaDictVideoInfoMapperFingerprint.apply {
                val moreExtendedMediaDataFieldName =
                    requireExactlyOne(
                        "more extended media data field",
                        LiveTreeMediaDictReelsMentionFingerprint.classDef.fields
                            .filter { it.type == classDef.type },
                    ).name
                GetMoreExtendedDataExtensionFingerprint.changeFirstString(moreExtendedMediaDataFieldName)
            }

            ProductInfoMapperFingerprint.apply {
                // resolver-lint: allow instruction-order raw-last because the last anchor marks the product-type boundary.
                val strIndex = stringMatches.last().index
                method.apply {
                    val productTypeIGetObjectInstruction = getInstruction(indexOfFirstInstruction(strIndex, Opcode.IGET_OBJECT))
                    val productTypeFieldName = productTypeIGetObjectInstruction.fieldExtractor().name
                    GetPostTypeExtensionFingerprint.changeFirstString(productTypeFieldName)
                }
            }

            // The download path reads the media through typed bridges instead of the reflective accessors.
            injectMediaBridges()

            // End.
        }
    }
