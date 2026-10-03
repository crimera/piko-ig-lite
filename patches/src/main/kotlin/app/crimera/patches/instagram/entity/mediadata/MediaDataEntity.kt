/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.entity.mediadata

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

private const val IMAGE_INFO_CLASS = "Lcom/instagram/model/mediasize/ImageInfo;"

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

            // Extracting image variants list. The variants method is invoked on the ImageInfo value, so
            // resolve it from the media image-info setter instead of the midcard helper.
            run {
                val imageVariantsReferences =
                    mutableClassDefBy(MEDIA_CLASS_NAME).methods
                        .asSequence()
                        .flatMap { method -> method.instructions.asSequence() }
                        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
                        .filter { reference ->
                            reference.definingClass == IMAGE_INFO_CLASS &&
                                reference.returnType == "Ljava/util/List;" &&
                                reference.parameterTypes.isEmpty()
                        }.distinct()
                        .toList()
                val imageVariantsMethodName =
                    requireAtMostOne("image variants ImageInfo accessor", imageVariantsReferences)?.name
                if (imageVariantsMethodName != null) {
                    GetImageVariantsExtensionFingerprint.changeStringAt(1, imageVariantsMethodName)
                }
            }

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

            // Extracting get video variants. Optional fallback: newer releases dropped the anchor
            // string and rely on the V2 field mapping instead.
            VideoMediaInIGTVFeedHasVideoVariantsFingerprint.matchOrNull()?.method?.apply {
                val firstInvokeInterfaceInstruction = getInstruction(indexOfFirstInstruction(Opcode.INVOKE_INTERFACE))
                val getVideoVariantsMethodName = firstInvokeInterfaceInstruction.methodExtractor().name
                GetVideoVariantsV1ExtensionFingerprint.changeFirstString(getVideoVariantsMethodName)
            }

            // Extracting method is video used in media class.
            AslSessionRelatedFingerprint.method.apply {
                val stringIndex = AslSessionRelatedFingerprint.stringMatches[1].index
                val isVideoVirtualInvokeIndex = indexOfFirstInstruction(stringIndex, Opcode.INVOKE_VIRTUAL)
                val isVideoCallingMethodName = getInstruction(isVideoVirtualInvokeIndex).methodExtractor().name
                IsVideoExtensionFingerprint.changeFirstString(isVideoCallingMethodName)
            }

            // Extraction of extended media data field.
            // Extraction of media list from extended media data.
            var foundMediaListMethod = false
            EditMediaInfoFragmentMediaSizeFingerprint.method.apply {
                val firstReturnIndex = indexOfFirstInstruction(Opcode.RETURN)

                val extendedDataFieldIndex = indexOfFirstInstruction(firstReturnIndex, Opcode.IGET_OBJECT)
                // If iget-object is found after return instruction.
                if (extendedDataFieldIndex > 0) {
                    val extendedDataFieldName =
                        getInstruction(
                            extendedDataFieldIndex,
                        ).fieldExtractor().name
                    val mediaListMethodName = getInstruction(extendedDataFieldIndex + 1).methodExtractor().name

                    GetExtendedDataExtensionFingerprint.changeFirstString(extendedDataFieldName)
                    GetMediaListExtensionFingerprint.changeFirstString(mediaListMethodName)
                    foundMediaListMethod = true
                }
            }

            // Backup for media list extraction if the first fingerprint fails.
            if (!foundMediaListMethod) {
                GetAndroidLinkFromMediaObject.matchOrNull()?.method?.apply {
                    val firstIfNeIndex = indexOfFirstInstruction(Opcode.IF_NE)

                    val extendedDataFieldIndex = indexOfFirstInstruction(firstIfNeIndex, Opcode.IGET_OBJECT)
                    val extendedDataFieldName =
                        getInstruction(
                            extendedDataFieldIndex,
                        ).fieldExtractor().name
                    val mediaListMethodName = getInstruction(extendedDataFieldIndex + 1).methodExtractor().name

                    GetExtendedDataExtensionFingerprint.changeFirstString(extendedDataFieldName)
                    GetMediaListExtensionFingerprint.changeFirstString(mediaListMethodName)
                    foundMediaListMethod = true
                }
            }

            // Flat media model: the media object itself exposes the extended-data accessors.
            // The extension build constant-folds a source-level sentinel away, so redirect the
            // compiled extension method to return the wrapped media object instead.
            if (!foundMediaListMethod) {
                val mediaListMethods =
                    EditMediaInfoFragmentMediaSizeFingerprint.method.instructions
                        .mapNotNull { it.getReference<MethodReference>() }
                        .filter { reference ->
                            reference.definingClass == MEDIA_CLASS_NAME &&
                                reference.returnType == "Ljava/util/List;" &&
                                reference.parameterTypes.isEmpty()
                        }.map { it.name }
                        .distinct()
                if (mediaListMethods.size != 1) {
                    throw PatchException(
                        "Expected one flat media list method, found ${mediaListMethods.size}: $mediaListMethods",
                    )
                }
                GetMediaListExtensionFingerprint.changeFirstString(mediaListMethods.single())
                GetExtendedDataExtensionFingerprint.method.addInstructions(
                    0,
                    """
                    iget-object p0, p0, $EXTENSION_ENTITY_DESCRIPTOR->obj:Ljava/lang/Object;
                    return-object p0
                    """.trimIndent(),
                )
                foundMediaListMethod = true
            }

            // Extraction of media pkid from media class.
            FanClubContentPreviewInteractorImplFingerprint.method.apply {
                val strIndex = FanClubContentPreviewInteractorImplFingerprint.stringMatches[1].index

                val mediaPkIdMethodName = instructions[indexOfFirstInstruction(strIndex, Opcode.INVOKE_VIRTUAL)].methodExtractor().name
                GetMediaPkIdExtensionFingerprint.changeFirstString(mediaPkIdMethodName)
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

                method.apply {
                    // Resolve the video versions field by its Pando key. The mapper no longer keeps the
                    // field at a fixed offset from the last anchor string on newer releases.
                    val videoVersionsIndex =
                        instructions.indexOfFirst { instruction ->
                            (instruction.opcode == Opcode.CONST_STRING ||
                                instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                                instruction.getReference<StringReference>()?.string == "video_versions"
                        }
                    if (videoVersionsIndex < 0) {
                        throw PatchException("video_versions key not found in video info mapper")
                    }
                    // The value is read right after the key and handed to a helper together with it, or on
                    // newer releases copied into a list first and put under the key afterwards. So read
                    // the field before the next call; otherwise take the nearest `List` field read before
                    // the key.
                    val mapperInstructions = instructions.toList()
                    val fieldAfterKey =
                        mapperInstructions
                            .drop(videoVersionsIndex + 1)
                            .takeWhile { it.opcode != Opcode.INVOKE_STATIC && it.opcode != Opcode.INVOKE_VIRTUAL }
                            .firstOrNull { it.opcode == Opcode.IGET_OBJECT }
                    // resolver-lint: allow instruction-order raw-last because the value list is read just before the key it is put under.
                    val fieldBeforeKey =
                        mapperInstructions
                            .take(videoVersionsIndex)
                            .lastOrNull { instruction ->
                                instruction.opcode == Opcode.IGET_OBJECT &&
                                    instruction.getReference<FieldReference>()?.type == "Ljava/util/List;"
                            }
                    val videoVariantsListFieldName =
                        (fieldAfterKey ?: fieldBeforeKey ?: throw PatchException("video_versions value field not found in video info mapper"))
                            .fieldExtractor()
                            .name

                    GetVideoVariantsV2ExtensionFingerprint.changeFirstString(videoVariantsListFieldName)
                }

                ExtMediaDictImageInfoMapperFingerprint.apply {
                    // resolver-lint: allow instruction-order raw-first because the first anchor marks the image-info list boundary.
                    val strIndex = stringMatches.first().index
                    method.apply {
                        val imageInfoListFieldName = getInstruction(strIndex + 2).fieldExtractor().name
                        GetImageVariantsExtensionFingerprint.changeFirstString(imageInfoListFieldName)
                    }
                }
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

            // End.
        }
    }
