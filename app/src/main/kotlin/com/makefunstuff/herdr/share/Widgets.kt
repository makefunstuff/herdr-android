package com.makefunstuff.herdr.share

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.R
import com.makefunstuff.herdr.core.push.Counts
import com.makefunstuff.herdr.ui.share.Ask
import java.util.Date

/** The home-screen widget that opens the Coordinator chat (the one used last, or asks which). */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.update(context)
}

/** The status widget: "2 need you · 3 working" across the machines; a tap opens the board. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Also every half hour: what works and wasn't confirmed since stops counting.
        (context.applicationContext as HerdrApp).container.tally.recount()
        Widgets.update(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = Widgets.update(context)
}

/** Draws the widgets from what the app knows: on a change of the counts or of the Coordinator used last. */
object Widgets {
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val container = (context.applicationContext as HerdrApp).container
        val asks = manager.getAppWidgetIds(ComponentName(context, AskWidget::class.java))
        if (asks.isNotEmpty()) {
            val label = container.shares.last.value?.let { container.machines.get(it)?.label }
            manager.updateAppWidget(asks, ask(context, label))
        }
        val statuses = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
        if (statuses.isNotEmpty()) manager.updateAppWidget(statuses, status(context, container.tally.counts.value))
    }

    private fun ask(context: Context, machine: String?) = RemoteViews(context.packageName, R.layout.widget_ask).apply {
        setTextViewText(R.id.widget_machine, machine ?: "Herdr")
        setContentDescription(R.id.widget_root, listOfNotNull("Ask the Coordinator", machine?.let { "on $it" }).joinToString(" "))
        setOnClickPendingIntent(R.id.widget_root, open(context, Ask.COORDINATOR))
    }

    private fun status(context: Context, c: Counts) = RemoteViews(context.packageName, R.layout.widget_status).apply {
        // Each count is in its colour when there is any, and dim at nought.
        setViewVisibility(R.id.widget_counts, if (c.known) View.VISIBLE else View.GONE)
        setTextViewText(R.id.widget_need, c.needYou.toString())
        setViewVisibility(R.id.widget_need, if (c.needYou > 0) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_need_none, if (c.needYou > 0) View.GONE else View.VISIBLE)
        setTextViewText(R.id.widget_need_words, if (c.needYou == 1) "needs you" else "need you")
        setTextViewText(R.id.widget_work, c.working.toString())
        setViewVisibility(R.id.widget_work, if (c.working > 0) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_work_none, if (c.working > 0) View.GONE else View.VISIBLE)
        val detail = when {
            !c.known -> "Nothing known yet · tap to open Herdr"
            else -> listOf(
                if (c.machines == 1) "on 1 machine" else "across ${c.machines} machines",
                at(context, c.at),
            ).joinToString(" · ")
        }
        setTextViewText(R.id.widget_detail, detail)
        setContentDescription(R.id.widget_root, if (c.known) "${c.words}, $detail. Opens the board." else detail)
        setOnClickPendingIntent(R.id.widget_root, open(context, Ask.BOARD))
    }

    /** When the counts were last known: "now" within a couple of minutes, else the time (and the day, when not today). */
    private fun at(context: Context, at: Long): String {
        val now = System.currentTimeMillis()
        if (now - at < 2 * 60_000) return "now"
        val time = DateFormat.getTimeFormat(context).format(Date(at))
        return if (DateUtils.isToday(at)) "as of $time" else "as of " + DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) + " $time"
    }

    private fun open(context: Context, to: String): PendingIntent = PendingIntent.getActivity(
        context, to.hashCode(), MainActivity.ask(context, to), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
