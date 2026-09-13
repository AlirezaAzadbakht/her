package com.her.agent.tools

import com.her.core.COMMON_DONE
import com.her.core.COMMON_DROPPED
import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.newId
import com.her.core.normalizeDateIso
import com.her.core.optDoubleOr
import com.her.core.optStringList
import com.her.core.optStringOrNull
import com.her.core.parseEnum
import com.her.core.requiredString
import com.her.data.repository.toJson
import com.her.domain.Commitment
import com.her.domain.CommitmentStatus
import com.her.domain.Goal
import com.her.domain.GoalStatus
import com.her.domain.GroceryItem
import com.her.domain.GroceryStatus
import com.her.domain.ImportantDate
import com.her.domain.MemoryRelationship
import com.her.domain.MemorySource
import com.her.domain.OpenLoop
import com.her.domain.OpenLoopStatus
import com.her.domain.Person
import com.her.domain.Project
import com.her.domain.ProjectStatus
import com.her.domain.RecurringResponsibility
import com.her.domain.Routine
import com.her.domain.TaskItem
import com.her.domain.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.registerEntityTools() {
    register("get_person", "Get a person by id or name.", objSchema("id" to str(), "name" to str())) { args ->
        val person = args.optStringOrNull("id")?.let { repo.getPerson(it) }
            ?: args.optStringOrNull("name")?.let { q -> repo.searchPeople(q).firstOrNull() }
        jsonObjectOf("ok" to true, "person" to person?.let { JSONObject(it.toJson()) })
    }
    register("search_people", "Search people.", objSchema("query" to str(), required = listOf("query"))) { args ->
        jsonObjectOf("ok" to true, "people" to JSONArray(repo.searchPeople(args.requiredString("query")).map { JSONObject(it.toJson()) }))
    }
    register(
        "update_person",
        "Create or update a person.",
        objSchema(
            "id" to str(),
            "name" to str(),
            "relationship" to str(),
            "birthday" to str("Gregorian or Jalali date; it is normalized to ISO before storage"),
            "importantNotes" to str(),
            "preferences" to str(),
            "confidence" to num(),
        ),
    ) { args ->
        val now = nowMillis()
        val birthday = normalizeDateIso(args.optStringOrNull("birthday"), nowZoned())
        val existing = args.optStringOrNull("id")?.let { repo.getPerson(it) }
            ?: args.optStringOrNull("name")?.let { n -> repo.people().firstOrNull { it.name.equals(n, true) } }
        val person = (existing ?: Person(newId(), args.requiredString("name"), null, null, null, null, now, 0.8, now, now, repo.deviceId, 1, null)).copy(
            name = args.optStringOrNull("name") ?: existing?.name ?: args.requiredString("name"),
            relationship = args.optStringOrNull("relationship") ?: existing?.relationship,
            birthday = birthday ?: existing?.birthday,
            importantNotes = args.optStringOrNull("importantNotes") ?: existing?.importantNotes,
            preferences = args.optStringOrNull("preferences") ?: existing?.preferences,
            confidence = args.optDoubleOr("confidence", existing?.confidence ?: 0.8),
            lastMentioned = now,
            updatedAt = now,
            version = (existing?.version ?: 0) + 1,
        )
        repo.savePerson(person)
        birthday?.let { bday ->
            val title = "${person.name}'s birthday"
            val existingDate = repo.importantDates().firstOrNull { it.relatedPersonId == person.id || it.title.equals(title, true) }
            repo.saveImportantDate(
                (existingDate ?: ImportantDate(newId(), title, bday, "yearly", person.id, null, 0.9, now, now, repo.deviceId, 1, null)).copy(
                    dateIso = bday,
                    relatedPersonId = person.id,
                    updatedAt = now,
                    version = (existingDate?.version ?: 0) + 1,
                ),
            )
        }
        jsonObjectOf("ok" to true, "id" to person.id)
    }

    register("get_project", "Get a project.", objSchema("id" to str(), "name" to str())) { args ->
        val project = args.optStringOrNull("id")?.let { repo.getProject(it) }
            ?: args.optStringOrNull("name")?.let { n -> repo.searchProjects(n).firstOrNull() }
        jsonObjectOf("ok" to true, "project" to project?.let { JSONObject(it.toJson()) })
    }
    register("search_projects", "Search projects.", objSchema("query" to str(), required = listOf("query"))) { args ->
        jsonObjectOf("ok" to true, "projects" to JSONArray(repo.searchProjects(args.requiredString("query")).map { JSONObject(it.toJson()) }))
    }
    register("update_project", "Create or update a project. Ideas can live in description/summary or related memories.", objSchema("id" to str(), "name" to str(), "description" to str(), "summary" to str(), "status" to enumField<ProjectStatus>(), "importance" to num())) { args ->
        val now = nowMillis()
        val existing = args.optStringOrNull("id")?.let { repo.getProject(it) }
            ?: args.optStringOrNull("name")?.let { n -> repo.projects().firstOrNull { it.name.equals(n, true) } }
        val project = (existing ?: Project(newId(), args.requiredString("name"), null, ProjectStatus.ACTIVE, null, 0.5, now, now, repo.deviceId, 1, null)).copy(
            name = args.optStringOrNull("name") ?: existing?.name ?: args.requiredString("name"),
            description = args.optStringOrNull("description") ?: existing?.description,
            summary = args.optStringOrNull("summary") ?: existing?.summary,
            status = parseEnum<ProjectStatus>(args.optStringOrNull("status"), COMMON_DONE + COMMON_DROPPED + mapOf("PAUSED" to "PAUSED", "PAUSE" to "PAUSED", "ON_HOLD" to "PAUSED", "HOLD" to "PAUSED")) ?: existing?.status ?: ProjectStatus.ACTIVE,
            importance = args.optDoubleOr("importance", existing?.importance ?: 0.5),
            updatedAt = now,
            version = (existing?.version ?: 0) + 1,
        )
        repo.saveProject(project)
        jsonObjectOf("ok" to true, "id" to project.id)
    }

    register("get_goals", "List goals.", objSchema()) { jsonObjectOf("ok" to true, "goals" to JSONArray(repo.goals().map { JSONObject(it.toJson()) })) }
    register("create_goal", "Create a goal.", objSchema("title" to str(), "description" to str(), "targetDate" to str(), "relatedProjectId" to str(), "priority" to num(), required = listOf("title"))) { args ->
        val now = nowMillis()
        val id = newId()
        repo.saveGoal(
            Goal(id, args.requiredString("title"), args.optStringOrNull("description"), GoalStatus.ACTIVE, args.optDoubleOr("priority", 0.6), parseWhen(args.optStringOrNull("targetDate")), args.optStringOrNull("relatedProjectId"), null, now, now, repo.deviceId, 1, null),
        )
        jsonObjectOf("ok" to true, "id" to id)
    }
    register("update_goal", "Update a goal.", objSchema("id" to str(), "title" to str(), "status" to enumField<GoalStatus>(), "progressSummary" to str(), "priority" to num(), required = listOf("id"))) { args ->
        val existing = repo.getGoal(args.requiredString("id")) ?: throw ToolValidationException("Goal not found")
        repo.saveGoal(existing.copy(
            title = args.optStringOrNull("title") ?: existing.title,
            status = parseEnum<GoalStatus>(args.optStringOrNull("status"), COMMON_DONE + COMMON_DROPPED) ?: existing.status,
            progressSummary = args.optStringOrNull("progressSummary") ?: existing.progressSummary,
            priority = args.optDoubleOr("priority", existing.priority),
            updatedAt = nowMillis(),
            version = existing.version + 1,
        ))
        jsonObjectOf("ok" to true)
    }

    register("get_tasks", "List tasks.", objSchema()) { jsonObjectOf("ok" to true, "tasks" to JSONArray(repo.tasks().map { JSONObject(it.toJson()) })) }
    register("create_task", "Create a task only when they intend to do something. Household facts and specs belong in remember, not here.", objSchema("title" to str(), "description" to str(), "dueAt" to str(WHEN_HINT), "relatedProjectId" to str(), "relatedGoalId" to str(), required = listOf("title"))) { args ->
        val now = nowMillis()
        val id = newId()
        repo.saveTask(TaskItem(id, args.requiredString("title"), args.optStringOrNull("description"), TaskStatus.OPEN, parseWhen(args.optStringOrNull("dueAt")), args.optStringOrNull("relatedProjectId"), args.optStringOrNull("relatedGoalId"), now, now, repo.deviceId, 1, null))
        jsonObjectOf("ok" to true, "id" to id)
    }
    register(
        "update_task",
        "Update a task. id may be the task id from context or the exact title. status: OPEN, DONE, DROPPED. cancelled/canceled maps to DROPPED. Pass dueAt to move its deadline instead of dropping and recreating it.",
        objSchema("id" to str("Task id or title"), "status" to enumField<TaskStatus>(), "title" to str(), "description" to str(), "dueAt" to str(WHEN_HINT), required = listOf("id")),
    ) { args ->
        val existing = requireTask(args.requiredString("id"))
        val status = parseEnum<TaskStatus>(
            args.optStringOrNull("status"),
            COMMON_DONE + COMMON_DROPPED + mapOf("TODO" to "OPEN", "PENDING" to "OPEN"),
        ) ?: existing.status
        repo.saveTask(
            existing.copy(
                title = args.optStringOrNull("title") ?: existing.title,
                description = args.optStringOrNull("description") ?: existing.description,
                dueAt = requiredWhen(args.optStringOrNull("dueAt")) ?: existing.dueAt,
                status = status,
                updatedAt = nowMillis(),
                version = existing.version + 1,
                deletedAt = if (status == TaskStatus.DROPPED) nowMillis() else existing.deletedAt,
            ),
        )
        jsonObjectOf("ok" to true, "id" to existing.id, "status" to status.name)
    }

    register("get_commitments", "List commitments.", objSchema()) { jsonObjectOf("ok" to true, "commitments" to JSONArray(repo.commitments().map { JSONObject(it.toJson()) })) }
    register("create_commitment", "Create a commitment the person actually promised.", objSchema("title" to str(), "description" to str(), "dueAt" to str(WHEN_HINT), "promisedTo" to str(), required = listOf("title"))) { args ->
        val now = nowMillis()
        val id = newId()
        repo.saveCommitment(Commitment(id, args.requiredString("title"), args.optStringOrNull("description"), CommitmentStatus.OPEN, parseWhen(args.optStringOrNull("dueAt")), args.optStringOrNull("promisedTo"), null, now, now, repo.deviceId, 1, null))
        jsonObjectOf("ok" to true, "id" to id)
    }
    register(
        "update_commitment",
        "Update a commitment. id may be the commitment id from context or the exact title. Pass dueAt to renegotiate the deadline instead of dropping and recreating it.",
        objSchema(
            "id" to str("Commitment id or title"),
            "status" to enumField<CommitmentStatus>(),
            "title" to str(),
            "description" to str(),
            "dueAt" to str(WHEN_HINT),
            "promisedTo" to str(),
            required = listOf("id"),
        ),
    ) { args ->
        val existing = requireCommitment(args.requiredString("id"))
        val status = parseEnum<CommitmentStatus>(args.optStringOrNull("status"), COMMON_DONE + COMMON_DROPPED) ?: existing.status
        repo.saveCommitment(
            existing.copy(
                title = args.optStringOrNull("title") ?: existing.title,
                description = args.optStringOrNull("description") ?: existing.description,
                dueAt = requiredWhen(args.optStringOrNull("dueAt")) ?: existing.dueAt,
                promisedTo = args.optStringOrNull("promisedTo") ?: existing.promisedTo,
                status = status,
                updatedAt = nowMillis(),
                version = existing.version + 1,
            ),
        )
        jsonObjectOf("ok" to true, "id" to existing.id, "status" to status.name)
    }

    register("get_open_loops", "List unfinished threads.", objSchema()) { jsonObjectOf("ok" to true, "openLoops" to JSONArray(repo.openLoops().map { JSONObject(it.toJson()) })) }
    register("create_open_loop", "Record an unfinished thread. Use importance and confidence; skip trivial unfinished sentences.", objSchema("description" to str(), "importance" to num(), "confidence" to num(), "relatedEntityType" to str("Optional: person, project, task, commitment"), "relatedEntityId" to str(), required = listOf("description"))) { args ->
        val now = nowMillis()
        val id = newId()
        repo.saveOpenLoop(OpenLoop(id, args.requiredString("description"), OpenLoopStatus.OPEN, args.optDoubleOr("importance", 0.5), args.optDoubleOr("confidence", 0.6), args.optStringOrNull("relatedEntityType"), args.optStringOrNull("relatedEntityId"), now, now, repo.deviceId, 1, null))
        jsonObjectOf("ok" to true, "id" to id)
    }
    register("update_open_loop", "Update an open loop.", objSchema("id" to str(), "status" to enumField<OpenLoopStatus>(), "description" to str(), required = listOf("id"))) { args ->
        val existing = repo.getOpenLoop(args.requiredString("id")) ?: throw ToolValidationException("Open loop not found")
        repo.saveOpenLoop(existing.copy(description = args.optStringOrNull("description") ?: existing.description, status = parseEnum<OpenLoopStatus>(args.optStringOrNull("status"), COMMON_DROPPED + mapOf("CLOSED" to "CLOSED", "DONE" to "CLOSED")) ?: existing.status, updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }
    register("close_open_loop", "Close an open loop.", objSchema("id" to str(), required = listOf("id"))) { args ->
        val existing = repo.getOpenLoop(args.requiredString("id")) ?: throw ToolValidationException("Open loop not found")
        repo.saveOpenLoop(existing.copy(status = OpenLoopStatus.CLOSED, updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }

    register("get_routines", "List routines.", objSchema()) { jsonObjectOf("ok" to true, "routines" to JSONArray(repo.routines().map { JSONObject(it.toJson()) })) }
    register("create_routine", "Create a routine. Inferred routines should have lower confidence.", objSchema("title" to str(), "description" to str(), "schedule" to str(), "confidence" to num(), "source" to oneOf("USER_EXPLICIT when they said it, AGENT_INFERENCE when you noticed a pattern", "USER_EXPLICIT", "AGENT_INFERENCE"), required = listOf("title"))) { args ->
        val now = nowMillis()
        val id = newId()
        val source = parseEnum<MemorySource>(args.optStringOrNull("source")) ?: MemorySource.AGENT_INFERENCE
        repo.saveRoutine(Routine(id, args.requiredString("title"), args.optStringOrNull("description"), args.optStringOrNull("schedule"), args.optDoubleOr("confidence", 0.55), source, now, now, repo.deviceId, 1, null))
        jsonObjectOf("ok" to true, "id" to id)
    }
    register("update_routine", "Update a routine.", objSchema("id" to str(), "confidence" to num(), "schedule" to str(), "title" to str(), required = listOf("id"))) { args ->
        val existing = repo.getRoutine(args.requiredString("id")) ?: throw ToolValidationException("Routine not found")
        repo.saveRoutine(existing.copy(title = args.optStringOrNull("title") ?: existing.title, schedule = args.optStringOrNull("schedule") ?: existing.schedule, confidence = args.optDoubleOr("confidence", existing.confidence), updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }

    register("get_groceries", "List groceries.", objSchema()) { jsonObjectOf("ok" to true, "groceries" to JSONArray(repo.groceries().map { JSONObject(it.toJson()) })) }
    register("add_grocery", "Add a grocery item. Use for statements like 'we're out of rice'.", objSchema("name" to str(), "quantity" to str(), "category" to str(), "reason" to str(), required = listOf("name"))) { args ->
        val now = nowMillis()
        val name = args.requiredString("name")
        val existing = repo.groceries().firstOrNull { it.name.equals(name, true) && it.status == GroceryStatus.ACTIVE }
        val item = (existing ?: GroceryItem(newId(), name, null, null, GroceryStatus.ACTIVE, null, 0.0, null, null, now, now, repo.deviceId, 1, null)).copy(
            quantity = args.optStringOrNull("quantity") ?: existing?.quantity,
            category = args.optStringOrNull("category") ?: existing?.category,
            reason = args.optStringOrNull("reason") ?: existing?.reason,
            status = GroceryStatus.ACTIVE,
            updatedAt = now,
            version = (existing?.version ?: 0) + 1,
        )
        repo.saveGrocery(item)
        jsonObjectOf("ok" to true, "id" to item.id, "created" to (existing == null))
    }
    register("update_grocery", "Update a grocery item.", objSchema("id" to str(), "status" to enumField<GroceryStatus>(), "quantity" to str(), required = listOf("id"))) { args ->
        val existing = repo.getGrocery(args.requiredString("id")) ?: throw ToolValidationException("Grocery not found")
        repo.saveGrocery(existing.copy(status = parseEnum<GroceryStatus>(args.optStringOrNull("status"), COMMON_DROPPED + mapOf("BOUGHT" to "PURCHASED", "PURCHASED" to "PURCHASED", "ACTIVE" to "ACTIVE")) ?: existing.status, quantity = args.optStringOrNull("quantity") ?: existing.quantity, updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }
    register("remove_grocery", "Remove a grocery item.", objSchema("id" to str(), required = listOf("id"))) { args ->
        val existing = repo.getGrocery(args.requiredString("id")) ?: throw ToolValidationException("Grocery not found")
        repo.saveGrocery(existing.copy(status = GroceryStatus.DROPPED, deletedAt = nowMillis(), updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }
    register("clear_purchased_groceries", "Mark active groceries purchased, optionally keeping some. Use for 'I bought everything except eggs'.", objSchema("except" to arr("Names to keep"))) { args ->
        repo.markGroceriesPurchased(args.optStringList("except"))
        jsonObjectOf("ok" to true)
    }

    register("get_important_dates", "List important dates.", objSchema()) { jsonObjectOf("ok" to true, "dates" to JSONArray(repo.importantDates().map { JSONObject(it.toJson()) })) }
    register(
        "create_important_date",
        "Create an important date.",
        objSchema(
            "title" to str(),
            "dateIso" to str("Gregorian or Jalali date; it is normalized to ISO before storage"),
            "relatedPersonId" to str(),
            "recurrence" to str("yearly, monthly, or omit for a one-off date"),
            "notes" to str(),
            "importance" to num("0-1"),
            required = listOf("title", "dateIso"),
        ),
    ) { args ->
        val now = nowMillis()
        val title = args.requiredString("title")
        val dateIso = normalizeDateIso(args.requiredString("dateIso"), nowZoned()) ?: args.requiredString("dateIso")
        val relatedPersonId = args.optStringOrNull("relatedPersonId")
        val existing = repo.importantDates().firstOrNull { date ->
            (relatedPersonId != null && date.relatedPersonId == relatedPersonId) ||
                date.title.equals(title, ignoreCase = true)
        }
        val item = (existing ?: ImportantDate(
            id = newId(),
            title = title,
            dateIso = dateIso,
            recurrence = null,
            relatedPersonId = relatedPersonId,
            notes = null,
            importance = 0.7,
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
            deletedAt = null,
        )).copy(
            title = title,
            dateIso = dateIso,
            recurrence = args.optStringOrNull("recurrence") ?: existing?.recurrence,
            relatedPersonId = relatedPersonId ?: existing?.relatedPersonId,
            notes = args.optStringOrNull("notes") ?: existing?.notes,
            importance = args.optDoubleOr("importance", existing?.importance ?: 0.7),
            updatedAt = now,
            version = (existing?.version ?: 0) + 1,
        )
        repo.saveImportantDate(item)
        jsonObjectOf("ok" to true, "id" to item.id, "created" to (existing == null))
    }
    register("update_important_date", "Update an important date.", objSchema("id" to str(), "title" to str(), "dateIso" to str(), required = listOf("id"))) { args ->
        val existing = repo.getImportantDate(args.requiredString("id")) ?: throw ToolValidationException("Date not found")
        repo.saveImportantDate(existing.copy(title = args.optStringOrNull("title") ?: existing.title, dateIso = normalizeDateIso(args.optStringOrNull("dateIso"), nowZoned()) ?: existing.dateIso, updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }

    register("get_recurring_responsibilities", "List recurring responsibilities.", objSchema()) { jsonObjectOf("ok" to true, "items" to JSONArray(repo.responsibilities().map { JSONObject(it.toJson()) })) }
    register("create_recurring_responsibility", "Create a recurring responsibility.", objSchema("title" to str(), "cadence" to str(), "nextDueAt" to str(WHEN_HINT), "notes" to str(), required = listOf("title", "cadence"))) { args ->
        val now = nowMillis()
        val id = newId()
        repo.saveResponsibility(RecurringResponsibility(id, args.requiredString("title"), args.requiredString("cadence"), parseWhen(args.optStringOrNull("nextDueAt")), null, args.optStringOrNull("notes"), now, now, repo.deviceId, 1, null))
        jsonObjectOf("ok" to true, "id" to id)
    }
    register("update_recurring_responsibility", "Update a recurring responsibility.", objSchema("id" to str(), "title" to str(), "cadence" to str(), required = listOf("id"))) { args ->
        val existing = repo.getResponsibility(args.requiredString("id")) ?: throw ToolValidationException("Not found")
        repo.saveResponsibility(existing.copy(title = args.optStringOrNull("title") ?: existing.title, cadence = args.optStringOrNull("cadence") ?: existing.cadence, updatedAt = nowMillis(), version = existing.version + 1))
        jsonObjectOf("ok" to true)
    }

    register("relate_memories", "Create a lightweight relationship between two entities.", objSchema("sourceType" to str(), "sourceId" to str(), "relationshipType" to str(), "targetType" to str(), "targetId" to str(), required = listOf("sourceType", "sourceId", "relationshipType", "targetType", "targetId"))) { args ->
        val now = nowMillis()
        val id = newId()
        repo.saveRelationship(MemoryRelationship(id, args.requiredString("sourceType"), args.requiredString("sourceId"), args.requiredString("relationshipType"), args.requiredString("targetType"), args.requiredString("targetId"), now, now, repo.deviceId, 1, null))
        jsonObjectOf("ok" to true, "id" to id)
    }
}
