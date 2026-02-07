import java.math.BigDecimal

data class Expense(val id: Int, val chatId: Long, val buyerName: String, val amount: BigDecimal)
data class Participant(val id: Int, val chatId: Long, val name: String)
