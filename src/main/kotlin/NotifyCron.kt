import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.bots.DefaultAbsSender
import org.telegram.telegrambots.bots.DefaultBotOptions
import java.math.BigDecimal

fun main() {
    val botToken = System.getenv("BOT_TOKEN") ?: throw IllegalArgumentException("BOT_TOKEN not set")
    val databaseUrl = System.getenv("DATABASE_PUBLIC_URL")
        ?: System.getenv("DATABASE_URL")
        ?: throw IllegalArgumentException("DATABASE_PUBLIC_URL or DATABASE_URL not set")

    val regex = Regex("postgresql://([^:]+):([^@]+)@([^:]+):(\\d+)/(.+)")
    val matchResult = regex.find(databaseUrl) ?: throw IllegalArgumentException("Invalid DATABASE_URL format")

    val (user, password, host, port, database) = matchResult.destructured
    val jdbcUrl = "jdbc:postgresql://$host:$port/$database?sslmode=require"

    val db = Database(jdbcUrl, user, password)
    db.initialize()

    val commandHandler = CommandHandler(db)

    val sender = object : DefaultAbsSender(DefaultBotOptions()) {
        override fun getBotToken(): String = botToken
    }

    val chatIds = db.getActiveChatIds()
    println("Running daily notifications for ${chatIds.size} chats...")

    for (chatId in chatIds) {
        val message = commandHandler.handleNotify(chatId)
        if (!message.startsWith("❌") && !message.contains("Все долги оплачены")) {
            val msg = SendMessage()
            msg.chatId = chatId.toString()
            msg.text = message
            sender.execute(msg)
            println("Notified chat $chatId")
        }
    }

    println("Done.")
}
