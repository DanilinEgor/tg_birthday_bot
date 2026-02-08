import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandHandlerTest {

    private lateinit var database: DatabaseOperations
    private lateinit var handler: CommandHandler

    companion object {
        private const val CHAT_ID = 12345L
    }

    @BeforeEach
    fun setUp() {
        database = mockk(relaxed = true)
        handler = CommandHandler(database)
    }

    // --- /addexpense ---

    @Test
    fun `addExpense with valid input returns success`() {
        every { database.addExpense(CHAT_ID, "Alice", BigDecimal("50")) } returns
                Expense(1, CHAT_ID, "Alice", BigDecimal("50"))
        every { database.addParticipant(CHAT_ID, "Alice") } returns
                Participant(1, CHAT_ID, "Alice")

        val result = handler.handleAddExpense(CHAT_ID, "/addexpense Alice 50")
        assertContains(result, "Alice spent €50.00")
        verify { database.addExpense(CHAT_ID, "Alice", BigDecimal("50")) }
        verify { database.addParticipant(CHAT_ID, "Alice") }
    }

    @Test
    fun `addExpense with missing args returns usage`() {
        val result = handler.handleAddExpense(CHAT_ID, "/addexpense")
        assertContains(result, "Usage: /addexpense")
    }

    @Test
    fun `addExpense with missing amount returns usage`() {
        val result = handler.handleAddExpense(CHAT_ID, "/addexpense Alice")
        assertContains(result, "Usage: /addexpense")
    }

    @Test
    fun `addExpense with invalid amount returns error`() {
        val result = handler.handleAddExpense(CHAT_ID, "/addexpense Alice abc")
        assertContains(result, "valid positive amount")
    }

    @Test
    fun `addExpense with zero amount returns error`() {
        val result = handler.handleAddExpense(CHAT_ID, "/addexpense Alice 0")
        assertContains(result, "valid positive amount")
    }

    @Test
    fun `addExpense with negative amount returns error`() {
        val result = handler.handleAddExpense(CHAT_ID, "/addexpense Alice -10")
        assertContains(result, "valid positive amount")
    }

    // --- /addparticipant ---

    @Test
    fun `addParticipant with valid input returns success`() {
        every { database.addParticipant(CHAT_ID, "Alice") } returns Participant(1, CHAT_ID, "Alice")

        val result = handler.handleAddParticipant(CHAT_ID, "/addparticipant Alice")
        assertContains(result, "Added Alice to participants list")
    }

    @Test
    fun `addParticipant with duplicate returns warning`() {
        every { database.addParticipant(CHAT_ID, "Alice") } returns null

        val result = handler.handleAddParticipant(CHAT_ID, "/addparticipant Alice")
        assertContains(result, "already in the participants list")
    }

    @Test
    fun `addParticipant with missing args returns usage`() {
        val result = handler.handleAddParticipant(CHAT_ID, "/addparticipant")
        assertContains(result, "Usage: /addparticipant")
    }

    @Test
    fun `addParticipant bulk add returns summary`() {
        every { database.addParticipant(CHAT_ID, "Alice") } returns Participant(1, CHAT_ID, "Alice")
        every { database.addParticipant(CHAT_ID, "Bob") } returns Participant(2, CHAT_ID, "Bob")
        every { database.addParticipant(CHAT_ID, "Charlie") } returns Participant(3, CHAT_ID, "Charlie")

        val result = handler.handleAddParticipant(CHAT_ID, "/addparticipant Alice Bob Charlie")
        assertContains(result, "Added: Alice, Bob, Charlie")
    }

    @Test
    fun `addParticipant bulk with some duplicates returns mixed summary`() {
        every { database.addParticipant(CHAT_ID, "Alice") } returns Participant(1, CHAT_ID, "Alice")
        every { database.addParticipant(CHAT_ID, "Bob") } returns null
        every { database.addParticipant(CHAT_ID, "Charlie") } returns Participant(3, CHAT_ID, "Charlie")

        val result = handler.handleAddParticipant(CHAT_ID, "/addparticipant Alice Bob Charlie")
        assertContains(result, "Added: Alice, Charlie")
        assertContains(result, "Already existed: Bob")
    }

    // --- /removeparticipant ---

    @Test
    fun `removeParticipant when found returns success`() {
        every { database.removeParticipant(CHAT_ID, "Alice") } returns true

        val result = handler.handleRemoveParticipant(CHAT_ID, "/removeparticipant Alice")
        assertContains(result, "Removed Alice from participants list")
    }

    @Test
    fun `removeParticipant when not found returns error`() {
        every { database.removeParticipant(CHAT_ID, "Alice") } returns false

        val result = handler.handleRemoveParticipant(CHAT_ID, "/removeparticipant Alice")
        assertContains(result, "not in the participants list")
    }

    @Test
    fun `removeParticipant with missing args returns usage`() {
        val result = handler.handleRemoveParticipant(CHAT_ID, "/removeparticipant")
        assertContains(result, "Usage: /removeparticipant")
    }

    // --- /participants ---

    @Test
    fun `listParticipants with participants returns list`() {
        every { database.getParticipants(CHAT_ID) } returns listOf(
            Participant(1, CHAT_ID, "Alice"),
            Participant(2, CHAT_ID, "Bob")
        )

        val result = handler.handleListParticipants(CHAT_ID)
        assertContains(result, "Participants (2)")
        assertContains(result, "Alice")
        assertContains(result, "Bob")
    }

    @Test
    fun `listParticipants with no participants returns empty message`() {
        every { database.getParticipants(CHAT_ID) } returns emptyList()

        val result = handler.handleListParticipants(CHAT_ID)
        assertContains(result, "No participants yet")
    }

    // --- /status ---

    @Test
    fun `status with expenses returns summary`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("60.00")),
            Expense(2, CHAT_ID, "Bob", BigDecimal("40.00"))
        )

        val result = handler.handleStatus(CHAT_ID)
        assertContains(result, "Alice: €60.00")
        assertContains(result, "Bob: €40.00")
        assertContains(result, "Total spent: €100.00")
    }

    @Test
    fun `status with no expenses returns empty message`() {
        every { database.getExpenses(CHAT_ID) } returns emptyList()

        val result = handler.handleStatus(CHAT_ID)
        assertContains(result, "No expenses recorded yet")
    }

    // --- /calculate ---

    @Test
    fun `calculate with expenses and participants returns breakdown`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("90.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns listOf(
            Participant(1, CHAT_ID, "Alice"),
            Participant(2, CHAT_ID, "Bob"),
            Participant(3, CHAT_ID, "Charlie")
        )
        every { database.getPaidDebts(CHAT_ID) } returns emptyMap()

        val result = handler.handleCalculate(CHAT_ID)
        assertContains(result, "Total: €90.00")
        assertContains(result, "Participants: 3")
        assertContains(result, "Per person: €30.00")
        assertContains(result, "Who owes money")
        assertContains(result, "Who should receive")
    }

    @Test
    fun `calculate with no expenses returns error`() {
        every { database.getExpenses(CHAT_ID) } returns emptyList()

        val result = handler.handleCalculate(CHAT_ID)
        assertContains(result, "No expenses to calculate")
    }

    @Test
    fun `calculate with no participants returns error`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("50.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns emptyList()

        val result = handler.handleCalculate(CHAT_ID)
        assertContains(result, "No participants added")
    }

    @Test
    fun `calculate when everyone is settled returns settled message`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("50.00")),
            Expense(2, CHAT_ID, "Bob", BigDecimal("50.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns listOf(
            Participant(1, CHAT_ID, "Alice"),
            Participant(2, CHAT_ID, "Bob")
        )
        every { database.getPaidDebts(CHAT_ID) } returns emptyMap()

        val result = handler.handleCalculate(CHAT_ID)
        assertContains(result, "Everyone is settled up!")
    }

    @Test
    fun `calculate shows paid marker for paid debts`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("90.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns listOf(
            Participant(1, CHAT_ID, "Alice"),
            Participant(2, CHAT_ID, "Bob"),
            Participant(3, CHAT_ID, "Charlie")
        )
        every { database.getPaidDebts(CHAT_ID) } returns mapOf("Bob" to BigDecimal("30.00"))

        val result = handler.handleCalculate(CHAT_ID)
        assertContains(result, "✅ Bob: €30.00 (paid)")
        assertContains(result, "Charlie: €30.00")
    }

    // --- /notify ---

    @Test
    fun `notify with debts returns reminders`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("90.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns listOf(
            Participant(1, CHAT_ID, "Alice"),
            Participant(2, CHAT_ID, "Bob"),
            Participant(3, CHAT_ID, "Charlie")
        )
        every { database.getPaidDebts(CHAT_ID) } returns emptyMap()

        val result = handler.handleNotify(CHAT_ID)
        assertContains(result, "Payment Reminder")
        assertContains(result, "please transfer")
    }

    @Test
    fun `notify with no expenses returns error`() {
        every { database.getExpenses(CHAT_ID) } returns emptyList()

        val result = handler.handleNotify(CHAT_ID)
        assertContains(result, "No expenses to notify about")
    }

    @Test
    fun `notify with no participants returns error`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("50.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns emptyList()

        val result = handler.handleNotify(CHAT_ID)
        assertContains(result, "No participants added")
    }

    @Test
    fun `notify when no one owes returns settled message`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("50.00")),
            Expense(2, CHAT_ID, "Bob", BigDecimal("50.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns listOf(
            Participant(1, CHAT_ID, "Alice"),
            Participant(2, CHAT_ID, "Bob")
        )
        every { database.getPaidDebts(CHAT_ID) } returns emptyMap()

        val result = handler.handleNotify(CHAT_ID)
        assertContains(result, "No one owes money")
    }

    @Test
    fun `notify skips paid debts`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("90.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns listOf(
            Participant(1, CHAT_ID, "Alice"),
            Participant(2, CHAT_ID, "Bob"),
            Participant(3, CHAT_ID, "Charlie")
        )
        // Bob paid his €30 debt
        every { database.getPaidDebts(CHAT_ID) } returns mapOf("Bob" to BigDecimal("30.00"))

        val result = handler.handleNotify(CHAT_ID)
        assertContains(result, "Payment Reminder")
        assertContains(result, "Charlie please transfer")
        assertTrue { !result.contains("Bob please transfer") }
    }

    @Test
    fun `notify returns settled when all debts paid`() {
        every { database.getExpenses(CHAT_ID) } returns listOf(
            Expense(1, CHAT_ID, "Alice", BigDecimal("90.00"))
        )
        every { database.getParticipants(CHAT_ID) } returns listOf(
            Participant(1, CHAT_ID, "Alice"),
            Participant(2, CHAT_ID, "Bob"),
            Participant(3, CHAT_ID, "Charlie")
        )
        every { database.getPaidDebts(CHAT_ID) } returns mapOf(
            "Bob" to BigDecimal("30.00"),
            "Charlie" to BigDecimal("30.00")
        )

        val result = handler.handleNotify(CHAT_ID)
        assertContains(result, "No one owes money")
    }

    // --- /help ---

    @Test
    fun `getHelpMessage returns expected content`() {
        val result = handler.getHelpMessage()
        assertContains(result, "Birthday Gift Bot")
        assertContains(result, "/addexpense")
        assertContains(result, "/addparticipant")
        assertContains(result, "/calculate")
        assertContains(result, "/reset")
    }

    // --- Usage flow tests (real H2 database) ---

    @Nested
    inner class UsageFlowTests {

        private lateinit var db: Database
        private lateinit var flowHandler: CommandHandler
        private val flowChatId = 77777L

        @BeforeEach
        fun setUp() {
            db = Database("jdbc:h2:mem:flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "", "")
            db.initialize()
            db.clearExpenses(flowChatId)
            db.clearParticipants(flowChatId)
            db.clearPaidDebts(flowChatId)
            flowHandler = CommandHandler(db)
        }

        @Test
        fun `typical workflow - add participants, expenses, calculate, notify`() {
            // 1. Add participants
            var result = flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice")
            assertContains(result, "Added Alice")

            result = flowHandler.handleAddParticipant(flowChatId, "/addparticipant Bob")
            assertContains(result, "Added Bob")

            result = flowHandler.handleAddParticipant(flowChatId, "/addparticipant Charlie")
            assertContains(result, "Added Charlie")

            // 2. Verify participants list
            result = flowHandler.handleListParticipants(flowChatId)
            assertContains(result, "Participants (3)")
            assertContains(result, "Alice")
            assertContains(result, "Bob")
            assertContains(result, "Charlie")

            // 3. Add expenses - Alice bought the gift, Bob bought food
            result = flowHandler.handleAddExpense(flowChatId, "/addexpense Alice 60")
            assertContains(result, "Alice spent €60.00")

            result = flowHandler.handleAddExpense(flowChatId, "/addexpense Bob 30")
            assertContains(result, "Bob spent €30.00")

            // 4. Check status
            result = flowHandler.handleStatus(flowChatId)
            assertContains(result, "Alice: €60.00")
            assertContains(result, "Bob: €30.00")
            assertContains(result, "Total spent: €90.00")

            // 5. Calculate - per person is €30, so Charlie owes €30, Alice gets €30 back
            result = flowHandler.handleCalculate(flowChatId)
            assertContains(result, "Total: €90.00")
            assertContains(result, "Per person: €30.00")
            assertContains(result, "Who owes money")
            assertContains(result, "Charlie: €30.00")
            assertContains(result, "Who should receive")
            assertContains(result, "Alice: €30.00")

            // 6. Notify - only Charlie should be reminded
            result = flowHandler.handleNotify(flowChatId)
            assertContains(result, "Payment Reminder")
            assertContains(result, "Charlie please transfer €30.00")
        }

        @Test
        fun `addexpense auto-adds participant`() {
            // No participants yet
            var result = flowHandler.handleListParticipants(flowChatId)
            assertContains(result, "No participants yet")

            // Adding expense auto-adds the buyer as participant
            flowHandler.handleAddExpense(flowChatId, "/addexpense Dave 25")

            result = flowHandler.handleListParticipants(flowChatId)
            assertContains(result, "Dave")
            assertContains(result, "Participants (1)")
        }

        @Test
        fun `duplicate participant then expense keeps single entry`() {
            flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice")

            // Adding expense for Alice should not create a duplicate
            flowHandler.handleAddExpense(flowChatId, "/addexpense Alice 40")

            val result = flowHandler.handleListParticipants(flowChatId)
            assertContains(result, "Participants (1)")
        }

        @Test
        fun `remove participant then re-add works`() {
            flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice")
            flowHandler.handleRemoveParticipant(flowChatId, "/removeparticipant Alice")

            var result = flowHandler.handleListParticipants(flowChatId)
            assertContains(result, "No participants yet")

            flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice")
            result = flowHandler.handleListParticipants(flowChatId)
            assertContains(result, "Alice")
        }

        @Test
        fun `equal spending results in everyone settled`() {
            flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice")
            flowHandler.handleAddParticipant(flowChatId, "/addparticipant Bob")
            flowHandler.handleAddExpense(flowChatId, "/addexpense Alice 50")
            flowHandler.handleAddExpense(flowChatId, "/addexpense Bob 50")

            val calcResult = flowHandler.handleCalculate(flowChatId)
            assertContains(calcResult, "Everyone is settled up!")

            val notifyResult = flowHandler.handleNotify(flowChatId)
            assertContains(notifyResult, "No one owes money")
        }

        @Test
        fun `bulk add participants then calculate`() {
            val result = flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice Bob Charlie")
            assertContains(result, "Added: Alice, Bob, Charlie")

            val listResult = flowHandler.handleListParticipants(flowChatId)
            assertContains(listResult, "Participants (3)")

            flowHandler.handleAddExpense(flowChatId, "/addexpense Alice 90")

            val calcResult = flowHandler.handleCalculate(flowChatId)
            assertContains(calcResult, "Per person: €30.00")
            assertContains(calcResult, "Charlie: €30.00")
        }

        @Test
        fun `mark debt as paid removes from notify`() {
            flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice Bob Charlie")
            flowHandler.handleAddExpense(flowChatId, "/addexpense Alice 90")

            // Before paying: both Bob and Charlie owe
            var notifyResult = flowHandler.handleNotify(flowChatId)
            assertContains(notifyResult, "Bob please transfer €30.00")
            assertContains(notifyResult, "Charlie please transfer €30.00")

            // Mark Bob as paid
            db.addPaidDebt(flowChatId, "Bob", BigDecimal("30.00"))

            // After paying: only Charlie owes
            notifyResult = flowHandler.handleNotify(flowChatId)
            assertContains(notifyResult, "Charlie please transfer €30.00")
            assertTrue { !notifyResult.contains("Bob please transfer") }

            // Calculate shows Bob as paid
            val calcResult = flowHandler.handleCalculate(flowChatId)
            assertContains(calcResult, "✅ Bob: €30.00 (paid)")

            // getUnpaidDebts only returns Charlie
            val unpaid = flowHandler.getUnpaidDebts(flowChatId)
            assertEquals(1, unpaid.size)
            assertTrue { "Charlie" in unpaid }
        }

        @Test
        fun `all debts paid results in settled notify`() {
            flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice Bob")
            flowHandler.handleAddExpense(flowChatId, "/addexpense Alice 100")

            db.addPaidDebt(flowChatId, "Bob", BigDecimal("50.00"))

            val result = flowHandler.handleNotify(flowChatId)
            assertContains(result, "No one owes money")
        }

        @Test
        fun `bulk add with duplicates returns mixed summary`() {
            flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice")

            val result = flowHandler.handleAddParticipant(flowChatId, "/addparticipant Alice Bob Charlie")
            assertContains(result, "Added: Bob, Charlie")
            assertContains(result, "Already existed: Alice")

            val listResult = flowHandler.handleListParticipants(flowChatId)
            assertContains(listResult, "Participants (3)")
        }
    }
}
