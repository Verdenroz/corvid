package com.verdenroz.corvid.benchmarks

import com.verdenroz.corvid.core.html.flattenHtml
import com.verdenroz.corvid.core.html.htmlToBlocks
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import java.util.concurrent.TimeUnit

private const val PROXY = "https://mail.example.com:4443/i/"

private fun section(i: Int) =
    """
    <table width="600"><tr><td>
    <h2>Story $i</h2>
    <a href="https://news.example.com/story/$i"><img src="${PROXY}hero$i" alt="Story $i" width="600"></a>
    <p>Section $i of the weekly digest, with <b>bold</b>, <i>italic</i> and <a href="https://news.example.com/more/$i">linked</a> prose.</p>
    <ul><li>First point $i</li><li>Second point $i</li></ul>
    </td><td><a href="https://news.example.com/share/$i">Share</a></td></tr></table>
    <img src="https://t.example.com/open/$i.gif" width="1" height="1">
    """.trimIndent()

private fun reply(depth: Int): String =
    if (depth == 0) {
        "<p>Original message.</p><pre>fun main() {\n    println(\"hi\")\n}</pre>"
    } else {
        "<p>On a day, alice@example.com wrote:</p><blockquote>${reply(depth - 1)}</blockquote><p>Reply $depth.</p>"
    }

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
class HtmlBenchmarks {
    private val newsletter = "<html><body>${(0 until 12).joinToString("") { section(it) }}</body></html>"
    private val thread = "<html><body>${reply(12)}</body></html>"
    private val links =
        (0 until 60).joinToString("") { """<p><a href="https://evil$it.example.net/login">https://bank$it.example.com/login</a></p>""" }

    @Benchmark
    fun blocksNewsletter() = htmlToBlocks(newsletter, PROXY)

    @Benchmark
    fun blocksReplyThread() = htmlToBlocks(thread, PROXY)

    @Benchmark
    fun blocksMismatchedLinks() = htmlToBlocks(links, PROXY)

    @Benchmark
    fun flattenNewsletter() = flattenHtml(newsletter)
}
