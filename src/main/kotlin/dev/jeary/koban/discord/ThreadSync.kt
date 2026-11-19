package dev.jeary.koban.discord

import dev.jeary.koban.KobanCore
import dev.jeary.koban.domain.actor.Source
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.DiscordRef
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.service.ReportChange
import dev.jeary.koban.service.ReportListener
import dev.jeary.koban.storage.repo.ReportQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.forums.ForumTagSnowflake
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Keeps one Discord thread per report up to date.
 *
 * Every change marks the report "dirty" in the database (same transaction as the change), and
 * this worker redraws dirty reports one at a time, then clears the flag only if nothing changed
 * meanwhile (by version). So if Discord is down or slow nothing is lost: the next sweep catches
 * up. Short log lines ("Steve claimed this") are posted in the thread as things happen.
 */
class ThreadSync(private val core: KobanCore, private val bot: DiscordBot) : ReportListener {

    private sealed interface Task {
        val id: ReportId

        data class Update(override val id: ReportId) : Task
        data class Post(override val id: ReportId, val text: String) : Task
    }

    @Volatile
    private var queue = Channel<Task>(Channel.UNLIMITED)
    private val queuedUpdates = ConcurrentHashMap.newKeySet<ReportId>()
    private var worker: Job? = null
    private var sweeper: Job? = null

    fun start() {
        stop()
        val fresh = Channel<Task>(Channel.UNLIMITED)
        queue = fresh
        queuedUpdates.clear()
        worker = core.scope.launch(Dispatchers.IO) {
            for (task in fresh) {
                if (task is Task.Update) queuedUpdates.remove(task.id)
                process(task)
            }
        }
        sweeper = core.scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(SWEEP_MS)
                enqueueDirty()
            }
        }
    }

    fun stop() {
        worker?.cancel()
        sweeper?.cancel()
        worker = null
        sweeper = null
        queue.close()
    }

    private fun update(id: ReportId) {
        if (queuedUpdates.add(id)) queue.trySend(Task.Update(id))
    }

    private fun post(id: ReportId, text: String) {
        queue.trySend(Task.Post(id, text))
    }

    suspend fun enqueueDirty() {
        if (bot.ready == null) return
        core.reports.dirtyReports(SWEEP_BATCH).forEach { update(it.id) }
    }

    /** Redraws every open report's message (after a reload changed texts or actions). */
    suspend fun enqueueActive() {
        core.reports.list(ReportQuery(ReportStatus.ACTIVE, limit = SWEEP_BATCH)).filter { it.discord != null }.forEach { update(it.id) }
    }

    override suspend fun onChange(change: ReportChange) {
        if (bot.ready == null) return // stays dirty; synced when the bot connects
        val text = bot.content
        val id = change.report.id
        when (change) {
            is ReportChange.Created -> Unit
            is ReportChange.Merged -> post(
                id,
                core.messenger.plain(
                    "discord.log-merged",
                    tag("reporter", DiscordText.escape(change.reporter.player.name)),
                    tag("category", DiscordText.escape(core.config.reasons.displayName(change.reporter.category))),
                    tag("details", change.reporter.details?.let { " — " + DiscordText.escape(it) } ?: ""),
                    tag("count", change.report.reporterCount),
                ),
            )
            is ReportChange.Transitioned -> {
                val key = when (change.transition.change) {
                    Change.CLAIMED -> "discord.log-claimed"
                    Change.TAKEN_OVER -> "discord.log-taken-over"
                    Change.UNCLAIMED -> "discord.log-unclaimed"
                    Change.RESOLVED -> "discord.log-resolved"
                    Change.DISMISSED -> "discord.log-dismissed"
                    Change.REOPENED -> "discord.log-reopened"
                }
                post(
                    id,
                    core.messenger.plain(
                        key,
                        tag("staff", DiscordText.escape(change.actor.displayName)),
                        tag("source", source(change.actor.source)),
                        tag("outcome", text.outcome(change.report.closure?.outcome)),
                        tag("note", change.note?.body?.let { "\n> " + DiscordText.escape(it).replace("\n", "\n> ") } ?: ""),
                    ),
                )
            }
            is ReportChange.NoteAdded -> {
                if (change.note.source == Source.DISCORD) return
                post(
                    id,
                    core.messenger.plain(
                        "discord.log-note",
                        tag("staff", DiscordText.escape(change.note.authorName)),
                        tag("source", source(change.note.source)),
                        tag("note", DiscordText.escape(change.note.body).replace("\n", "\n> ")),
                    ),
                )
                return
            }
        }
        update(id)
    }

    private fun source(source: Source) = core.messenger.plain("discord.source-" + source.name.lowercase())

    private fun tag(name: String, value: Any?) = dev.jeary.koban.platform.Messenger.tag(name, value)

    // ------------------------------------------------------------------ the worker

    private suspend fun process(task: Task) {
        val ready = bot.ready ?: return
        try {
            when (task) {
                is Task.Update -> redraw(ready, task.id)
                is Task.Post -> {
                    val report = core.reports.find(task.id) ?: return
                    val ref = report.discord ?: redraw(ready, task.id) ?: return
                    val thread = findThread(ready, ref.threadId) ?: return
                    thread.sendMessage(bot.adapters.plain(task.text)).submit().await()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErrorResponseException) {
            core.logger.warning("Discord: couldn't update report ${task.id} (${e.errorResponse.meaning}). It will be retried.")
        } catch (e: Exception) {
            core.logger.log(Level.WARNING, "Discord: couldn't update report ${task.id}; it will be retried", e)
        }
    }

    /** Creates or updates the thread of report [id]. @return its Discord reference. */
    private suspend fun redraw(ready: DiscordBot.State.Ready, id: ReportId): DiscordRef? {
        val details = core.reports.details(id) ?: return null
        val report = details.report
        val content = bot.content
        val spec = content.embed(report, details.reporters, details.evidence, details.notes.size)
        val rows = bot.adapters.rows(report, core.config.actions.templates)
        val title = content.title(report)
        val channel = ready.channel

        var ref = report.discord
        var thread = ref?.let { findThread(ready, it.threadId) }
        if (ref == null || thread == null) {
            // A closed report that never had a thread (made while the bot was off) doesn't need one now.
            if (ref == null && report.status.isClosed) {
                core.reports.markDiscordSynced(id, report.version)
                return null
            }
            val created = create(channel, title, spec, rows, content.tags(report, details.reporters, tagNames(channel))) ?: return null
            ref = created.first
            thread = created.second
            core.reports.setDiscord(id, ref)
        } else {
            if (thread.isArchived) thread.manager.setArchived(false).submit().await()
            val edit = bot.adapters.edit(spec, rows)
            if (channel is TextChannel && ref.messageId != ref.threadId) {
                channel.editMessageById(ref.messageId, edit).submit().await()
            } else {
                thread.editMessageById(ref.messageId, edit).submit().await()
            }
            val manager = thread.manager
            if (thread.name != title) manager.setName(title)
            if (channel is ForumChannel) manager.setAppliedTags(tags(channel, content.tags(report, details.reporters, tagNames(channel))))
            manager.submit().await()
        }
        // Closed reports are archived so the forum shows what's waiting; any change reopens them.
        // (Any archived thread was unarchived above to be edited, so this is never a no-op.)
        if (report.status.isClosed) thread.manager.setArchived(true).submit().await()
        core.reports.markDiscordSynced(id, report.version)
        return ref
    }

    private suspend fun create(
        channel: GuildChannel,
        title: String,
        spec: EmbedSpec,
        rows: List<net.dv8tion.jda.api.components.actionrow.ActionRow>,
        tags: List<String>,
    ): Pair<DiscordRef, ThreadChannel>? {
        val message = bot.adapters.create(spec, rows)
        return when (channel) {
            is ForumChannel -> {
                val post = channel.createForumPost(title, message).setTags(tags(channel, tags)).submit().await()
                DiscordRef(post.threadChannel.idLong, post.message.idLong) to post.threadChannel
            }
            is TextChannel -> {
                val sent = channel.sendMessage(message).submit().await()
                val thread = sent.createThreadChannel(title).submit().await()
                DiscordRef(thread.idLong, sent.idLong) to thread
            }
            else -> null
        }
    }

    private fun tagNames(channel: GuildChannel): Set<String> =
        (channel as? ForumChannel)?.availableTags?.map { it.name }?.toSet().orEmpty()

    private fun tags(forum: ForumChannel, names: List<String>): List<ForumTagSnowflake> {
        val byName = forum.availableTags.associateBy { it.name.lowercase() }
        return names.mapNotNull { byName[it.lowercase()] }.take(DiscordLimits.FORUM_TAGS)
    }

    /**
     * The thread, from the cache if it's active, otherwise by searching the parent's archived
     * threads (Discord has no "get archived thread by ID"). Null if it was deleted.
     */
    private suspend fun findThread(ready: DiscordBot.State.Ready, threadId: Long): ThreadChannel? {
        ready.guild.getThreadChannelById(threadId)?.let { return it }
        val container = ready.channel as? net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer ?: return null
        return try {
            container.retrieveArchivedPublicThreadChannels().takeAsync(ARCHIVE_SEARCH).await().firstOrNull { it.idLong == threadId }
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.MISSING_PERMISSIONS || e.errorResponse == ErrorResponse.MISSING_ACCESS) null else throw e
        }
    }

    private companion object {
        const val SWEEP_MS = 60_000L
        const val SWEEP_BATCH = 50
        const val ARCHIVE_SEARCH = 500
    }
}
