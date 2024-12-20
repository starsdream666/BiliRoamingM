package app.revanced.bilibili.patches.protobuf.hooks

import android.net.Uri
import app.revanced.bilibili.patches.okhttp.hooks.Subtitle
import app.revanced.bilibili.patches.protobuf.MossHook
import app.revanced.bilibili.settings.Settings
import app.revanced.bilibili.utils.*
import com.bapis.bilibili.community.service.dm.v1.*
import com.bilibili.lib.moss.api.MossException
import com.bilibili.lib.moss.api.NetworkException
import com.google.protobuf.GeneratedMessageLite
import org.json.JSONArray
import org.json.JSONObject

object DmView : MossHook<DmViewReq, DmViewReply>() {
    private val furrySubNameExtRegex = Regex("""[\[(\s]*(非官方|富睿字幕组)[])\s]*""")

    override fun shouldHook(req: GeneratedMessageLite<*, *>): Boolean {
        return req is DmViewReq
    }

    override fun hookAfter(
        req: DmViewReq,
        reply: DmViewReply?,
        error: MossException?
    ): DmViewReply? {
        val videoPopups = Settings.RemoveVideoPopups()
        if (videoPopups.isNotEmpty() && reply != null) {
            if (videoPopups.contains("other"))
                reply.clearActivityMeta() // 云视听小电视
            runCatchingOrNull {
                val types = arrayOf(9, 5, 11, 12, 2)
                reply.command.commandDmsList.forEachIndexedReversed { index, dm ->
                    if (videoPopups.contains("vote") && (dm.command == "#VOTE#" || dm.type == 9)) // 投票弹幕
                        reply.command.removeCommandDms(index)
                    else if (videoPopups.contains("attention") && (dm.command == "#ATTENTION#" || dm.type == 5)) // 三连关注弹幕
                        reply.command.removeCommandDms(index)
                    else if (videoPopups.contains("grade") && (dm.command == "#GRADE#" || dm.type == 11)) // 评分弹幕
                        reply.command.removeCommandDms(index)
                    else if (videoPopups.contains("gradeSummary") && (dm.command == "#GRADESUMMARY#" || dm.type == 12)) // 评分总结弹幕
                        reply.command.removeCommandDms(index)
                    else if (videoPopups.contains("link") && (dm.command == "#LINK#" || dm.type == 2)) // 关联视频弹幕
                        reply.command.removeCommandDms(index)
                    else if (videoPopups.contains("other") && dm.type !in types)
                        reply.command.removeCommandDms(index)
                }
                if (videoPopups.contains("other")) {
                    reply.clearQoe()
                    reply.dmMaskWallList.mapIndexedNotNull { index, wall ->
                        if (wall.bizType == DmMaskWallBizType.AIGC) index else null
                    }.asReversed().forEach { reply.removeDmMaskWall(it) }
                }
            }
            reply.clearUnknownFields()
        }
        if (Settings.OldDmPanel() && reply != null) runCatchingOrNull {
            val kv = reply.kv
            if (kv.isNotEmpty()) {
                kv.toJSONObject().apply {
                    put("dm_config_panel_exp", false)
                    reply.kv = toString()
                }
            }
        }
        if (error !is NetworkException)
            return addSubtitles(req, reply)
        return super.hookAfter(req, reply, error)
    }

    private fun addSubtitles(dmViewReq: DmViewReq, dmViewReply: DmViewReply?): DmViewReply {
        val result = dmViewReply ?: DmViewReply()
        val extraSubtitles = ArrayList<SubtitleItem>()
        if (Settings.AutoGenerateSubtitle()) {
            val subtitles = result.subtitle.subtitlesList + extraSubtitles
            if (subtitles.map { it.lan }.let { "zh-Hant" in it && "zh-CN" !in it }) {
                val hantSub = subtitles.first { it.lan == "zh-Hant" }
                if (!hantSub.lanDoc.contains("機翻")) {
                    val cnUrl = Uri.parse(hantSub.subtitleUrl).buildUpon()
                        .appendQueryParameter("zh_converter", "t2cn")
                        .build().toString()
                    val cnId = hantSub.id + 1
                    val cnSub = SubtitleItem().apply {
                        lan = "zh-CN"
                        lanDoc = "简中（漫游生成）"
                        lanDocBrief = "简中"
                        subtitleUrl = cnUrl
                        id = cnId
                        idStr = cnId.toString()
                    }
                    extraSubtitles.add(cnSub)
                }
            }
        }
        if (Settings.AutoGenerateSubtitle()) {
            val subtitles = result.subtitle.subtitlesList + extraSubtitles
            if (subtitles.map { it.lan }.let {
                    "zh-Hans" !in it && "zh-CN" !in it && "ai-zh" !in it && it.any { lan -> lan.startsWith("en") }
                }) {
                val enSub = subtitles.first { it.lan.startsWith("en") }
                val autoCNSub = SubtitleItem().apply {
                    aiStatus = SubtitleAiStatus.Assist
                    aiType = SubtitleAiType.Translate
                    id = enSub.id + 2233
                    idStr = id.toString()
                    lan = "ai-zh"
                    lanDoc = "简中（漫游翻译）"
                    lanDocBrief = "简中"
                    subtitleUrl = Uri.parse(enSub.subtitleUrl).buildUpon()
                        .appendQueryParameter("zh_converter", "en2cn").toString()
                    type = SubtitleType.AI
                }
                extraSubtitles.add(autoCNSub)
            }
        }
        if (extraSubtitles.isNotEmpty())
            result.subtitle.addAllSubtitles(extraSubtitles)
        val subtitle = result.subtitle
        val (cid, importedSubtitles) = Subtitle.importedSubtitles
        if (cid == dmViewReq.oid && subtitle.subtitlesList.isNotEmpty() && importedSubtitles.isNotEmpty()) {
            importedSubtitles.indices.forEach { index ->
                newImportSubtitle(index, subtitle)
                    ?.let { subtitle.addSubtitles(it) }
            }
        } else if (cid != dmViewReq.oid) {
            Subtitle.importedSubtitles = dmViewReq.oid to mutableListOf()
        }
        val subtitlesList = result.subtitle.subtitlesList
        if (Settings.AutoSelectAISubtitle() && subtitlesList.map { it.lan }
                .none { it == "zh-Hans" || it == "zh-CN" }) {
            subtitlesList.find { it.lan == "ai-zh" }?.run {
                lan = "zh-Hans"
                type = SubtitleType.CC
            }
        }
        return result
    }

    private fun JSONArray.toSubtitles(): List<SubtitleItem> {
        val subList = mutableListOf<SubtitleItem>()
        val distinct = asSequence<JSONObject>().distinctBy { it.optString("key") }.toList()
        val replaceable = distinct.none { it.optString("key") == "zh-Hans" }
        var replaced = false
        for (subtitle in distinct) {
            SubtitleItem().apply {
                id = subtitle.optLong("id")
                idStr = subtitle.optLong("id").toString()
                subtitleUrl = subtitle.optString("url")
                lanDoc = subtitle.optString("title").replace(furrySubNameExtRegex, "")
                val lan = subtitle.optString("key")
                this.lan = lan.let {
                    if (replaceable && !replaced && !lanDoc.contains("机翻") && it.startsWith("cn")) {
                        replaced = true
                        "zh-Hans"
                    } else it
                }
                if (lan == "zh-Hans") {
                    subtitleUrl = Uri.parse(subtitleUrl).buildUpon()
                        .appendQueryParameter("zh_converter", "s2cn").toString()
                }
            }.let { subList.add(it) }
        }
        return subList
    }

    fun newImportSubtitle(index: Int, subtitle: VideoSubtitle): SubtitleItem? {
        val order = index + 1
        val importLan = "import${order}"
        val subtitles = subtitle.subtitlesList
        if (subtitles.any { it.lan == importLan })
            return null
        return SubtitleItem().apply {
            val baseId = 114514L
            id = baseId + index
            idStr = id.toString()
            lan = importLan
            lanDoc = "漫游导入${order}"
            lanDocBrief = "导入"
            subtitleUrl = "https://interface.bilibili.com/serverdate.js?zh_converter=import&import_index=$index"
        }
    }
}
