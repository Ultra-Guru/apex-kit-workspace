package com.apex.workspace.core.watch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 轮询 watcher 虚拟时间测试。
 *
 * 挂起安全纪律：watcher 主循环是自重排的无限 delay 循环 —— 测试中绝不使用
 * advanceUntilIdle（永不空闲）；用 advanceTimeBy 驱动具体轮次 + runCurrent
 * 排空同刻任务；watcher 与 collector 挂在 backgroundScope（测试结束自动取消）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceWatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Collector {
        val batches = mutableListOf<List<WorkspaceWatchEvent>>()
        val all: List<WorkspaceWatchEvent> get() = batches.flatten()
    }

    private val poll = 500L
    private val debounce = 300L

    @Test
    fun `新增文件产生 ADD`() = runTest {
        val ws = tmp.newFolder("ws")
        val c = Collector()
        val w = WorkspaceWatcher(backgroundScope, poll)
        backgroundScope.launch(Dispatchers.Unconfined) { w.events.collect { c.batches.add(it) } }
        w.start(ws)
        advanceTimeBy(poll + 100); runCurrent()   // 首轮基线（静默）
        assertTrue("baseline must be silent, got ${c.all}", c.all.isEmpty())

        File(ws, "hello.txt").writeText("hi")
        advanceTimeBy(poll + 100); runCurrent()   // 检测轮
        advanceTimeBy(debounce + 50); runCurrent() // 去抖窗
        w.stop()
        assertTrue("expect ADD, got ${c.all}", c.all.any { it.kind == WatchKind.ADD && it.path == "hello.txt" })
    }

    @Test
    fun `修改文件产生 CHANGE`() = runTest {
        val ws = tmp.newFolder("ws")
        val f = File(ws, "a.txt").apply { writeText("v1") }
        val c = Collector()
        val w = WorkspaceWatcher(backgroundScope, poll)
        backgroundScope.launch(Dispatchers.Unconfined) { w.events.collect { c.batches.add(it) } }
        w.start(ws)
        advanceTimeBy(poll + 100); runCurrent()
        assertTrue(c.all.isEmpty())

        f.writeText("v2 - changed content")
        f.setLastModified(f.lastModified() + 60_000)
        advanceTimeBy(poll + 100); runCurrent()
        advanceTimeBy(debounce + 50); runCurrent()
        w.stop()
        assertTrue("expect CHANGE, got ${c.all}", c.all.any { it.kind == WatchKind.CHANGE && it.path == "a.txt" })
    }

    @Test
    fun `删除文件产生 UNLINK`() = runTest {
        val ws = tmp.newFolder("ws")
        val f = File(ws, "doomed.txt").apply { writeText("x") }
        val c = Collector()
        val w = WorkspaceWatcher(backgroundScope, poll)
        backgroundScope.launch(Dispatchers.Unconfined) { w.events.collect { c.batches.add(it) } }
        w.start(ws)
        advanceTimeBy(poll + 100); runCurrent()

        f.delete()
        advanceTimeBy(poll + 100); runCurrent()
        advanceTimeBy(debounce + 50); runCurrent()
        w.stop()
        assertTrue("expect UNLINK, got ${c.all}", c.all.any { it.kind == WatchKind.UNLINK && it.path == "doomed.txt" })
    }

    @Test
    fun `新目录 ADD_DIR 且忽略 git 目录`() = runTest {
        val ws = tmp.newFolder("ws")
        val c = Collector()
        val w = WorkspaceWatcher(backgroundScope, poll)
        backgroundScope.launch(Dispatchers.Unconfined) { w.events.collect { c.batches.add(it) } }
        w.start(ws)
        advanceTimeBy(poll + 100); runCurrent()

        File(ws, "sub").mkdirs()
        File(ws, ".git").mkdirs()
        File(ws, ".git/config").writeText("x")
        advanceTimeBy(poll + 100); runCurrent()
        advanceTimeBy(debounce + 50); runCurrent()
        w.stop()
        assertTrue(c.all.any { it.kind == WatchKind.ADD_DIR && it.path == "sub" })
        assertTrue(".git must be ignored, got ${c.all}", c.all.none { it.path.startsWith(".git") })
    }

    @Test
    fun `首轮基线静默 - 已有文件不产生事件`() = runTest {
        val ws = tmp.newFolder("ws")
        File(ws, "pre.txt").writeText("existing")
        val c = Collector()
        val w = WorkspaceWatcher(backgroundScope, poll)
        backgroundScope.launch(Dispatchers.Unconfined) { w.events.collect { c.batches.add(it) } }
        w.start(ws)
        advanceTimeBy(poll * 4); runCurrent()
        w.stop()
        assertTrue("first poll must be silent baseline, got ${c.all}", c.all.isEmpty())
    }

    @Test
    fun `stop 后停发`() = runTest {
        val ws = tmp.newFolder("ws")
        val c = Collector()
        val w = WorkspaceWatcher(backgroundScope, poll)
        backgroundScope.launch(Dispatchers.Unconfined) { w.events.collect { c.batches.add(it) } }
        w.start(ws)
        advanceTimeBy(poll + 100); runCurrent()
        w.stop()
        w.stop() // 幂等
        File(ws, "after-stop.txt").writeText("x")
        advanceTimeBy(poll * 4); runCurrent()
        assertTrue("no events after stop, got ${c.all}", c.all.none { it.path == "after-stop.txt" })
    }
}
