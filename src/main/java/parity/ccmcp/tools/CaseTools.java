package parity.ccmcp.tools;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import parity.ccmcp.mask.SensitiveData;
import parity.ccmcp.owner.OwnerClient;
import parity.ccmcp.owner.OwnerErrorException;
import parity.ccmcp.owner.OwnerUnavailableException;
import parity.ccmcp.scope.Scope;
import parity.ccmcp.tools.Choices.CaseState;
import parity.ccmcp.tools.Choices.Channel;
import parity.ccmcp.tools.Choices.Person;
import parity.ccmcp.tools.Choices.ResponseFormat;
import parity.ccmcp.tools.Choices.Risk;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The case tools: the current customer's context, and their cases (one per problem, open
 * until it's solved). customer-context owns the data; each tool is one request to it.
 *
 * <p>Built to Anthropic's guidelines for agent tools and MCP servers:
 * <ul>
 *   <li>names are {@code parity_<action>_<resource>}, snake_case with the service prefix,
 *       because the server runs next to other MCP servers;</li>
 *   <li>descriptions say what a tool does, when to use it and where its ids come from;</li>
 *   <li>the method parameters are the tools' argument names, so they are snake_case and
 *       precise ({@code case_id}, not {@code id});</li>
 *   <li>reading tools page ({@code limit}, {@code offset}) and offer
 *       {@code response_format};</li>
 *   <li>every tool has its annotations; errors come back as results the agent can act on.</li>
 * </ul>
 * The customer is always the session's ({@link Scope}); no tool takes a customer argument.
 */
@Component
@RequiredArgsConstructor
public class CaseTools {

    public static final String GET_CUSTOMER_CONTEXT = "parity_get_customer_context";
    public static final String LIST_CASES = "parity_list_cases";
    public static final String OPEN_CASE = "parity_open_case";
    public static final String UPDATE_CASE = "parity_update_case";
    public static final String ASK_PERSON = "parity_ask_person";

    private static final String CASE_ID_FROM = "Use a case_id from parity_list_cases.";
    private static final String ITEM_ID_FROM = "Use item_ids from parity_get_customer_context.";
    private static final String NARROW_CONTEXT = "Ask for fewer items (limit), one channel, or a later since; "
            + "get more with offset.";
    private static final String NARROW_CASES = "Ask for fewer cases (limit) and get more with offset.";

    private final OwnerClient owner;
    private final ToolRunner runner;
    private final ToolResults results;
    private final JsonMapper json;

    @McpTool(name = GET_CUSTOMER_CONTEXT, title = "Get the customer's context", description = """
            Get what the current customer said and what happened on every channel (calls, web chat, SMS, \
            WhatsApp, Messenger, Instagram, email), with signal-detection's alerts and people's notes, newest \
            first. The current customer is the one of the interaction this session was opened for; you can't \
            look up another customer. Each item has an item_id, for parity_open_case and parity_update_case, \
            and a case_id when it already belongs to a case. Narrow it with channel and since, and page with \
            limit and offset.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult getCustomerContext(McpTransportContext context,
            @McpToolParam(required = false, description = "Only items from this channel. Omit for every channel.")
            Channel channel,
            @McpToolParam(required = false, description = "Only items at or after this time: a date such as "
                    + "2026-10-01 (midnight UTC), or a date and time such as 2026-10-01T09:00:00Z. Omit for the "
                    + "customer's whole history.")
            String since,
            @McpToolParam(required = false, description = "How many items to return, 1 to 50. Default 20.")
            Integer limit,
            @McpToolParam(required = false, description = "How many items to skip: next_offset from the previous "
                    + "result. Default 0.")
            Integer offset,
            @McpToolParam(required = false, description = "markdown (default) to read; json for every field, "
                    + "ids and timestamps included.")
            ResponseFormat response_format) {
        return runner.run(context, GET_CUSTOMER_CONTEXT, scope -> {
            ToolRunner.requireCustomer(scope);
            Arguments.Page page = Arguments.page(limit, offset);
            Instant from = Arguments.since(since);
            ObjectNode fields = paged(page);
            if (channel != null) {
                fields.put("channel", channel.name());
            }
            if (from != null) {
                fields.put("since", from.toString());
            }
            JsonNode result = SensitiveData.mask(owner.customerContext(scope, "get_context", fields));
            return response_format == ResponseFormat.json
                    ? results.json(CaseFormat.contextJson(result, page, json), NARROW_CONTEXT)
                    : results.text(CaseFormat.context(result, page), NARROW_CONTEXT);
        });
    }

    @McpTool(name = LIST_CASES, title = "List the customer's cases", description = """
            List the current customer's cases, newest first. A case is one problem of the customer, open until \
            it is solved. Each has its case_id, the problem, a state (open, waiting_customer, waiting_us, \
            solved), the latest assessment (summary, risk, recommended action), how many items it holds, and a \
            question to a person with its answer, if there is one. Call it before parity_open_case, so a problem \
            that already has a case isn't opened twice.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult listCases(McpTransportContext context,
            @McpToolParam(required = false, description = "Also list solved cases. Default false: unsolved only.")
            Boolean include_solved,
            @McpToolParam(required = false, description = "How many cases to return, 1 to 50. Default 20.")
            Integer limit,
            @McpToolParam(required = false, description = "How many cases to skip: next_offset from the previous "
                    + "result. Default 0.")
            Integer offset,
            @McpToolParam(required = false, description = "markdown (default) to read; json for every field.")
            ResponseFormat response_format) {
        return runner.run(context, LIST_CASES, scope -> {
            ToolRunner.requireCustomer(scope);
            Arguments.Page page = Arguments.page(limit, offset);
            boolean solvedToo = Boolean.TRUE.equals(include_solved);
            ObjectNode fields = paged(page);
            fields.put("include_solved", solvedToo);
            JsonNode result = SensitiveData.mask(owner.customerContext(scope, "list_cases", fields));
            return response_format == ResponseFormat.json
                    ? results.json(CaseFormat.casesJson(result, page, json), NARROW_CASES)
                    : results.text(CaseFormat.cases(result, page, solvedToo), NARROW_CASES);
        });
    }

    @McpTool(name = OPEN_CASE, title = "Open a case", description = """
            Open a case for one new problem of the current customer, and put the items that belong to it in \
            it. One case per problem: call parity_list_cases first, and if the problem already has a case, use \
            parity_update_case instead. Item ids come from parity_get_customer_context. Returns the new case \
            with its case_id.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false,
                    openWorldHint = false))
    public CallToolResult openCase(McpTransportContext context,
            @McpToolParam(description = "The customer's problem in one sentence, in their terms, at most 200 "
                    + "characters. Example: Refund for order 48213 hasn't arrived after 14 days.")
            String problem,
            @McpToolParam(required = false, description = "The item_ids from parity_get_customer_context that "
                    + "belong to this problem, at most 100.")
            List<String> item_ids,
            @McpToolParam(required = false, description = "Your assessment so far: what happened and what is "
                    + "known, at most 2000 characters.")
            String summary,
            @McpToolParam(required = false, description = "How likely the customer is to escalate, leave or be "
                    + "harmed if this isn't solved soon.")
            Risk risk,
            @McpToolParam(required = false, description = "The next step you recommend, at most 1000 characters. "
                    + "Example: Expedite the refund and confirm by email.")
            String action) {
        return runner.run(context, OPEN_CASE, scope -> {
            ToolRunner.requireCustomer(scope);
            ObjectNode fields = json.createObjectNode();
            fields.put("problem", Arguments.text("problem", problem, 200, true,
                    "Refund for order 48213 hasn't arrived after 14 days."));
            fields.set("item_ids", json.valueToTree(Arguments.ids("item_ids", item_ids, ITEM_ID_FROM)));
            putText(fields, "summary", Arguments.text("summary", summary, 2000, false, "The customer called twice."));
            putText(fields, "action", Arguments.text("action", action, 1000, false, "Expedite the refund."));
            if (risk != null) {
                fields.put("risk", risk.name());
            }
            JsonNode result = SensitiveData.mask(owner.customerContext(scope, "open_case", fields));
            return results.text("Opened the case.\n\n" + CaseFormat.caseCard(result.path("case"), "#"), NARROW_CASES);
        });
    }

    @McpTool(name = UPDATE_CASE, title = "Update a case", description = """
            Change one of the current customer's cases: its state, its problem, your assessment (summary, risk, \
            recommended action), or add items to it. Only the fields you pass change, and at least one is \
            needed. Set state to solved only when the customer's problem is resolved; to waiting_customer when \
            the next step is the customer's, and to waiting_us when it is ours. Returns the updated case.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult updateCase(McpTransportContext context,
            @McpToolParam(description = "The case to change, from parity_list_cases.")
            String case_id,
            @McpToolParam(required = false, description = "The case's new state.")
            CaseState state,
            @McpToolParam(required = false, description = "The problem restated in one sentence, at most 200 "
                    + "characters.")
            String problem,
            @McpToolParam(required = false, description = "Your assessment now, replacing the previous one, at "
                    + "most 2000 characters.")
            String summary,
            @McpToolParam(required = false, description = "How likely the customer is to escalate, leave or be "
                    + "harmed if this isn't solved soon.")
            Risk risk,
            @McpToolParam(required = false, description = "The next step you recommend now, at most 1000 "
                    + "characters.")
            String action,
            @McpToolParam(required = false, description = "item_ids from parity_get_customer_context to add to "
                    + "the case; items already in it stay as they are.")
            List<String> add_item_ids) {
        return runner.run(context, UPDATE_CASE, scope -> {
            ToolRunner.requireCustomer(scope);
            ObjectNode fields = json.createObjectNode();
            fields.put("case_id", Arguments.id("case_id", case_id, CASE_ID_FROM));
            if (state != null) {
                fields.put("state", state.name());
            }
            if (risk != null) {
                fields.put("risk", risk.name());
            }
            putText(fields, "problem", Arguments.text("problem", problem, 200, false, "Refund for order 48213 not received."));
            putText(fields, "summary", Arguments.text("summary", summary, 2000, false, "The refund was sent today."));
            putText(fields, "action", Arguments.text("action", action, 1000, false, "Confirm with the customer."));
            List<String> items = Arguments.ids("add_item_ids", add_item_ids, ITEM_ID_FROM);
            if (!items.isEmpty()) {
                fields.set("add_item_ids", json.valueToTree(items));
            }
            if (fields.size() == 1) {
                throw ToolError.invalid("Nothing to change: pass at least one of state, problem, summary, risk, action "
                        + "or add_item_ids. Example: case_id=" + case_id + ", state=waiting_customer.");
            }
            JsonNode result = SensitiveData.mask(caseRequest(scope, "update_case", fields, case_id));
            return results.text("Updated the case.\n\n" + CaseFormat.caseCard(result.path("case"), "#"), NARROW_CASES);
        });
    }

    @McpTool(name = ASK_PERSON, title = "Ask a person about a case", description = """
            Hand one of the current customer's cases to a person with a question, when you can't or shouldn't \
            decide alone: for example a refund above what you may approve, an unclear policy, or a customer at \
            high risk. ask=agent asks the agent working on this interaction; ask=supervisor asks their \
            supervisor. The person answers in their desktop, and their answer is final. Returns the question's \
            question_id; the answer shows on the case in parity_list_cases. Don't ask the same question again \
            while it is open.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false,
                    openWorldHint = false))
    public CallToolResult askPerson(McpTransportContext context,
            @McpToolParam(description = "The case the question is about, from parity_list_cases.")
            String case_id,
            @McpToolParam(description = "Whom to ask: the agent on this interaction, or their supervisor.")
            Person ask,
            @McpToolParam(description = "What you need the person to decide or tell you, at most 1000 characters. "
                    + "Example: May I offer a 10 EUR goodwill credit for the late refund?")
            String question,
            @McpToolParam(required = false, description = "Your proposed answer or action, for the person to "
                    + "confirm or change, at most 2000 characters.")
            String draft) {
        return runner.run(context, ASK_PERSON, scope -> {
            ToolRunner.requireCustomer(scope);
            ObjectNode fields = json.createObjectNode();
            fields.put("case_id", Arguments.id("case_id", case_id, CASE_ID_FROM));
            if (ask == null) {
                throw ToolError.invalid("ask is required: agent or supervisor.");
            }
            fields.put("ask", ask.name());
            fields.put("question", Arguments.text("question", question, 1000, true,
                    "May I offer a 10 EUR goodwill credit for the late refund?"));
            putText(fields, "draft", Arguments.text("draft", draft, 2000, false, "Offer the credit and apologize."));
            return results.text(CaseFormat.asked(caseRequest(scope, "ask_person", fields, case_id)), NARROW_CASES);
        });
    }

    /** A request about one case; a case that isn't the customer's says where the ids come from. */
    private JsonNode caseRequest(Scope scope, String cmd, ObjectNode fields, String caseId)
            throws OwnerUnavailableException, OwnerErrorException {
        try {
            return owner.customerContext(scope, cmd, fields);
        } catch (OwnerErrorException e) {
            if ("not_found".equals(e.code())) {
                throw new ToolError("owner_not_found", "Case " + caseId + " isn't one of this customer's cases. "
                        + "Call parity_list_cases to see the customer's cases and their case_ids.");
            }
            throw e;
        }
    }

    private ObjectNode paged(Arguments.Page page) {
        ObjectNode fields = json.createObjectNode();
        fields.put("limit", page.limit());
        fields.put("offset", page.offset());
        return fields;
    }

    private static void putText(ObjectNode fields, String name, String value) {
        if (value != null) {
            fields.put(name, value);
        }
    }
}
