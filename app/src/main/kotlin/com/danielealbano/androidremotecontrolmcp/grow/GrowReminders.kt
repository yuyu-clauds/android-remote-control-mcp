package com.danielealbano.androidremotecontrolmcp.grow

/**
 * In-app reminder schedule. Deliberately mirrors the server-side Telegram reminder
 * (`med-reminder.sh`): Telegram stays the reliable primary channel (server push, can't
 * be killed by the phone), and this in-app layer is the more visible second tier she
 * asked for. If the in-app alarm ever drifts or gets dozed, the Telegram one still fires.
 *
 * Times: 7:00 泮托拉唑 / 7:45·11:45·17:45 莫沙必利 / 9:30·13:30·19:30 铝碳酸镁.
 */
internal object GrowReminders {

    const val CHANNEL_MED = "grow_med"
    const val CHANNEL_TASK = "grow_task"

    /** [id] is unique and doubles as the alarm requestCode and the notification id. */
    data class Reminder(
        val id: Int,
        val hour: Int,
        val minute: Int,
        val title: String,
        val text: String,
        val channel: String,
    )

    val ALL =
        listOf(
            Reminder(701, 7, 0, "该吃泮托拉唑了 💊", "早餐前空腹一粒，吃完再去吃早饭。", CHANNEL_MED),
            Reminder(745, 7, 45, "莫沙必利 · 饭前 💊", "这顿饭前吃一粒。", CHANNEL_MED),
            Reminder(930, 9, 30, "铝碳酸镁 💊", "两餐之间，嚼着吃。", CHANNEL_MED),
            Reminder(1145, 11, 45, "莫沙必利 · 饭前 💊", "午饭前吃一粒。", CHANNEL_MED),
            Reminder(1330, 13, 30, "铝碳酸镁 💊", "两餐之间，嚼着吃。", CHANNEL_MED),
            Reminder(1745, 17, 45, "莫沙必利 · 饭前 💊", "晚饭前吃一粒。", CHANNEL_MED),
            Reminder(1930, 19, 30, "铝碳酸镁 💊", "两餐之间，嚼着吃。", CHANNEL_MED),
        )
}
