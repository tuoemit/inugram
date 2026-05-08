package desu.inugram.helpers

import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.TranslateAlert2
import java.util.concurrent.ConcurrentHashMap

object TranslateHelper {
    // dialog id -> message ids
    private val manual = ConcurrentHashMap<Long, MutableSet<Int>>()

    @JvmStatic
    @JvmOverloads
    fun isManualTranslated(msg: MessageObject?, group: MessageObject.GroupedMessages? = null): Boolean {
        if (msg != null && manual[msg.dialogId]?.contains(msg.id) == true) return true
        val cap = group?.captionMessage ?: return false
        return manual[cap.dialogId]?.contains(cap.id) == true
    }

    /** @return true if handled in-place; false → caller should fall back to stock TranslateAlert. */
    @JvmStatic
    fun startTranslate(
        activity: ChatActivity,
        selected: MessageObject?,
        group: MessageObject.GroupedMessages?,
        fromLang: String?,
        toLang: String?,
    ): Boolean {
        if (!InuConfig.IN_PLACE_TRANSLATION.value) return false
        if (selected == null || toLang == null) return false
        if (selected.isPoll) return false

        val target = group?.captionMessage?.takeIf { !it.messageOwner?.message.isNullOrEmpty() } ?: selected
        val owner = target.messageOwner ?: return false
        val account = activity.currentAccount
        val controller = MessagesController.getInstance(account).translateController
        val dialogId = target.dialogId

        manual.computeIfAbsent(dialogId) { ConcurrentHashMap.newKeySet() }.add(target.id)
        activity.dimBehindView(false)
        if (fromLang != null && fromLang != "und" && owner.originalLanguage == null) {
            owner.originalLanguage = fromLang
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslating, target)

        controller.pushToTranslate(target, toLang) { isTranscription, id, text, lang ->
            if (id != target.id) return@pushToTranslate
            if (text == null || text.text.isNullOrEmpty()) {
                revert(activity, target)
                BulletinFactory.of(activity)
                    .createErrorBulletin(LocaleController.getString(R.string.TranslationFailedAlert1))
                    .show()
                return@pushToTranslate
            }
            owner.translatedToLanguage = lang
            if (isTranscription) owner.translatedVoiceTranscription = text
            else owner.translatedText = text
            owner.translatedPoll = null
            finish(account, dialogId, owner, target)
        }
        return true
    }

    private fun finish(account: Int, dialogId: Long, owner: TLRPC.Message, target: MessageObject) {
        MessagesStorage.getInstance(account).updateMessageCustomParams(dialogId, owner)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, target)
    }

    @JvmStatic
    fun extraTimeWidth(msg: MessageObject?): Int {
        if (msg == null || !msg.translated) return 0
        val owner = msg.messageOwner ?: return 0
        if (owner.translatedToLanguage == null) return 0
        val fromLang = owner.originalLanguage
        return if (isManualTranslated(msg) && fromLang != null && fromLang != "und") {
            arrowDrawable?.intrinsicWidth ?: 0
        } else {
            AndroidUtilities.dp(11f)
        }
    }

    private val arrowDrawable: Drawable? by lazy {
        ContextCompat.getDrawable(
            ApplicationLoader.applicationContext,
            R.drawable.search_arrow,
        )
    }

    @JvmStatic
    fun translatedTimePrefix(msg: MessageObject?, time: CharSequence?): CharSequence? {
        if (time == null || msg == null || !msg.translated) return time
        val owner = msg.messageOwner ?: return time
        val toLang = owner.translatedToLanguage ?: return time
        val sb = SpannableStringBuilder()
        val fromLang = owner.originalLanguage
        if (isManualTranslated(msg) && fromLang != null && fromLang != "und") {
            sb.append(label(fromLang)).append(" ")
            sb.append("​")
            sb.setSpan(ColoredImageSpan(R.drawable.search_arrow, ColoredImageSpan.ALIGN_CENTER), sb.length - 1, sb.length, 0)
            sb.append(" ").append(label(toLang)).append(" ")
        } else {
            sb.append("​")
            sb.setSpan(ColoredImageSpan(R.drawable.msg_translate).apply {
                setSize(AndroidUtilities.dp(11f))
                setTranslateY(AndroidUtilities.dpf2(1f))
            }, sb.length - 1, sb.length, 0)
            sb.append(" ")
        }
        return sb.append(time)
    }

    private fun label(code: String): String {
        val name = TranslateAlert2.languageName(code) ?: code.uppercase()
        return if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)
    }

    @JvmStatic
    fun revert(activity: ChatActivity, msg: MessageObject) {
        manual[msg.dialogId]?.remove(msg.id)
        NotificationCenter.getInstance(activity.currentAccount)
            .postNotificationName(NotificationCenter.messageTranslated, msg)
    }

    fun resetForDialog(dialogId: Long) {
        manual.remove(dialogId)
    }
}
