package app.revanced.bilibili.patches.protobuf

import androidx.annotation.Keep
import app.revanced.bilibili.account.Accounts
import app.revanced.bilibili.api.MossResponseHandlerProxy
import app.revanced.bilibili.meta.Client
import app.revanced.bilibili.meta.HookFlags
import app.revanced.bilibili.patches.protobuf.hooks.*
import app.revanced.bilibili.settings.Settings
import app.revanced.bilibili.utils.*
import com.bapis.bilibili.metadata.network.Network
import com.bapis.bilibili.metadata.network.NetworkType
import com.bilibili.lib.moss.api.MossException
import com.bilibili.lib.moss.api.MossResponseHandler
import com.google.protobuf.GeneratedMessageLite
import java.util.AbstractMap

object MossPatch {
    private val hooks = arrayOf(
        DistributionUserPreference,
        DmSegMobile,
        DmView,
        DynAll,
        DynRed,
        DynTab,
        DynVideo,
        HomeRecent,
        ListenPlayHistory,
        ListenPlaylist,
        ListenPlayURL,
        ListenRcmdPlaylist,
        ListFavoriteTab,
        ModuleList,
        PlayHalfChannels,
        PlayURLPlayConf,
        PlayURLPlayConfEdit,
        PlayURLPlayViewUGC,
        Popular,
        ReplyDetailList,
        ReplyMainList,
        ReplyShareReplyMaterial,
        ReplySubjectDescription,
        ResourceTopActivity,
        SearchAll,
        SearchDefaultWords,
        View,
        ViewContinuousPlay,
        ViewLike,
        ViewProgress,
        ViewProgressUnite,
        ViewRelatesFeed,
        ViewRelatesFeedUnite,
        ViewTFInfo,
        ViewUnite
    )

    const val PLAY_VIEW_UNITE_API = "bilibili.app.playerunite.v1.Player/PlayViewUnite"

    /**
     * @return [HookFlags.STOP_EXECUTION] to stop method execution and return null,
     * or null to not intercept method execution,
     * or custom response,
     * or throw [MossException].
     */
    @Keep
    @JvmStatic
    fun hookBlockingBefore(req: GeneratedMessageLite<*, *>): Any? {
        return hooks.firstOrNull { it.shouldHook(req) }?.hookBefore(req)
    }

    /**
     * @return null or [reply], or custom response, or throw nonnull [error].
     *
     * **should never throw a custom exception**.
     */
    @Keep
    @JvmStatic
    fun hookBlockingAfter(
        req: GeneratedMessageLite<*, *>,
        reply: GeneratedMessageLite<*, *>?,
        error: MossException?
    ): GeneratedMessageLite<*, *>? {
        MossDebugPrinter.printBlocking(req, reply, error)
        return hooks.firstOrNull { it.shouldHook(req) }?.hookAfter(req, reply, error)
            ?: if (error != null) throw error else reply
    }

    /**
     * @return [HookFlags.STOP_EXECUTION] to stop method execution and return void,
     * or null to not intercept method execution,
     * or [handler], or a proxy handler like [MossResponseHandlerProxy].
     */
    @Keep
    @JvmStatic
    fun hookAsyncBefore(
        req: GeneratedMessageLite<*, *>,
        handler: MossResponseHandler<Any?>?
    ): Any? {
        if (handler == null) return null
        val finalHandler = MossDebugPrinter.printAsync(req, handler)
        val hook = hooks.firstOrNull { it.shouldHook(req) }
            ?: return if (Settings.Debug()) finalHandler else null
        if (hook.async) {
            Utils.async {
                try {
                    val before = hook.hookBefore(req)
                    finalHandler.onNext(before)
                    finalHandler.onCompleted()
                } catch (e: MossException) {
                    finalHandler.onError(e)
                }
            }
            return HookFlags.STOP_EXECUTION
        } else try {
            val before = hook.hookBefore(req)
                ?: return MossResponseHandlerProxy.get(finalHandler, { reply ->
                    reply as GeneratedMessageLite<*, *>?
                    hook.hookAfter(req, reply, null)
                }, { delegate, error ->
                    try {
                        val after = hook.hookAfter(req, null, error)
                        delegate.onNext(after)
                        delegate.onCompleted()
                        true
                    } catch (e: MossException) {
                        false
                    }
                })
            return if (before === HookFlags.STOP_EXECUTION) {
                finalHandler.onNext(null)
                finalHandler.onCompleted()
                HookFlags.STOP_EXECUTION
            } else {
                finalHandler.onNext(before)
                finalHandler.onCompleted()
                HookFlags.STOP_EXECUTION
            }
        } catch (e: MossException) {
            finalHandler.onError(e)
            return HookFlags.STOP_EXECUTION
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline operator fun ArrayList<Map.Entry<String, String>>.set(key: String, value: String) {
        for (i in 0 until size) {
            if (get(i).key == key) {
                removeAt(i)
                break
            }
        }
        add(AbstractMap.SimpleImmutableEntry(key, value))
    }

    @Keep
    @JvmStatic
    fun hookBeforeRequest(url: String, headers: ArrayList<Map.Entry<String, String>>): String {
        if (url.endsWith(PLAY_VIEW_UNITE_API))
            headers["authorization"] = "identify_v1 ${getFinalAccessKey(false)}"
        return url
    }
}
