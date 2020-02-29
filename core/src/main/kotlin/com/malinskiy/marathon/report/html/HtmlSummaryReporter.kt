package com.malinskiy.marathon.report.html

import com.google.gson.Gson
import com.malinskiy.marathon.analytics.internal.sub.ExecutionReport
import com.malinskiy.marathon.analytics.internal.sub.PoolSummary
import com.malinskiy.marathon.analytics.internal.sub.Summary
import com.malinskiy.marathon.device.DeviceFeature
import com.malinskiy.marathon.device.DeviceInfo
import com.malinskiy.marathon.execution.AttachmentType
import com.malinskiy.marathon.execution.Configuration
import com.malinskiy.marathon.execution.TestResult
import com.malinskiy.marathon.execution.TestStatus
import com.malinskiy.marathon.extension.relativePathTo
import com.malinskiy.marathon.report.HtmlDevice
import com.malinskiy.marathon.report.HtmlFullTest
import com.malinskiy.marathon.report.HtmlIndex
import com.malinskiy.marathon.report.HtmlPoolSummary
import com.malinskiy.marathon.report.HtmlShortTest
import com.malinskiy.marathon.report.HtmlTestLogDetails
import com.malinskiy.marathon.report.Reporter
import com.malinskiy.marathon.report.Status
import org.apache.commons.text.StringEscapeUtils
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToLong

class HtmlSummaryReporter(
    private val gson: Gson,
    private val rootOutput: File,
    private val configuration: Configuration
) : Reporter {

    /**
     * Following file tree structure will be created:
     * - index.json
     * - suites/suiteId.json
     * - suites/deviceId/testId.json
     */
    override fun generate(executionReport: ExecutionReport) {
        val summary = executionReport.summary
        if (summary.pools.isEmpty()) return

        val outputDir = File(rootOutput, "/html")
        rootOutput.mkdirs()
        outputDir.mkdirs()

        val htmlIndexJson = gson.toJson(summary.toHtmlIndex())

        val formattedDate = SimpleDateFormat("HH:mm:ss z, MMM d yyyy").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

        val appJs = File(outputDir, "app.min.js")
        inputStreamFromResources("html-report/app.min.js").copyTo(appJs.outputStream())

        val appCss = File(outputDir, "app.min.css")
        inputStreamFromResources("html-report/app.min.css").copyTo(appCss.outputStream())

        // index.html is a page that can render all kinds of inner pages: Index, Suite, Test.
        val indexHtml = inputStreamFromResources("html-report/index.html").reader().readText()

        val indexHtmlFile = File(outputDir, "index.html")

        fun File.relativePathToHtmlDir(): String = outputDir.relativePathTo(this.parentFile).let { relativePath ->
            when (relativePath) {
                "" -> relativePath
                else -> "$relativePath/"
            }
        }

        indexHtmlFile.writeText(
            indexHtml
                .replace("\${relative_path}", indexHtmlFile.relativePathToHtmlDir())
                .replace("\${data_json}", "window.mainData = $htmlIndexJson")
                .replace("\${log}", "")
                .replace("\${date}", formattedDate)
        )

        val poolsDir = File(outputDir, "pools").apply { mkdirs() }

        summary.pools.forEach { pool ->
            val poolJson = gson.toJson(pool.toHtmlPoolSummary())
            val poolHtmlFile = File(poolsDir, "${pool.poolId.name}.html")

            poolHtmlFile.writeText(
                indexHtml
                    .replace("\${relative_path}", poolHtmlFile.relativePathToHtmlDir())
                    .replace("\${data_json}", "window.pool = $poolJson")
                    .replace("\${log}", "")
                    .replace("\${date}", formattedDate)
            )

            pool.tests.map { it to File(File(poolsDir, pool.poolId.name), it.device.serialNumber).apply { mkdirs() } }
                .map { (test, testDir) -> Triple(test, test.toHtmlFullTest(poolId = pool.poolId.name), testDir) }
                .forEach { (test, htmlTest, testDir) ->
                    val testJson = gson.toJson(htmlTest)
                    val testHtmlFile = File(testDir, "${htmlTest.id}.html")

                    testHtmlFile.writeText(
                        indexHtml
                            .replace("\${relative_path}", testHtmlFile.relativePathToHtmlDir())
                            .replace("\${data_json}", "window.test = $testJson")
                            .replace("\${log}", generateLogcatHtml(test.stacktrace ?: ""))
                            .replace("\${date}", formattedDate)
                    )

                    val logDir = File(testDir, "logs")
                    logDir.mkdirs()

                    val testLogDetails = toHtmlTestLogDetails(pool.poolId.name, htmlTest)
                    val testLogJson = gson.toJson(testLogDetails)
                    val testLogHtmlFile = File(logDir, "${htmlTest.id}.html")

                    testLogHtmlFile.writeText(
                        indexHtml
                            .replace("\${relative_path}", testLogHtmlFile.relativePathToHtmlDir())
                            .replace("\${data_json}", "window.logs = $testLogJson")
                            .replace("\${log}", "")
                            .replace("\${date}", formattedDate)
                    )
                }
        }
    }

    fun inputStreamFromResources(path: String): InputStream = HtmlPoolSummary::class.java.classLoader.getResourceAsStream(path)

    fun generateLogcatHtml(logcatOutput: String): String = when (logcatOutput.isNotEmpty()) {
        false -> ""
        true -> logcatOutput
            .lines()
            .map { line -> """<div class="log__${cssClassForLogcatLine(line)}">${StringEscapeUtils.escapeXml11(line)}</div>""" }
            .fold(StringBuilder("""<div class="content"><div class="card log">""")) { stringBuilder, line ->
                stringBuilder.appendln(line)
            }.appendln("""</div></div>""").toString()
    }

    fun cssClassForLogcatLine(logcatLine: String): String {
        // Logcat line example: `06-07 16:55:14.490  2100  2100 I MicroDetectionWorker: #onError(false)`
        // First letter is Logcat level.
        return when (logcatLine.firstOrNull { it.isLetter() }) {
            'V' -> "verbose"
            'D' -> "debug"
            'I' -> "info"
            'W' -> "warning"
            'E' -> "error"
            'A' -> "assert"
            else -> "default"
        }
    }

    fun DeviceInfo.toHtmlDevice() = HtmlDevice(
        apiLevel = operatingSystem.version,
        isTablet = false,
        serial = serialNumber,
        modelName = model
    )

    private fun TestResult.receiveScreenshotPath(): String {
        val screenshotFile = attachments.find { it.type == AttachmentType.SCREENSHOT }?.file ?: return ""
        val screenshotRelativePath = screenshotFile.relativePathTo(rootOutput)
        return "../../../../" + screenshotRelativePath.replace("#", "%23")
    }

    private fun TestResult.receiveVideoPath(): String {
        val videoFile = attachments.find { it.type == AttachmentType.VIDEO }?.file ?: return ""
        val videoRelativePath = videoFile.relativePathTo(rootOutput)
        return "../../../../" + videoRelativePath.replace("#", "%23")
    }

    private fun TestResult.receiveLogPath(): String {
        val logFile = attachments.find { it.type == AttachmentType.LOG }?.file ?: return ""
        val logRelativePath = logFile.relativePathTo(rootOutput)
        return "../../../../" + logRelativePath.replace("#", "%23")
    }

    fun TestResult.toHtmlFullTest(poolId: String) = HtmlFullTest(
        poolId = poolId,
        id = "${test.pkg}.${test.clazz}.${test.method}",
        packageName = test.pkg,
        className = test.clazz,
        name = test.method,
        durationMillis = durationMillis(),
        status = status.toHtmlStatus(),
        deviceId = this.device.serialNumber,
        diagnosticVideo = device.deviceFeatures.contains(DeviceFeature.VIDEO),
        diagnosticScreenshots = device.deviceFeatures.contains(DeviceFeature.SCREENSHOT),
        stacktrace = stacktrace,
        screenshot = receiveScreenshotPath(),
        video = receiveVideoPath(),
        logFile = receiveLogPath()
    )

    fun TestStatus.toHtmlStatus() = when (this) {
        TestStatus.PASSED -> Status.Passed
        TestStatus.FAILURE -> Status.Failed
        TestStatus.IGNORED, TestStatus.ASSUMPTION_FAILURE -> Status.Ignored
        else -> Status.Failed
    }

    fun PoolSummary.toHtmlPoolSummary() = HtmlPoolSummary(
        id = poolId.name,
        tests = tests.map { it.toHtmlShortSuite() },
        passedCount = passed,
        failedCount = failed,
        ignoredCount = ignored,
        durationMillis = durationMillis,
        devices = devices.map { it.toHtmlDevice() }
    )


    fun Summary.toHtmlIndex() = HtmlIndex(
        title = configuration.name,
        totalFailed = pools.sumBy { it.failed },
        totalIgnored = pools.sumBy { it.ignored },
        totalPassed = pools.sumBy { it.passed },
        totalFlaky = pools.sumBy { it.flaky },
        totalDuration = totalDuration(pools),
        averageDuration = averageDuration(pools),
        maxDuration = maxDuration(pools),
        minDuration = minDuration(pools),
        pools = pools.map { it.toHtmlPoolSummary() }
    )

    fun totalDuration(poolSummaries: List<PoolSummary>): Long {
        return poolSummaries.flatMap { it.tests }.sumByDouble { it.durationMillis() * 1.0 }.toLong()
    }

    fun averageDuration(poolSummaries: List<PoolSummary>) = durationPerPool(poolSummaries).average().roundToLong()

    fun minDuration(poolSummaries: List<PoolSummary>) = durationPerPool(poolSummaries).min() ?: 0

    private fun durationPerPool(poolSummaries: List<PoolSummary>) =
        poolSummaries.map { it.tests }
            .map { it.sumByDouble { it.durationMillis() * 1.0 } }.map { it.toLong() }

    fun maxDuration(poolSummaries: List<PoolSummary>) = durationPerPool(poolSummaries).max() ?: 0


    fun TestResult.toHtmlShortSuite() = HtmlShortTest(
        id = "${test.pkg}.${test.clazz}.${test.method}",
        packageName = test.pkg,
        className = test.clazz,
        name = test.method,
        durationMillis = durationMillis(),
        status = status.toHtmlStatus(),
        deviceId = this.device.serialNumber
    )

    fun toHtmlTestLogDetails(
        poolId: String,
        fullTest: HtmlFullTest
    ) = HtmlTestLogDetails(
        poolId = poolId,
        testId = fullTest.id,
        displayName = fullTest.name,
        deviceId = fullTest.deviceId,
        logPath = "../" + fullTest.logFile
    )
}
