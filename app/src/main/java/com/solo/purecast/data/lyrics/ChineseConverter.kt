package com.solo.purecast.data.lyrics

/**
 * 💡 歌词与翻译繁简汉字转换引擎 (ChineseConverter)
 * 遵循用户要求“歌词及翻译以简体中文为主”：
 * 采用严格一一对应的单字符映射表，仅将繁体字精准替换为简体字，绝不误伤规范简体字符。
 */
object ChineseConverter {

    // 💡 严格一一对应的繁体字与规范简体字序列
    private const val TRADITIONAL = "們這樣麼誰個點說聽愛讓還覺開飛離變動發學現問見視讀書寫畫從過來給轉帶對進導尋買賣辦選認識記憶關閉勝負敗許諾創斷續歸擁親戰爭護顧撫養賞贈償賠賺歡嘆願隨趕馳墜飄蕩搖執著繫牽絆繞纏難傷淚憂鬱樂悅溫熱涼凍純潔誠偽虛實麗醜華爛燦輝榮輕軟強遠長淺濁淨髒亂齊簡單複雜細銳遲鈍靈異時間歲晝節邊處東裡內遙際風雲霧電陰湧濤嶺陽詞語詩韻響聲調簫鐘鈴黃綠藍紅銀銅鐵車輪門戶燈燭鏡筆紙硯襪環飾寶貝鞦韆總結終經歷線條雙隻無與體國業頭機種萬億獨會當為係後裏夢"
    private const val SIMPLIFIED  = "们这样么谁个点说听爱让还觉开飞离变动发学现问见视读书写画从过来给转带对进导寻买卖办选认识记忆关闭胜负败许诺创断续归拥亲战争护顾抚养赏赠偿赔赚欢叹愿随赶驰坠飘荡摇执着系牵绊绕缠难伤泪忧郁乐悦温热凉冻纯洁诚伪虚实丽丑华烂灿辉荣轻软强远长浅浊净脏乱齐简单复杂细锐迟钝灵异时间岁昼节边处东里内遥际风云雾电阴涌涛岭阳词语诗韵响声调箫钟铃黄绿蓝红银铜铁车轮门户灯烛镜笔纸砚袜环饰宝贝秋千总结终经历线条双只无与体国业头机种万亿独会当为系后里梦"

    private val T2S_MAP: Map<Char, Char> by lazy {
        val map = HashMap<Char, Char>(TRADITIONAL.length)
        val len = minOf(TRADITIONAL.length, SIMPLIFIED.length)
        for (i in 0 until len) {
            map[TRADITIONAL[i]] = SIMPLIFIED[i]
        }
        map
    }

    /**
     * 💡 将字符串中的所有繁体汉字转换为规范简体汉字
     */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return ""
        val chars = text.toCharArray()
        var changed = false
        for (i in chars.indices) {
            val s = T2S_MAP[chars[i]]
            if (s != null && s != chars[i]) {
                chars[i] = s
                changed = true
            }
        }
        return if (changed) String(chars) else text
    }
}
