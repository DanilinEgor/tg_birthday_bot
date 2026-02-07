import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseTest {

    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database("jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "", "")
        database.initialize()
        // Clear data between tests
        database.clearExpenses(CHAT_ID)
        database.clearParticipants(CHAT_ID)
        database.clearExpenses(OTHER_CHAT_ID)
        database.clearParticipants(OTHER_CHAT_ID)
    }

    companion object {
        private const val CHAT_ID = 12345L
        private const val OTHER_CHAT_ID = 99999L
    }

    // --- Expense tests ---

    @Test
    fun `addExpense returns expense with generated id`() {
        val expense = database.addExpense(CHAT_ID, "Alice", BigDecimal("50.00"))
        assertTrue(expense.id > 0)
        assertEquals(CHAT_ID, expense.chatId)
        assertEquals("Alice", expense.buyerName)
        assertEquals(BigDecimal("50.00"), expense.amount)
    }

    @Test
    fun `getExpenses returns all expenses for chat`() {
        database.addExpense(CHAT_ID, "Alice", BigDecimal("50.00"))
        database.addExpense(CHAT_ID, "Bob", BigDecimal("30.00"))

        val expenses = database.getExpenses(CHAT_ID)
        assertEquals(2, expenses.size)
        assertEquals("Alice", expenses[0].buyerName)
        assertEquals("Bob", expenses[1].buyerName)
    }

    @Test
    fun `getExpenses returns empty list when no expenses`() {
        val expenses = database.getExpenses(CHAT_ID)
        assertTrue(expenses.isEmpty())
    }

    @Test
    fun `getExpenses isolates by chatId`() {
        database.addExpense(CHAT_ID, "Alice", BigDecimal("50.00"))
        database.addExpense(OTHER_CHAT_ID, "Bob", BigDecimal("30.00"))

        val expenses = database.getExpenses(CHAT_ID)
        assertEquals(1, expenses.size)
        assertEquals("Alice", expenses[0].buyerName)
    }

    @Test
    fun `clearExpenses removes all expenses for chat`() {
        database.addExpense(CHAT_ID, "Alice", BigDecimal("50.00"))
        database.addExpense(CHAT_ID, "Bob", BigDecimal("30.00"))

        database.clearExpenses(CHAT_ID)

        val expenses = database.getExpenses(CHAT_ID)
        assertTrue(expenses.isEmpty())
    }

    @Test
    fun `clearExpenses does not affect other chats`() {
        database.addExpense(CHAT_ID, "Alice", BigDecimal("50.00"))
        database.addExpense(OTHER_CHAT_ID, "Bob", BigDecimal("30.00"))

        database.clearExpenses(CHAT_ID)

        assertTrue(database.getExpenses(CHAT_ID).isEmpty())
        assertEquals(1, database.getExpenses(OTHER_CHAT_ID).size)
    }

    // --- Participant tests ---

    @Test
    fun `addParticipant returns participant with generated id`() {
        val participant = database.addParticipant(CHAT_ID, "Alice")
        assertNotNull(participant)
        assertTrue(participant.id > 0)
        assertEquals(CHAT_ID, participant.chatId)
        assertEquals("Alice", participant.name)
    }

    @Test
    fun `addParticipant returns null for duplicate`() {
        database.addParticipant(CHAT_ID, "Alice")
        val duplicate = database.addParticipant(CHAT_ID, "Alice")
        assertNull(duplicate)
    }

    @Test
    fun `addParticipant allows same name in different chats`() {
        val p1 = database.addParticipant(CHAT_ID, "Alice")
        val p2 = database.addParticipant(OTHER_CHAT_ID, "Alice")
        assertNotNull(p1)
        assertNotNull(p2)
    }

    @Test
    fun `removeParticipant returns true when participant exists`() {
        database.addParticipant(CHAT_ID, "Alice")
        assertTrue(database.removeParticipant(CHAT_ID, "Alice"))
    }

    @Test
    fun `removeParticipant returns false when participant does not exist`() {
        assertFalse(database.removeParticipant(CHAT_ID, "NonExistent"))
    }

    @Test
    fun `getParticipants returns all participants for chat`() {
        database.addParticipant(CHAT_ID, "Alice")
        database.addParticipant(CHAT_ID, "Bob")

        val participants = database.getParticipants(CHAT_ID)
        assertEquals(2, participants.size)
    }

    @Test
    fun `getParticipants returns empty list when no participants`() {
        val participants = database.getParticipants(CHAT_ID)
        assertTrue(participants.isEmpty())
    }

    @Test
    fun `clearParticipants removes all participants for chat`() {
        database.addParticipant(CHAT_ID, "Alice")
        database.addParticipant(CHAT_ID, "Bob")

        database.clearParticipants(CHAT_ID)

        assertTrue(database.getParticipants(CHAT_ID).isEmpty())
    }

    @Test
    fun `clearParticipants does not affect other chats`() {
        database.addParticipant(CHAT_ID, "Alice")
        database.addParticipant(OTHER_CHAT_ID, "Bob")

        database.clearParticipants(CHAT_ID)

        assertTrue(database.getParticipants(CHAT_ID).isEmpty())
        assertEquals(1, database.getParticipants(OTHER_CHAT_ID).size)
    }
}
