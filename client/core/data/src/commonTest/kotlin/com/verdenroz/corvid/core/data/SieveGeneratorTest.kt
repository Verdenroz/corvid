package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.ConditionField
import com.verdenroz.corvid.core.model.ConditionOperator
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterCondition
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.MatchMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val QUOTED = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
private const val HOSTILE = "a\"b\\c\"; discard; #"

private fun rule(
    vararg conditions: FilterCondition,
    actions: List<FilterAction> = listOf(FilterAction.MarkRead),
    match: MatchMode = MatchMode.All,
    stop: Boolean = false,
    enabled: Boolean = true,
) = FilterRule("r", enabled, match, conditions.toList(), actions, stop)

private fun condition(
    field: ConditionField,
    operator: ConditionOperator,
    value: String = "x",
) = FilterCondition(field, operator, value)

private val TRASH = FilterAction.MoveToFolder("t1", "Trash")

private fun body(
    rule: FilterRule,
    trash: FilterAction.MoveToFolder = TRASH,
) = CorvidScript(listOf(rule)).render(trash).lines().drop(2).joinToString("\n").trim()

class SieveGeneratorTest {
    @Test
    fun `a from contains condition tests the from header`() {
        val script = body(rule(condition(ConditionField.From, ConditionOperator.Contains)))

        assertEquals("if allof (header :contains \"from\" \"x\") {\n  addflag \"\\\\Seen\";\n}", script)
    }

    @Test
    fun `a from is condition tests the whole address`() {
        val script = body(rule(condition(ConditionField.From, ConditionOperator.Is)))

        assertTrue(script.startsWith("if allof (address :is \"from\" \"x\")"))
    }

    @Test
    fun `a to or cc condition tests both headers`() {
        val script = body(rule(condition(ConditionField.ToOrCc, ConditionOperator.Contains)))

        assertTrue(script.startsWith("if allof (header :contains [\"to\", \"cc\"] \"x\")"))
    }

    @Test
    fun `a subject is condition compares the whole header`() {
        val script = body(rule(condition(ConditionField.Subject, ConditionOperator.Is)))

        assertTrue(script.startsWith("if allof (header :is \"subject\" \"x\")"))
    }

    @Test
    fun `a list id does not contain condition is negated`() {
        val script = body(rule(condition(ConditionField.ListId, ConditionOperator.DoesNotContain)))

        assertTrue(script.startsWith("if allof (not header :contains \"list-id\" \"x\")"))
    }

    @Test
    fun `match any joins conditions with anyof`() {
        val script =
            body(
                rule(
                    condition(ConditionField.From, ConditionOperator.Contains, "a"),
                    condition(ConditionField.Subject, ConditionOperator.Contains, "b"),
                    match = MatchMode.Any,
                ),
            )

        assertTrue(script.startsWith("if anyof (header :contains \"from\" \"a\", header :contains \"subject\" \"b\")"))
    }

    @Test
    fun `mark read and star set the imap flags`() {
        val script =
            body(
                rule(
                    condition(ConditionField.From, ConditionOperator.Contains),
                    actions = listOf(FilterAction.MarkRead, FilterAction.Star),
                ),
            )

        assertTrue("addflag \"\\\\Seen\";" in script)
        assertTrue("addflag \"\\\\Flagged\";" in script)
    }

    @Test
    fun `a label is added as a corvid-rule keyword never the bare label`() {
        val script =
            body(
                rule(
                    condition(ConditionField.From, ConditionOperator.Contains),
                    actions = listOf(FilterAction.AddLabel("Receipts 2024")),
                ),
            )

        assertTrue("addflag \"corvid-rule-receipts-2024\";" in script)
        assertFalse("\"Receipts 2024\"" in script)
    }

    @Test
    fun `a move files into the named folder after the flags so the filed copy carries them`() {
        val script =
            body(
                rule(
                    condition(ConditionField.From, ConditionOperator.Contains),
                    actions = listOf(FilterAction.MoveToFolder("m7", "Work"), FilterAction.MarkRead),
                ),
            )

        assertTrue(script.indexOf("addflag") < script.indexOf("fileinto :mailboxid \"m7\" \"Work\";"))
    }

    @Test
    fun `move to trash files by the trash mailbox id passed in`() {
        val script =
            body(
                rule(condition(ConditionField.From, ConditionOperator.Contains), actions = listOf(FilterAction.MoveToTrash)),
                trash = FilterAction.MoveToFolder("t9", "Deleted Items"),
            )

        assertTrue("fileinto :mailboxid \"t9\" \"Deleted Items\";" in script)
    }

    @Test
    fun `stop ends the rule with a stop command`() {
        val script = body(rule(condition(ConditionField.From, ConditionOperator.Contains), stop = true))

        assertTrue(script.endsWith("  stop;\n}"))
    }

    @Test
    fun `a disabled rule is left out of the script but kept in the rules line`() {
        val disabled = rule(condition(ConditionField.From, ConditionOperator.Contains), enabled = false)
        val script = CorvidScript(listOf(disabled)).render(TRASH)

        assertFalse("if " in script)
        assertEquals(listOf(disabled), parseRules(script))
    }

    @Test
    fun `a value with quotes backslashes and sieve syntax stays inert text`() {
        val script =
            CorvidScript(listOf(rule(condition(ConditionField.Subject, ConditionOperator.Contains, HOSTILE)))).render(TRASH)

        val code = script.lines().filterNot { it.startsWith("#") }.joinToString("\n")
        assertTrue(quote(HOSTILE) in code)
        assertFalse("discard" in code.replace(QUOTED, "\"\""))
    }

    @Test
    fun `a hostile value comes back exactly from the saved script`() {
        val saved = rule(condition(ConditionField.Subject, ConditionOperator.Contains, HOSTILE))

        val loaded = parseRules(CorvidScript(listOf(saved)).render(TRASH))

        assertEquals(listOf(saved), loaded)
    }

    @Test
    fun `every action kind survives the round trip`() {
        val saved =
            rule(
                condition(ConditionField.ToOrCc, ConditionOperator.DoesNotContain, "é\n日本"),
                actions =
                    listOf(
                        FilterAction.MoveToFolder("m8", "Work/Sub"),
                        FilterAction.AddLabel("Bills & Co"),
                        FilterAction.MarkRead,
                        FilterAction.Star,
                        FilterAction.MoveToTrash,
                    ),
                match = MatchMode.Any,
                stop = true,
            )

        assertEquals(listOf(saved), parseRules(CorvidScript(listOf(saved)).render(TRASH)))
    }

    @Test
    fun `a rule missing a condition an action or a target is left out of the script`() {
        val incomplete =
            listOf(
                rule(),
                rule(condition(ConditionField.From, ConditionOperator.Contains, " ")),
                rule(condition(ConditionField.From, ConditionOperator.Contains), actions = emptyList()),
                rule(condition(ConditionField.From, ConditionOperator.Contains), actions = listOf(FilterAction.AddLabel(""))),
                rule(condition(ConditionField.From, ConditionOperator.Contains), actions = listOf(FilterAction.MoveToFolder("", "x"))),
            )

        val script = CorvidScript(incomplete).render(TRASH)

        assertFalse("if " in script)
        assertEquals(incomplete, parseRules(script))
    }

    @Test
    fun `a rules line that is present but unreadable is a format error`() {
        assertFailsWith<FiltersFormatException> { parseRules("# corvid-rules v1 [{\nkeep;\n") }
        assertFailsWith<FiltersFormatException> { parseRules("# corvid-rules v1 [{\"name\":\"r\"}]\n") }
        assertFailsWith<FiltersFormatException> {
            parseRules(
                "# corvid-rules v1 [{\"name\":\"r\",\"enabled\":\"x\",\"match\":\"All\"," +
                    "\"conditions\":[],\"actions\":[],\"stop\":1}]\n",
            )
        }
    }

    @Test
    fun `a script with no rules line parses as null`() {
        assertEquals(null, parseRules("require [\"fileinto\"];\nkeep;\n"))
    }

    @Test
    fun `the rules line keeps its pinned wire shape`() {
        val saved =
            rule(
                condition(ConditionField.From, ConditionOperator.Is, "a@b.test"),
                actions = listOf(FilterAction.AddLabel("x"), FilterAction.MoveToFolder("m1", "A/B")),
            )

        assertEquals(
            "# corvid-rules v1 [{\"name\":\"r\",\"enabled\":true,\"match\":\"All\"," +
                "\"conditions\":[{\"field\":\"From\",\"operator\":\"Is\",\"value\":\"a@b.test\"}]," +
                "\"actions\":[{\"type\":\"AddLabel\",\"value\":\"x\"}," +
                "{\"type\":\"MoveToFolder\",\"id\":\"m1\",\"value\":\"A/B\"}],\"stop\":false}]",
            rulesLine(listOf(saved)),
        )
    }
}
