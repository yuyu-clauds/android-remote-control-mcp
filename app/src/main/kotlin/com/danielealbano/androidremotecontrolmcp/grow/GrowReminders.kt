package com.danielealbano.androidremotecontrolmcp.grow

/**
 * In-app medication reminders.
 *
 * 6/8 雨雨 redesign: reminders no longer fire at fixed clock times — they follow her real
 * rhythm via home-screen widget taps, because the whole point of the app is to spend LESS
 * life-management effort and a fixed clock just fights her schedule:
 *   醒了 → 泮托拉唑 (空腹晨起)
 *   吃饭 (早/午/晚) → 莫沙必利 当下 + 铝碳酸镁 饭后 1.5h
 * The server-side Telegram reminder (`med-reminder.sh`) stays armed at fixed times as the
 * can't-be-killed backstop, so even if she forgets to tap she still gets nudged.
 *
 * [ALL] keeps the legacy fixed-time definitions (no longer armed in-app; kept for the texts
 * and reference). [EVENT_ALL] holds the signal-triggered reminders.
 */
internal object GrowReminders {

    const val CHANNEL_MED = "grow_med"
    const val CHANNEL_TASK = "grow_task"

    /**
     * [id] is unique and doubles as the alarm requestCode and the notification id.
     * For [EVENT_ALL] entries [hour]/[minute] are unused (they are scheduled relative to a tap).
     */
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

    // Signal-triggered reminders (6/8). Fired relative to a widget tap, not a clock time.
    val EVENT_PANTUOLA = Reminder(801, 0, 0, "醒了？先吃泮托拉唑 💊", "空腹一粒，吃完过一会儿再吃早饭。", CHANNEL_MED)
    val EVENT_MOSHA = Reminder(802, 0, 0, "这顿的莫沙必利 💊", "饭前吃一粒最好，饭中也行。", CHANNEL_MED)
    val EVENT_LVTAN = Reminder(803, 0, 0, "铝碳酸镁 💊", "两餐之间，嚼着吃。", CHANNEL_MED)
    val EVENT_ALL = listOf(EVENT_PANTUOLA, EVENT_MOSHA, EVENT_LVTAN)

    fun byId(id: Int): Reminder? = ALL.find { it.id == id } ?: EVENT_ALL.find { it.id == id }
}
