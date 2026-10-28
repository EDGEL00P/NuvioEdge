package com.nuvio.tv.data.iptv

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial playlist-vs-guide corpus. Automatic matching may miss, but it must never pick the
 * wrong guide channel: a wrong guide is worse than an empty one.
 */
class EpgMatchingCorpusTest {

    private val now = 1_792_836_000_000L

    private val guide = """<tv>
      <channel id="bbc1.uk"><display-name>BBC One</display-name><display-name>BBC One HD</display-name></channel>
      <channel id="bbc1.scot"><display-name>BBC One Scotland</display-name></channel>
      <channel id="cctv1.cn"><display-name>CCTV-1 综合</display-name><display-name>CCTV1</display-name></channel>
      <channel id="cctv13.cn"><display-name>CCTV-13 新闻</display-name></channel>
      <channel id="phoenix.hk"><display-name>凤凰卫视中文台</display-name></channel>
      <channel id="phoenixinfo.hk"><display-name>凤凰卫视资讯台</display-name></channel>
      <channel id="news.uk"><display-name>News</display-name></channel>
      <channel id="news.us"><display-name>News</display-name></channel>
      <channel id="tf1.fr"><display-name>TF1</display-name></channel>
      <channel id="tf1.be"><display-name>TF1</display-name></channel>
      <channel id="sky.main"><display-name>Sky Sports Main Event</display-name></channel>
      <channel id="espn.us"><display-name>ESPN</display-name></channel>
      <channel id="espn2.us"><display-name>ESPN 2</display-name></channel>
      <channel id="Das.Erste.de"><display-name>Das Erste</display-name></channel>
      <channel id="discovery.us"><display-name>Discovery Channel</display-name></channel>
      <channel id="nhk.jp"><display-name>NHK総合</display-name></channel>
      <channel id="kera.us"><display-name>13</display-name><display-name>13 KERA</display-name></channel>
      <channel id="ktvt.us"><display-name>2</display-name></channel>
    </tv>"""

    /** [expected] null = must stay unmatched; [mustMatch] false = a miss is acceptable, a wrong id is not. */
    private data class Case(
        val label: String,
        val name: String,
        val epgId: String? = null,
        val epgName: String? = null,
        val expected: String?,
        val mustMatch: Boolean = expected != null
    )

    private val cases = listOf(
        Case("exact id beats name", "Something Else", epgId = "bbc1.uk", expected = "bbc1.uk"),
        Case("id case/whitespace drift", "x", epgId = "BBC1.UK ", expected = "bbc1.uk"),
        Case("quality suffix", "BBC One FHD", expected = "bbc1.uk"),
        Case("region prefix + quality", "UK: BBC One HD", expected = "bbc1.uk"),
        Case("regional variant stays distinct", "BBC One Scotland", expected = "bbc1.scot"),
        Case("region suffix is not stripped", "BBC One UK", expected = "bbc1.uk", mustMatch = false),
        Case("CJK with quality tag", "CCTV-1 综合 HD", expected = "cctv1.cn"),
        Case("bracket tag + alias", "[CN] CCTV1", expected = "cctv1.cn"),
        Case("full-width forms", "ＣＣＴＶ－１３ 新闻", expected = "cctv13.cn"),
        Case("hyphenated number is not a tag", "CCTV-13", expected = null),
        Case("hyphenated short name is not a tag", "ITV-2", expected = null),
        Case("CJK exact", "凤凰卫视中文台 [HD]", expected = "phoenix.hk"),
        Case("CJK partial must not guess", "凤凰卫视", expected = null),
        Case("Japanese exact", "NHK総合", expected = "nhk.jp"),
        Case("duplicate guide names", "News", expected = null),
        Case("duplicate after prefix strip", "UK: News", expected = null),
        Case("duplicate national feeds", "TF1 HD", expected = null),
        Case("no prefix-of-name guessing", "Sky Sports", expected = null),
        Case("no substring guessing", "Discovery", epgId = "unknown.id", expected = null),
        Case("numbered sibling stays distinct", "ESPN2", expected = "espn2.us"),
        Case("base channel", "ESPN", expected = "espn.us"),
        Case("unrelated numeric id falls back to name", "Das Erste", epgId = "987654", expected = "Das.Erste.de"),
        Case("stale tvg-name falls back to title", "Das Erste", epgName = "ARD Old Name", expected = "Das.Erste.de"),
        Case("bare number tag remainder", "CH: 13", expected = null)
    )

    @Test
    fun `automatic matching never assigns a wrong guide channel`() {
        val wantedIds = cases.mapNotNullTo(HashSet()) { it.epgId }
        val wantedNames = cases.flatMapTo(HashSet()) { XmltvParser.candidateNames(it.epgName, it.name) }
        val result = XmltvParser(now).parse(guide.byteInputStream(), wantedIds, wantedNames)

        val wrong = ArrayList<String>()
        val missed = ArrayList<String>()
        var matched = 0
        for (case in cases) {
            val displayNames = listOfNotNull(case.epgName?.takeIf { it.isNotBlank() }, case.name.takeIf { it.isNotBlank() })
            val resolved = result.resolveGuide(case.epgId, displayNames).guideId
            if (resolved != null) matched++
            when {
                resolved != null && resolved != case.expected -> wrong += "${case.label}: '${case.name}' -> $resolved (expected ${case.expected})"
                resolved == null && case.mustMatch -> missed += "${case.label}: '${case.name}' expected ${case.expected}"
            }
        }

        assertTrue("Wrong automatic matches:\n" + wrong.joinToString("\n"), wrong.isEmpty())
        assertTrue("Regressed matches:\n" + missed.joinToString("\n"), missed.isEmpty())
        val matchable = cases.count { it.expected != null }
        assertTrue("Coverage $matched/$matchable", matched >= cases.count { it.mustMatch })
    }
}
